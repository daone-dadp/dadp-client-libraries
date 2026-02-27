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

/**
 * Hub 암복호화 서비스 (Wrapper 전용 - Spring 의존성 없음)
 *
 * HttpURLConnection 기반으로 Engine과 직접 통신합니다.
 * hub-crypto-lib의 HubCryptoService와 동일한 public API를 제공합니다.
 *
 * @author DADP Development Team
 * @version 5.5.5
 * @since 2025-01-01
 */
public class HubCryptoService {

    private static final Logger log = LoggerFactory.getLogger(HubCryptoService.class);

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

    // ── HTTP Response holder ──────────────────────────────────────────

    private static class HttpResponse {
        final int statusCode;
        final String body;
        HttpResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
        boolean is2xx() { return statusCode >= 200 && statusCode < 300; }
    }

    // ── SSL ───────────────────────────────────────────────────────────

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

            log.info("DADP CA SSL 설정 완료: path={}", caCertPath);
            return ctx;
        } catch (Exception e) {
            log.warn("DADP CA 인증서 로드 실패, 기본 SSL 사용: path={}, error={}", caCertPath, e.getMessage());
            return null;
        }
    }

    // ── Constructor & Factory ─────────────────────────────────────────

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
                "Hub 직접 암복호화 경로는 사용할 수 없습니다. Engine 경로(/api)만 허용됩니다. 감지된 경로: %s", apiBasePath);
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        instance.apiBasePath = apiBasePath;
        instance.timeout = timeout;
        instance.enableLogging = logging;
        instance.initialized = true;

        if (logging) {
            log.info("HubCryptoService 초기화 완료 (Wrapper/HttpURLConnection): baseUrl={}, apiBasePath={}, timeout={}ms",
                    baseUrl, instance.apiBasePath, timeout);
        }

        return instance;
    }

    // ── Utilities ─────────────────────────────────────────────────────

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
            log.warn("URL 파싱 실패, 원본 사용: {}", url);
            return url.trim();
        }
    }

    public void setApiBasePath(String apiBasePath) {
        String path = apiBasePath != null ? apiBasePath : "/api";
        if (path.contains("/hub/api") || path.equals(HUB_API_PATH)) {
            throw new IllegalStateException("Hub 직접 암복호화 경로는 사용할 수 없습니다. Engine 경로(/api)만 허용됩니다.");
        }
        this.apiBasePath = path;
    }

    public String getApiBasePath() { return this.apiBasePath; }
    public boolean isInitialized() { return initialized; }

    public void initializeIfNeeded() {
        if (!isInitialized()) {
            this.initialized = true;
            if (enableLogging) log.info("HubCryptoService 런타임 초기화 완료");
        }
    }

    private void validateNotHubPath() {
        if (apiBasePath != null && (apiBasePath.contains("/hub/api") || apiBasePath.equals(HUB_API_PATH))) {
            String msg = "Hub 직접 암복호화 경로는 사용할 수 없습니다. 현재 경로: " + apiBasePath;
            log.error(msg);
            throw new IllegalStateException(msg);
        }
    }

    private void recordEndpointUsage(String endpoint) {
        this.lastUsedEndpoint = endpoint;
        this.endpointUsageCount++;
        if (enableLogging && (endpointUsageCount == 1 || endpointUsageCount % 100 == 0)) {
            log.info("Telemetry: endpoint={}, count={}", endpoint, endpointUsageCount);
        }
    }

    public String getLastUsedEndpoint() { return lastUsedEndpoint; }
    public long getEndpointUsageCount() { return endpointUsageCount; }

    // ── HTTP ──────────────────────────────────────────────────────────

    private HttpResponse doPost(String url, String requestBody) {
        HttpURLConnection conn = null;
        try {
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
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            String responseBody = readStream(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            return new HttpResponse(code, responseBody);

        } catch (java.net.SocketTimeoutException e) {
            throw new HubConnectionException("Engine 연결 타임아웃: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new HubConnectionException("Engine 연결 실패: " + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
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

    // ── Encrypt ───────────────────────────────────────────────────────

    public String encrypt(String data, String policy) {
        return encrypt(data, policy, false);
    }

    public String encrypt(String data, String policy, boolean includeStats) {
        initializeIfNeeded();
        validateNotHubPath();

        if (enableLogging) {
            log.info("Engine 암호화 요청: data={}, policy={}",
                    data != null ? data.substring(0, Math.min(20, data.length())) + "..." : "null", policy);
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
                throw new HubCryptoException("요청 데이터 직렬화 실패: " + e.getMessage());
            }

            if (enableLogging) {
                log.info("Engine 요청 URL: {}", url);
            }

            HttpResponse response = doPost(url, requestBody);

            if (enableLogging) {
                log.info("Engine 응답: status={}", response.statusCode);
            }

            if (response.is2xx()) {
                JsonNode rootNode = parseJson(response.body);

                JsonNode successNode = rootNode.get("success");
                if (successNode == null || !successNode.asBoolean()) {
                    JsonNode messageNode = rootNode.get("message");
                    String errorMessage = messageNode != null && !messageNode.isNull() ? messageNode.asText() : "암호화 실패";
                    throw new HubCryptoException("암호화 실패: " + errorMessage);
                }

                JsonNode dataNode = rootNode.get("data");
                if (dataNode == null || dataNode.isNull()) {
                    throw new HubCryptoException("암호화 실패: 응답에 data 필드가 없습니다");
                }

                // Engine 응답: data가 암호화된 문자열
                if (dataNode.isTextual()) {
                    String encryptedData = dataNode.asText();
                    if (enableLogging) {
                        log.info("Engine 암호화 성공: {} -> {}",
                                data != null ? data.substring(0, Math.min(10, data.length())) + "..." : "null",
                                encryptedData.substring(0, Math.min(20, encryptedData.length())) + "...");
                    }
                    return encryptedData;
                }

                // 레거시 응답: data가 EncryptResponse 객체
                EncryptResponse encryptResponse;
                try {
                    encryptResponse = objectMapper.treeToValue(dataNode, EncryptResponse.class);
                } catch (Exception e) {
                    throw new HubCryptoException("Engine 응답 data 파싱 실패: " + e.getMessage());
                }

                if (encryptResponse != null && encryptResponse.getSuccess() != null
                        && encryptResponse.getSuccess() && encryptResponse.getEncryptedData() != null) {
                    return encryptResponse.getEncryptedData();
                } else {
                    String msg = encryptResponse != null ? encryptResponse.getMessage() : "null";
                    throw new HubCryptoException("암호화 실패: " + msg);
                }
            } else {
                throw new HubCryptoException("Engine API 호출 실패: " + response.statusCode + " " + response.body);
            }

        } catch (Exception e) {
            if (enableLogging) log.debug("Engine 암호화 실패: {}", e.getMessage());
            if (e instanceof HubCryptoException) throw e;
            throw new HubConnectionException("Engine 연결 실패: " + e.getMessage(), e);
        }
    }

    // ── Encrypt for Search ────────────────────────────────────────────

    public String encryptForSearch(String data, String policyName) {
        initializeIfNeeded();
        validateNotHubPath();

        if (enableLogging) log.info("Engine 검색용 암호화 요청: policy={}", policyName);

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
                throw new HubCryptoException("요청 데이터 직렬화 실패: " + e.getMessage());
            }

            HttpResponse response = doPost(url, requestBody);

            if (response.is2xx()) {
                JsonNode rootNode = parseJson(response.body);
                JsonNode successNode = rootNode.get("success");
                if (successNode == null || !successNode.asBoolean()) {
                    if (enableLogging) log.warn("검색용 암호화 실패, 평문 반환: {}", rootNode.get("message"));
                    return data;
                }
                JsonNode dataNode = rootNode.get("data");
                if (dataNode != null && dataNode.isTextual()) {
                    String result = dataNode.asText();
                    if (enableLogging) {
                        log.info("검색용 암호화 완료: result={}",
                                result.length() > 30 ? result.substring(0, 30) + "..." : result);
                    }
                    return result;
                }
                return data;
            } else {
                if (enableLogging) log.warn("검색용 암호화 API 실패({}), 평문 반환", response.statusCode);
                return data;
            }
        } catch (Exception e) {
            if (enableLogging) log.warn("검색용 암호화 실패, 평문 반환: {}", e.getMessage());
            return data;
        }
    }

    // ── Decrypt ───────────────────────────────────────────────────────

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

        if (enableLogging) {
            log.info("Engine 복호화 요청: encryptedData={}, maskPolicyName={}, maskPolicyUid={}",
                    encryptedData != null ? encryptedData.substring(0, Math.min(20, encryptedData.length())) + "..." : "null",
                    maskPolicyName, maskPolicyUid);
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
                throw new HubCryptoException("요청 데이터 직렬화 실패: " + e.getMessage());
            }

            if (enableLogging) {
                log.info("Engine 요청 URL: {}", url);
            }

            HttpResponse response = doPost(url, requestBody);

            if (enableLogging) {
                log.info("Engine 복호화 응답: status={}", response.statusCode);
            }

            if (response.is2xx()) {
                return parseDecryptResponse(response.body, encryptedData);
            } else {
                // 비-2xx 응답에서도 "데이터가 암호화되지 않았습니다" 확인
                if (response.body != null && response.body.contains("데이터가 암호화되지 않았습니다")) {
                    if (enableLogging) log.warn("데이터가 암호화되지 않았습니다 (정책 추가 전 데이터)");
                    return null;
                }
                throw new HubConnectionException("Engine API 호출 실패: " + response.statusCode + " " + response.body);
            }

        } catch (Exception e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : "";

            // "데이터가 암호화되지 않았습니다" 감지
            if (errorMessage.contains("데이터가 암호화되지 않았습니다")) {
                if (enableLogging) log.warn("데이터가 암호화되지 않았습니다 (정책 추가 전 데이터)");
                return null;
            }

            if (enableLogging) log.debug("Engine 복호화 실패: {}", errorMessage);
            if (e instanceof HubCryptoException) throw e;
            throw new HubConnectionException("Engine 연결 실패: " + errorMessage, e);
        }
    }

    private String parseDecryptResponse(String responseBody, String encryptedData) {
        JsonNode rootNode = parseJson(responseBody);

        JsonNode successNode = rootNode.get("success");
        if (successNode == null || !successNode.asBoolean()) {
            JsonNode messageNode = rootNode.get("message");
            String errorMessage = messageNode != null && !messageNode.isNull() ? messageNode.asText() : "복호화 실패";

            if (errorMessage.contains("데이터가 암호화되지 않았습니다")) {
                if (enableLogging) log.warn("데이터가 암호화되지 않았습니다 (정책 추가 전 데이터)");
                return null;
            }
            throw new HubCryptoException("복호화 실패: " + errorMessage);
        }

        JsonNode dataNode = rootNode.get("data");
        if (dataNode == null || dataNode.isNull()) {
            throw new HubCryptoException("복호화 실패: 응답에 data 필드가 없습니다");
        }

        // Engine 응답: data가 복호화된 문자열
        if (dataNode.isTextual()) {
            String decryptedData = dataNode.asText();
            if (enableLogging) {
                log.info("Engine 복호화 성공: {} -> {}",
                        encryptedData != null ? encryptedData.substring(0, Math.min(20, encryptedData.length())) + "..." : "null",
                        decryptedData.substring(0, Math.min(10, decryptedData.length())) + "...");
            }
            return decryptedData;
        }

        // 레거시 응답: data가 DecryptResponse 객체
        DecryptResponse decryptResponse;
        try {
            decryptResponse = objectMapper.treeToValue(dataNode, DecryptResponse.class);
        } catch (Exception e) {
            throw new HubCryptoException("Engine 응답 data 파싱 실패: " + e.getMessage());
        }

        if (decryptResponse == null) {
            throw new HubCryptoException("복호화 실패: 응답에 data 필드가 없습니다");
        }

        if (Boolean.TRUE.equals(decryptResponse.getSuccess()) && decryptResponse.getDecryptedData() != null) {
            return decryptResponse.getDecryptedData();
        } else if (decryptResponse.getDecryptedData() != null) {
            // 마스킹 적용된 경우
            return decryptResponse.getDecryptedData();
        } else {
            String message = decryptResponse.getMessage() != null ? decryptResponse.getMessage() : "복호화 실패";
            if (message.contains("데이터가 암호화되지 않았습니다")) {
                if (enableLogging) log.warn("데이터가 암호화되지 않았습니다 (정책 추가 전 데이터)");
                return null;
            }
            throw new HubCryptoException("복호화 실패: " + message);
        }
    }

    // ── Batch Decrypt ─────────────────────────────────────────────────

    public java.util.List<String> batchDecrypt(java.util.List<String> encryptedDataList,
                                                String maskPolicyName,
                                                String maskPolicyUid,
                                                boolean includeStats) {
        initializeIfNeeded();

        if (encryptedDataList == null || encryptedDataList.isEmpty()) {
            return new java.util.ArrayList<>();
        }

        if (enableLogging) {
            log.info("Engine 배치 복호화 요청: itemsCount={}, maskPolicyName={}, maskPolicyUid={}",
                    encryptedDataList.size(), maskPolicyName, maskPolicyUid);
        }

        try {
            validateNotHubPath();

            String url = hubUrl + apiBasePath + "/decrypt/batch";
            recordEndpointUsage(url);

            // 배치 요청 생성
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
                throw new HubCryptoException("요청 데이터 직렬화 실패: " + e.getMessage());
            }

            if (enableLogging) log.info("Engine 배치 요청 URL: {}", url);

            HttpResponse response = doPost(url, requestBody);

            if (enableLogging) log.info("Engine 배치 응답: status={}", response.statusCode);

            if (response.is2xx()) {
                JsonNode rootNode = parseJson(response.body);

                // results 배열 추출 (최상위 레벨)
                JsonNode resultsNode = rootNode.get("results");
                if (resultsNode == null || !resultsNode.isArray()) {
                    // ApiResponse 래퍼가 있는 경우
                    JsonNode successNode = rootNode.get("success");
                    if (successNode != null && successNode.asBoolean()) {
                        JsonNode dataNode = rootNode.get("data");
                        if (dataNode != null && !dataNode.isNull()) {
                            resultsNode = dataNode.get("results");
                        }
                    }
                    if (resultsNode == null || !resultsNode.isArray()) {
                        throw new HubCryptoException("배치 복호화 실패: 응답에 results 배열이 없습니다");
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

                if (enableLogging) log.info("Engine 배치 복호화 성공: {}개 항목", decryptedList.size());
                return decryptedList;
            } else {
                throw new HubCryptoException("배치 복호화 실패: " + response.statusCode);
            }

        } catch (HubCryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new HubCryptoException("배치 복호화 중 오류: " + e.getMessage(), e);
        }
    }

    // ── Batch Encrypt ─────────────────────────────────────────────────

    public java.util.List<String> batchEncrypt(java.util.List<String> dataList,
                                                java.util.List<String> policyList) {
        initializeIfNeeded();

        if (dataList == null || dataList.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        if (policyList == null || policyList.size() != dataList.size()) {
            throw new HubCryptoException("정책 목록의 크기가 데이터 목록과 일치하지 않습니다");
        }

        log.info("Engine batchEncrypt called: itemsCount={}, hubUrl={}, apiBasePath={}",
                dataList.size(), hubUrl, apiBasePath);

        if (enableLogging) {
            log.info("Engine 배치 암호화 요청: itemsCount={}", dataList.size());
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
                throw new HubCryptoException("요청 데이터 직렬화 실패: " + e.getMessage());
            }

            if (enableLogging) log.info("Engine 배치 암호화 URL: {}", url);

            HttpResponse response = doPost(url, requestBody);

            if (enableLogging) log.info("Engine 배치 암호화 응답: status={}", response.statusCode);

            if (response.is2xx()) {
                JsonNode rootNode = parseJson(response.body);

                JsonNode successNode = rootNode.get("success");
                if (successNode == null || !successNode.asBoolean()) {
                    JsonNode messageNode = rootNode.get("message");
                    String errorMessage = messageNode != null && !messageNode.isNull() ? messageNode.asText() : "배치 암호화 실패";
                    throw new HubCryptoException("배치 암호화 실패: " + errorMessage);
                }

                JsonNode dataNode = rootNode.get("data");
                if (dataNode == null || dataNode.isNull()) {
                    throw new HubCryptoException("배치 암호화 실패: 응답에 data 필드가 없습니다");
                }

                JsonNode resultsNode = dataNode.get("results");
                if (resultsNode == null || !resultsNode.isArray()) {
                    throw new HubCryptoException("배치 암호화 실패: 응답에 results 배열이 없습니다");
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

                if (enableLogging) log.info("Engine 배치 암호화 성공: {}개 항목", encryptedList.size());
                return encryptedList;
            } else {
                throw new HubCryptoException("배치 암호화 실패: " + response.statusCode);
            }

        } catch (HubCryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new HubCryptoException("배치 암호화 중 오류: " + e.getMessage(), e);
        }
    }

    // ── isEncryptedData ───────────────────────────────────────────────

    public boolean isEncryptedData(String data) {
        if (data == null || data.isEmpty()) {
            return false;
        }

        if (enableLogging && log.isDebugEnabled()) {
            log.debug("isEncryptedData 체크: dataLength={}, preview={}",
                    data.length(),
                    data.length() > 50 ? data.substring(0, 50) + "..." : data);
        }

        // 부분암호화 형식: "[평문]::ENC::[암호문]"
        String checkPart = data;
        if (data.contains("::ENC::")) {
            int idx = data.indexOf("::ENC::");
            checkPart = data.substring(idx + "::ENC::".length());
        }

        // hub: 접두사
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

        // kms: 접두사
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

        // vault: 접두사
        if (checkPart.startsWith("vault:")) {
            String[] parts = checkPart.split(":", 4);
            return parts.length >= 4 && parts[2].startsWith("v");
        }

        // 레거시 형식: Base64 + Policy UUID 검증
        try {
            byte[] decoded = Base64.getDecoder().decode(checkPart);
            if (decoded.length >= 64 && decoded.length >= 36) {
                try {
                    String uuidCandidate = new String(decoded, 0, 36, StandardCharsets.UTF_8);
                    boolean isValidUuid = uuidCandidate.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
                    if (enableLogging && log.isDebugEnabled()) {
                        log.debug("레거시 형식 체크: decodedLength={}, isValidUuid={}", decoded.length, isValidUuid);
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

    // ── JSON Helper ───────────────────────────────────────────────────

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new HubCryptoException("Engine 응답 파싱 실패: " + e.getMessage());
        }
    }
}
