/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.bulk;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.ResourceAlreadyExistsException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRunnable;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.DocWriteRequest.OpType;
import org.elasticsearch.action.admin.indices.create.AutoCreateAction;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.rollover.LazyRolloverAction;
import org.elasticsearch.action.admin.indices.rollover.RolloverRequest;
import org.elasticsearch.action.admin.indices.rollover.RolloverResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.ingest.IngestActionForwarder;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.action.support.RefCountingRunnable;
import org.elasticsearch.action.support.WriteResponse;
import org.elasticsearch.action.support.replication.ReplicationResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.internal.OriginSettingClient;
import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateObserver;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.ComposableIndexTemplate;
import org.elasticsearch.cluster.metadata.DataStream;
import org.elasticsearch.cluster.metadata.IndexAbstraction;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.metadata.MetadataIndexTemplateService;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.util.concurrent.AtomicArray;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.core.Assertions;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.features.FeatureService;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.IndexingPressure;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.indices.SystemIndices;
import org.elasticsearch.ingest.IngestService;
import org.elasticsearch.node.NodeClosedException;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.threadpool.ThreadPool.Names;
import org.elasticsearch.transport.TransportService;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;

import static org.elasticsearch.index.seqno.SequenceNumbers.UNASSIGNED_PRIMARY_TERM;
import static org.elasticsearch.index.seqno.SequenceNumbers.UNASSIGNED_SEQ_NO;

/**
 * Groups bulk request items by shard, optionally creating non-existent indices and
 * delegates to {@link TransportShardBulkAction} for shard-level bulk execution
 */
public class TransportBulkAction extends HandledTransportAction<BulkRequest, BulkResponse> {

    public static final String NAME = "indices:data/write/bulk";
    public static final ActionType<BulkResponse> TYPE = new ActionType<>(NAME);
    private static final Logger logger = LogManager.getLogger(TransportBulkAction.class);
    public static final String LAZY_ROLLOVER_ORIGIN = "lazy_rollover";

    private final ActionType<BulkResponse> bulkAction;
    private final ThreadPool threadPool;
    private final ClusterService clusterService;
    private final IngestService ingestService;
    private final FeatureService featureService;
    private final LongSupplier relativeTimeProvider;
    private final IngestActionForwarder ingestForwarder;
    private final NodeClient client;
    private final IndexNameExpressionResolver indexNameExpressionResolver;
    private final IndexingPressure indexingPressure;
    private final SystemIndices systemIndices;
    private final OriginSettingClient rolloverClient;

    private final Executor writeExecutor;
    private final Executor systemWriteExecutor;

    @Inject
    public TransportBulkAction(
        ThreadPool threadPool,
        TransportService transportService,
        ClusterService clusterService,
        IngestService ingestService,
        FeatureService featureService,
        NodeClient client,
        ActionFilters actionFilters,
        IndexNameExpressionResolver indexNameExpressionResolver,
        IndexingPressure indexingPressure,
        SystemIndices systemIndices
    ) {
        this(
            threadPool,
            transportService,
            clusterService,
            ingestService,
            featureService,
            client,
            actionFilters,
            indexNameExpressionResolver,
            indexingPressure,
            systemIndices,
            System::nanoTime
        );
    }

    public TransportBulkAction(
        ThreadPool threadPool,
        TransportService transportService,
        ClusterService clusterService,
        IngestService ingestService,
        FeatureService featureService,
        NodeClient client,
        ActionFilters actionFilters,
        IndexNameExpressionResolver indexNameExpressionResolver,
        IndexingPressure indexingPressure,
        SystemIndices systemIndices,
        LongSupplier relativeTimeProvider
    ) {
        this(
            TYPE,
            BulkRequest::new,
            threadPool,
            transportService,
            clusterService,
            ingestService,
            featureService,
            client,
            actionFilters,
            indexNameExpressionResolver,
            indexingPressure,
            systemIndices,
            relativeTimeProvider
        );
    }

