package com.dadp.jdbc;

import com.dadp.jdbc.policy.SqlParser;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

final class WrapperSqlMappingDebug {

    private WrapperSqlMappingDebug() {
    }

    static boolean enabled(DadpProxyConnection connection) {
        return connection != null
                && connection.getConfig() != null
                && connection.getConfig().isSqlMappingDebugEnabled();
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
        emit("prepared-statement"
                + " alias=" + connection.getConfig().getAlias()
                + " vendor=" + connection.getDbVendor()
                + " datasourceId=" + connection.getDatasourceId()
                + " sqlType=" + value(sqlParseResult != null ? sqlParseResult.getSqlType() : null)
                + " schema=" + value(sqlParseResult != null ? sqlParseResult.getSchemaName() : null)
                + " table=" + value(sqlParseResult != null ? sqlParseResult.getTableName() : null)
                + " columns=" + (sqlParseResult != null && sqlParseResult.getColumns() != null
                ? Arrays.toString(sqlParseResult.getColumns()) : "null")
                + " parameterMapping=" + value(parameterToColumnMap)
                + " whereParams=" + value(whereClauseParamIndices)
                + " wildcardParams=" + value(sqlWildcardParamIndices)
                + " classification=" + value(statementClassification)
                + " sql=" + abbreviateSql(sql));
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
        emit("parameter-plan"
                + " alias=" + connection.getConfig().getAlias()
                + " vendor=" + connection.getDbVendor()
                + " datasourceId=" + connection.getDatasourceId()
                + " method=" + value(methodName)
                + " parameterIndex=" + parameterIndex
                + " searchContext=" + searchContext
                + " sqlWildcardSearch=" + sqlWildcardSearch
                + " schema=" + value(schemaName) + " -> " + value(normalizedSchemaName)
                + " table=" + value(tableName) + " -> " + value(normalizedTableName)
                + " column=" + value(columnName) + " -> " + value(normalizedColumnName)
                + " policy=" + value(policyName));
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
        emit("resultset-parsed"
                + " alias=" + connection.getConfig().getAlias()
                + " vendor=" + connection.getDbVendor()
                + " datasourceId=" + connection.getDatasourceId()
                + " requestedLabel=" + value(requestedLabel)
                + " columnIndex=" + columnIndex
                + " parserSchema=" + value(parserSchemaName)
                + " parserTable=" + value(parserTableName)
                + " metadataSchema=" + value(metadataSchemaName)
                + " metadataTable=" + value(metadataTableName)
                + " metadataColumnName=" + value(metadataColumnName)
                + " metadataColumnLabel=" + value(metadataColumnLabel)
                + " resolvedColumnName=" + value(resolvedColumnName)
                + " normalizedSchema=" + value(normalizedSchemaName)
                + " normalizedTable=" + value(normalizedTableName)
                + " normalizedColumn=" + value(normalizedColumnName)
                + " policy=" + value(policyName));
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
        emit("resultset-fallback"
                + " alias=" + connection.getConfig().getAlias()
                + " vendor=" + connection.getDbVendor()
                + " datasourceId=" + connection.getDatasourceId()
                + " columnIndex=" + columnIndex
                + " metadataSchema=" + value(metadataSchemaName)
                + " metadataTable=" + value(metadataTableName)
                + " metadataColumnName=" + value(metadataColumnName)
                + " metadataColumnLabel=" + value(metadataColumnLabel)
                + " lookupSchema=" + value(lookupSchemaName)
                + " normalizedSchema=" + value(normalizedSchemaName)
                + " normalizedTable=" + value(normalizedTableName)
                + " normalizedColumn=" + value(normalizedColumnName)
                + " policy=" + value(policyName));
    }

    static void logLabelResolution(DadpProxyConnection connection,
                                   String requestedLabel,
                                   Integer resolvedIndex,
                                   String resolutionSource) {
        if (!enabled(connection)) {
            return;
        }
        emit("label-resolution"
                + " alias=" + connection.getConfig().getAlias()
                + " vendor=" + connection.getDbVendor()
                + " datasourceId=" + connection.getDatasourceId()
                + " requestedLabel=" + value(requestedLabel)
                + " resolvedIndex=" + value(resolvedIndex)
                + " source=" + value(resolutionSource));
    }

    private static synchronized void emit(String message) {
        System.out.println("[DADP-MAPPING-TRACE] " + message);
    }

    private static String abbreviateSql(String sql) {
        if (sql == null) {
            return "null";
        }
        String trimmed = sql.replaceAll("\\s+", " ").trim();
        return trimmed.length() > 240 ? trimmed.substring(0, 240) + "..." : trimmed;
    }

    private static String value(Object value) {
        return value != null ? String.valueOf(value) : "null";
    }
}
