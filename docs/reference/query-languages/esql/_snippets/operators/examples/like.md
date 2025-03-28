% This is generated by ESQL's AbstractFunctionTestCase. Do no edit it. See ../README.md for how to regenerate it.

**Examples**

```esql
FROM employees
| WHERE first_name LIKE """?b*"""
| KEEP first_name, last_name
```

| first_name:keyword | last_name:keyword |
| --- | --- |
| Ebbe | Callaway |
| Eberhardt | Terkki |

Matching the exact characters `*` and `.` will require escaping. The escape character is backslash `\`. Since also backslash is a special character in string literals, it will require further escaping.

```esql
ROW message = "foo * bar"
| WHERE message LIKE "foo \\* bar"
```

To reduce the overhead of escaping, we suggest using triple quotes strings `"""`

```esql
ROW message = "foo * bar"
| WHERE message LIKE """foo \* bar"""
```

