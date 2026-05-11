package com.dadp.jdbc.resolution;

import com.dadp.jdbc.policy.SqlParser;

/**
 * Vendor-specific SQL mapping resolution strategy.
 *
 * <p>Wrapper keeps the crypto execution path common, but database vendors may differ in how they
 * expose default schema, column labels, and metadata naming. This strategy isolates those
 * differences so vendor-specific behavior does not keep leaking into the common ResultSet and
 * PreparedStatement code paths.</p>
 */
public interface JdbcVendorResolutionStrategy {

    String resolveLookupSchema(String explicitSchemaName, String currentSchemaName, String currentDatabaseName);

    String resolveParsedColumnName(SqlParser.SqlParseResult sqlParseResult, String rawColumnName, String columnLabel);
}