    TransportBulkAction(
        ActionType<BulkResponse> bulkAction,
        Writeable.Reader<BulkRequest> requestReader,
        ThreadPool threadPool,
        TransportService transportService,
        ClusterService clusterService,
        IngestService ingestService,
        FeatureService featureService,
        NodeClient client,
        ActionFilters actionFilters,
        IndexNameExpressionResolver indexNameExpressionResolver,
        IndexingPressure indexingPressure,
        SystemIndices systemIndices,
        LongSupplier relativeTimeProvider
    ) {
        super(bulkAction.name(), transportService, actionFilters, requestReader, EsExecutors.DIRECT_EXECUTOR_SERVICE);
        Objects.requireNonNull(relativeTimeProvider);
        this.bulkAction = bulkAction;
        this.threadPool = threadPool;
        this.clusterService = clusterService;
        this.ingestService = ingestService;
        this.featureService = featureService;
        this.relativeTimeProvider = relativeTimeProvider;
        this.ingestForwarder = new IngestActionForwarder(transportService);
        this.client = client;
        this.indexNameExpressionResolver = indexNameExpressionResolver;
        this.indexingPressure = indexingPressure;
        this.systemIndices = systemIndices;
        clusterService.addStateApplier(this.ingestForwarder);
        this.rolloverClient = new OriginSettingClient(client, LAZY_ROLLOVER_ORIGIN);
        this.writeExecutor = threadPool.executor(Names.WRITE);
        this.systemWriteExecutor = threadPool.executor(Names.SYSTEM_WRITE);
    }

    /**
     * Retrieves the {@link IndexRequest} from the provided {@link DocWriteRequest} for index or upsert actions.  Upserts are
     * modeled as {@link IndexRequest} inside the {@link UpdateRequest}. Ignores {@link org.elasticsearch.action.delete.DeleteRequest}'s
     *
     * @param docWriteRequest The request to find the {@link IndexRequest}
     * @return the found {@link IndexRequest} or {@code null} if one can not be found.
     */
    public static IndexRequest getIndexWriteRequest(DocWriteRequest<?> docWriteRequest) {
        IndexRequest indexRequest = null;
        if (docWriteRequest instanceof IndexRequest) {
            indexRequest = (IndexRequest) docWriteRequest;
        } else if (docWriteRequest instanceof UpdateRequest updateRequest) {
            indexRequest = updateRequest.docAsUpsert() ? updateRequest.doc() : updateRequest.upsertRequest();
        }
        return indexRequest;
    }

    public static <Response extends ReplicationResponse & WriteResponse> ActionListener<BulkResponse> unwrappingSingleItemBulkResponse(
        final ActionListener<Response> listener
    ) {
        return listener.delegateFailureAndWrap((l, bulkItemResponses) -> {
            assert bulkItemResponses.getItems().length == 1 : "expected exactly one item in bulk response";
            final BulkItemResponse bulkItemResponse = bulkItemResponses.getItems()[0];
            if (bulkItemResponse.isFailed() == false) {
                @SuppressWarnings("unchecked")
                final Response response = (Response) bulkItemResponse.getResponse();
                l.onResponse(response);
            } else {
                l.onFailure(bulkItemResponse.getFailure().getCause());
            }
        });
    }

    @Override
    protected void doExecute(Task task, BulkRequest bulkRequest, ActionListener<BulkResponse> listener) {
        /*
         * This is called on the Transport thread so we can check the indexing
         * memory pressure *quickly* but we don't want to keep the transport
         * thread busy. Then, as soon as we have the indexing pressure in we fork
         * to one of the write thread pools. We do this because juggling the
         * bulk request can get expensive for a few reasons:
         * 1. Figuring out which shard should receive a bulk request might require
         *    parsing the _source.
         * 2. When dispatching the sub-requests to shards we may have to compress
         *    them. LZ4 is super fast, but slow enough that it's best not to do it
         *    on the transport thread, especially for large sub-requests.
         *
         * We *could* detect these cases and only fork in then, but that is complex
         * to get right and the fork is fairly low overhead.
         */
        final int indexingOps = bulkRequest.numberOfActions();
        final long indexingBytes = bulkRequest.ramBytesUsed();
        final boolean isOnlySystem = isOnlySystem(bulkRequest, clusterService.state().metadata().getIndicesLookup(), systemIndices);
        final Releasable releasable = indexingPressure.markCoordinatingOperationStarted(indexingOps, indexingBytes, isOnlySystem);
        final ActionListener<BulkResponse> releasingListener = ActionListener.runBefore(listener, releasable::close);
        final Executor executor = isOnlySystem ? systemWriteExecutor : writeExecutor;
        ensureClusterStateThenForkAndExecute(task, bulkRequest, executor, releasingListener);
    }

