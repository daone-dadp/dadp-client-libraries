package com.dadp.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DadpJdbcUrlSupportTest {

    @Test
    void convertsDadpUrlWithDatabaseParametersOnly() {
        String dadpUrl = "jdbc:dadp:mysql://localhost:3306/appdb?useSSL=false&serverTimezone=UTC";

        DadpJdbcUrlSupport.validateNoDadpRuntimeParams(dadpUrl);
        assertTrue(DadpJdbcUrlSupport.extractProxyParams(dadpUrl).isEmpty());
        assertEquals("jdbc:mysql://localhost:3306/appdb?useSSL=false&serverTimezone=UTC",
                DadpJdbcUrlSupport.extractActualUrl(dadpUrl));
    }

    @Test
    void convertsSqreamUrlWithDatabaseParametersOnly() {
        String dadpUrl = "jdbc:dadp:Sqream://192.168.0.26:3108/master;"
                + "user=sqream;password=secret;ssl=false;cluster=true;service=sqream";

        DadpJdbcUrlSupport.validateNoDadpRuntimeParams(dadpUrl);
        assertTrue(DadpJdbcUrlSupport.extractProxyParams(dadpUrl).isEmpty());
        assertEquals("jdbc:Sqream://192.168.0.26:3108/master;"
                        + "user=sqream;password=secret;ssl=false;cluster=true;service=sqream",
                DadpJdbcUrlSupport.extractActualUrl(dadpUrl));
    }

    @Test
    void rejectsQueryStyleRuntimeParameters() {
        String dadpUrl = "jdbc:dadp:mysql://localhost:3306/appdb?useSSL=false"
                + "&hubUrl=http%3A%2F%2Flocalhost%3A9004&alias=shared-db-a";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DadpJdbcUrlSupport.validateNoDadpRuntimeParams(dadpUrl));
        assertTrue(error.getMessage().contains("hubUrl"));
    }

    @Test
    void rejectsSqreamRuntimeParameters() {
        String dadpUrl = "jdbc:dadp:Sqream://192.168.0.26:3108/master;"
                + "user=sqream;password=secret;alias=shared-db-sq";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DadpJdbcUrlSupport.validateNoDadpRuntimeParams(dadpUrl));
        assertTrue(error.getMessage().contains("alias"));
    }
}
