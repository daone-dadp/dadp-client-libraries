package com.dadp.wrapper.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Java 8 compatible Hub policy API client.
 */
public class HubPolicyMaterialClient {

    private final String hubBaseUrl;
    private final int timeoutMillis;
    private final ObjectMapper objectMapper;

    public HubPolicyMaterialClient(String hubBaseUrl, int timeoutMillis) {
        this.hubBaseUrl = normalizeBaseUrl(hubBaseUrl);
        this.timeoutMillis = timeoutMillis > 0 ? timeoutMillis : 30000;
        this.objectMapper = new ObjectMapper();
    }

    public PolicyMaterial fetchByName(String policyName) {
        if (policyName == null || policyName.trim().isEmpty()) {
            throw new IllegalArgumentException("policyName is required");
        }
        return toPolicyMaterial(getData("/api/v1/policies/name/" + encodePathSegment(policyName)));
    }

    public PolicyMaterial fetchByUid(String policyUid) {
        if (policyUid == null || policyUid.trim().isEmpty()) {
            throw new IllegalArgumentException("policyUid is required");
        }
        return toPolicyMaterial(getData("/api/v1/policies/uuid/" + encodePathSegment(policyUid)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(String engineStylePath) {
        HttpURLConnection connection = null;
        String url = buildHubApiUrl(engineStylePath);
        try {
            connection = (HttpURLConnection) new java.net.URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json");

            int status = connection.getResponseCode();
            byte[] body = readAll(status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream());
            if (status < 200 || status >= 300) {
                throw new WrapperCryptoException("Hub policy API failed: status=" + status
                        + ", url=" + url + ", body=" + new String(body, StandardCharsets.UTF_8));
            }

            Map<String, Object> response = objectMapper.readValue(body, Map.class);
            Object data = response.get("data");
            if (!(data instanceof Map)) {
                throw new WrapperCryptoException("Hub policy API response has no data object: url=" + url);
            }
            return (Map<String, Object>) data;
        } catch (IOException e) {
            throw new WrapperCryptoException("Hub policy API request failed: url=" + url
                    + ", error=" + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    String buildHubApiUrl(String engineStylePath) {
        if (engineStylePath.startsWith("/hub/api/v1")) {
            return hubBaseUrl + engineStylePath;
        }
        if (engineStylePath.startsWith("/api/v1")) {
            return hubBaseUrl + "/hub" + engineStylePath;
        }
        return hubBaseUrl + "/hub/api/v1" + (engineStylePath.startsWith("/") ? "" : "/") + engineStylePath;
    }

    private static PolicyMaterial toPolicyMaterial(Map<String, Object> data) {
        String policyUid = firstString(data, "policyUid", "id");
        String policyName = stringValue(data.get("policyName"));
        String keyAlias = stringValue(data.get("keyAlias"));
        Integer keyVersion = intValue(data.get("keyVersion"));
        String algorithm = stringValue(data.get("algorithm"));
        Boolean usePlain = booleanValue(data.get("usePlain"));
        Integer plainStart = intValue(data.get("plainStart"));
        Integer plainLength = intValue(data.get("plainLength"));
        if (keyVersion == null) {
            throw new WrapperCryptoException("Hub policy response has no keyVersion");
        }
        return new PolicyMaterial(policyName, policyUid, keyAlias, keyVersion,
                algorithm, usePlain, plainStart, plainLength);
    }

    private static String normalizeBaseUrl(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "http://localhost:9004";
        }
        String baseUrl = value.trim();
        try {
            URI uri = URI.create(baseUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (scheme != null && host != null) {
                return port >= 0 ? scheme + "://" + host + ":" + port : scheme + "://" + host;
            }
        } catch (Exception ignored) {
            // Fall back to trimmed string below.
        }
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private static String encodePathSegment(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            throw new IllegalArgumentException("Path segment cannot be encoded", e);
        }
    }

    private static String firstString(Map<String, Object> data, String first, String second) {
        String value = stringValue(data.get(first));
        return value != null && !value.trim().isEmpty() ? value : stringValue(data.get(second));
    }

    private static String stringValue(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private static Integer intValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.valueOf(String.valueOf(value));
    }

    private static Boolean booleanValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.valueOf(String.valueOf(value));
    }

    private static byte[] readAll(InputStream input) throws IOException {
        if (input == null) {
            return new byte[0];
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