    private void ensureClusterStateThenForkAndExecute(
        Task task,
        BulkRequest bulkRequest,
        Executor executor,
        ActionListener<BulkResponse> releasingListener
    ) {
        final ClusterState initialState = clusterService.state();
        final ClusterBlockException blockException = initialState.blocks().globalBlockedException(ClusterBlockLevel.WRITE);
        if (blockException != null) {
            if (false == blockException.retryable()) {
                releasingListener.onFailure(blockException);
                return;
            }
            logger.trace("cluster is blocked, waiting for it to recover", blockException);
            final ClusterStateObserver clusterStateObserver = new ClusterStateObserver(
                initialState,
                clusterService,
                bulkRequest.timeout(),
                logger,
                threadPool.getThreadContext()
            );
            clusterStateObserver.waitForNextChange(new ClusterStateObserver.Listener() {
                @Override
                public void onNewClusterState(ClusterState state) {
                    forkAndExecute(task, bulkRequest, executor, releasingListener);
                }

                @Override
                public void onClusterServiceClose() {
                    releasingListener.onFailure(new NodeClosedException(clusterService.localNode()));
                }

                @Override
                public void onTimeout(TimeValue timeout) {
                    releasingListener.onFailure(blockException);
                }
            }, newState -> false == newState.blocks().hasGlobalBlockWithLevel(ClusterBlockLevel.WRITE));
        } else {
            forkAndExecute(task, bulkRequest, executor, releasingListener);
        }
    }

    private void forkAndExecute(Task task, BulkRequest bulkRequest, Executor executor, ActionListener<BulkResponse> releasingListener) {
        executor.execute(new ActionRunnable<>(releasingListener) {
            @Override
            protected void doRun() {
                doInternalExecute(task, bulkRequest, executor, releasingListener);
            }
        });
    }

    protected void doInternalExecute(Task task, BulkRequest bulkRequest, Executor executor, ActionListener<BulkResponse> listener) {
        final long startTime = relativeTime();

        boolean hasIndexRequestsWithPipelines = false;
        final Metadata metadata = clusterService.state().getMetadata();
        for (DocWriteRequest<?> actionRequest : bulkRequest.requests) {
            IndexRequest indexRequest = getIndexWriteRequest(actionRequest);
            if (indexRequest != null) {
                IngestService.resolvePipelinesAndUpdateIndexRequest(actionRequest, indexRequest, metadata);
                hasIndexRequestsWithPipelines |= IngestService.hasPipeline(indexRequest);
            }

            if (actionRequest instanceof IndexRequest ir) {
                if (ir.getAutoGeneratedTimestamp() != IndexRequest.UNSET_AUTO_GENERATED_TIMESTAMP) {
                    throw new IllegalArgumentException("autoGeneratedTimestamp should not be set externally");
                }
            }
        }

        if (hasIndexRequestsWithPipelines) {
            // this method (doExecute) will be called again, but with the bulk requests updated from the ingest node processing but
            // also with IngestService.NOOP_PIPELINE_NAME on each request. This ensures that this on the second time through this method,
            // this path is never taken.
            ActionListener.run(listener, l -> {
                if (Assertions.ENABLED) {
                    final boolean arePipelinesResolved = bulkRequest.requests()
                        .stream()
                        .map(TransportBulkAction::getIndexWriteRequest)
                        .filter(Objects::nonNull)
                        .allMatch(IndexRequest::isPipelineResolved);
                    assert arePipelinesResolved : bulkRequest;
                }
                if (clusterService.localNode().isIngestNode()) {
                    processBulkIndexIngestRequest(task, bulkRequest, executor, metadata, l);
                } else {
                    ingestForwarder.forwardIngestRequest(bulkAction, bulkRequest, l);
                }
            });
            return;
        }

        Map<String, CreateIndexRequest> indicesToAutoCreate = new HashMap<>();
        Set<String> dataStreamsToBeRolledOver = new HashSet<>();
        Set<String> failureStoresToBeRolledOver = new HashSet<>();
        populateMissingTargets(bulkRequest, indicesToAutoCreate, dataStreamsToBeRolledOver, failureStoresToBeRolledOver);

        createMissingIndicesAndIndexData(
            task,
            bulkRequest,
            executor,
            listener,
            indicesToAutoCreate,
            dataStreamsToBeRolledOver,
            failureStoresToBeRolledOver,
            startTime
        );
    }

