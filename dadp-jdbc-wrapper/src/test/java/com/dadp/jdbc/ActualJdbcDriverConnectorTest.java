package com.dadp.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class ActualJdbcDriverConnectorTest {

    @Test
    void resolvesPostgreSQLDriverFromActualUrl() {
        assertArrayEquals(new String[]{"org.postgresql.Driver"},
                ActualJdbcDriverConnector.candidateDriverClassNames("jdbc:postgresql://db:5432/app"));
    }

    @Test
    void resolvesOracleDriverFromActualUrl() {
        assertArrayEquals(new String[]{"oracle.jdbc.OracleDriver", "oracle.jdbc.driver.OracleDriver"},
                ActualJdbcDriverConnector.candidateDriverClassNames("jdbc:oracle:thin:@//db:1521/ORCL"));
    }

    @Test
    void resolvesTiberoDriverFromActualUrl() {
        assertArrayEquals(new String[]{"com.tmax.tibero.jdbc.TbDriver"},
                ActualJdbcDriverConnector.candidateDriverClassNames("jdbc:tibero:thin:@db:8629:tibero"));
    }

    @Test
    void keepsUnknownVendorsOnDriverManagerFallbackPath() {
        assertArrayEquals(new String[0],
                ActualJdbcDriverConnector.candidateDriverClassNames("jdbc:custom://db/app"));
    }
}
