package com.dadp.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DadpJdbcUrlSupportTest {

    @Test
    void extractsSqreamProxyParamsAndStripsWrapperOnlyOptions() {
        String dadpUrl =
            "jdbc:dadp:Sqream://192.168.0.26:3108/master;"
                + "user=sqream;password=secret;ssl=false;cluster=true;service=sqream;"
                + "enabled=true;hubUrl=http%3A%2F%2Flocalhost%3A9004;instanceId=wrapper-01;failOpen=true;enableLogging=false";

        Map<String, String> proxyParams = DadpJdbcUrlSupport.extractProxyParams(dadpUrl);
        String actualUrl = DadpJdbcUrlSupport.extractActualUrl(dadpUrl);

        assertEquals("true", proxyParams.get("enabled"));
        assertEquals("http://localhost:9004", proxyParams.get("hubUrl"));
        assertEquals("wrapper-01", proxyParams.get("instanceId"));
        assertEquals("true", proxyParams.get("failOpen"));
        assertEquals("false", proxyParams.get("enableLogging"));
        assertEquals(
            "jdbc:Sqream://192.168.0.26:3108/master;user=sqream;password=secret;ssl=false;cluster=true;service=sqream",
            actualUrl
        );
        assertFalse(actualUrl.contains("hubUrl="));
        assertFalse(actualUrl.contains("instanceId="));
    }

    @Test
    void preservesExistingQueryStyleUrlsForSupportedVendors() {
        String dadpUrl =
            "jdbc:dadp:mysql://localhost:3306/appdb?useSSL=false&hubUrl=http%3A%2F%2Flocalhost%3A9004&instanceId=test-app&enabled=true";

        Map<String, String> proxyParams = DadpJdbcUrlSupport.extractProxyParams(dadpUrl);
        String actualUrl = DadpJdbcUrlSupport.extractActualUrl(dadpUrl);

        assertEquals("http://localhost:9004", proxyParams.get("hubUrl"));
        assertEquals("test-app", proxyParams.get("instanceId"));
        assertEquals("true", proxyParams.get("enabled"));
        assertEquals("jdbc:mysql://localhost:3306/appdb?useSSL=false", actualUrl);
    }

    @Test
    void extractsAliasFromQueryStyleUrls() {
        String dadpUrl =
            "jdbc:dadp:mysql://localhost:3306/appdb?useSSL=false&hubUrl=http%3A%2F%2Flocalhost%3A9004&alias=shared-db-a&enabled=true";

        Map<String, String> proxyParams = DadpJdbcUrlSupport.extractProxyParams(dadpUrl);
        String actualUrl = DadpJdbcUrlSupport.extractActualUrl(dadpUrl);

        assertEquals("http://localhost:9004", proxyParams.get("hubUrl"));
        assertEquals("shared-db-a", proxyParams.get("alias"));
        assertEquals("true", proxyParams.get("enabled"));
        assertEquals("jdbc:mysql://localhost:3306/appdb?useSSL=false", actualUrl);
        assertFalse(actualUrl.contains("alias="));
    }

    @Test
    void extractsAliasFromSqreamUrls() {
        String dadpUrl =
            "jdbc:dadp:Sqream://192.168.0.26:3108/master;"
                + "user=sqream;password=secret;ssl=false;service=sqream;"
                + "alias=shared-db-sq;hubUrl=http%3A%2F%2Flocalhost%3A9004;enabled=true";

        Map<String, String> proxyParams = DadpJdbcUrlSupport.extractProxyParams(dadpUrl);
        String actualUrl = DadpJdbcUrlSupport.extractActualUrl(dadpUrl);

        assertEquals("shared-db-sq", proxyParams.get("alias"));
        assertEquals("http://localhost:9004", proxyParams.get("hubUrl"));
        assertEquals("true", proxyParams.get("enabled"));
        assertFalse(actualUrl.contains("alias="));
    }
}