    /**
     * Determine all the targets (i.e. indices, data streams, failure stores) that require an action before we can proceed with the bulk
     * request. Indices might need to be created, and data streams and failure stores might need to be rolled over when they're marked
     * for lazy rollover.
     *
     * @param bulkRequest the bulk request
     * @param indicesToAutoCreate a map of index names to their creation request that need to be auto-created
     * @param dataStreamsToBeRolledOver a set of data stream names that were marked for lazy rollover and thus need to be rolled over now
     * @param failureStoresToBeRolledOver a set of data stream names whose failure store was marked for lazy rollover and thus need to be
     * rolled over now
     */
    private void populateMissingTargets(
        BulkRequest bulkRequest,
        Map<String, CreateIndexRequest> indicesToAutoCreate,
        Set<String> dataStreamsToBeRolledOver,
        Set<String> failureStoresToBeRolledOver
    ) {
        ClusterState state = clusterService.state();
        // A map for memorizing which indices exist.
        Map<String, Boolean> indexExistence = new HashMap<>();
        Function<String, Boolean> indexExistenceComputation = (index) -> indexNameExpressionResolver.hasIndexAbstraction(index, state);
        boolean lazyRolloverFeature = featureService.clusterHasFeature(state, LazyRolloverAction.DATA_STREAM_LAZY_ROLLOVER);
        boolean lazyRolloverFailureStoreFeature = DataStream.isFailureStoreFeatureFlagEnabled();
        Set<String> indicesThatRequireAlias = new HashSet<>();

        for (DocWriteRequest<?> request : bulkRequest.requests) {
            // Delete requests should not attempt to create the index (if the index does not exist), unless an external versioning is used.
            if (request.opType() == OpType.DELETE
                && request.versionType() != VersionType.EXTERNAL
                && request.versionType() != VersionType.EXTERNAL_GTE) {
                continue;
            }
            boolean writeToFailureStore = request instanceof IndexRequest indexRequest && indexRequest.isWriteToFailureStore();
            boolean indexExists = indexExistence.computeIfAbsent(request.index(), indexExistenceComputation);
            if (indexExists == false) {
                // We should only auto-create an index if _none_ of the requests are requiring it to be an alias.
                if (request.isRequireAlias()) {
                    // Remember that this request required this index to be an alias.
                    if (indicesThatRequireAlias.add(request.index())) {
                        // If we didn't already know that, we remove the index from the list of indices to create (if present).
                        indicesToAutoCreate.remove(request.index());
                    }
                } else if (indicesThatRequireAlias.contains(request.index()) == false) {
                    CreateIndexRequest createIndexRequest = indicesToAutoCreate.get(request.index());
                    // Create a new CreateIndexRequest if we didn't already have one.
                    if (createIndexRequest == null) {
                        createIndexRequest = new CreateIndexRequest(request.index()).cause("auto(bulk api)")
                            .masterNodeTimeout(bulkRequest.timeout())
                            .requireDataStream(request.isRequireDataStream())
                            // If this IndexRequest is directed towards a failure store, but the data stream doesn't exist, we initialize
                            // the failure store on data stream creation instead of lazily.
                            .initializeFailureStore(writeToFailureStore);
                        indicesToAutoCreate.put(request.index(), createIndexRequest);
                    } else {
                        // Track whether one of the index requests in this bulk request requires the target to be a data stream.
                        if (createIndexRequest.isRequireDataStream() == false && request.isRequireDataStream()) {
                            createIndexRequest.requireDataStream(true);
                        }
                        // Track whether one of the index requests in this bulk request is directed towards a failure store.
                        if (createIndexRequest.isInitializeFailureStore() == false && writeToFailureStore) {
                            createIndexRequest.initializeFailureStore(true);
                        }
                    }
                }
            }
            // Determine which data streams and failure stores need to be rolled over.
            if (lazyRolloverFeature) {
                DataStream dataStream = state.metadata().dataStreams().get(request.index());
                if (dataStream != null) {
                    if (writeToFailureStore == false && dataStream.getBackingIndices().isRolloverOnWrite()) {
                        dataStreamsToBeRolledOver.add(request.index());
                    } else if (lazyRolloverFailureStoreFeature
                        && writeToFailureStore
                        && dataStream.getFailureIndices().isRolloverOnWrite()) {
                            failureStoresToBeRolledOver.add(request.index());
                        }
                }
            }
        }
    }

