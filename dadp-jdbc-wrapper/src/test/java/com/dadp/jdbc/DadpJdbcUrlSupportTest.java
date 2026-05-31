package com.dadp.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DadpJdbcUrlSupportTest {

    @Test
    void extractsOnlyAliasFromSqreamProxyParamsAndStripsDadpOnlyOptions() {
        String dadpUrl =
            "jdbc:dadp:Sqream://192.168.0.26:3108/master;"
                + "user=sqream;password=secret;ssl=false;cluster=true;service=sqream;"
                + "alias=shared-sq;enabled=true;hubUrl=http%3A%2F%2Flocalhost%3A9004;instanceId=wrapper-01;failOpen=true;enableLogging=false";

        Map<String, String> proxyParams = DadpJdbcUrlSupport.extractProxyParams(dadpUrl);
        String actualUrl = DadpJdbcUrlSupport.extractActualUrl(dadpUrl);

        assertEquals(1, proxyParams.size());
        assertEquals("shared-sq", proxyParams.get("alias"));
        assertEquals(
            "jdbc:Sqream://192.168.0.26:3108/master;user=sqream;password=secret;ssl=false;cluster=true;service=sqream",
            actualUrl
        );
        assertFalse(actualUrl.contains("alias="));
        assertFalse(actualUrl.contains("hubUrl="));
        assertFalse(actualUrl.contains("instanceId="));
    }

    @Test
    void preservesExistingQueryStyleUrlsForSupportedVendors() {
        String dadpUrl =
            "jdbc:dadp:mysql://localhost:3306/appdb?useSSL=false&hubUrl=http%3A%2F%2Flocalhost%3A9004&instanceId=test-app&enabled=true";

        Map<String, String> proxyParams = DadpJdbcUrlSupport.extractProxyParams(dadpUrl);
        String actualUrl = DadpJdbcUrlSupport.extractActualUrl(dadpUrl);

        assertEquals(0, proxyParams.size());
        assertEquals("jdbc:mysql://localhost:3306/appdb?useSSL=false", actualUrl);
    }

    @Test
    void extractsAliasFromQueryStyleUrls() {
        String dadpUrl =
            "jdbc:dadp:mysql://localhost:3306/appdb?useSSL=false&hubUrl=http%3A%2F%2Flocalhost%3A9004&alias=shared-db-a&enabled=true";

        Map<String, String> proxyParams = DadpJdbcUrlSupport.extractProxyParams(dadpUrl);
        String actualUrl = DadpJdbcUrlSupport.extractActualUrl(dadpUrl);

        assertEquals(1, proxyParams.size());
        assertEquals("shared-db-a", proxyParams.get("alias"));
        assertEquals("jdbc:mysql://localhost:3306/appdb?useSSL=false", actualUrl);
        assertFalse(actualUrl.contains("alias="));
        assertFalse(actualUrl.contains("hubUrl="));
        assertFalse(actualUrl.contains("enabled="));
    }

    @Test
    void extractsAliasFromSqreamUrls() {
        String dadpUrl =
            "jdbc:dadp:Sqream://192.168.0.26:3108/master;"
                + "user=sqream;password=secret;ssl=false;service=sqream;"
                + "alias=shared-db-sq;hubUrl=http%3A%2F%2Flocalhost%3A9004;enabled=true";

        Map<String, String> proxyParams = DadpJdbcUrlSupport.extractProxyParams(dadpUrl);
        String actualUrl = DadpJdbcUrlSupport.extractActualUrl(dadpUrl);

        assertEquals(1, proxyParams.size());
        assertEquals("shared-db-sq", proxyParams.get("alias"));
        assertFalse(actualUrl.contains("alias="));
        assertFalse(actualUrl.contains("hubUrl="));
        assertFalse(actualUrl.contains("enabled="));
    }
}
