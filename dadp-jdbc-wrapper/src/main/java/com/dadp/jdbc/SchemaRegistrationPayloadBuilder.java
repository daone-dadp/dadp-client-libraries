package com.dadp.jdbc;

import com.dadp.common.sync.schema.SchemaMetadata;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SchemaRegistrationPayloadBuilder {

    private SchemaRegistrationPayloadBuilder() {
    }

    public static Map<String, Object> build(String dadpJdbcUrl,
                                            String actualJdbcUrl,
                                            Connection connection,
                                            List<SchemaMetadata> schemas,
                                            String appName,
                                            String wrapperVersion,
                                            String clientInstanceId) throws SQLException {
        throw new IllegalArgumentException("alias must be supplied separately in DADP 6 schema register");
    }

    public static Map<String, Object> build(String dadpJdbcUrl,
                                            String actualJdbcUrl,
                                            Connection connection,
                                            List<SchemaMetadata> schemas,
                                            String appName,
                                            String wrapperVersion,
                                            String clientInstanceId,
                                            String existingTenantId) throws SQLException {
        throw new IllegalArgumentException("alias must be supplied separately in DADP 6 schema register");
    }

    public static Map<String, Object> buildWithAlias(String alias,
                                                     String actualJdbcUrl,
                                                     Connection connection,
                                                     List<SchemaMetadata> schemas,
                                                     String appName,
                                                     String wrapperVersion,
                                                     String clientInstanceId,
                                                     String existingTenantId) throws SQLException {
        Map<String, Object> payload = buildSchemaCacheWithAlias(
                alias,
                actualJdbcUrl,
                connection,
                schemas,
                appName,
                wrapperVersion,
                clientInstanceId);
        putIfNotBlank(payload, "tenantId", existingTenantId);
        return payload;
    }

    public static Map<String, Object> buildSchemaCache(String dadpJdbcUrl,
                                                       String actualJdbcUrl,
                                                       Connection connection,
                                                       List<SchemaMetadata> schemas,
                                                       String appName,
                                                       String wrapperVersion,
                                                       String clientInstanceId) throws SQLException {
        return buildSchemaCacheWithAlias(
                null,
                actualJdbcUrl,
                connection,
                schemas,
                appName,
                wrapperVersion,
                clientInstanceId);
    }

    public static Map<String, Object> buildSchemaCacheWithAlias(String alias,
                                                                String actualJdbcUrl,
                                                                Connection connection,
                                                                List<SchemaMetadata> schemas,
                                                                String appName,
                                                                String wrapperVersion,
                                                                String clientInstanceId) throws SQLException {
        alias = trimToNull(alias);

        DatabaseMetaData metaData = connection.getMetaData();
        DatasourceDescriptor datasource = describeDatasource(actualJdbcUrl, connection, metaData, alias);

        Map<String, Object> payload = new LinkedHashMap<>();
        putIfNotBlank(payload, "alias", alias);
        payload.put("wrapperType", "JDBC");
        putIfNotBlank(payload, "appName", appName);
        putIfNotBlank(payload, "wrapperVersion", wrapperVersion);
        putIfNotBlank(payload, "clientInstanceId", clientInstanceId);
        payload.put("datasource", datasource.toJson());
        payload.put("schema", buildSchemaDocument(schemas));

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("manualSchemaCollection", true);
        capabilities.put("localCrypto", true);
        capabilities.put("remoteCrypto", true);
        payload.put("capabilities", capabilities);

        return payload;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> buildRegistrationPayload(Map<String, Object> schemaCache,
                                                              String existingTenantId,
                                                              String appName,
                                                              String wrapperVersion,
                                                              String clientInstanceId) {
        if (schemaCache == null || schemaCache.isEmpty()) {
            throw new IllegalArgumentException("schema cache is empty");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "tenantId", existingTenantId);
        putIfPresent(payload, "alias", schemaCache.get("alias"));
        putIfPresent(payload, "wrapperType", firstNonNull(schemaCache.get("wrapperType"), "JDBC"));
        putIfPresent(payload, "appName", firstNonNull(appName, schemaCache.get("appName")));
        putIfPresent(payload, "wrapperVersion", firstNonNull(wrapperVersion, schemaCache.get("wrapperVersion")));
        putIfPresent(payload, "clientInstanceId", firstNonNull(clientInstanceId, schemaCache.get("clientInstanceId")));
        putIfPresent(payload, "datasource", schemaCache.get("datasource"));
        putIfPresent(payload, "schema", schemaCache.get("schema"));
        Object capabilities = schemaCache.get("capabilities");
        if (capabilities instanceof Map) {
            putIfPresent(payload, "capabilities", capabilities);
        } else {
            Map<String, Object> defaultCapabilities = new LinkedHashMap<>();
            defaultCapabilities.put("manualSchemaCollection", true);
            defaultCapabilities.put("localCrypto", true);
            defaultCapabilities.put("remoteCrypto", true);
            payload.put("capabilities", defaultCapabilities);
        }
        return payload;
    }

    private static Map<String, Object> buildSchemaDocument(List<SchemaMetadata> schemas) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("version", 1);
        document.put("capturedAt", Instant.now().toString());
        document.put("format", "dadp.schema.v1");

        Map<String, TableDocument> tables = new LinkedHashMap<>();
        if (schemas != null) {
            for (SchemaMetadata schema : schemas) {
                if (schema == null || trimToNull(schema.getTableName()) == null || trimToNull(schema.getColumnName()) == null) {
                    continue;
                }
                String databaseName = nullToEmpty(schema.getDatabaseName());
                String schemaName = nullToEmpty(schema.getSchemaName());
                String tableName = schema.getTableName();
                String key = databaseName + "\u0000" + schemaName + "\u0000" + tableName;
                TableDocument table = tables.get(key);
                if (table == null) {
                    table = new TableDocument(databaseName, schemaName, tableName);
                    tables.put(key, table);
                }
                table.addColumn(schema);
            }
        }

        List<Map<String, Object>> tablePayloads = new ArrayList<>();
        for (TableDocument table : tables.values()) {
            tablePayloads.add(table.toJson());
        }
        document.put("tables", tablePayloads);
        return document;
    }

    private static DatasourceDescriptor describeDatasource(String actualJdbcUrl,
                                                           Connection connection,
                                                           DatabaseMetaData metaData,
                                                           String alias) throws SQLException {
        String vendor = normalizeVendor(metaData.getDatabaseProductName(), actualJdbcUrl);
        String databaseName = trimToNull(connection.getCatalog());
        String schemaName = trimToNull(connection.getSchema());

        DatasourceDescriptor descriptor = parseJdbcUrl(actualJdbcUrl);
        descriptor.vendor = firstNonEmpty(descriptor.vendor, vendor);
        descriptor.databaseName = firstNonEmpty(descriptor.databaseName, databaseName);
        descriptor.schemaName = firstNonEmpty(descriptor.schemaName, schemaName, descriptor.databaseName);
        descriptor.datasourceKey = alias;
        descriptor.displayName = alias;
        return descriptor;
    }

    private static DatasourceDescriptor parseJdbcUrl(String actualJdbcUrl) {
        DatasourceDescriptor descriptor = new DatasourceDescriptor();
        if (actualJdbcUrl == null || !actualJdbcUrl.startsWith("jdbc:")) {
            return descriptor;
        }

        String rest = actualJdbcUrl.substring("jdbc:".length());
        if (rest.regionMatches(true, 0, "oracle:", 0, "oracle:".length())) {
            descriptor.vendor = "oracle";
            parseOracle(rest, descriptor);
            return descriptor;
        }
        if (rest.regionMatches(true, 0, "sqlserver://", 0, "sqlserver://".length())) {
            descriptor.vendor = "mssql";
            parseSqlServer(rest, descriptor);
            return descriptor;
        }

        int schemeEnd = rest.indexOf("://");
        if (schemeEnd <= 0) {
            descriptor.vendor = normalizeVendor(rest, actualJdbcUrl);
            return descriptor;
        }

        descriptor.vendor = normalizeVendor(rest.substring(0, schemeEnd), actualJdbcUrl);
        try {
            URI uri = new URI(rest);
            descriptor.host = trimToNull(uri.getHost());
            descriptor.port = uri.getPort() >= 0 ? uri.getPort() : null;
            String path = trimToNull(uri.getPath());
            if (path != null && path.startsWith("/")) {
                path = path.substring(1);
            }
            descriptor.databaseName = trimToNull(path);
        } catch (URISyntaxException ignored) {
            // Leave URL-derived fields empty; JDBC metadata still fills vendor/database/schema.
        }
        return descriptor;
    }

    private static void parseOracle(String rest, DatasourceDescriptor descriptor) {
        int marker = rest.indexOf("@//");
        if (marker < 0) {
            return;
        }
        String target = rest.substring(marker + 3);
        int slash = target.indexOf('/');
        String hostPort = slash >= 0 ? target.substring(0, slash) : target;
        descriptor.databaseName = slash >= 0 ? trimToNull(target.substring(slash + 1)) : null;
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0) {
            descriptor.host = trimToNull(hostPort.substring(0, colon));
            descriptor.port = parsePort(hostPort.substring(colon + 1));
        } else {
            descriptor.host = trimToNull(hostPort);
        }
    }

    private static void parseSqlServer(String rest, DatasourceDescriptor descriptor) {
        String withoutScheme = rest.substring("sqlserver://".length());
        int semicolon = withoutScheme.indexOf(';');
        String hostPort = semicolon >= 0 ? withoutScheme.substring(0, semicolon) : withoutScheme;
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0) {
            descriptor.host = trimToNull(hostPort.substring(0, colon));
            descriptor.port = parsePort(hostPort.substring(colon + 1));
        } else {
            descriptor.host = trimToNull(hostPort);
        }
        if (semicolon >= 0) {
            String params = withoutScheme.substring(semicolon + 1);
            for (String pair : params.split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && "databaseName".equalsIgnoreCase(pair.substring(0, eq).trim())) {
                    descriptor.databaseName = trimToNull(pair.substring(eq + 1));
                }
            }
        }
    }

    private static String normalizeVendor(String productName, String actualJdbcUrl) {
        String source = trimToNull(productName);
        if (source == null && actualJdbcUrl != null && actualJdbcUrl.startsWith("jdbc:")) {
            String rest = actualJdbcUrl.substring("jdbc:".length());
            int end = rest.indexOf(':');
            source = end > 0 ? rest.substring(0, end) : rest;
        }
        if (source == null) {
            return "unknown";
        }
        String lower = source.toLowerCase(Locale.ROOT);
        if (lower.contains("mysql")) {
            return "mysql";
        }
        if (lower.contains("mariadb")) {
            return "mariadb";
        }
        if (lower.contains("postgres")) {
            return "postgres";
        }
        if (lower.contains("oracle")) {
            return "oracle";
        }
        if (lower.contains("tibero")) {
            return "tibero";
        }
        if (lower.contains("sql server") || lower.contains("sqlserver") || lower.contains("mssql")) {
            return "mssql";
        }
        if (lower.contains("sqream")) {
            return "sqream";
        }
        return lower.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private static void putIfNotBlank(Map<String, Object> payload, String key, String value) {
        String trimmed = trimToNull(value);
        if (trimmed != null) {
            payload.put(key, trimmed);
        }
    }

    private static void putIfPresent(Map<String, Object> payload, String key, Object value) {
        if (value instanceof String) {
            putIfNotBlank(payload, key, (String) value);
        } else if (value != null) {
            payload.put(key, value);
        }
    }

    private static Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private static Integer parsePort(String value) {
        try {
            return Integer.valueOf(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static final class DatasourceDescriptor {
        private String datasourceKey;
        private String vendor;
        private String host;
        private Integer port;
        private String databaseName;
        private String schemaName;
        private String displayName;

        private Map<String, Object> toJson() {
            Map<String, Object> value = new LinkedHashMap<>();
            putIfNotBlank(value, "datasourceKey", datasourceKey);
            putIfNotBlank(value, "vendor", vendor);
            putIfNotBlank(value, "host", host);
            if (port != null) {
                value.put("port", port);
            }
            putIfNotBlank(value, "databaseName", databaseName);
            putIfNotBlank(value, "schemaName", schemaName);
            putIfNotBlank(value, "displayName", displayName);
            return value;
        }
    }

    private static final class TableDocument {
        private final String databaseName;
        private final String schemaName;
        private final String tableName;
        private final List<Map<String, Object>> columns = new ArrayList<>();

        private TableDocument(String databaseName, String schemaName, String tableName) {
            this.databaseName = databaseName;
            this.schemaName = schemaName;
            this.tableName = tableName;
        }

        private void addColumn(SchemaMetadata schema) {
            Map<String, Object> column = new LinkedHashMap<>();
            putIfNotBlank(column, "databaseName", schema.getDatabaseName());
            putIfNotBlank(column, "schemaName", schema.getSchemaName());
            putIfNotBlank(column, "tableName", schema.getTableName());
            putIfNotBlank(column, "columnName", schema.getColumnName());
            putIfNotBlank(column, "dataType", schema.getColumnType());
            putIfNotBlank(column, "columnType", schema.getColumnType());
            if (schema.getIsNullable() != null) {
                column.put("nullable", schema.getIsNullable());
                column.put("isNullable", schema.getIsNullable());
            }
            putIfNotBlank(column, "columnDefault", schema.getColumnDefault());
            columns.add(column);
        }

        private Map<String, Object> toJson() {
            Map<String, Object> table = new LinkedHashMap<>();
            putIfNotBlank(table, "databaseName", databaseName);
            putIfNotBlank(table, "schemaName", schemaName);
            putIfNotBlank(table, "tableName", tableName);
            table.put("columns", columns);
            return table;
        }
    }
}