    /**
     * This method is responsible for creating any missing indices, rolling over data streams and their failure stores when needed, and then
     * indexing the data in the BulkRequest.
     */
    protected void createMissingIndicesAndIndexData(
        Task task,
        BulkRequest bulkRequest,
        Executor executor,
        ActionListener<BulkResponse> listener,
        Map<String, CreateIndexRequest> indicesToAutoCreate,
        Set<String> dataStreamsToBeRolledOver,
        Set<String> failureStoresToBeRolledOver,
        long startTime
    ) {
        final AtomicArray<BulkItemResponse> responses = new AtomicArray<>(bulkRequest.requests.size());
        // Optimizing when there are no prerequisite actions
        if (indicesToAutoCreate.isEmpty() && dataStreamsToBeRolledOver.isEmpty() && failureStoresToBeRolledOver.isEmpty()) {
            executeBulk(task, bulkRequest, startTime, listener, executor, responses, Map.of());
            return;
        }
        final Map<String, IndexNotFoundException> indicesThatCannotBeCreated = new HashMap<>();
        Runnable executeBulkRunnable = () -> executor.execute(new ActionRunnable<>(listener) {
            @Override
            protected void doRun() {
                executeBulk(task, bulkRequest, startTime, listener, executor, responses, indicesThatCannotBeCreated);
            }
        });
        try (RefCountingRunnable refs = new RefCountingRunnable(executeBulkRunnable)) {
            createIndices(bulkRequest, indicesToAutoCreate, indicesThatCannotBeCreated, responses, refs);
            rollOverDataStreams(bulkRequest, dataStreamsToBeRolledOver, false, responses, refs);
            rollOverDataStreams(bulkRequest, failureStoresToBeRolledOver, true, responses, refs);
        }
    }

    private void createIndices(
        BulkRequest bulkRequest,
        Map<String, CreateIndexRequest> indicesToAutoCreate,
        Map<String, IndexNotFoundException> indicesThatCannotBeCreated,
        AtomicArray<BulkItemResponse> responses,
        RefCountingRunnable refs
    ) {
        for (Map.Entry<String, CreateIndexRequest> indexEntry : indicesToAutoCreate.entrySet()) {
            final String index = indexEntry.getKey();
            createIndex(indexEntry.getValue(), ActionListener.releaseAfter(new ActionListener<>() {
                @Override
                public void onResponse(CreateIndexResponse createIndexResponse) {}

                @Override
                public void onFailure(Exception e) {
                    final Throwable cause = ExceptionsHelper.unwrapCause(e);
                    if (cause instanceof IndexNotFoundException indexNotFoundException) {
                        synchronized (indicesThatCannotBeCreated) {
                            indicesThatCannotBeCreated.put(index, indexNotFoundException);
                        }
                    } else if ((cause instanceof ResourceAlreadyExistsException) == false) {
                        // fail all requests involving this index, if create didn't work
                        failRequestsWhenPrerequisiteActionFailed(index, bulkRequest, responses, e);
                    }
                }
            }, refs.acquire()));
        }
    }

