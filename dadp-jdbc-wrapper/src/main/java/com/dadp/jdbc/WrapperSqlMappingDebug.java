package com.dadp.jdbc;

import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;
import com.dadp.jdbc.policy.SqlParser;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

final class WrapperSqlMappingDebug {

    private static final DadpLogger log = DadpLoggerFactory.getLogger(WrapperSqlMappingDebug.class);

    private WrapperSqlMappingDebug() {
    }

    static boolean enabled(DadpProxyConnection connection) {
        return connection != null
                && connection.getConfig() != null
                && connection.getConfig().isSqlMappingDebugEnabled()
                && log.isInfoEnabled();
    }

    static void logPreparedStatementMapping(DadpProxyConnection connection,
                                            String sql,
                                            SqlParser.SqlParseResult sqlParseResult,
                                            Map<Integer, String> parameterToColumnMap,
                                            Set<Integer> whereClauseParamIndices,
                                            Set<Integer> sqlWildcardParamIndices,
                                            Object statementClassification) {
        if (!enabled(connection)) {
            return;
        }
        log.info("[Wrapper SQL Mapping] prepared-statement alias={}, vendor={}, datasourceId={}, sqlType={}, schema={}, table={}, columns={}, parameterMapping={}, whereParams={}, wildcardParams={}, classification={}, sql={}",
                connection.getConfig().getAlias(),
                connection.getDbVendor(),
                connection.getDatasourceId(),
                sqlParseResult != null ? sqlParseResult.getSqlType() : null,
                sqlParseResult != null ? sqlParseResult.getSchemaName() : null,
                sqlParseResult != null ? sqlParseResult.getTableName() : null,
                sqlParseResult != null && sqlParseResult.getColumns() != null ? Arrays.toString(sqlParseResult.getColumns()) : "null",
                parameterToColumnMap,
                whereClauseParamIndices,
                sqlWildcardParamIndices,
                statementClassification,
                abbreviateSql(sql));
    }

    static void logParameterPlan(DadpProxyConnection connection,
                                 String methodName,
                                 int parameterIndex,
                                 String schemaName,
                                 String normalizedSchemaName,
                                 String tableName,
                                 String normalizedTableName,
                                 String columnName,
                                 String normalizedColumnName,
                                 String policyName,
                                 boolean searchContext,
                                 boolean sqlWildcardSearch) {
        if (!enabled(connection)) {
            return;
        }
        log.info("[Wrapper SQL Mapping] parameter-plan alias={}, vendor={}, datasourceId={}, method={}, parameterIndex={}, searchContext={}, sqlWildcardSearch={}, schema={} -> {}, table={} -> {}, column={} -> {}, policy={}",
                connection.getConfig().getAlias(),
                connection.getDbVendor(),
                connection.getDatasourceId(),
                methodName,
                parameterIndex,
                searchContext,
                sqlWildcardSearch,
                schemaName,
                normalizedSchemaName,
                tableName,
                normalizedTableName,
                columnName,
                normalizedColumnName,
                policyName);
    }

    static void logParsedResultSetPlan(DadpProxyConnection connection,
                                       int columnIndex,
                                       String requestedLabel,
                                       String parserSchemaName,
                                       String parserTableName,
                                       String metadataSchemaName,
                                       String metadataTableName,
                                       String metadataColumnName,
                                       String metadataColumnLabel,
                                       String resolvedColumnName,
                                       String normalizedSchemaName,
                                       String normalizedTableName,
                                       String normalizedColumnName,
                                       String policyName) {
        if (!enabled(connection)) {
            return;
        }
        log.info("[Wrapper SQL Mapping] resultset-parsed alias={}, vendor={}, datasourceId={}, requestedLabel={}, columnIndex={}, parserSchema={}, parserTable={}, metadataSchema={}, metadataTable={}, metadataColumnName={}, metadataColumnLabel={}, resolvedColumnName={}, normalizedSchema={}, normalizedTable={}, normalizedColumn={}, policy={}",
                connection.getConfig().getAlias(),
                connection.getDbVendor(),
                connection.getDatasourceId(),
                requestedLabel,
                columnIndex,
                parserSchemaName,
                parserTableName,
                metadataSchemaName,
                metadataTableName,
                metadataColumnName,
                metadataColumnLabel,
                resolvedColumnName,
                normalizedSchemaName,
                normalizedTableName,
                normalizedColumnName,
                policyName);
    }

    static void logFallbackResultSetPlan(DadpProxyConnection connection,
                                         int columnIndex,
                                         String metadataSchemaName,
                                         String metadataTableName,
                                         String metadataColumnName,
                                         String metadataColumnLabel,
                                         String lookupSchemaName,
                                         String normalizedSchemaName,
                                         String normalizedTableName,
                                         String normalizedColumnName,
                                         String policyName) {
        if (!enabled(connection)) {
            return;
        }
        log.info("[Wrapper SQL Mapping] resultset-fallback alias={}, vendor={}, datasourceId={}, columnIndex={}, metadataSchema={}, metadataTable={}, metadataColumnName={}, metadataColumnLabel={}, lookupSchema={}, normalizedSchema={}, normalizedTable={}, normalizedColumn={}, policy={}",
                connection.getConfig().getAlias(),
                connection.getDbVendor(),
                connection.getDatasourceId(),
                columnIndex,
                metadataSchemaName,
                metadataTableName,
                metadataColumnName,
                metadataColumnLabel,
                lookupSchemaName,
                normalizedSchemaName,
                normalizedTableName,
                normalizedColumnName,
                policyName);
    }

    static void logLabelResolution(DadpProxyConnection connection,
                                   String requestedLabel,
                                   Integer resolvedIndex,
                                   String resolutionSource) {
        if (!enabled(connection)) {
            return;
        }
        log.info("[Wrapper SQL Mapping] label-resolution alias={}, vendor={}, datasourceId={}, requestedLabel={}, resolvedIndex={}, source={}",
                connection.getConfig().getAlias(),
                connection.getDbVendor(),
                connection.getDatasourceId(),
                requestedLabel,
                resolvedIndex,
                resolutionSource);
    }

    private static String abbreviateSql(String sql) {
        if (sql == null) {
            return "null";
        }
        String trimmed = sql.replaceAll("\\s+", " ").trim();
        return trimmed.length() > 240 ? trimmed.substring(0, 240) + "..." : trimmed;
    }
}
