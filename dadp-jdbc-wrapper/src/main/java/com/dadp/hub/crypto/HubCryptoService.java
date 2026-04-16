package com.dadp.hub.crypto;

import com.dadp.hub.crypto.dto.*;
import com.dadp.hub.crypto.exception.HubCryptoException;
import com.dadp.hub.crypto.exception.HubConnectionException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wrapper-side Hub crypto service with no Spring dependency.
 *
 * This implementation talks directly to the Engine over {@link HttpURLConnection}
 * and preserves the same public API surface as the hub-crypto-lib variant.
 *
 * @author DADP Development Team
 * @version 5.8.3
 * @since 2025-01-01
 */
public class HubCryptoService {

    private static final Logger log = LoggerFactory.getLogger(HubCryptoService.class);
    private static final AtomicLong HTTP_REQUEST_SEQUENCE = new AtomicLong(0);

    private final ObjectMapper objectMapper;
    private final SSLContext sslContext;
    private String hubUrl;
    private String apiBasePath = "/api";
    private int timeout;
    private boolean enableLogging;
    private boolean initialized = false;

    @Deprecated
    private static final String HUB_API_PATH = "/hub/api/v1";
    private static final String ENGINE_API_PATH = "/api";

    // Telemetry
    private volatile String lastUsedEndpoint = null;
    private volatile long endpointUsageCount = 0;

    // HTTP response holder

    private static class HttpResponse {
        final int statusCode;
        final String body;
        HttpResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
        boolean is2xx() { return statusCode >= 200 && statusCode < 300; }
    }

    // SSL helpers

    private static String getDadpCaCertPath() {
        String caCertPath = System.getenv("DADP_CA_CERT_PATH");
        if (caCertPath == null || caCertPath.trim().isEmpty()) {
            caCertPath = System.getProperty("dadp.ca.cert.path");
        }
        return caCertPath != null && !caCertPath.trim().isEmpty() ? caCertPath.trim() : null;
    }