    private void rollOverDataStreams(
        BulkRequest bulkRequest,
        Set<String> dataStreamsToBeRolledOver,
        boolean targetFailureStore,
        AtomicArray<BulkItemResponse> responses,
        RefCountingRunnable refs
    ) {
        for (String dataStream : dataStreamsToBeRolledOver) {
            RolloverRequest rolloverRequest = new RolloverRequest(dataStream, null);
            rolloverRequest.masterNodeTimeout(bulkRequest.timeout);
            if (targetFailureStore) {
                rolloverRequest.setIndicesOptions(
                    IndicesOptions.builder(rolloverRequest.indicesOptions())
                        .failureStoreOptions(new IndicesOptions.FailureStoreOptions(false, true))
                        .build()
                );
            }
            // We are executing a lazy rollover because it is an action specialised for this situation, when we want an
            // unconditional and performant rollover.
            rolloverClient.execute(LazyRolloverAction.INSTANCE, rolloverRequest, ActionListener.releaseAfter(new ActionListener<>() {

                @Override
                public void onResponse(RolloverResponse result) {
                    logger.debug(
                        "Data stream{} {} has {} over, the latest index is {}",
                        rolloverRequest.targetsFailureStore() ? " failure store" : "",
                        dataStream,
                        result.isRolledOver() ? "been successfully rolled" : "skipped rolling",
                        result.getNewIndex()
                    );
                }

                @Override
                public void onFailure(Exception e) {
                    failRequestsWhenPrerequisiteActionFailed(dataStream, bulkRequest, responses, e);
                }
            }, refs.acquire()));
        }
    }

    /**
     * Fails all requests involving this index or data stream because the prerequisite action failed too.
     */
    private static void failRequestsWhenPrerequisiteActionFailed(
        String target,
        BulkRequest bulkRequest,
        AtomicArray<BulkItemResponse> responses,
        Exception error
    ) {
        for (int i = 0; i < bulkRequest.requests.size(); i++) {
            DocWriteRequest<?> request = bulkRequest.requests.get(i);
            if (request != null && setResponseFailureIfIndexMatches(responses, i, request, target, error)) {
                bulkRequest.requests.set(i, null);
            }
        }
    }

    /*
     * This returns the IngestService to be used for the given request. The default implementation ignores the request and always returns
     * the same ingestService, but child classes might use information in the request in creating an IngestService specific to that request.
     */
    protected IngestService getIngestService(BulkRequest request) {
        return ingestService;
    }

    static void prohibitAppendWritesInBackingIndices(DocWriteRequest<?> writeRequest, Metadata metadata) {
        DocWriteRequest.OpType opType = writeRequest.opType();
        if ((opType == OpType.CREATE || opType == OpType.INDEX) == false) {
            // op type not create or index, then bail early
            return;
        }
        IndexAbstraction indexAbstraction = metadata.getIndicesLookup().get(writeRequest.index());
        if (indexAbstraction == null) {
            return;
        }
        if (indexAbstraction.getType() != IndexAbstraction.Type.CONCRETE_INDEX) {
            return;
        }
        if (indexAbstraction.getParentDataStream() == null) {
            return;
        }

        DataStream dataStream = indexAbstraction.getParentDataStream();

        // At this point with write op is targeting a backing index of a data stream directly,
        // so checking if write op is append-only and if so fail.
        // (Updates and deletes are allowed to target a backing index)

        // CREATE op_type is considered append-only and
        // INDEX op_type is considered append-only when no if_primary_term and if_seq_no is specified.
        // (the latter maybe an update, but at this stage we can't determine that. In order to determine
        // that an engine level change is needed and for now this check is sufficient.)
        if (opType == DocWriteRequest.OpType.CREATE) {
            throw new IllegalArgumentException(
                "index request with op_type=create targeting backing indices is disallowed, "
                    + "target corresponding data stream ["
                    + dataStream.getName()
                    + "] instead"
            );
        }
        if (opType == DocWriteRequest.OpType.INDEX
            && writeRequest.ifPrimaryTerm() == UNASSIGNED_PRIMARY_TERM
            && writeRequest.ifSeqNo() == UNASSIGNED_SEQ_NO) {
            throw new IllegalArgumentException(
                "index request with op_type=index and no if_primary_term and if_seq_no set "
                    + "targeting backing indices is disallowed, target corresponding data stream ["
                    + dataStream.getName()
                    + "] instead"
            );
        }
    }

    static void prohibitCustomRoutingOnDataStream(DocWriteRequest<?> writeRequest, Metadata metadata) {
        IndexAbstraction indexAbstraction = metadata.getIndicesLookup().get(writeRequest.index());
        if (indexAbstraction == null) {
            return;
        }
        if (indexAbstraction.getType() != IndexAbstraction.Type.DATA_STREAM) {
            return;
        }

        if (writeRequest.routing() != null) {
            DataStream dataStream = (DataStream) indexAbstraction;
            if (dataStream.isAllowCustomRouting() == false) {
                throw new IllegalArgumentException(
                    "index request targeting data stream ["
                        + dataStream.getName()
                        + "] specifies a custom routing but the [allow_custom_routing] setting was "
                        + "not enabled in the data stream's template."
                );
            }
        }
    }

