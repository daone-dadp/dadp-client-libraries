package com.dadp.hub.crypto;

import com.dadp.common.sync.crypto.CryptoProfileRecorder;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private String singleTransportMode = "json";
    private boolean initialized = false;
    private volatile CryptoProfileRecorder profileRecorder;

    @Deprecated
    private static final String HUB_API_PATH = "/hub/api/v1";
    private static final String ENGINE_API_PATH = "/api";

    // Telemetry
    private volatile String lastUsedEndpoint = null;
    private volatile long endpointUsageCount = 0;

    // HTTP response holder

    private static class HttpResponse {
        final long requestId;
        final int statusCode;
        final String body;
        final String endpoint;
        final int requestBytes;
        final int responseBytes;
        final double writeMs;
        final double responseCodeMs;
        final double readMs;
        final double totalMs;

        HttpResponse(long requestId, int statusCode, String body, String endpoint, int requestBytes, int responseBytes,
                     double writeMs, double responseCodeMs, double readMs, double totalMs) {
            this.requestId = requestId;
            this.statusCode = statusCode;
            this.body = body;
            this.endpoint = endpoint;
            this.requestBytes = requestBytes;
            this.responseBytes = responseBytes;
            this.writeMs = writeMs;
            this.responseCodeMs = responseCodeMs;
            this.readMs = readMs;
            this.totalMs = totalMs;
        }
        boolean is2xx() { return statusCode >= 200 && statusCode < 300; }
    }

    private static class BinaryHttpResponse {
        final long requestId;
        final int statusCode;
        final byte[] body;
        final String endpoint;
        final int requestBytes;
        final int responseBytes;
        final double writeMs;
        final double responseCodeMs;
        final double readMs;
        final double totalMs;

        BinaryHttpResponse(long requestId, int statusCode, byte[] body, String endpoint, int requestBytes, int responseBytes,
                           double writeMs, double responseCodeMs, double readMs, double totalMs) {
            this.requestId = requestId;
            this.statusCode = statusCode;
            this.body = body;
            this.endpoint = endpoint;
            this.requestBytes = requestBytes;
            this.responseBytes = responseBytes;
            this.writeMs = writeMs;
            this.responseCodeMs = responseCodeMs;
            this.readMs = readMs;
            this.totalMs = totalMs;
        }

        boolean is2xx() { return statusCode >= 200 && statusCode < 300; }
        String bodyAsText() { return new String(body, StandardCharsets.UTF_8); }
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

    public void setProfileRecorder(CryptoProfileRecorder profileRecorder) {
        this.profileRecorder = profileRecorder;
    }

    public void setSingleTransportMode(String singleTransportMode) {
        if (singleTransportMode == null || singleTransportMode.trim().isEmpty()) {
            this.singleTransportMode = "json";
        } else {
            this.singleTransportMode = singleTransportMode.trim().toLowerCase();
        }
    }

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

    private boolean useSingleBinaryFramedTransport() {
        return "binary-framed".equals(singleTransportMode);
    }

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
        boolean captureTimings = profileRecorder != null || (enableLogging && log.isDebugEnabled());
        long requestId = HTTP_REQUEST_SEQUENCE.incrementAndGet();
        int requestBytes = utf8Length(requestBody);
        String endpoint = summarizeEndpoint(url);
        long startedNs = captureTimings ? System.nanoTime() : 0L;
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
            if (captureTimings) {
                writeStartedNs = System.nanoTime();
            }
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();
            if (captureTimings) {
                writeFinishedNs = System.nanoTime();
            }

            if (captureTimings) {
                responseStartedNs = System.nanoTime();
            }
            int code = conn.getResponseCode();
            if (captureTimings) {
                responseFinishedNs = System.nanoTime();
                readStartedNs = System.nanoTime();
            }
            String responseBody = readStream(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            if (captureTimings) {
                readFinishedNs = System.nanoTime();
            }
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
                    captureTimings ? String.format("%.3f", elapsedMs(writeStartedNs, writeFinishedNs)) : "n/a",
                    captureTimings ? String.format("%.3f", elapsedMs(responseStartedNs, responseFinishedNs)) : "n/a",
                    captureTimings ? String.format("%.3f", elapsedMs(readStartedNs, readFinishedNs)) : "n/a",
                    captureTimings ? String.format("%.3f", elapsedMs(startedNs, readFinishedNs)) : "n/a");
            }

            if (code < 200 || code >= 300) {
                log.warn(
                    "Wrapper HTTP request returned non-2xx: requestId={}, operation={}, endpoint={}, status={}, totalMs={}",
                    requestId,
                    operation,
                    endpoint,
                    code,
                    captureTimings ? String.format("%.3f", elapsedMs(startedNs, readFinishedNs)) : "n/a");
            }

            return new HttpResponse(
                    requestId,
                    code,
                    responseBody,
                    endpoint,
                    requestBytes,
                    utf8Length(responseBody),
                    captureTimings ? elapsedMs(writeStartedNs, writeFinishedNs) : 0.0,
                    captureTimings ? elapsedMs(responseStartedNs, responseFinishedNs) : 0.0,
                    captureTimings ? elapsedMs(readStartedNs, readFinishedNs) : 0.0,
                    captureTimings ? elapsedMs(startedNs, readFinishedNs) : 0.0);

        } catch (java.net.SocketTimeoutException e) {
            log.warn(
                "Wrapper HTTP request timed out: requestId={}, operation={}, endpoint={}, requestBytes={}, timeoutMs={}, totalMs={}",
                requestId,
                operation,
                endpoint,
                requestBytes,
                timeout,
                captureTimings ? String.format("%.3f", elapsedMs(startedNs, System.nanoTime())) : "n/a");
            throw new HubConnectionException("Engine connection timeout: " + e.getMessage(), e);
        } catch (IOException e) {
            log.warn(
                "Wrapper HTTP request failed: requestId={}, operation={}, endpoint={}, requestBytes={}, totalMs={}, error={}",
                requestId,
                operation,
                endpoint,
                requestBytes,
                captureTimings ? String.format("%.3f", elapsedMs(startedNs, System.nanoTime())) : "n/a",
                e.getMessage());
            throw new HubConnectionException("Engine connection failed: " + e.getMessage(), e);
        } finally {
            // Keep successful connections eligible for JDK keep-alive reuse.
            if (conn != null && !responseFullyConsumed) {
                conn.disconnect();
            }
        }
    }

    private BinaryHttpResponse doPostBinary(String operation, String url, byte[] requestBody, String contentType, String acceptType) {
        HttpURLConnection conn = null;
        boolean responseFullyConsumed = false;
        boolean captureTimings = profileRecorder != null || (enableLogging && log.isDebugEnabled());
        long requestId = HTTP_REQUEST_SEQUENCE.incrementAndGet();
        int requestBytes = requestBody != null ? requestBody.length : 0;
        String endpoint = summarizeEndpoint(url);
        long startedNs = captureTimings ? System.nanoTime() : 0L;
        long writeStartedNs = startedNs;
        long writeFinishedNs = startedNs;
        long responseStartedNs = startedNs;
        long responseFinishedNs = startedNs;
        long readStartedNs = startedNs;
        long readFinishedNs = startedNs;
        try {
            if (enableLogging && log.isDebugEnabled()) {
                log.debug(
                    "Wrapper binary HTTP request start: requestId={}, operation={}, endpoint={}, requestBytes={}, contentType={}",
                    requestId, operation, endpoint, requestBytes, contentType);
            }

            URL urlObj = new URL(url);
            conn = (HttpURLConnection) urlObj.openConnection();

            if (conn instanceof HttpsURLConnection && sslContext != null) {
                ((HttpsURLConnection) conn).setSSLSocketFactory(sslContext.getSocketFactory());
            }

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", contentType);
            conn.setRequestProperty("Accept", acceptType);
            conn.setDoOutput(true);
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setFixedLengthStreamingMode(requestBytes);

            if (captureTimings) {
                writeStartedNs = System.nanoTime();
            }
            OutputStream os = conn.getOutputStream();
            os.write(requestBody);
            os.flush();
            os.close();
            if (captureTimings) {
                writeFinishedNs = System.nanoTime();
            }

            if (captureTimings) {
                responseStartedNs = System.nanoTime();
            }
            int code = conn.getResponseCode();
            if (captureTimings) {
                responseFinishedNs = System.nanoTime();
                readStartedNs = System.nanoTime();
            }
            byte[] responseBody = readStreamBytes(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            if (captureTimings) {
                readFinishedNs = System.nanoTime();
            }
            responseFullyConsumed = true;

            return new BinaryHttpResponse(
                    requestId,
                    code,
                    responseBody,
                    endpoint,
                    requestBytes,
                    responseBody.length,
                    captureTimings ? elapsedMs(writeStartedNs, writeFinishedNs) : 0.0,
                    captureTimings ? elapsedMs(responseStartedNs, responseFinishedNs) : 0.0,
                    captureTimings ? elapsedMs(readStartedNs, readFinishedNs) : 0.0,
                    captureTimings ? elapsedMs(startedNs, readFinishedNs) : 0.0);
        } catch (java.net.SocketTimeoutException e) {
            throw new HubConnectionException("Engine connection timeout: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new HubConnectionException("Engine connection failed: " + e.getMessage(), e);
        } finally {
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

    private static byte[] readStreamBytes(InputStream is) throws IOException {
        if (is == null) return new byte[0];
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = is.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
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
        CryptoProfileRecorder recorder = this.profileRecorder;
        boolean captureProfile = recorder != null;
        boolean useBinaryFramed = useSingleBinaryFramedTransport();

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
            request.setIncludeStats(includeStats || captureProfile);

            long requestBuildStartedNs = captureProfile ? System.nanoTime() : 0L;
            ParsedEncryptResult parsed;
            int requestBytes;
            int responseBytes;
            long responseId;
            int responseStatus;
            String responseEndpoint;
            double responseWriteMs;
            double responseCodeMs;
            double responseReadMs;
            long responseParseStartedNs = captureProfile ? System.nanoTime() : 0L;
            long requestBuildFinishedNs;
            long responseParseFinishedNs;

            if (useBinaryFramed) {
                byte[] requestBody = SingleBinaryFramedCodec.writeEncryptRequest(request);
                requestBuildFinishedNs = captureProfile ? System.nanoTime() : 0L;
                BinaryHttpResponse response = doPostBinary("encrypt", url, requestBody,
                        SingleBinaryFramedCodec.CONTENT_TYPE, SingleBinaryFramedCodec.CONTENT_TYPE);
                requestBytes = response.requestBytes;
                responseBytes = response.responseBytes;
                responseId = response.requestId;
                responseStatus = response.statusCode;
                responseEndpoint = response.endpoint;
                responseWriteMs = response.writeMs;
                responseCodeMs = response.responseCodeMs;
                responseReadMs = response.readMs;
                if (response.is2xx()) {
                    if (captureProfile) {
                        responseParseStartedNs = System.nanoTime();
                    }
                    parsed = parseEncryptResponse(SingleBinaryFramedCodec.readEncryptResponse(response.body, objectMapper), captureProfile);
                    responseParseFinishedNs = captureProfile ? System.nanoTime() : 0L;
                } else {
                    throw new HubCryptoException("Engine API call failed: " + response.statusCode + " " + response.bodyAsText());
                }
            } else {
                String requestBody;
                try {
                    requestBody = objectMapper.writeValueAsString(request);
                } catch (Exception e) {
                    throw new HubCryptoException("Request data serialization failed: " + e.getMessage());
                }
                requestBuildFinishedNs = captureProfile ? System.nanoTime() : 0L;
                HttpResponse response = doPost("encrypt", url, requestBody);
                requestBytes = response.requestBytes;
                responseBytes = response.responseBytes;
                responseId = response.requestId;
                responseStatus = response.statusCode;
                responseEndpoint = response.endpoint;
                responseWriteMs = response.writeMs;
                responseCodeMs = response.responseCodeMs;
                responseReadMs = response.readMs;
                if (response.is2xx()) {
                    if (captureProfile) {
                        responseParseStartedNs = System.nanoTime();
                    }
                    parsed = parseEncryptResponse(response.body, captureProfile);
                    responseParseFinishedNs = captureProfile ? System.nanoTime() : 0L;
                } else {
                    throw new HubCryptoException("Engine API call failed: " + response.statusCode + " " + response.body);
                }
            }

            if (captureProfile) {
                recordProfileEvent(recorder, "encrypt", responseEndpoint, responseId, responseStatus,
                        true, policy, null, requestBytes, responseBytes,
                        elapsedMs(requestBuildStartedNs, requestBuildFinishedNs), responseWriteMs,
                        responseCodeMs, responseReadMs,
                        elapsedMs(responseParseStartedNs, responseParseFinishedNs),
                        elapsedMs(requestBuildStartedNs, responseParseFinishedNs),
                        parsed.processingTime, parsed.engineTraceId, parsed.engineStats, null);
            }
            return parsed.encryptedData;

        } catch (Exception e) {
            if (captureProfile) {
                recordProfileEvent(recorder, "encrypt", null, null, null, false, policy, null,
                        null, null, null, null, null, null, null, null, null, null, null, e.getMessage());
            }
            if (enableLogging) log.debug("Engine encryption failed: {}", e.getMessage());
            if (e instanceof HubCryptoException) throw e;
            throw new HubConnectionException("Engine connection failed: " + e.getMessage(), e);
        }
    }

    // Encrypt for search

    public String encryptForSearch(String data, String policyName) {
        initializeIfNeeded();
        validateNotHubPath();
        CryptoProfileRecorder recorder = this.profileRecorder;
        boolean captureProfile = recorder != null;

        if (enableLogging && log.isDebugEnabled()) log.debug("Wrapper encrypt-for-search request: policy={}", policyName);

        try {
            String url = hubUrl + apiBasePath + "/encrypt";
            recordEndpointUsage(url);

            EncryptRequest request = new EncryptRequest();
            request.setData(data);
            request.setPolicyName(policyName);
            request.setForSearch(true);
            request.setIncludeStats(captureProfile);

            String requestBody;
            long requestBuildStartedNs = captureProfile ? System.nanoTime() : 0L;
            try {
                requestBody = objectMapper.writeValueAsString(request);
            } catch (Exception e) {
                throw new HubCryptoException("Request data serialization failed: " + e.getMessage());
            }
            long requestBuildFinishedNs = captureProfile ? System.nanoTime() : 0L;

            HttpResponse response = doPost("encryptForSearch", url, requestBody);

            if (response.is2xx()) {
                long responseParseStartedNs = captureProfile ? System.nanoTime() : 0L;
                ParsedEncryptResult parsed = parseEncryptResponse(response.body, captureProfile);
                long responseParseFinishedNs = captureProfile ? System.nanoTime() : 0L;
                // Prefer the v2 "code" contract, then fall back to the legacy success flag.
                if (captureProfile) {
                    recordProfileEvent(recorder, "encryptForSearch", response.endpoint, response.requestId, response.statusCode,
                            true, policyName, null, response.requestBytes, response.responseBytes,
                            elapsedMs(requestBuildStartedNs, requestBuildFinishedNs), response.writeMs,
                            response.responseCodeMs, response.readMs,
                            elapsedMs(responseParseStartedNs, responseParseFinishedNs),
                            elapsedMs(requestBuildStartedNs, responseParseFinishedNs),
                            parsed.processingTime, parsed.engineTraceId, parsed.engineStats, null);
                }
                return parsed.encryptedData != null ? parsed.encryptedData : data;
            } else {
                if (enableLogging) log.warn("Encrypt-for-search API failed ({}), returning plaintext", response.statusCode);
                return data;
            }
        } catch (Exception e) {
            if (captureProfile) {
                recordProfileEvent(recorder, "encryptForSearch", null, null, null, false, policyName, null,
                        null, null, null, null, null, null, null, null, null, null, null, e.getMessage());
            }
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
        CryptoProfileRecorder recorder = this.profileRecorder;
        boolean captureProfile = recorder != null;
        boolean useBinaryFramed = useSingleBinaryFramedTransport();

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
            request.setIncludeStats(includeStats || captureProfile);

            long requestBuildStartedNs = captureProfile ? System.nanoTime() : 0L;
            ParsedDecryptResult parsed;
            int requestBytes;
            int responseBytes;
            long responseId;
            int responseStatus;
            String responseEndpoint;
            double responseWriteMs;
            double responseCodeMs;
            double responseReadMs;
            long requestBuildFinishedNs;
            long responseParseStartedNs = captureProfile ? System.nanoTime() : 0L;
            long responseParseFinishedNs;

            if (useBinaryFramed) {
                byte[] requestBody = SingleBinaryFramedCodec.writeDecryptRequest(request);
                requestBuildFinishedNs = captureProfile ? System.nanoTime() : 0L;
                BinaryHttpResponse response = doPostBinary("decrypt", url, requestBody,
                        SingleBinaryFramedCodec.CONTENT_TYPE, SingleBinaryFramedCodec.CONTENT_TYPE);
                requestBytes = response.requestBytes;
                responseBytes = response.responseBytes;
                responseId = response.requestId;
                responseStatus = response.statusCode;
                responseEndpoint = response.endpoint;
                responseWriteMs = response.writeMs;
                responseCodeMs = response.responseCodeMs;
                responseReadMs = response.readMs;
                if (response.is2xx()) {
                    if (captureProfile) {
                        responseParseStartedNs = System.nanoTime();
                    }
                    parsed = parseDecryptResponse(SingleBinaryFramedCodec.readDecryptResponse(response.body, objectMapper), encryptedData, captureProfile);
                    responseParseFinishedNs = captureProfile ? System.nanoTime() : 0L;
                } else {
                    String errorBody = response.bodyAsText();
                    if (errorBody.contains("Data is not encrypted")) {
                        if (enableLogging) log.warn("Data is not encrypted (pre-policy data)");
                        return null;
                    }
                    throw new HubConnectionException("Engine API call failed: " + response.statusCode + " " + errorBody);
                }
            } else {
                String requestBody;
                try {
                    requestBody = objectMapper.writeValueAsString(request);
                } catch (Exception e) {
                    throw new HubCryptoException("Request data serialization failed: " + e.getMessage());
                }
                requestBuildFinishedNs = captureProfile ? System.nanoTime() : 0L;
                HttpResponse response = doPost("decrypt", url, requestBody);
                requestBytes = response.requestBytes;
                responseBytes = response.responseBytes;
                responseId = response.requestId;
                responseStatus = response.statusCode;
                responseEndpoint = response.endpoint;
                responseWriteMs = response.writeMs;
                responseCodeMs = response.responseCodeMs;
                responseReadMs = response.readMs;
                if (response.is2xx()) {
                    if (captureProfile) {
                        responseParseStartedNs = System.nanoTime();
                    }
                    parsed = parseDecryptResponse(response.body, encryptedData, captureProfile);
                    responseParseFinishedNs = captureProfile ? System.nanoTime() : 0L;
                } else {
                    if (response.body != null && response.body.contains("Data is not encrypted")) {
                        if (enableLogging) log.warn("Data is not encrypted (pre-policy data)");
                        return null;
                    }
                    throw new HubConnectionException("Engine API call failed: " + response.statusCode + " " + response.body);
                }
            }

            if (captureProfile) {
                recordProfileEvent(recorder, "decrypt", responseEndpoint, responseId, responseStatus,
                        true, policyName, maskPolicyName, requestBytes, responseBytes,
                        elapsedMs(requestBuildStartedNs, requestBuildFinishedNs), responseWriteMs,
                        responseCodeMs, responseReadMs,
                        elapsedMs(responseParseStartedNs, responseParseFinishedNs),
                        elapsedMs(requestBuildStartedNs, responseParseFinishedNs),
                        parsed.processingTime, parsed.engineTraceId, parsed.engineStats, null);
            }
            return parsed.decryptedData;

        } catch (Exception e) {
            if (captureProfile) {
                recordProfileEvent(recorder, "decrypt", null, null, null, false, policyName, maskPolicyName,
                        null, null, null, null, null, null, null, null, null, null, null, e.getMessage());
            }
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

    private ParsedDecryptResult parseDecryptResponse(String responseBody, String encryptedData, boolean includeEngineObservability) {
        return parseDecryptResponse(parseJson(responseBody), encryptedData, includeEngineObservability);
    }

    private ParsedDecryptResult parseDecryptResponse(JsonNode rootNode, String encryptedData, boolean includeEngineObservability) {
        Long processingTime = includeEngineObservability ? extractProcessingTime(rootNode) : null;
        String engineTraceId = includeEngineObservability ? extractTraceId(rootNode) : null;
        Map<String, Object> engineStats = includeEngineObservability ? extractEngineStats(rootNode) : null;

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
                return new ParsedDecryptResult(null, processingTime, engineTraceId, engineStats);
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
            return new ParsedDecryptResult(decryptedData, processingTime, engineTraceId, engineStats);
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
            return new ParsedDecryptResult(
                    decryptResponse.getDecryptedData(),
                    decryptResponse.getProcessingTime() != null ? decryptResponse.getProcessingTime() : processingTime,
                    engineTraceId,
                    engineStats);
        } else if (decryptResponse.getDecryptedData() != null) {
            // Masked results can still be returned as decryptedData.
            return new ParsedDecryptResult(
                    decryptResponse.getDecryptedData(),
                    decryptResponse.getProcessingTime() != null ? decryptResponse.getProcessingTime() : processingTime,
                    engineTraceId,
                    engineStats);
        } else {
            String message = decryptResponse.getMessage() != null ? decryptResponse.getMessage() : "Decryption failed";
            if (message.contains("Data is not encrypted")) {
                if (enableLogging) log.warn("Data is not encrypted (pre-policy data)");
                return new ParsedDecryptResult(null, processingTime, engineTraceId, engineStats);
            }
            throw new HubCryptoException("Decryption failed: " + message);
        }
    }

    private ParsedEncryptResult parseEncryptResponse(String responseBody, boolean includeEngineObservability) {
        return parseEncryptResponse(parseJson(responseBody), includeEngineObservability);
    }

    private ParsedEncryptResult parseEncryptResponse(JsonNode rootNode, boolean includeEngineObservability) {
        Long processingTime = includeEngineObservability ? extractProcessingTime(rootNode) : null;
        String engineTraceId = includeEngineObservability ? extractTraceId(rootNode) : null;
        Map<String, Object> engineStats = includeEngineObservability ? extractEngineStats(rootNode) : null;

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

        if (dataNode.isTextual()) {
            String encryptedData = dataNode.asText();
            if (enableLogging && log.isDebugEnabled()) {
                log.debug("Wrapper encrypt response parsed: encryptedLength={}", encryptedData.length());
            }
            return new ParsedEncryptResult(encryptedData, processingTime, engineTraceId, engineStats);
        }

        EncryptResponse encryptResponse;
        try {
            encryptResponse = objectMapper.treeToValue(dataNode, EncryptResponse.class);
        } catch (Exception e) {
            throw new HubCryptoException("Engine response data parsing failed: " + e.getMessage());
        }

        if (encryptResponse != null && encryptResponse.getSuccess() != null
                && encryptResponse.getSuccess() && encryptResponse.getEncryptedData() != null) {
            return new ParsedEncryptResult(
                    encryptResponse.getEncryptedData(),
                    encryptResponse.getProcessingTime() != null ? encryptResponse.getProcessingTime() : processingTime,
                    engineTraceId,
                    engineStats);
        }

        String msg = encryptResponse != null ? encryptResponse.getMessage() : "null";
        throw new HubCryptoException("Encryption failed: " + msg);
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

    private void recordProfileEvent(CryptoProfileRecorder recorder,
                                    String operation,
                                    String endpoint,
                                    Long requestId,
                                    Integer statusCode,
                                    boolean success,
                                    String policyName,
                                    String maskPolicyName,
                                    Integer requestBytes,
                                    Integer responseBytes,
                                    Double requestBuildMs,
                                    Double httpWriteMs,
                                    Double httpResponseCodeMs,
                                    Double httpReadMs,
                                    Double responseParseMs,
                                    Double totalMs,
                                    Long engineProcessingTimeMs,
                                    String engineTraceId,
                                    Map<String, Object> engineStats,
                                    String error) {
        if (recorder == null) {
            return;
        }

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("operation", operation);
        event.put("endpoint", endpoint);
        event.put("requestId", requestId);
        event.put("statusCode", statusCode);
        event.put("success", success);
        event.put("policyName", policyName);
        event.put("maskPolicyName", maskPolicyName);
        event.put("requestBytes", requestBytes);
        event.put("responseBytes", responseBytes);
        event.put("requestBuildMs", requestBuildMs);
        event.put("httpWriteMs", httpWriteMs);
        event.put("httpResponseCodeMs", httpResponseCodeMs);
        event.put("httpReadMs", httpReadMs);
        event.put("responseParseMs", responseParseMs);
        event.put("totalMs", totalMs);
        event.put("engineProcessingTimeMs", engineProcessingTimeMs);
        event.put("engineTraceId", engineTraceId);
        event.put("engineStats", engineStats);
        event.put("error", error);
        recorder.record(event);
    }

    private Long extractProcessingTime(JsonNode rootNode) {
        if (rootNode == null || rootNode.isNull()) {
            return null;
        }

        JsonNode processingTimeNode = rootNode.get("processingTime");
        if (processingTimeNode != null && processingTimeNode.isNumber()) {
            return processingTimeNode.asLong();
        }

        JsonNode dataNode = rootNode.get("data");
        if (dataNode != null && !dataNode.isNull()) {
            JsonNode nestedProcessingTimeNode = dataNode.get("processingTime");
            if (nestedProcessingTimeNode != null && nestedProcessingTimeNode.isNumber()) {
                return nestedProcessingTimeNode.asLong();
            }
        }

        JsonNode observabilityNode = rootNode.get("observability");
        if (observabilityNode != null && !observabilityNode.isNull()) {
            JsonNode cryptoOperationTimeNode = observabilityNode.get("cryptoOperationTime");
            if (cryptoOperationTimeNode != null && cryptoOperationTimeNode.isNumber()) {
                return cryptoOperationTimeNode.asLong();
            }
        }

        JsonNode timingsNode = rootNode.get("timings");
        if (timingsNode != null && !timingsNode.isNull()) {
            JsonNode apiProcessingTimeNode = timingsNode.get("apiProcessingTimeMs");
            if (apiProcessingTimeNode != null && apiProcessingTimeNode.isNumber()) {
                return apiProcessingTimeNode.asLong();
            }
            JsonNode cryptoOperationTimeNode = timingsNode.get("cryptoOperationTimeMs");
            if (cryptoOperationTimeNode != null && cryptoOperationTimeNode.isNumber()) {
                return cryptoOperationTimeNode.asLong();
            }
        }

        JsonNode statsNode = rootNode.get("stats");
        if (statsNode != null && !statsNode.isNull()) {
            JsonNode totalProcessingTimeNode = statsNode.get("totalProcessingTime");
            if (totalProcessingTimeNode != null && totalProcessingTimeNode.isNumber()) {
                return totalProcessingTimeNode.asLong();
            }
            JsonNode cryptoOperationTimeNode = statsNode.get("cryptoOperationTime");
            if (cryptoOperationTimeNode != null && cryptoOperationTimeNode.isNumber()) {
                return cryptoOperationTimeNode.asLong();
            }
        }

        return null;
    }

    private String extractTraceId(JsonNode rootNode) {
        if (rootNode == null || rootNode.isNull()) {
            return null;
        }

        JsonNode traceIdNode = rootNode.get("traceId");
        if (traceIdNode != null && traceIdNode.isTextual()) {
            return traceIdNode.asText();
        }

        JsonNode observabilityNode = rootNode.get("observability");
        if (observabilityNode != null && !observabilityNode.isNull()) {
            JsonNode nestedTraceIdNode = observabilityNode.get("traceId");
            if (nestedTraceIdNode != null && nestedTraceIdNode.isTextual()) {
                return nestedTraceIdNode.asText();
            }
        }

        JsonNode dataNode = rootNode.get("data");
        if (dataNode != null && !dataNode.isNull()) {
            JsonNode nestedTraceIdNode = dataNode.get("traceId");
            if (nestedTraceIdNode != null && nestedTraceIdNode.isTextual()) {
                return nestedTraceIdNode.asText();
            }
        }

        return null;
    }

    private Map<String, Object> extractEngineStats(JsonNode rootNode) {
        if (rootNode == null || rootNode.isNull()) {
            return null;
        }

        JsonNode observabilityNode = rootNode.get("observability");
        if (observabilityNode != null && !observabilityNode.isNull()) {
            return toMap(observabilityNode);
        }

        JsonNode statsNode = rootNode.get("stats");
        if (statsNode != null && !statsNode.isNull()) {
            return toMap(statsNode);
        }

        JsonNode dataNode = rootNode.get("data");
        if (dataNode != null && !dataNode.isNull()) {
            JsonNode nestedStatsNode = dataNode.get("stats");
            if (nestedStatsNode != null && !nestedStatsNode.isNull()) {
                return toMap(nestedStatsNode);
            }
        }

        return null;
    }

    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        Map<String, Object> mapped = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            mapped.put(field.getKey(), toValue(field.getValue()));
        }
        return mapped;
    }

    private Object toValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            return toMap(node);
        }
        if (node.isArray()) {
            java.util.List<Object> values = new java.util.ArrayList<>();
            for (JsonNode child : node) {
                values.add(toValue(child));
            }
            return values;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.toString();
    }

    private static final class ParsedEncryptResult {
        private final String encryptedData;
        private final Long processingTime;
        private final String engineTraceId;
        private final Map<String, Object> engineStats;

        private ParsedEncryptResult(String encryptedData, Long processingTime, String engineTraceId, Map<String, Object> engineStats) {
            this.encryptedData = encryptedData;
            this.processingTime = processingTime;
            this.engineTraceId = engineTraceId;
            this.engineStats = engineStats;
        }
    }

    private static final class ParsedDecryptResult {
        private final String decryptedData;
        private final Long processingTime;
        private final String engineTraceId;
        private final Map<String, Object> engineStats;

        private ParsedDecryptResult(String decryptedData, Long processingTime, String engineTraceId, Map<String, Object> engineStats) {
            this.decryptedData = decryptedData;
            this.processingTime = processingTime;
            this.engineTraceId = engineTraceId;
            this.engineStats = engineStats;
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
