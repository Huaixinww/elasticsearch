/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.services.alibabacloudsearch.sparse;

import org.elasticsearch.TransportVersion;
import org.elasticsearch.TransportVersions;
import org.elasticsearch.common.ValidationException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.inference.ServiceSettings;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xpack.inference.services.ConfigurationParseContext;
import org.elasticsearch.xpack.inference.services.alibabacloudsearch.AlibabaCloudSearchServiceSettings;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

public class AlibabaCloudSearchSparseServiceSettings implements ServiceSettings {
    public static final String NAME = "alibabacloud_search_sparse_embeddings_service_settings";

    public static AlibabaCloudSearchSparseServiceSettings fromMap(Map<String, Object> map, ConfigurationParseContext context) {
        ValidationException validationException = new ValidationException();
        var commonServiceSettings = AlibabaCloudSearchServiceSettings.fromMap(map, context);
        if (validationException.validationErrors().isEmpty() == false) {
            throw validationException;
        }

        return new AlibabaCloudSearchSparseServiceSettings(commonServiceSettings);
    }

    private final AlibabaCloudSearchServiceSettings commonSettings;

    public AlibabaCloudSearchSparseServiceSettings(AlibabaCloudSearchServiceSettings commonSettings) {
        this.commonSettings = commonSettings;
    }

    public AlibabaCloudSearchSparseServiceSettings(StreamInput in) throws IOException {
        commonSettings = new AlibabaCloudSearchServiceSettings(in);
    }

    public AlibabaCloudSearchServiceSettings getCommonSettings() {
        return commonSettings;
    }

    @Override
    public String modelId() {
        return commonSettings.modelId();
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        commonSettings.toXContentFragment(builder, params);
        builder.endObject();
        return builder;
    }

    @Override
    public ToXContentObject getFilteredXContentObject() {
        return this;
    }

    @Override
    public TransportVersion getMinimalSupportedVersion() {
        return TransportVersions.V_8_16_0;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        commonSettings.writeTo(out);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AlibabaCloudSearchSparseServiceSettings that = (AlibabaCloudSearchSparseServiceSettings) o;
        return Objects.equals(commonSettings, that.commonSettings);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonSettings);
    }
}
