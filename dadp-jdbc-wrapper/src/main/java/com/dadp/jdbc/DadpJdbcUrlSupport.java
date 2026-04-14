package com.dadp.jdbc;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class DadpJdbcUrlSupport {

    static final String DADP_URL_PREFIX = "jdbc:dadp:";

    private static final List<String> PROXY_PARAM_KEYS = Arrays.asList(
        "hubUrl", "instanceId", "failOpen", "enableLogging", "enabled"
    );

    private DadpJdbcUrlSupport() {
    }

    static Map<String, String> extractProxyParams(String dadpUrl) {
        if (dadpUrl == null || !dadpUrl.startsWith(DADP_URL_PREFIX)) {
            return new HashMap<>();
        }

        String urlWithoutPrefix = dadpUrl.substring(DADP_URL_PREFIX.length());
        if (isSqreamUrl(urlWithoutPrefix)) {
            return extractSqreamProxyParams(urlWithoutPrefix);
        }
        return extractQueryProxyParams(dadpUrl);
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

    private static Map<String, String> extractSqreamProxyParams(String urlWithoutPrefix) {
        Map<String, String> params = new HashMap<>();
        int firstSemicolon = urlWithoutPrefix.indexOf(';');
        if (firstSemicolon < 0 || firstSemicolon == urlWithoutPrefix.length() - 1) {
            return params;
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
            String value = pair.substring(eqIndex + 1).trim();
            if (PROXY_PARAM_KEYS.contains(key)) {
                params.put(key, decode(value));
            }
        }
        return params;
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
                if (!PROXY_PARAM_KEYS.contains(key)) {
                    preservedParams.add(pair);
                }
            } else {
                preservedParams.add(pair);
            }
        }

        return preservedParams.isEmpty() ? baseUrl : baseUrl + ";" + String.join(";", preservedParams);
    }

    private static Map<String, String> extractQueryProxyParams(String dadpUrl) {
        Map<String, String> params = new HashMap<>();

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
            return params;
        }

        String queryString = dadpUrl.substring(paramStartIndex + 1);
        for (String pair : queryString.split("&")) {
            int eqIndex = pair.indexOf('=');
            if (eqIndex <= 0) {
                continue;
            }
            String key = pair.substring(0, eqIndex).trim();
            String value = pair.substring(eqIndex + 1).trim();
            if (PROXY_PARAM_KEYS.contains(key)) {
                params.put(key, decode(value));
            }
        }

        return params;
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
                if (!PROXY_PARAM_KEYS.contains(key)) {
                    validParams.add(pair);
                }
            } else {
                validParams.add(pair);
            }
        }

        return validParams.isEmpty() ? baseUrl : baseUrl + "?" + String.join("&", validParams);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}