    private static X509Certificate pemToCertificate(String pem) throws Exception {
        String certContent = pem.replace("-----BEGIN CERTIFICATE-----", "")
                                .replace("-----END CERTIFICATE-----", "")
                                .replaceAll("\\s", "");
        byte[] certBytes = Base64.getDecoder().decode(certContent);
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) certFactory.generateCertificate(new ByteArrayInputStream(certBytes));
    }

    private static SSLContext createDadpCaSSLContext() {
        String caCertPath = getDadpCaCertPath();
        if (caCertPath == null) {
            return null;
        }
        try {
            String pem = new String(Files.readAllBytes(Paths.get(caCertPath)));
            X509Certificate caCert = pemToCertificate(pem);

            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("dadp-root-ca", caCert);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);

            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), new java.security.SecureRandom());

            log.info("DADP CA SSL configured: path={}", caCertPath);
            return ctx;
        } catch (Exception e) {
            log.warn("Failed to load DADP CA certificate, using default SSL: path={}, error={}", caCertPath, e.getMessage());
            return null;
        }
    }

    // Construction and factory methods

    public HubCryptoService() {
        this.sslContext = createDadpCaSSLContext();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public static HubCryptoService createInstance() {
        boolean enableLogging = isLoggingEnabled();
        return createInstance("http://localhost:9003", 5000, enableLogging);
    }

    public static HubCryptoService createInstance(String hubUrl, int timeout, Boolean enableLogging) {
        boolean logging = enableLogging != null ? enableLogging : isLoggingEnabled();
        return createInstance(hubUrl, null, timeout, logging);
    }

    public static HubCryptoService createInstance(String hubUrl, String apiBasePath, int timeout, Boolean enableLogging) {
        boolean logging = enableLogging != null ? enableLogging : isLoggingEnabled();
        HubCryptoService instance = new HubCryptoService();

        String baseUrl = extractBaseUrl(hubUrl);
        instance.hubUrl = baseUrl;

        if (apiBasePath == null || apiBasePath.trim().isEmpty()) {
            apiBasePath = ENGINE_API_PATH;
        }

        if (apiBasePath.contains("/hub/api") || apiBasePath.equals(HUB_API_PATH)) {
            String errorMsg = String.format(
                "Direct Hub crypto path is not allowed. Only Engine path (/api) is permitted. Detected path: %s", apiBasePath);
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        instance.apiBasePath = apiBasePath;
        instance.timeout = timeout;
        instance.enableLogging = logging;
        instance.initialized = true;

        if (logging) {
            log.info("HubCryptoService initialized (Wrapper/HttpURLConnection): baseUrl={}, apiBasePath={}, timeout={}ms",
                    baseUrl, instance.apiBasePath, timeout);
        }

        return instance;
    }

    // Utility helpers

    private static boolean isLoggingEnabled() {
        String val = System.getenv("DADP_ENABLE_LOGGING");
        if (val == null || val.trim().isEmpty()) {
            val = System.getProperty("dadp.enable-logging");
        }
        return val != null && !val.trim().isEmpty() && ("true".equalsIgnoreCase(val) || "1".equals(val));
    }

    private static String extractBaseUrl(String url) {
        if (url == null || url.trim().isEmpty()) return url;
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (scheme == null || host == null) return url.trim();
            return port != -1 ? scheme + "://" + host + ":" + port : scheme + "://" + host;
        } catch (Exception e) {
            log.warn("URL parsing failed, using original: {}", url);
            return url.trim();
        }
    }

    public void setApiBasePath(String apiBasePath) {
        String path = apiBasePath != null ? apiBasePath : "/api";
        if (path.contains("/hub/api") || path.equals(HUB_API_PATH)) {
            throw new IllegalStateException("Direct Hub crypto path is not allowed. Only Engine path (/api) is permitted.");
        }
        this.apiBasePath = path;
    }

    public String getApiBasePath() { return this.apiBasePath; }
    public boolean isInitialized() { return initialized; }

    public void initializeIfNeeded() {
        if (!isInitialized()) {
            this.initialized = true;
            if (enableLogging) log.info("HubCryptoService runtime initialization completed");
        }
    }

    private void validateNotHubPath() {
        if (apiBasePath != null && (apiBasePath.contains("/hub/api") || apiBasePath.equals(HUB_API_PATH))) {
            String msg = "Direct Hub crypto path is not allowed. Current path: " + apiBasePath;
            log.error(msg);
            throw new IllegalStateException(msg);
        }
    }

    private void recordEndpointUsage(String endpoint) {
        this.lastUsedEndpoint = endpoint;
        this.endpointUsageCount++;
        if (enableLogging && log.isDebugEnabled() && (endpointUsageCount == 1 || endpointUsageCount % 100 == 0)) {
            log.debug("Wrapper endpoint usage: endpoint={}, count={}", endpoint, endpointUsageCount);
        }
    }

    public String getLastUsedEndpoint() { return lastUsedEndpoint; }
    public long getEndpointUsageCount() { return endpointUsageCount; }

    private static double elapsedMs(long startedNs, long finishedNs) {
        return (finishedNs - startedNs) / 1_000_000.0;
    }

    private static int utf8Length(String value) {
        return value != null ? value.getBytes(StandardCharsets.UTF_8).length : 0;
    }

    private static String summarizeEndpoint(String url) {
        try {
            return java.net.URI.create(url).getPath();
        } catch (Exception e) {
            return url;
        }
    }

    // HTTP execution

    private HttpResponse doPost(String operation, String url, String requestBody) {
        HttpURLConnection conn = null;
        boolean responseFullyConsumed = false;
        long requestId = HTTP_REQUEST_SEQUENCE.incrementAndGet();
        int requestBytes = utf8Length(requestBody);
        String endpoint = summarizeEndpoint(url);
        long startedNs = System.nanoTime();
        long writeStartedNs = startedNs;
        long writeFinishedNs = startedNs;
        long responseStartedNs = startedNs;
        long responseFinishedNs = startedNs;
        long readStartedNs = startedNs;
        long readFinishedNs = startedNs;
        try {
            if (enableLogging && log.isDebugEnabled()) {
                log.debug(
                    "Wrapper HTTP request start: requestId={}, operation={}, endpoint={}, requestBytes={}",
                    requestId, operation, endpoint, requestBytes);
            }

            URL urlObj = new URL(url);
            conn = (HttpURLConnection) urlObj.openConnection();

            if (conn instanceof HttpsURLConnection && sslContext != null) {
                ((HttpsURLConnection) conn).setSSLSocketFactory(sslContext.getSocketFactory());
            }

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);

            byte[] body = requestBody.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            writeStartedNs = System.nanoTime();
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();
            writeFinishedNs = System.nanoTime();

            responseStartedNs = System.nanoTime();
            int code = conn.getResponseCode();
            responseFinishedNs = System.nanoTime();
            readStartedNs = System.nanoTime();
            String responseBody = readStream(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            readFinishedNs = System.nanoTime();
            responseFullyConsumed = true;

            if (enableLogging && log.isDebugEnabled()) {
                log.debug(
                    "Wrapper HTTP request complete: requestId={}, operation={}, endpoint={}, status={}, requestBytes={}, responseBytes={}, writeMs={}, responseCodeMs={}, readMs={}, totalMs={}",
                    requestId,
                    operation,
                    endpoint,
                    code,
                    requestBytes,
                    utf8Length(responseBody),
                    String.format("%.3f", elapsedMs(writeStartedNs, writeFinishedNs)),
                    String.format("%.3f", elapsedMs(responseStartedNs, responseFinishedNs)),
                    String.format("%.3f", elapsedMs(readStartedNs, readFinishedNs)),
                    String.format("%.3f", elapsedMs(startedNs, readFinishedNs)));
            }

            if (code < 200 || code >= 300) {
                log.warn(
                    "Wrapper HTTP request returned non-2xx: requestId={}, operation={}, endpoint={}, status={}, totalMs={}",
                    requestId,
                    operation,
                    endpoint,
                    code,
                    String.format("%.3f", elapsedMs(startedNs, readFinishedNs)));
            }

            return new HttpResponse(code, responseBody);

        } catch (java.net.SocketTimeoutException e) {
            log.warn(
                "Wrapper HTTP request timed out: requestId={}, operation={}, endpoint={}, requestBytes={}, timeoutMs={}, totalMs={}",
                requestId,
                operation,
                endpoint,
                requestBytes,
                timeout,
                String.format("%.3f", elapsedMs(startedNs, System.nanoTime())));
            throw new HubConnectionException("Engine connection timeout: " + e.getMessage(), e);
        } catch (IOException e) {
            log.warn(
                "Wrapper HTTP request failed: requestId={}, operation={}, endpoint={}, requestBytes={}, totalMs={}, error={}",
                requestId,
                operation,
                endpoint,
                requestBytes,
                String.format("%.3f", elapsedMs(startedNs, System.nanoTime())),
                e.getMessage());
            throw new HubConnectionException("Engine connection failed: " + e.getMessage(), e);
        } finally {
            // Keep successful connections eligible for JDK keep-alive reuse.
            if (conn != null && !responseFullyConsumed) {
                conn.disconnect();
            }
        }
    }

    private static String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } finally {
            is.close();
        }
    }

    // Encrypt

    public String encrypt(String data, String policy) {
        return encrypt(data, policy, false);
    }

    public String encrypt(String data, String policy, boolean includeStats) {
        initializeIfNeeded();
        validateNotHubPath();

        if (enableLogging && log.isDebugEnabled()) {
            log.debug("Wrapper encrypt request: dataLength={}, policy={}",
                    data != null ? data.length() : 0, policy);
        }

        try {
            String url = hubUrl + apiBasePath + "/encrypt";
            recordEndpointUsage(url);

            EncryptRequest request = new EncryptRequest();
            request.setData(data);
            request.setPolicyName(policy);

            String requestBody;
            try {
                requestBody = objectMapper.writeValueAsString(request);
            } catch (Exception e) {
                throw new HubCryptoException("Request data serialization failed: " + e.getMessage());
            }

            HttpResponse response = doPost("encrypt", url, requestBody);

            if (response.is2xx()) {
                JsonNode rootNode = parseJson(response.body);

                // Prefer the v2 "code" contract, then fall back to the legacy success flag.
                JsonNode codeNode = rootNode.get("code");
                boolean responseSuccess;
                if (codeNode != null && codeNode.isTextual()) {
                    responseSuccess = "SUCCESS".equals(codeNode.asText());
                } else {
                    JsonNode successNode = rootNode.get("success");
                    responseSuccess = successNode != null && successNode.asBoolean();
                }
                if (!responseSuccess) {
                    JsonNode messageNode = rootNode.get("message");
                    String errorMessage = messageNode != null && !messageNode.isNull() ? messageNode.asText() : "Encryption failed";
                    throw new HubCryptoException("Encryption failed: " + errorMessage);
                }

                JsonNode dataNode = rootNode.get("data");
                if (dataNode == null || dataNode.isNull()) {
                    throw new HubCryptoException("Encryption failed: no data field in response");
                }

                // Engine response: "data" is the encrypted string.
                if (dataNode.isTextual()) {
                    String encryptedData = dataNode.asText();
                    if (enableLogging && log.isDebugEnabled()) {
                        log.debug("Wrapper encrypt response parsed: encryptedLength={}",
                                encryptedData.length());
                    }
                    return encryptedData;
                }

                // Legacy response: "data" contains an EncryptResponse object.
                EncryptResponse encryptResponse;
                try {
                    encryptResponse = objectMapper.treeToValue(dataNode, EncryptResponse.class);
                } catch (Exception e) {
                    throw new HubCryptoException("Engine response data parsing failed: " + e.getMessage());
                }

                if (encryptResponse != null && encryptResponse.getSuccess() != null
                        && encryptResponse.getSuccess() && encryptResponse.getEncryptedData() != null) {
                    return encryptResponse.getEncryptedData();
                } else {
                    String msg = encryptResponse != null ? encryptResponse.getMessage() : "null";
                    throw new HubCryptoException("Encryption failed: " + msg);
                }
            } else {
                throw new HubCryptoException("Engine API call failed: " + response.statusCode + " " + response.body);
            }

        } catch (Exception e) {
            if (enableLogging) log.debug("Engine encryption failed: {}", e.getMessage());
            if (e instanceof HubCryptoException) throw e;
            throw new HubConnectionException("Engine connection failed: " + e.getMessage(), e);
        }
    }

    // Encrypt for search

    public String encryptForSearch(String data, String policyName) {
        initializeIfNeeded();
        validateNotHubPath();

        if (enableLogging && log.isDebugEnabled()) log.debug("Wrapper encrypt-for-search request: policy={}", policyName);

        try {
            String url = hubUrl + apiBasePath + "/encrypt";
            recordEndpointUsage(url);

            EncryptRequest request = new EncryptRequest();
            request.setData(data);
            request.setPolicyName(policyName);
            request.setForSearch(true);

            String requestBody;
            try {
                requestBody = objectMapper.writeValueAsString(request);
            } catch (Exception e) {
                throw new HubCryptoException("Request data serialization failed: " + e.getMessage());
            }

            HttpResponse response = doPost("encryptForSearch", url, requestBody);

            if (response.is2xx()) {
                JsonNode rootNode = parseJson(response.body);
                // Prefer the v2 "code" contract, then fall back to the legacy success flag.
                JsonNode codeNode = rootNode.get("code");
                boolean responseSuccess;
                if (codeNode != null && codeNode.isTextual()) {
                    responseSuccess = "SUCCESS".equals(codeNode.asText());
                } else {
                    JsonNode successNode = rootNode.get("success");
                    responseSuccess = successNode != null && successNode.asBoolean();
                }
                if (!responseSuccess) {
                    if (enableLogging) log.warn("Encrypt-for-search failed, returning plaintext: {}", rootNode.get("message"));
                    return data;
                }
                JsonNode dataNode = rootNode.get("data");
                if (dataNode != null && dataNode.isTextual()) {
                    String result = dataNode.asText();
                    if (enableLogging && log.isDebugEnabled()) {
                        log.debug("Wrapper encrypt-for-search completed: resultLength={}", result.length());
                    }
                    return result;
                }
                return data;
            } else {
                if (enableLogging) log.warn("Encrypt-for-search API failed ({}), returning plaintext", response.statusCode);
                return data;
            }
        } catch (Exception e) {
            if (enableLogging) log.warn("Encrypt-for-search failed, returning plaintext: {}", e.getMessage());
            return data;
        }
    }

    // Decrypt

    public String decrypt(String encryptedData) {
        return decrypt(encryptedData, null, null, null, false);
    }

    public String decrypt(String encryptedData, String maskPolicyName, String maskPolicyUid) {
        return decrypt(encryptedData, null, maskPolicyName, maskPolicyUid, false);
    }

    public String decrypt(String encryptedData, String maskPolicyName, String maskPolicyUid, boolean includeStats) {
        return decrypt(encryptedData, null, maskPolicyName, maskPolicyUid, includeStats);
    }

    public String decrypt(String encryptedData, String policyName, String maskPolicyName, String maskPolicyUid, boolean includeStats) {
        initializeIfNeeded();
        validateNotHubPath();

        if (enableLogging && log.isDebugEnabled()) {
            log.debug("Wrapper decrypt request: encryptedLength={}, maskPolicyName={}, maskPolicyUid={}",
                    encryptedData != null ? encryptedData.length() : 0, maskPolicyName, maskPolicyUid);
        }

        try {
            String url = hubUrl + apiBasePath + "/decrypt";
            recordEndpointUsage(url);

            DecryptRequest request = new DecryptRequest();
            request.setEncryptedData(encryptedData);
            request.setPolicyName(policyName);
            request.setMaskPolicyName(maskPolicyName);
            request.setMaskPolicyUid(maskPolicyUid);

            String requestBody;
            try {
                requestBody = objectMapper.writeValueAsString(request);
            } catch (Exception e) {
                throw new HubCryptoException("Request data serialization failed: " + e.getMessage());
            }

            HttpResponse response = doPost("decrypt", url, requestBody);

            if (response.is2xx()) {
                return parseDecryptResponse(response.body, encryptedData);
            } else {
                // Handle the known "Data is not encrypted" case even on non-2xx responses.
                if (response.body != null && response.body.contains("Data is not encrypted")) {
                    if (enableLogging) log.warn("Data is not encrypted (pre-policy data)");
                    return null;
                }
                throw new HubConnectionException("Engine API call failed: " + response.statusCode + " " + response.body);
            }

        } catch (Exception e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : "";

            // Detect the known plaintext / pre-policy response case.
            if (errorMessage.contains("Data is not encrypted")) {
                if (enableLogging) log.warn("Data is not encrypted (pre-policy data)");
                return null;
            }

            if (enableLogging && log.isDebugEnabled()) log.debug("Engine decryption failed: {}", errorMessage);
            if (e instanceof HubCryptoException) throw e;
            throw new HubConnectionException("Engine connection failed: " + errorMessage, e);
        }
    }

    private String parseDecryptResponse(String responseBody, String encryptedData) {
        JsonNode rootNode = parseJson(responseBody);

        // Prefer the v2 "code" contract, then fall back to the legacy success flag.
        JsonNode codeNode = rootNode.get("code");
        boolean responseSuccess;
        if (codeNode != null && codeNode.isTextual()) {
            responseSuccess = "SUCCESS".equals(codeNode.asText());
        } else {
            JsonNode successNode = rootNode.get("success");
            responseSuccess = successNode != null && successNode.asBoolean();
        }
        if (!responseSuccess) {
            JsonNode messageNode = rootNode.get("message");
            String errorMessage = messageNode != null && !messageNode.isNull() ? messageNode.asText() : "Decryption failed";

            if (errorMessage.contains("Data is not encrypted")) {
                if (enableLogging) log.warn("Data is not encrypted (pre-policy data)");
                return null;
            }
            throw new HubCryptoException("Decryption failed: " + errorMessage);
        }

        JsonNode dataNode = rootNode.get("data");
        if (dataNode == null || dataNode.isNull()) {
            throw new HubCryptoException("Decryption failed: no data field in response");
        }

        // Engine response: "data" is the decrypted string.
        if (dataNode.isTextual()) {
            String decryptedData = dataNode.asText();
            if (enableLogging && log.isDebugEnabled()) {
                log.debug("Wrapper decrypt response parsed: decryptedLength={}",
                        decryptedData.length());
            }
            return decryptedData;
        }

        // Legacy response: "data" contains a DecryptResponse object.
        DecryptResponse decryptResponse;
        try {
            decryptResponse = objectMapper.treeToValue(dataNode, DecryptResponse.class);
        } catch (Exception e) {
            throw new HubCryptoException("Engine response data parsing failed: " + e.getMessage());
        }

        if (decryptResponse == null) {
            throw new HubCryptoException("Decryption failed: no data field in response");
        }

        if (Boolean.TRUE.equals(decryptResponse.getSuccess()) && decryptResponse.getDecryptedData() != null) {
            return decryptResponse.getDecryptedData();
        } else if (decryptResponse.getDecryptedData() != null) {
            // Masked results can still be returned as decryptedData.
            return decryptResponse.getDecryptedData();
        } else {
            String message = decryptResponse.getMessage() != null ? decryptResponse.getMessage() : "Decryption failed";
            if (message.contains("Data is not encrypted")) {
                if (enableLogging) log.warn("Data is not encrypted (pre-policy data)");
                return null;
            }
            throw new HubCryptoException("Decryption failed: " + message);
        }
    }

    // Batch decrypt

    public java.util.List<String> batchDecrypt(java.util.List<String> encryptedDataList,
                                                String maskPolicyName,
                                                String maskPolicyUid,
                                                boolean includeStats) {
        initializeIfNeeded();

        if (encryptedDataList == null || encryptedDataList.isEmpty()) {
            return new java.util.ArrayList<>();
        }

        if (enableLogging && log.isDebugEnabled()) {
            log.debug("Wrapper batch decrypt request: itemsCount={}, maskPolicyName={}, maskPolicyUid={}",
                    encryptedDataList.size(), maskPolicyName, maskPolicyUid);
        }

        try {
            validateNotHubPath();

            String url = hubUrl + apiBasePath + "/decrypt/batch";
            recordEndpointUsage(url);

            // Build the batch request payload.
            java.util.Map<String, Object> batchRequest = new java.util.HashMap<>();
            java.util.List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();

            for (String ed : encryptedDataList) {
                java.util.Map<String, Object> item = new java.util.HashMap<>();
                item.put("data", ed);
                if (maskPolicyName != null && !maskPolicyName.trim().isEmpty()) {
                    item.put("maskPolicyName", maskPolicyName);
                }
                if (maskPolicyUid != null && !maskPolicyUid.trim().isEmpty()) {
                    item.put("maskPolicyUid", maskPolicyUid);
                }
                items.add(item);
            }
            batchRequest.put("items", items);

            String requestBody;
            try {
                requestBody = objectMapper.writeValueAsString(batchRequest);
            } catch (Exception e) {
                throw new HubCryptoException("Request data serialization failed: " + e.getMessage());
            }

            HttpResponse response = doPost("batchDecrypt", url, requestBody);

            if (response.is2xx()) {
                JsonNode rootNode = parseJson(response.body);

                // Extract the top-level "results" array.
                JsonNode resultsNode = rootNode.get("results");
                if (resultsNode == null || !resultsNode.isArray()) {
                    // Handle the ApiResponse wrapper form.
                    // Prefer the v2 "code" contract, then fall back to the legacy success flag.
                    JsonNode codeNode = rootNode.get("code");
                    boolean outerSuccess;
                    if (codeNode != null && codeNode.isTextual()) {
                        outerSuccess = "SUCCESS".equals(codeNode.asText());
                    } else {
                        JsonNode successNode = rootNode.get("success");
                        outerSuccess = successNode != null && successNode.asBoolean();
                    }
                    if (outerSuccess) {
                        JsonNode dataNode = rootNode.get("data");
                        if (dataNode != null && !dataNode.isNull()) {
                            resultsNode = dataNode.get("results");
                        }
                    }
                    if (resultsNode == null || !resultsNode.isArray()) {
                        throw new HubCryptoException("Batch decryption failed: no results array in response");
                    }
                }

                java.util.List<String> decryptedList = new java.util.ArrayList<>();
                for (JsonNode resultNode : resultsNode) {
                    if (resultNode.get("success") != null && resultNode.get("success").asBoolean()) {
                        JsonNode decryptedDataNode = resultNode.get("decryptedData");
                        if (decryptedDataNode != null && !decryptedDataNode.isNull()) {
                            decryptedList.add(decryptedDataNode.asText());
                        } else {
                            JsonNode originalDataNode = resultNode.get("originalData");
                            decryptedList.add(originalDataNode != null ? originalDataNode.asText() : null);
                        }
                    } else {
                        JsonNode originalDataNode = resultNode.get("originalData");
                        decryptedList.add(originalDataNode != null ? originalDataNode.asText() : null);
                    }
                }

                if (enableLogging && log.isDebugEnabled()) log.debug("Wrapper batch decrypt response parsed: itemsCount={}", decryptedList.size());
                return decryptedList;
            } else {
                throw new HubCryptoException("Batch decryption failed: " + response.statusCode);
            }

        } catch (HubCryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new HubCryptoException("Error during batch decryption: " + e.getMessage(), e);
        }
    }

    // Batch encrypt

    public java.util.List<String> batchEncrypt(java.util.List<String> dataList,
                                                java.util.List<String> policyList) {
        initializeIfNeeded();

        if (dataList == null || dataList.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        if (policyList == null || policyList.size() != dataList.size()) {
            throw new HubCryptoException("Policy list size does not match data list size");
        }

        if (enableLogging && log.isDebugEnabled()) {
            log.debug("Wrapper batch encrypt request: itemsCount={}, apiBasePath={}",
                    dataList.size(), apiBasePath);
        }

        try {
            validateNotHubPath();

            String url = hubUrl + apiBasePath + "/encrypt/batch";
            recordEndpointUsage(url);

            java.util.Map<String, Object> batchRequest = new java.util.HashMap<>();
            java.util.List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();

            for (int i = 0; i < dataList.size(); i++) {
                java.util.Map<String, Object> item = new java.util.HashMap<>();
                item.put("data", dataList.get(i));
                String policy = policyList.get(i);
                if (policy != null && !policy.trim().isEmpty()) {
                    item.put("policyName", policy);
                }
                items.add(item);
            }
            batchRequest.put("items", items);

            String requestBody;
            try {
                requestBody = objectMapper.writeValueAsString(batchRequest);
            } catch (Exception e) {
                throw new HubCryptoException("Request data serialization failed: " + e.getMessage());
            }

            HttpResponse response = doPost("batchEncrypt", url, requestBody);

            if (response.is2xx()) {
                JsonNode rootNode = parseJson(response.body);

                // Prefer the v2 "code" contract, then fall back to the legacy success flag.
                JsonNode codeNode = rootNode.get("code");
                boolean responseSuccess;
                if (codeNode != null && codeNode.isTextual()) {
                    responseSuccess = "SUCCESS".equals(codeNode.asText());
                } else {
                    JsonNode successNode = rootNode.get("success");
                    responseSuccess = successNode != null && successNode.asBoolean();
                }
                if (!responseSuccess) {
                    JsonNode messageNode = rootNode.get("message");
                    String errorMessage = messageNode != null && !messageNode.isNull() ? messageNode.asText() : "Batch encryption failed";
                    throw new HubCryptoException("Batch encryption failed: " + errorMessage);
                }

                JsonNode dataNode = rootNode.get("data");
                if (dataNode == null || dataNode.isNull()) {
                    throw new HubCryptoException("Batch encryption failed: no data field in response");
                }

                JsonNode resultsNode = dataNode.get("results");
                if (resultsNode == null || !resultsNode.isArray()) {
                    throw new HubCryptoException("Batch encryption failed: no results array in response");
                }

                java.util.List<String> encryptedList = new java.util.ArrayList<>();
                for (JsonNode resultNode : resultsNode) {
                    if (resultNode.get("success") != null && resultNode.get("success").asBoolean()) {
                        JsonNode encryptedDataNode = resultNode.get("encryptedData");
                        if (encryptedDataNode != null && !encryptedDataNode.isNull()) {
                            encryptedList.add(encryptedDataNode.asText());
                        } else {
                            JsonNode originalDataNode = resultNode.get("originalData");
                            encryptedList.add(originalDataNode != null ? originalDataNode.asText() : null);
                        }
                    } else {
                        JsonNode originalDataNode = resultNode.get("originalData");
                        encryptedList.add(originalDataNode != null ? originalDataNode.asText() : null);
                    }
                }

                if (enableLogging && log.isDebugEnabled()) log.debug("Wrapper batch encrypt response parsed: itemsCount={}", encryptedList.size());
                return encryptedList;
            } else {
                throw new HubCryptoException("Batch encryption failed: " + response.statusCode);
            }

        } catch (HubCryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new HubCryptoException("Error during batch encryption: " + e.getMessage(), e);
        }
    }

    // Encrypted-data detection

    public boolean isEncryptedData(String data) {
        if (data == null || data.isEmpty()) {
            return false;
        }

        if (enableLogging && log.isDebugEnabled()) {
            log.debug("isEncryptedData check: dataLength={}", data.length());
        }

        // Partial-encryption format: "[plain]::ENC::[ciphertext]".
        String checkPart = data;
        if (data.contains("::ENC::")) {
            int idx = data.indexOf("::ENC::");
            checkPart = data.substring(idx + "::ENC::".length());
        }

        // hub: prefix
        if (checkPart.startsWith("hub:")) {
            String[] parts = checkPart.split(":", 3);
            if (parts.length >= 3) {
                String policyUuid = parts[1];
                String base64Data = parts[2];
                if (policyUuid.length() == 36 && policyUuid.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) {
                    try {
                        byte[] decoded = Base64.getDecoder().decode(base64Data);
                        return decoded.length >= 16;
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                }
            }
            return false;
        }

        // kms: prefix
        if (checkPart.startsWith("kms:")) {
            String[] parts = checkPart.split(":", 4);
            if (parts.length >= 4) {
                String policyUuid = parts[1];
                String base64Data = parts[3];
                if (policyUuid.length() == 36 && policyUuid.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) {
                    try {
                        byte[] decoded = Base64.getDecoder().decode(base64Data);
                        return decoded.length >= 28;
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                }
            }
            return false;
        }

        // vault: prefix
        if (checkPart.startsWith("vault:")) {
            String[] parts = checkPart.split(":", 4);
            return parts.length >= 4 && parts[2].startsWith("v");
        }

        // Legacy format: Base64 payload plus Policy UUID validation.
        try {
            byte[] decoded = Base64.getDecoder().decode(checkPart);
            if (decoded.length >= 64 && decoded.length >= 36) {
                try {
                    String uuidCandidate = new String(decoded, 0, 36, StandardCharsets.UTF_8);
                    boolean isValidUuid = uuidCandidate.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
                    if (enableLogging && log.isDebugEnabled()) {
                        log.debug("Legacy format check: decodedLength={}, isValidUuid={}", decoded.length, isValidUuid);
                    }
                    return isValidUuid;
                } catch (Exception e) {
                    return false;
                }
            }
            return false;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // JSON helper

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new HubCryptoException("Engine response parsing failed: " + e.getMessage());
        }
    }
}
