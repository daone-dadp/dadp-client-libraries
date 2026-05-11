package com.dadp.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.dadp.jdbc.policy.SqlParser;
import org.junit.jupiter.api.Test;

class JdbcVendorResolutionStrategyTest {

    @Test
    void sqreamStrategyFallsBackToLabelWhenMetadataColumnNameIsMissing() {
        SqlParser.SqlParseResult parseResult =
                new SqlParser().parse("SELECT user0_.email AS email3_0_ FROM users user0_");

        assertEquals("email",
                DadpProxyConnection.resolveParsedResultColumnName("sqream", parseResult, null, "email3_0_"));
    }

    @Test
    void defaultStrategyKeepsNullWhenMetadataColumnNameIsMissing() {
        SqlParser.SqlParseResult parseResult =
                new SqlParser().parse("SELECT user0_.email AS email3_0_ FROM users user0_");

        assertNull(DadpProxyConnection.resolveParsedResultColumnName("mysql", parseResult, null, "email3_0_"));
    }

    @Test
    void defaultStrategyStillResolvesAliasFromMetadataColumnName() {
        SqlParser.SqlParseResult parseResult =
                new SqlParser().parse("SELECT user0_.email AS email3_0_ FROM users user0_");

        assertEquals("email",
                DadpProxyConnection.resolveParsedResultColumnName("mysql", parseResult, "email", "email3_0_"));
    }
}
