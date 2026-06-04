package com.dadp.wrapper.crypto;

import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Java 8 client for the DADP 6.0.0 runtime execution-key resolve contract.
 */
public class RuntimeExecutionKeyClient {

    private static final DadpLogger log = DadpLoggerFactory.getLogger(RuntimeExecutionKeyClient.class);
    private static final String RESOLVE_PATH = "/hub/api/v1/runtime/execution-keys/resolve";

    private final String hubBaseUrl;
    private final int timeoutMillis;
    private final HubRuntimeHeaderProvider authHeaderProvider;
    private final ObjectMapper objectMapper;

    public RuntimeExecutionKeyClient(String hubBaseUrl, int timeoutMillis,
                                     HubRuntimeHeaderProvider authHeaderProvider) {
        this.hubBaseUrl = normalizeBaseUrl(hubBaseUrl);
        this.timeoutMillis = timeoutMillis > 0 ? timeoutMillis : 30000;
        if (authHeaderProvider == null) {
            throw new IllegalArgumentException("DADP 6.0 runtime execution-key resolve requires tenant auth");
        }
        this.authHeaderProvider = authHeaderProvider;
        this.objectMapper = new ObjectMapper();
    }

    public RuntimeExecutionKeyMaterial resolveByPolicyName(String policyName) {
        if (policyName == null || policyName.trim().isEmpty()) {
            throw new IllegalArgumentException("policyName is required");
        }
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("policyName", policyName.trim());
        request.put("purpose", "WRAPPER_LOCAL");
        return resolve(request, "policyName=" + policyName.trim());
    }

    public RuntimeExecutionKeyMaterial resolveByPolicyCode(String policyCode) {
        if (policyCode == null || policyCode.trim().isEmpty()) {
            throw new IllegalArgumentException("policyCode is required");
        }
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("policyCode", policyCode.trim());
        request.put("purpose", "WRAPPER_LOCAL");
        return resolve(request, "policyCode=" + policyCode.trim());
    }

    @SuppressWarnings("unchecked")
    private RuntimeExecutionKeyMaterial resolve(Map<String, Object> request, String label) {
        HttpURLConnection connection = null;
        String url = hubBaseUrl + RESOLVE_PATH;
        try {
            byte[] body = objectMapper.writeValueAsBytes(request);
            URL target = new URL(url);
            connection = (HttpURLConnection) target.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json");
            authHeaderProvider.applyAuthHeaders(connection, "POST", target.getPath(), target.getQuery(), body);

            OutputStream os = connection.getOutputStream();
            os.write(body);
            os.flush();
            os.close();

            int status = connection.getResponseCode();
            byte[] responseBody = readAll(status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream());
            log.trace("Runtime execution-key resolve response: {}, status={}, bodyLength={}",
                    label, status, responseBody.length);
            if (status < 200 || status >= 300) {
                throw new WrapperCryptoException("Runtime execution-key resolve failed: status=" + status
                        + ", url=" + url + ", body=" + new String(responseBody, StandardCharsets.UTF_8));
            }

            Map<String, Object> response = objectMapper.readValue(responseBody, Map.class);
            Object data = response.get("data");
            Map<String, Object> material = data instanceof Map ? (Map<String, Object>) data : response;
            RuntimeExecutionKeyMaterial parsed = parseMaterial(material);
            validateMaterial(parsed);
            log.trace("Runtime execution-key material parsed: policyCode={}, policyVersion={}, keyAlias={}, keyVersion={}, providerType={}, algorithm={}, ttlSeconds={}",
                    parsed.getPolicyCode(), parsed.getPolicyVersion(), parsed.getKeyAlias(), parsed.getKeyVersion(),
                    parsed.getProviderType(), parsed.getAlgorithm(), parsed.getCacheTtlSeconds());
            return parsed;
        } catch (IOException e) {
            throw new WrapperCryptoException("Runtime execution-key resolve request failed: url=" + url
                    + ", error=" + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static RuntimeExecutionKeyMaterial parseMaterial(Map<String, Object> data) {
        return new RuntimeExecutionKeyMaterial(
                stringValue(data.get("policyCode")),
                intValue(data.get("policyVersion"), 1),
                stringValue(data.get("keyAlias")),
                intValue(data.get("keyVersion"), 1),
                stringValue(data.get("providerType")),
                stringValue(data.get("providerVendor")),
                stringValue(data.get("algorithm")),
                stringValue(data.get("materialType")),
                stringValue(data.get("materialEncoding")),
                stringValue(data.get("executionKeyBase64")),
                intValue(data.get("cacheTtlSeconds"), 0),
                millisValue(data.get("expiresAt")),
                partialEnabled(data),
                integerValue(firstPresent(data, "plainStart", "partialPlainStart")),
                integerValue(firstPresent(data, "plainLength", "partialPlainLength")));
    }

    private static void validateMaterial(RuntimeExecutionKeyMaterial material) {
        if (!"HUB".equalsIgnoreCase(material.getProviderType())) {
            throw new UnsupportedCryptoMaterialException("Provider requires remote fallback: " + material.getProviderType());
        }
        if (!"RAW_AES_256".equalsIgnoreCase(material.getMaterialType())) {
            throw new UnsupportedCryptoMaterialException("Unsupported execution key material type: " + material.getMaterialType());
        }
        if (!"base64".equalsIgnoreCase(material.getMaterialEncoding())) {
            throw new UnsupportedCryptoMaterialException("Unsupported execution key material encoding: " + material.getMaterialEncoding());
        }
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

    private static String stringValue(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private static int intValue(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static Integer integerValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return Integer.valueOf(((Number) value).intValue());
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Object firstPresent(Map<String, Object> data, String... names) {
        if (data == null) {
            return null;
        }
        for (String name : names) {
            if (data.containsKey(name)) {
                return data.get(name);
            }
        }
        String[] nestedNames = {"policyMetadata", "metadata", "policy", "attributes"};
        for (String nestedName : nestedNames) {
            Object nested = data.get(nestedName);
            if (nested instanceof Map) {
                Object value = firstPresent((Map<String, Object>) nested, names);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private static Boolean partialEnabled(Map<String, Object> data) {
        Object usePlain = firstPresent(data, "usePlain");
        if (usePlain != null) {
            return Boolean.valueOf(booleanValue(usePlain, false));
        }
        Object partialEncryption = firstPresent(data, "partialEncryption", "partialEnabled");
        if (partialEncryption != null) {
            return Boolean.valueOf(booleanValue(partialEncryption, false));
        }
        return null;
    }

    private static boolean booleanValue(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static long millisValue(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Instant.parse(String.valueOf(value)).toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
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