    static boolean isOnlySystem(BulkRequest request, SortedMap<String, IndexAbstraction> indicesLookup, SystemIndices systemIndices) {
        return request.getIndices().stream().allMatch(indexName -> isSystemIndex(indicesLookup, systemIndices, indexName));
    }

    private static boolean isSystemIndex(SortedMap<String, IndexAbstraction> indicesLookup, SystemIndices systemIndices, String indexName) {
        final IndexAbstraction abstraction = indicesLookup.get(indexName);
        if (abstraction != null) {
            return abstraction.isSystem();
        } else {
            return systemIndices.isSystemIndex(indexName);
        }
    }

    void createIndex(CreateIndexRequest createIndexRequest, ActionListener<CreateIndexResponse> listener) {
        client.execute(AutoCreateAction.INSTANCE, createIndexRequest, listener);
    }

    private static boolean setResponseFailureIfIndexMatches(
        AtomicArray<BulkItemResponse> responses,
        int idx,
        DocWriteRequest<?> request,
        String index,
        Exception e
    ) {
        if (index.equals(request.index())) {
            BulkItemResponse.Failure failure = new BulkItemResponse.Failure(request.index(), request.id(), e);
            responses.set(idx, BulkItemResponse.failure(idx, request.opType(), failure));
            return true;
        }
        return false;
    }

    protected long buildTookInMillis(long startTimeNanos) {
        return TimeUnit.NANOSECONDS.toMillis(relativeTime() - startTimeNanos);
    }

    void executeBulk(
        Task task,
        BulkRequest bulkRequest,
        long startTimeNanos,
        ActionListener<BulkResponse> listener,
        Executor executor,
        AtomicArray<BulkItemResponse> responses,
        Map<String, IndexNotFoundException> indicesThatCannotBeCreated
    ) {
        new BulkOperation(
            task,
            threadPool,
            executor,
            clusterService,
            bulkRequest,
            client,
            responses,
            indicesThatCannotBeCreated,
            indexNameExpressionResolver,
            relativeTimeProvider,
            startTimeNanos,
            listener
        ).run();
    }

    private long relativeTime() {
        return relativeTimeProvider.getAsLong();
    }

