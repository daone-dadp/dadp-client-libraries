package com.dadp.wrapper.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Java 8 compatible Hub internal key API client.
 *
 * This client intentionally follows the same Hub API contract used by Engine.
 */
public class HubInternalKeyClient {

    private final String hubBaseUrl;
    private final int timeoutMillis;
    private final AuthHeaderProvider authHeaderProvider;
    private final ObjectMapper objectMapper;

    public HubInternalKeyClient(String hubBaseUrl, int timeoutMillis, AuthHeaderProvider authHeaderProvider) {
        this.hubBaseUrl = normalizeBaseUrl(hubBaseUrl);
        this.timeoutMillis = timeoutMillis > 0 ? timeoutMillis : 30000;
        this.authHeaderProvider = authHeaderProvider;
        this.objectMapper = new ObjectMapper();
    }

    public KeyMetadata fetchKeyMetadata(String keyAlias, int keyVersion) {
        String path = "/api/v1/keys/internal/" + encodePathSegment(keyAlias) + "/" + keyVersion;
        Map<String, Object> data = getData(path);
        return new KeyMetadata(
                keyAlias,
                keyVersion,
                stringValue(data.get("provider")),
                stringValue(data.get("configJson")),
                stringValue(data.get("accessInfo")),
                stringValue(data.get("algorithm")));
    }

    public String fetchKeyData(String keyAlias, int keyVersion) {
        String path = "/api/v1/keys/internal-data/" + encodePathSegment(keyAlias) + "/" + keyVersion;
        Map<String, Object> data = getData(path);
        String keyData = stringValue(data.get("keyData"));
        if (keyData == null || keyData.trim().isEmpty()) {
            throw new WrapperCryptoException("Hub internal key-data response is empty: keyAlias=" + keyAlias
                    + ", keyVersion=" + keyVersion);
        }
        return keyData;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(String engineStylePath) {
        HttpURLConnection connection = null;
        String url = buildHubApiUrl(engineStylePath);
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json");
            if (authHeaderProvider != null) {
                String authHeader = authHeaderProvider.createAuthHeader(engineStylePath);
                if (authHeader != null && !authHeader.trim().isEmpty()) {
                    connection.setRequestProperty("X-Hub-Auth", authHeader);
                }
            }

            int status = connection.getResponseCode();
            byte[] body = readAll(status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream());
            if (status < 200 || status >= 300) {
                throw new WrapperCryptoException("Hub internal key API failed: status=" + status
                        + ", url=" + url + ", body=" + new String(body, StandardCharsets.UTF_8));
            }

            Map<String, Object> response = objectMapper.readValue(body, Map.class);
            Object data = response.get("data");
            if (!(data instanceof Map)) {
                throw new WrapperCryptoException("Hub internal key API response has no data object: url=" + url);
            }
            return (Map<String, Object>) data;
        } catch (IOException e) {
            throw new WrapperCryptoException("Hub internal key API request failed: url=" + url
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
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Path segment is empty");
        }
        return value.replace("/", "%2F");
    }

    private static String stringValue(Object value) {
        return value != null ? String.valueOf(value) : null;
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

    public interface AuthHeaderProvider {
        String createAuthHeader(String engineStylePath);
    }
}
