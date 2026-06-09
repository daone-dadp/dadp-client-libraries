package com.dadp.jdbc;

import com.dadp.common.sync.schema.SchemaMetadata;
import com.dadp.jdbc.schema.JdbcSchemaCollector;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

/**
 * Public schema collection helper for the external CLI.
 *
 * <p>This helper returns data only. The CLI decides whether to keep it in
 * memory, send it directly to Hub, or write it to a file.</p>
 */
public final class WrapperSchemaCollectSupport {

    private WrapperSchemaCollectSupport() {
    }

    public static Map<String, Object> collectSchemaCache(String dadpJdbcUrl,
                                                         Connection connection,
                                                         String appName,
                                                         String wrapperVersion,
                                                         String clientInstanceId) throws Exception {
        throw new IllegalArgumentException("alias must be supplied separately in DADP 6 schema collect");
    }

    public static Map<String, Object> collectSchemaCache(String alias,
                                                         String dadpJdbcUrl,
                                                         Connection connection,
                                                         String appName,
                                                         String wrapperVersion,
                                                         String clientInstanceId) throws Exception {
        alias = trimToNull(alias);
        if (alias == null) {
            throw new IllegalArgumentException("alias is required");
        }
        DadpJdbcUrlSupport.validateNoDadpRuntimeParams(dadpJdbcUrl);
        String actualJdbcUrl = DadpJdbcUrlSupport.extractActualUrl(dadpJdbcUrl);

        JdbcSchemaCollector collector = new JdbcSchemaCollector(alias);
        List<SchemaMetadata> schemas = collector.collectSchemas(connection);
        return SchemaRegistrationPayloadBuilder.buildSchemaCacheWithAlias(
                alias,
                actualJdbcUrl,
                connection,
                schemas,
                appName,
                wrapperVersion,
                clientInstanceId);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
