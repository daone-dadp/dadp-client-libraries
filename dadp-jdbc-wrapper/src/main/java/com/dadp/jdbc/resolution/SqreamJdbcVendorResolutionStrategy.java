package com.dadp.jdbc.resolution;

import com.dadp.jdbc.policy.SqlParser;

final class SqreamJdbcVendorResolutionStrategy extends StandardJdbcVendorResolutionStrategy {

    SqreamJdbcVendorResolutionStrategy(String dbVendor) {
        super(dbVendor);
    }

    @Override
    public String resolveLookupSchema(String explicitSchemaName, String currentSchemaName, String currentDatabaseName) {
        if (hasText(explicitSchemaName)) {
            return explicitSchemaName;
        }
        return "public";
    }

    @Override
    public String resolveParsedColumnName(SqlParser.SqlParseResult sqlParseResult, String rawColumnName, String columnLabel) {
        if (!hasText(rawColumnName) && hasText(columnLabel)) {
            String originalColumnName = sqlParseResult.getOriginalColumnName(columnLabel);
            return originalColumnName != null && !originalColumnName.isEmpty() ? originalColumnName : columnLabel;
        }
        return super.resolveParsedColumnName(sqlParseResult, rawColumnName, columnLabel);
    }
}