    private void processBulkIndexIngestRequest(
        Task task,
        BulkRequest original,
        Executor executor,
        Metadata metadata,
        ActionListener<BulkResponse> listener
    ) {
        final long ingestStartTimeInNanos = System.nanoTime();
        final BulkRequestModifier bulkRequestModifier = new BulkRequestModifier(original);
        getIngestService(original).executeBulkRequest(
            original.numberOfActions(),
            () -> bulkRequestModifier,
            bulkRequestModifier::markItemAsDropped,
            (indexName) -> shouldStoreFailure(indexName, metadata, threadPool.absoluteTimeInMillis()),
            bulkRequestModifier::markItemForFailureStore,
            bulkRequestModifier::markItemAsFailed,
            (originalThread, exception) -> {
                if (exception != null) {
                    logger.debug("failed to execute pipeline for a bulk request", exception);
                    listener.onFailure(exception);
                } else {
                    long ingestTookInMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - ingestStartTimeInNanos);
                    BulkRequest bulkRequest = bulkRequestModifier.getBulkRequest();
                    ActionListener<BulkResponse> actionListener = bulkRequestModifier.wrapActionListenerIfNeeded(
                        ingestTookInMillis,
                        listener
                    );
                    if (bulkRequest.requests().isEmpty()) {
                        // at this stage, the transport bulk action can't deal with a bulk request with no requests,
                        // so we stop and send an empty response back to the client.
                        // (this will happen if pre-processing all items in the bulk failed)
                        actionListener.onResponse(new BulkResponse(new BulkItemResponse[0], 0));
                    } else {
                        ActionRunnable<BulkResponse> runnable = new ActionRunnable<>(actionListener) {
                            @Override
                            protected void doRun() {
                                doInternalExecute(task, bulkRequest, executor, actionListener);
                            }

                            @Override
                            public boolean isForceExecution() {
                                // If we fork back to a write thread we **not** should fail, because tp queue is full.
                                // (Otherwise the work done during ingest will be lost)
                                // It is okay to force execution here. Throttling of write requests happens prior to
                                // ingest when a node receives a bulk request.
                                return true;
                            }
                        };
                        // If a processor went async and returned a response on a different thread then
                        // before we continue the bulk request we should fork back on a write thread:
                        if (originalThread == Thread.currentThread()) {
                            runnable.run();
                        } else {
                            executor.execute(runnable);
                        }
                    }
                }
            },
            executor
        );
    }

    /**
     * Determines if an index name is associated with either an existing data stream or a template
     * for one that has the failure store enabled.
     * @param indexName The index name to check.
     * @param metadata Cluster state metadata.
     * @param epochMillis A timestamp to use when resolving date math in the index name.
     * @return true if the given index name corresponds to a data stream with a failure store,
     * or if it matches a template that has a data stream failure store enabled.
     */
    static boolean shouldStoreFailure(String indexName, Metadata metadata, long epochMillis) {
        return DataStream.isFailureStoreFeatureFlagEnabled()
            && resolveFailureStoreFromMetadata(indexName, metadata, epochMillis).or(
                () -> resolveFailureStoreFromTemplate(indexName, metadata)
            ).orElse(false);
    }

    /**
     * Determines if an index name is associated with an existing data stream that has a failure store enabled.
     * @param indexName The index name to check.
     * @param metadata Cluster state metadata.
     * @param epochMillis A timestamp to use when resolving date math in the index name.
     * @return true if the given index name corresponds to an existing data stream with a failure store enabled.
     */
    private static Optional<Boolean> resolveFailureStoreFromMetadata(String indexName, Metadata metadata, long epochMillis) {
        if (indexName == null) {
            return Optional.empty();
        }

        // Get index abstraction, resolving date math if it exists
        IndexAbstraction indexAbstraction = metadata.getIndicesLookup()
            .get(IndexNameExpressionResolver.resolveDateMathExpression(indexName, epochMillis));

        // We only store failures if the failure is being written to a data stream,
        // not when directly writing to backing indices/failure stores
        if (indexAbstraction == null || indexAbstraction.isDataStreamRelated() == false) {
            return Optional.empty();
        }

        // Locate the write index for the abstraction, and check if it has a data stream associated with it.
        // This handles alias resolution as well as data stream resolution.
        Index writeIndex = indexAbstraction.getWriteIndex();
        assert writeIndex != null : "Could not resolve write index for resource [" + indexName + "]";
        IndexAbstraction writeAbstraction = metadata.getIndicesLookup().get(writeIndex.getName());
        DataStream targetDataStream = writeAbstraction.getParentDataStream();

        // We will store the failure if the write target belongs to a data stream with a failure store.
        return Optional.of(targetDataStream != null && targetDataStream.isFailureStoreEnabled());
    }

    /**
     * Determines if an index name is associated with an index template that has a data stream failure store enabled.
     * @param indexName The index name to check.
     * @param metadata Cluster state metadata.
     * @return true if the given index name corresponds to an index template with a data stream failure store enabled.
     */
    private static Optional<Boolean> resolveFailureStoreFromTemplate(String indexName, Metadata metadata) {
        if (indexName == null) {
            return Optional.empty();
        }

        // Check to see if the index name matches any templates such that an index would have been attributed
        // We don't check v1 templates at all because failure stores can only exist on data streams via a v2 template
        String template = MetadataIndexTemplateService.findV2Template(metadata, indexName, false);
        if (template != null) {
            // Check if this is a data stream template or if it is just a normal index.
            ComposableIndexTemplate composableIndexTemplate = metadata.templatesV2().get(template);
            if (composableIndexTemplate.getDataStreamTemplate() != null) {
                // Check if the data stream has the failure store enabled
                return Optional.of(composableIndexTemplate.getDataStreamTemplate().hasFailureStore());
            }
        }

        // Could not locate a failure store via template
        return Optional.empty();
    }
}
