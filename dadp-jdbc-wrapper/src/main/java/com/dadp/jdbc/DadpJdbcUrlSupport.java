package com.dadp.jdbc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

final class DadpJdbcUrlSupport {

    static final String DADP_URL_PREFIX = "jdbc:dadp:";

    private static final List<String> REMOVED_DADP_PARAM_KEYS = Arrays.asList(
        "hubUrl",
        "alias",
        "instanceId",
        "failOpen",
        "enableLogging",
        "enabled",
        "cryptoMode",
        "cryptoLocalFallbackRemote",
        "wrapperCryptoStatsEnabled",
        "sqlMappingDebugEnabled",
        "policySyncAutoEnabled",
        "tenantId",
        "datasourceId",
        "runtimeAuthKey",
        "runtimeAuthSecret",
        "wrapperAuthKey",
        "wrapperAuthSecret"
    );

    private DadpJdbcUrlSupport() {
    }

    static Map<String, String> extractProxyParams(String dadpUrl) {
        validateNoDadpRuntimeParams(dadpUrl);
        return Collections.emptyMap();
    }

    static void validateNoDadpRuntimeParams(String dadpUrl) {
        if (dadpUrl == null || !dadpUrl.startsWith(DADP_URL_PREFIX)) {
            return;
        }

        String urlWithoutPrefix = dadpUrl.substring(DADP_URL_PREFIX.length());
        if (isSqreamUrl(urlWithoutPrefix)) {
            validateNoSqreamDadpRuntimeParams(urlWithoutPrefix);
            return;
        }
        validateNoQueryDadpRuntimeParams(dadpUrl);
    }

    static String extractActualUrl(String dadpUrl) {
        if (dadpUrl == null || !dadpUrl.startsWith(DADP_URL_PREFIX)) {
            throw new IllegalArgumentException("Invalid DADP URL: " + dadpUrl);
        }

        String urlWithoutPrefix = dadpUrl.substring(DADP_URL_PREFIX.length());
        if (isSqreamUrl(urlWithoutPrefix)) {
            return "jdbc:" + stripSqreamProxyParams(urlWithoutPrefix);
        }
        return "jdbc:" + stripQueryProxyParams(urlWithoutPrefix);
    }

    private static boolean isSqreamUrl(String urlWithoutPrefix) {
        return urlWithoutPrefix.regionMatches(true, 0, "sqream://", 0, "sqream://".length());
    }

    private static void validateNoSqreamDadpRuntimeParams(String urlWithoutPrefix) {
        int firstSemicolon = urlWithoutPrefix.indexOf(';');
        if (firstSemicolon < 0 || firstSemicolon == urlWithoutPrefix.length() - 1) {
            return;
        }

        String parameterSection = urlWithoutPrefix.substring(firstSemicolon + 1);
        for (String pair : parameterSection.split(";")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eqIndex = pair.indexOf('=');
            if (eqIndex <= 0) {
                continue;
            }
            String key = pair.substring(0, eqIndex).trim();
            if (isDadpOnlyParam(key)) {
                throw forbiddenRuntimeParam(key);
            }
        }
    }

    private static String stripSqreamProxyParams(String urlWithoutPrefix) {
        int firstSemicolon = urlWithoutPrefix.indexOf(';');
        if (firstSemicolon < 0 || firstSemicolon == urlWithoutPrefix.length() - 1) {
            return urlWithoutPrefix;
        }

        String baseUrl = urlWithoutPrefix.substring(0, firstSemicolon);
        String parameterSection = urlWithoutPrefix.substring(firstSemicolon + 1);
        List<String> preservedParams = new ArrayList<>();

        for (String pair : parameterSection.split(";")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eqIndex = pair.indexOf('=');
            if (eqIndex > 0) {
                String key = pair.substring(0, eqIndex).trim();
                if (!isDadpOnlyParam(key)) {
                    preservedParams.add(pair);
                }
            } else {
                preservedParams.add(pair);
            }
        }

        return preservedParams.isEmpty() ? baseUrl : baseUrl + ";" + String.join(";", preservedParams);
    }

    private static void validateNoQueryDadpRuntimeParams(String dadpUrl) {
        int queryIndex = dadpUrl.indexOf('?');
        int ampIndex = dadpUrl.indexOf('&');
        int paramStartIndex = -1;
        if (queryIndex != -1 && ampIndex != -1) {
            paramStartIndex = Math.min(queryIndex, ampIndex);
        } else if (queryIndex != -1) {
            paramStartIndex = queryIndex;
        } else if (ampIndex != -1) {
            paramStartIndex = ampIndex;
        }

        if (paramStartIndex == -1) {
            return;
        }

        String queryString = dadpUrl.substring(paramStartIndex + 1);
        for (String pair : queryString.split("&")) {
            int eqIndex = pair.indexOf('=');
            if (eqIndex <= 0) {
                continue;
            }
            String key = pair.substring(0, eqIndex).trim();
            if (isDadpOnlyParam(key)) {
                throw forbiddenRuntimeParam(key);
            }
        }
    }

    private static String stripQueryProxyParams(String urlWithoutPrefix) {
        int queryIndex = urlWithoutPrefix.indexOf('?');
        int ampIndex = urlWithoutPrefix.indexOf('&');
        int paramStartIndex = -1;
        if (queryIndex != -1 && ampIndex != -1) {
            paramStartIndex = Math.min(queryIndex, ampIndex);
        } else if (queryIndex != -1) {
            paramStartIndex = queryIndex;
        } else if (ampIndex != -1) {
            paramStartIndex = ampIndex;
        }

        if (paramStartIndex == -1) {
            return urlWithoutPrefix;
        }

        String baseUrl = urlWithoutPrefix.substring(0, paramStartIndex);
        String queryString = urlWithoutPrefix.substring(paramStartIndex + 1);
        List<String> validParams = new ArrayList<>();

        for (String pair : queryString.split("&")) {
            int eqIndex = pair.indexOf('=');
            if (eqIndex > 0) {
                String key = pair.substring(0, eqIndex).trim();
                if (!isDadpOnlyParam(key)) {
                    validParams.add(pair);
                }
            } else {
                validParams.add(pair);
            }
        }

        return validParams.isEmpty() ? baseUrl : baseUrl + "?" + String.join("&", validParams);
    }

    private static boolean isDadpOnlyParam(String key) {
        return REMOVED_DADP_PARAM_KEYS.contains(key);
    }

    private static IllegalArgumentException forbiddenRuntimeParam(String key) {
        return new IllegalArgumentException("DADP 6 JDBC URL must contain DB connection parameters only; remove DADP runtime parameter: " + key);
    }
}
