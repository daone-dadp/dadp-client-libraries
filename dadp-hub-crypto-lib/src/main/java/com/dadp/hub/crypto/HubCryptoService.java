package com.dadp.hub.crypto;

import com.dadp.hub.crypto.dto.*;
import com.dadp.hub.crypto.exception.HubCryptoException;
import com.dadp.hub.crypto.exception.HubConnectionException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.security.cert.X509Certificate;

/**
 * Hub 암복호화 서비스
 * 
 * Hub와의 암복호화 통신을 담당하는 핵심 서비스입니다.
 * RestTemplate을 사용하여 모든 Java 버전에서 동작합니다.
 * 
 * @author DADP Development Team
 * @version 2.0.0
 * @since 2025-01-01
 */
public class HubCryptoService {
    
    private static final Logger log = LoggerFactory.getLogger(HubCryptoService.class);
    
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private String hubUrl;
    private String apiBasePath = "/api";  // 기본값: Engine 경로만 허용
    private int timeout;
    private boolean enableLogging;
    private boolean initialized = false;
    
    // Hub 경로 상수 (deprecated - 사용 불가)
    @Deprecated
    private static final String HUB_API_PATH = "/hub/api/v1";
    private static final String ENGINE_API_PATH = "/api";
    
    // Telemetry: 암복호화 엔드포인트 추적
    private volatile String lastUsedEndpoint = null;
    private volatile long endpointUsageCount = 0;

    /**
     * DADP_CA_CERT_PATH 환경 변수에서 DADP CA 인증서 경로 가져오기
     * 
     * @return DADP CA 인증서 파일 경로 또는 null
     */
    private static String getDadpCaCertPath() {
        String caCertPath = System.getenv("DADP_CA_CERT_PATH");
        if (caCertPath == null || caCertPath.trim().isEmpty()) {
            caCertPath = System.getProperty("dadp.ca.cert.path");
        }
        return caCertPath != null && !caCertPath.trim().isEmpty() ? caCertPath.trim() : null;
    }
    
    /**
     * PEM 형식 인증서를 X.509 인증서로 변환 (Java 표준 라이브러리만 사용)
     */
    private static X509Certificate pemToCertificate(String pem) throws Exception {
        // PEM 형식에서 BEGIN/END CERTIFICATE 사이의 Base64 디코딩
        String certContent = pem.replace("-----BEGIN CERTIFICATE-----", "")
                                .replace("-----END CERTIFICATE-----", "")
                                .replaceAll("\\s", "");
        
        byte[] certBytes = Base64.getDecoder().decode(certContent);
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) certFactory.generateCertificate(new ByteArrayInputStream(certBytes));
    }
    
    /**
     * DADP CA 인증서만 신뢰하는 SSLContext 생성
     * DADP_CA_CERT_PATH 환경 변수로 DADP CA 인증서 경로 지정
     */
    private static SSLContext createDadpCaSSLContext() {
        String caCertPath = getDadpCaCertPath();
        if (caCertPath == null) {
            return null;
        }
        
        try {
            // PEM 파일 읽기
            String pem = new String(Files.readAllBytes(Paths.get(caCertPath)));
            X509Certificate caCert = pemToCertificate(pem);
            
            // TrustStore 생성 및 DADP CA 추가
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("dadp-root-ca", caCert);
            
            // TrustManagerFactory 생성
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            );
            trustManagerFactory.init(trustStore);
            
            // SSLContext 생성
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagerFactory.getTrustManagers(), new java.security.SecureRandom());
            
            log.info("✅ DADP CA 인증서만 신뢰하도록 SSL 설정 완료: path={}", caCertPath);
            return sslContext;
        } catch (Exception e) {
            log.warn("⚠️ DADP CA 인증서 로드 실패, 기본 SSL 설정 사용: path={}, error={}", caCertPath, e.getMessage());
            return null;
        }
    }
    
    /**
     * SSL 설정이 적용된 RestTemplate 생성
     * 우선순위:
     * 1. DADP_CA_CERT_PATH: DADP CA 인증서만 신뢰 (운영 환경 권장)
     * 2. 기본: Java 기본 TrustStore 사용
     */
    private static RestTemplate createRestTemplateWithSSL() {
        SSLContext sslContext = null;
        
        // 1. DADP CA 인증서만 신뢰 (운영 환경 권장)
        sslContext = createDadpCaSSLContext();
        
        final SSLContext finalSslContext = sslContext;
        
        if (finalSslContext != null) {
            // SSL 설정이 적용된 ClientHttpRequestFactory 생성
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
                @Override
                protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws java.io.IOException {
                    super.prepareConnection(connection, httpMethod);
                    if (connection instanceof HttpsURLConnection) {
                        HttpsURLConnection httpsConnection = (HttpsURLConnection) connection;
                        httpsConnection.setSSLSocketFactory(finalSslContext.getSocketFactory());
                        // DADP CA만 신뢰하는 경우 호스트명 검증 유지
                    }
                }
            };
            
            return new RestTemplate(factory);
        }
        
        // SSL 설정 실패 시 또는 환경 변수가 없을 때 기본 RestTemplate 반환 (정상적인 SSL 검증)
        return new RestTemplate();
    }
    
    /**
     * 생성자
     */
    public HubCryptoService() {
        this.restTemplate = createRestTemplateWithSSL();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 자동 초기화 메서드 - Spring Bean이 아닌 경우 사용
     * DADP_ENABLE_LOGGING 환경 변수를 자동으로 확인합니다.
     */
    public static HubCryptoService createInstance() {
        boolean enableLogging = isLoggingEnabled();
        return createInstance("http://localhost:9003", 5000, enableLogging);
    }

    /**
     * 자동 초기화 메서드 - 커스텀 설정으로 생성
     * @param hubUrl Hub 또는 Engine URL (예: http://localhost:9003 또는 http://hub:9004/hub)
     *               base URL만 제공하면 자동으로 경로 감지
     * @param timeout 타임아웃 (ms)
     * @param enableLogging 로깅 활성화 (null이면 DADP_ENABLE_LOGGING 환경 변수 확인)
     */
    public static HubCryptoService createInstance(String hubUrl, int timeout, Boolean enableLogging) {
        // enableLogging이 null이면 DADP_ENABLE_LOGGING 환경 변수 확인
        boolean logging = enableLogging != null ? enableLogging : isLoggingEnabled();
        // apiBasePath를 null로 전달하여 자동 감지
        return createInstance(hubUrl, null, timeout, logging);
    }
    
    /**
     * DADP_ENABLE_LOGGING 환경 변수 확인
     * 
     * @return 로그 활성화 여부
     */
    private static boolean isLoggingEnabled() {
        String enableLogging = System.getenv("DADP_ENABLE_LOGGING");
        if (enableLogging == null || enableLogging.trim().isEmpty()) {
            enableLogging = System.getProperty("dadp.enable-logging");
        }
        return enableLogging != null && !enableLogging.trim().isEmpty() && 
               ("true".equalsIgnoreCase(enableLogging) || "1".equals(enableLogging));
    }
    
    /**
     * Base URL에서 경로를 제거하여 추출
     * 예: "http://hub:9004/hub" → "http://hub:9004"
     * 예: "http://engine:9003/api" → "http://engine:9003"
     * 
     * @param url 전체 URL 또는 base URL
     * @return base URL (경로 제외)
     */
    private static String extractBaseUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return url;
        }
        
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            
            if (scheme == null || host == null) {
                // URI 파싱 실패 시 원본 반환
                return url.trim();
            }
            
            // base URL 구성 (scheme://host:port)
            if (port != -1) {
                return scheme + "://" + host + ":" + port;
            } else {
                return scheme + "://" + host;
            }
        } catch (Exception e) {
            // URI 파싱 실패 시 원본 반환
            log.warn("⚠️ URL 파싱 실패, 원본 사용: {}", url);
            return url.trim();
        }
    }
    
    /**
     * 자동 초기화 메서드 - API 경로 포함
     * @param hubUrl Engine URL (예: http://localhost:9003)
     * @param apiBasePath API 기본 경로 (Engine: "/api"만 허용)
     *                   null이면 "/api"로 설정 (Hub 경로는 사용 불가)
     * @param timeout 타임아웃 (ms)
     * @param enableLogging 로깅 활성화 (null이면 DADP_ENABLE_LOGGING 환경 변수 확인)
     * @throws IllegalStateException Hub 경로가 감지된 경우
     */
    public static HubCryptoService createInstance(String hubUrl, String apiBasePath, int timeout, Boolean enableLogging) {
        // enableLogging이 null이면 DADP_ENABLE_LOGGING 환경 변수 확인
        boolean logging = enableLogging != null ? enableLogging : isLoggingEnabled();
        HubCryptoService instance = new HubCryptoService();
        
        // base URL 추출 (경로 제거)
        String baseUrl = extractBaseUrl(hubUrl);
        instance.hubUrl = baseUrl;
        
        // apiBasePath가 null이면 Engine 경로로 설정
        if (apiBasePath == null || apiBasePath.trim().isEmpty()) {
            apiBasePath = ENGINE_API_PATH;
        }
        
        // Hub 경로 사용 시 예외 발생 (런타임 가드)
        if (apiBasePath.contains("/hub/api") || apiBasePath.equals(HUB_API_PATH)) {
            String errorMsg = String.format(
                "Hub 직접 암복호화 경로는 사용할 수 없습니다. Engine 경로(/api)만 허용됩니다. " +
                "감지된 경로: %s. Hub를 통한 암복호화는 제거되었습니다. Engine에 직접 연결하세요.",
                apiBasePath
            );
            log.error("❌ {}", errorMsg);
            throw new IllegalStateException(errorMsg);
        }
        
        instance.apiBasePath = apiBasePath;
        instance.timeout = timeout;
        instance.enableLogging = logging;
        instance.initialized = true;
        
        if (logging) {
            log.info("✅ HubCryptoService 자동 초기화 완료: baseUrl={}, apiBasePath={}, timeout={}ms", 
                    baseUrl, instance.apiBasePath, timeout);
        }
        
        return instance;
    }
    
    /**
     * API 기본 경로 설정
     * @param apiBasePath API 기본 경로 (Engine: "/api"만 허용, Hub 경로는 사용 불가)
     * @throws IllegalStateException Hub 경로가 감지된 경우
     */
    public void setApiBasePath(String apiBasePath) {
        String path = apiBasePath != null ? apiBasePath : "/api";
        
        // Hub 경로 사용 시 예외 발생 (런타임 가드)
        if (path.contains("/hub/api") || path.equals(HUB_API_PATH)) {
            String errorMsg = String.format(
                "Hub 직접 암복호화 경로는 사용할 수 없습니다. Engine 경로(/api)만 허용됩니다. " +
                "감지된 경로: %s. Hub를 통한 암복호화는 제거되었습니다. Engine에 직접 연결하세요.",
                path
            );
            log.error("❌ {}", errorMsg);
            throw new IllegalStateException(errorMsg);
        }
        
        this.apiBasePath = path;
    }
    
    /**
     * API 기본 경로 조회
     */
    public String getApiBasePath() {
        return this.apiBasePath;
    }

    /**
     * 초기화 상태 확인
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * Spring Boot 2.x/3.x 호환성을 위한 getStatusCode 헬퍼 메서드
     */
    private boolean is2xxSuccessful(ResponseEntity<?> response) {
        try {
            // 리플렉션을 사용하여 getStatusCode() 호출 (Spring Boot 2.x/3.x 호환)
            Method getStatusCodeMethod = response.getClass().getMethod("getStatusCode");
            Object statusCode = getStatusCodeMethod.invoke(response);
            // is2xxSuccessful() 메서드 호출
            Method is2xxMethod = statusCode.getClass().getMethod("is2xxSuccessful");
            return (Boolean) is2xxMethod.invoke(statusCode);
        } catch (Exception e) {
            // 최후의 수단: getStatusCodeValue() 사용 (Spring Boot 2.x)
            try {
                Method getValueMethod = response.getClass().getMethod("getStatusCodeValue");
                int statusValue = (Integer) getValueMethod.invoke(response);
                return statusValue >= 200 && statusValue < 300;
            } catch (Exception e2) {
                log.error("상태 코드 확인 실패", e2);
                return false;
            }
        }
    }
    
    /**
     * Spring Boot 2.x/3.x 호환성을 위한 예외에서 상태코드 추출
     */
    private String getExceptionStatusCode(Exception e) {
        try {
            // 리플렉션을 사용하여 getStatusCode() 호출 (Spring Boot 2.x/3.x 호환)
            Method getStatusCodeMethod = e.getClass().getMethod("getStatusCode");
            Object statusCode = getStatusCodeMethod.invoke(e);
            return statusCode.toString();
        } catch (Exception ex) {
            // 최후의 수단: getRawStatusCode() 사용 (Spring Boot 2.x)
            try {
                Method getRawStatusCodeMethod = e.getClass().getMethod("getRawStatusCode");
                int statusValue = (Integer) getRawStatusCodeMethod.invoke(e);
                return String.valueOf(statusValue);
            } catch (Exception ex2) {
                return "UNKNOWN";
            }
        }
    }
    
    /**
     * Spring Boot 2.x/3.x 호환성을 위한 getStatusCode 문자열 변환
     */
    private String getStatusCodeString(ResponseEntity<?> response) {
        try {
            // 리플렉션을 사용하여 getStatusCode() 호출 (Spring Boot 2.x/3.x 호환)
            Method getStatusCodeMethod = response.getClass().getMethod("getStatusCode");
            Object statusCode = getStatusCodeMethod.invoke(response);
            // toString() 메서드 호출
            return statusCode.toString();
        } catch (Exception e) {
            // 최후의 수단: getStatusCodeValue() 사용 (Spring Boot 2.x)
            try {
                Method getValueMethod = response.getClass().getMethod("getStatusCodeValue");
                int statusValue = (Integer) getValueMethod.invoke(response);
                return String.valueOf(statusValue);
            } catch (Exception e2) {
                return "UNKNOWN";
            }
        }
    }

    /**
     * 런타임 초기화 (필요시)
     */
    public void initializeIfNeeded() {
        if (!isInitialized()) {
            this.initialized = true;
            
            if (enableLogging) {
                log.info("✅ HubCryptoService 런타임 초기화 완료");
            }
        }
    }
    
    /**
     * 데이터 암호화
     * 
     * @param data 암호화할 데이터
     * @param policy 암호화 정책명
     * @return 암호화된 데이터
     * @throws HubCryptoException 암호화 실패 시
     */
    public String encrypt(String data, String policy) {
        return encrypt(data, policy, false);
    }
    
    /**
     * 데이터 암호화 (통계 정보 포함 옵션)
     * 
     * @param data 암호화할 데이터
     * @param policy 암호화 정책명
     * @param includeStats 통계 정보 포함 여부
     * @return 암호화된 데이터
     * @throws HubCryptoException 암호화 실패 시
     */
    public String encrypt(String data, String policy, boolean includeStats) {
        // 초기화 확인
        initializeIfNeeded();
        
        // Hub 경로 사용 시 예외 발생 (런타임 가드)
        validateNotHubPath();
        
        if (enableLogging) {
            log.info("🔐 Engine 암호화 요청 시작: data={}, policy={}", 
                    data != null ? data.substring(0, Math.min(20, data.length())) + "..." : "null", policy);
        }
        
        try {
            String url = hubUrl + apiBasePath + "/encrypt";
            
            // Telemetry: 엔드포인트 추적
            recordEndpointUsage(url);
            
            EncryptRequest request = new EncryptRequest();
            request.setData(data);
            request.setPolicyName(policy);
            // includeStats는 엔진에서 제거되었으므로 전달하지 않음
            
            String requestBody;
            try {
                requestBody = objectMapper.writeValueAsString(request);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new HubCryptoException("요청 데이터 직렬화 실패: " + e.getMessage());
            }
            
            if (enableLogging) {
                log.info("🔐 Engine 요청 URL: {}", url);
                log.info("🔐 Engine 요청 데이터: {}", request);
            }
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<String> response;
            try {
                response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            } catch (HttpClientErrorException | HttpServerErrorException e) {
                throw new HubConnectionException("Engine 연결 실패: " + getExceptionStatusCode(e) + " " + e.getResponseBodyAsString(), e);
            } catch (Exception e) {
                throw new HubConnectionException("Engine 연결 실패: " + e.getMessage(), e);
            }
            
            if (enableLogging) {
                log.info("🔐 Engine 응답 상태: {} {}", getStatusCodeString(response), url);
                log.info("🔐 Engine 응답 데이터: {}", response.getBody());
            }
            
                if (is2xxSuccessful(response)) {
                // Engine 응답은 ApiResponse<EncryptResponse> 형태
                // TypeReference로 제네릭 파싱이 실패할 수 있으므로 JsonNode로 먼저 파싱
                JsonNode rootNode;
                try {
                    rootNode = objectMapper.readTree(response.getBody());
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new HubCryptoException("Engine 응답 파싱 실패: " + e.getMessage());
                }
                
                // ApiResponse의 success 확인
                JsonNode successNode = rootNode.get("success");
                if (successNode == null || !successNode.asBoolean()) {
                    JsonNode messageNode = rootNode.get("message");
                    String errorMessage = messageNode != null && !messageNode.isNull() ? messageNode.asText() : "암호화 실패";
                    throw new HubCryptoException("암호화 실패: " + errorMessage);
                }
                
                // data 필드 추출
                JsonNode dataNode = rootNode.get("data");
                if (dataNode == null || dataNode.isNull()) {
                    throw new HubCryptoException("암호화 실패: 응답에 data 필드가 없습니다");
                }
                
                String encryptedData;
                
                // Engine 응답: data가 암호화된 문자열
                if (dataNode.isTextual()) {
                    encryptedData = dataNode.asText();
                    if (enableLogging) {
                        log.info("✅ Engine 암호화 성공: {} → {}", 
                                data != null ? data.substring(0, Math.min(10, data.length())) + "..." : "null",
                                encryptedData != null ? encryptedData.substring(0, Math.min(20, encryptedData.length())) + "..." : "null");
                    }
                    return encryptedData;
                }
                
                // 레거시 응답 형식: data가 EncryptResponse 객체 (현재는 사용되지 않음)
                EncryptResponse encryptResponse;
                try {
                    encryptResponse = objectMapper.treeToValue(dataNode, EncryptResponse.class);
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new HubCryptoException("Engine 응답 data 파싱 실패: " + e.getMessage());
                }
                
                if (encryptResponse == null) {
                    throw new HubCryptoException("암호화 실패: 응답에 data 필드가 없습니다");
                }
                
                if (encryptResponse.getSuccess() != null && encryptResponse.getSuccess() && encryptResponse.getEncryptedData() != null) {
                    encryptedData = encryptResponse.getEncryptedData();
                    if (enableLogging) {
                        log.info("✅ Engine 암호화 성공: {} → {}", 
                                data != null ? data.substring(0, Math.min(10, data.length())) + "..." : "null",
                                encryptedData != null ? encryptedData.substring(0, Math.min(20, encryptedData.length())) + "..." : "null");
                    }
                    return encryptedData;
                } else {
                    String errorMsg = String.format("암호화 실패: success=%s, encryptedData=%s, message=%s", 
                            encryptResponse.getSuccess(), 
                            encryptResponse.getEncryptedData() != null ? "있음" : "null",
                            encryptResponse.getMessage());
                    // 에러 로그는 상위 레이어(HubCryptoAdapter)에서 처리하므로 여기서는 DEBUG 레벨만 사용
                    if (enableLogging) {
                        log.debug("Engine 암호화 실패 (상위 레이어에서 처리): {}", errorMsg);
                    }
                    throw new HubCryptoException(errorMsg);
                }
            } else {
                throw new HubCryptoException("Engine API 호출 실패: " + getStatusCodeString(response) + " " + response.getBody());
            }
            
        } catch (Exception e) {
            // 에러 로그는 상위 레이어(HubCryptoAdapter)에서 처리하므로 여기서는 DEBUG 레벨만 사용
            if (enableLogging) {
                log.debug("Engine 암호화 실패 (상위 레이어에서 처리): {}", e.getMessage());
            }
            if (e instanceof HubCryptoException) {
                throw e;
            } else {
                throw new HubConnectionException("Engine 연결 실패: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * 검색용 암호화
     * Engine이 정책(useIv/usePlain)에 따라 암호화된 값 또는 평문을 반환한다.
     * - useIv=false AND usePlain=false → 고정 IV 전체 암호화 (전체 일치 검색용)
     * - 그 외 → 평문 그대로 반환
     *
     * @param data 검색할 데이터 (평문)
     * @param policyName 암호화 정책명
     * @return 암호문 또는 평문 (Engine이 결정)
     */
    public String encryptForSearch(String data, String policyName) {
        initializeIfNeeded();
        validateNotHubPath();

        if (enableLogging) {
            log.info("🔍 Engine 검색용 암호화 요청: policy={}", policyName);
        }

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
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new HubCryptoException("요청 데이터 직렬화 실패: " + e.getMessage());
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response;
            try {
                response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            } catch (HttpClientErrorException | HttpServerErrorException e) {
                throw new HubConnectionException("Engine 연결 실패: " + getExceptionStatusCode(e) + " " + e.getResponseBodyAsString(), e);
            } catch (Exception e) {
                throw new HubConnectionException("Engine 연결 실패: " + e.getMessage(), e);
            }

            if (is2xxSuccessful(response)) {
                JsonNode rootNode;
                try {
                    rootNode = objectMapper.readTree(response.getBody());
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new HubCryptoException("Engine 응답 파싱 실패: " + e.getMessage());
                }

                JsonNode successNode = rootNode.get("success");
                if (successNode == null || !successNode.asBoolean()) {
                    // 실패 시 평문 반환 (검색은 best-effort)
                    if (enableLogging) {
                        log.warn("검색용 암호화 실패, 평문 반환: {}", rootNode.get("message"));
                    }
                    return data;
                }

                JsonNode dataNode = rootNode.get("data");
                if (dataNode != null && dataNode.isTextual()) {
                    String result = dataNode.asText();
                    if (enableLogging) {
                        log.info("🔍 검색용 암호화 완료: 결과={}",
                                result.length() > 30 ? result.substring(0, 30) + "..." : result);
                    }
                    return result;
                }

                // data 필드가 없으면 평문 반환
                return data;
            } else {
                if (enableLogging) {
                    log.warn("검색용 암호화 API 실패({}), 평문 반환", getStatusCodeString(response));
                }
                return data;
            }
        } catch (Exception e) {
            // 검색용 암호화 실패 시 평문 반환 (best-effort)
            if (enableLogging) {
                log.warn("검색용 암호화 실패, 평문 반환: {}", e.getMessage());
            }
            return data;
        }
    }

    /**
     * 데이터 복호화
     *
     * @param encryptedData 복호화할 암호화된 데이터
     * @return 복호화된 데이터
     * @throws HubCryptoException 복호화 실패 시
     */
    public String decrypt(String encryptedData) {
        return decrypt(encryptedData, null, null);
    }
    
    /**
     * 데이터 복호화 (마스킹 정책 포함)
     * 
     * @param encryptedData 복호화할 암호화된 데이터
     * @param maskPolicyName 마스킹 정책명 (선택사항)
     * @param maskPolicyUid 마스킹 정책 UID (선택사항)
     * @return 복호화된 데이터 (마스킹 정책이 지정된 경우 마스킹 적용)
     * @throws HubCryptoException 복호화 실패 시
     */
    public String decrypt(String encryptedData, String maskPolicyName, String maskPolicyUid) {
        return decrypt(encryptedData, maskPolicyName, maskPolicyUid, false);
    }
    
    /**
     * 데이터 복호화 (마스킹 정책 및 통계 정보 포함 옵션)
     * 
     * @param encryptedData 복호화할 암호화된 데이터
     * @param maskPolicyName 마스킹 정책명 (선택사항)
     * @param maskPolicyUid 마스킹 정책 UID (선택사항)
     * @param includeStats 통계 정보 포함 여부
     * @return 복호화된 데이터 (마스킹 정책이 지정된 경우 마스킹 적용)
     * @throws HubCryptoException 복호화 실패 시
     */
    public String decrypt(String encryptedData, String maskPolicyName, String maskPolicyUid, boolean includeStats) {
        // 초기화 확인
        initializeIfNeeded();
        
        // Hub 경로 사용 시 예외 발생 (런타임 가드)
        validateNotHubPath();
        
        if (enableLogging) {
            log.info("🔓 Engine 복호화 요청 시작: encryptedData={}, maskPolicyName={}, maskPolicyUid={}", 
                    encryptedData != null ? encryptedData.substring(0, Math.min(20, encryptedData.length())) + "..." : "null",
                    maskPolicyName, maskPolicyUid);
        }
        
        try {
            String url = hubUrl + apiBasePath + "/decrypt";
            
            // Telemetry: 엔드포인트 추적
            recordEndpointUsage(url);
            
            DecryptRequest request = new DecryptRequest();
            request.setEncryptedData(encryptedData);
            request.setMaskPolicyName(maskPolicyName);
            request.setMaskPolicyUid(maskPolicyUid);
            // includeStats는 엔진에서 제거되었으므로 전달하지 않음
            
            String requestBody;
            try {
                requestBody = objectMapper.writeValueAsString(request);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new HubCryptoException("요청 데이터 직렬화 실패: " + e.getMessage());
            }
            
            if (enableLogging) {
                log.info("🔓 Engine 요청 URL: {}", url);
                log.info("🔓 Engine 요청 데이터: {}", request);
            }
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<String> response;
            try {
                response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            } catch (HttpClientErrorException | HttpServerErrorException e) {
                throw new HubConnectionException("Engine 연결 실패: " + getExceptionStatusCode(e) + " " + e.getResponseBodyAsString(), e);
            } catch (Exception e) {
                throw new HubConnectionException("Engine 연결 실패: " + e.getMessage(), e);
            }
            
            if (enableLogging) {
                log.info("🔓 Engine 응답 상태: {} {}", getStatusCodeString(response), url);
                log.info("🔓 Engine 응답 데이터: {}", response.getBody());
            }
            
            if (is2xxSuccessful(response)) {
                // Engine 응답은 ApiResponse<DecryptResponse> 형태
                // TypeReference로 제네릭 파싱이 실패할 수 있으므로 JsonNode로 먼저 파싱
                JsonNode rootNode;
                try {
                    rootNode = objectMapper.readTree(response.getBody());
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new HubCryptoException("Engine 응답 파싱 실패: " + e.getMessage());
                }
                
                // ApiResponse의 success 확인
                JsonNode successNode = rootNode.get("success");
                if (successNode == null || !successNode.asBoolean()) {
                    JsonNode messageNode = rootNode.get("message");
                    String errorMessage = messageNode != null && !messageNode.isNull() ? messageNode.asText() : "복호화 실패";
                    
                    // "데이터가 암호화되지 않았습니다" 메시지인 경우 null 반환
                    if (errorMessage.contains("데이터가 암호화되지 않았습니다")) {
                        if (enableLogging) {
                            log.warn("⚠️ 데이터가 암호화되지 않았습니다 (정책 추가 전 데이터)");
                        }
                        return null; // null 반환 시 HubCryptoAdapter에서 원본 데이터 반환
                    }
                    
                    throw new HubCryptoException("복호화 실패: " + errorMessage);
                }
                
                // data 필드 추출
                JsonNode dataNode = rootNode.get("data");
                if (dataNode == null || dataNode.isNull()) {
                    throw new HubCryptoException("복호화 실패: 응답에 data 필드가 없습니다");
                }
                
                String decryptedData;
                
                // Engine 응답: data가 복호화된 문자열
                if (dataNode.isTextual()) {
                    decryptedData = dataNode.asText();
                    if (enableLogging) {
                        log.info("✅ Engine 복호화 성공: {} → {}", 
                                encryptedData != null ? encryptedData.substring(0, Math.min(20, encryptedData.length())) + "..." : "null",
                                decryptedData != null ? decryptedData.substring(0, Math.min(10, decryptedData.length())) + "..." : "null");
                    }
                    return decryptedData;
                }
                
                // 레거시 응답 형식: data가 DecryptResponse 객체 (현재는 사용되지 않음)
                DecryptResponse decryptResponse;
                try {
                    decryptResponse = objectMapper.treeToValue(dataNode, DecryptResponse.class);
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new HubCryptoException("Engine 응답 data 파싱 실패: " + e.getMessage());
                }
                
                if (decryptResponse == null) {
                    throw new HubCryptoException("복호화 실패: 응답에 data 필드가 없습니다");
                }
                
                // DecryptResponse의 success 확인
                // success가 true이고 decryptedData가 있으면 반환
                if (Boolean.TRUE.equals(decryptResponse.getSuccess()) && decryptResponse.getDecryptedData() != null) {
                    decryptedData = decryptResponse.getDecryptedData();
                    if (enableLogging) {
                        log.info("✅ Engine 복호화 성공: {} → {}", 
                                encryptedData != null ? encryptedData.substring(0, Math.min(20, encryptedData.length())) + "..." : "null",
                                decryptedData != null ? decryptedData.substring(0, Math.min(10, decryptedData.length())) + "..." : "null");
                    }
                    return decryptedData;
                } else if (decryptResponse.getDecryptedData() != null) {
                    // success가 false여도 decryptedData가 있으면 반환 (평문 데이터에 마스킹 적용된 경우)
                    decryptedData = decryptResponse.getDecryptedData();
                    if (enableLogging) {
                        log.info("✅ Engine 처리 완료 (마스킹 적용 가능): {} → {}", 
                                encryptedData != null ? encryptedData.substring(0, Math.min(20, encryptedData.length())) + "..." : "null",
                                decryptedData != null ? decryptedData.substring(0, Math.min(10, decryptedData.length())) + "..." : "null");
                    }
                    return decryptedData;
                } else {
                    // DecryptResponse의 success가 false이고 decryptedData도 null인 경우
                    String message = decryptResponse.getMessage() != null ? decryptResponse.getMessage() : "복호화 실패";
                    
                    // "데이터가 암호화되지 않았습니다" 메시지인 경우 null 반환
                    if (message.contains("데이터가 암호화되지 않았습니다")) {
                        if (enableLogging) {
                            log.warn("⚠️ 데이터가 암호화되지 않았습니다 (정책 추가 전 데이터)");
                        }
                        return null; // null 반환 시 HubCryptoAdapter에서 원본 데이터 반환
                    }
                    
                    throw new HubCryptoException("복호화 실패: " + message);
                }
            } else {
                // HTTP 400 등 에러 응답 처리
                String responseBody = response.getBody();
                String errorMessage = "Engine API 호출 실패: " + getStatusCodeString(response) + " " + responseBody;
                
                // "데이터가 암호화되지 않았습니다" 메시지인 경우 null 반환 (예외 던지지 않음)
                boolean isUnencryptedData = responseBody != null && responseBody.contains("데이터가 암호화되지 않았습니다");
                if (isUnencryptedData) {
                    if (enableLogging) {
                        log.warn("⚠️ 데이터가 암호화되지 않았습니다 (정책 추가 전 데이터)");
                    }
                    return null; // null 반환 시 HubCryptoAdapter에서 원본 데이터 반환
                }
                
                // 다른 에러는 예외 던지기
                // 에러 로그는 상위 레이어(HubCryptoAdapter)에서 처리하므로 여기서는 DEBUG 레벨만 사용
                if (enableLogging) {
                    log.debug("Engine 복호화 실패 (상위 레이어에서 처리): {}", errorMessage);
                }
                throw new HubConnectionException(errorMessage);
            }
            
        } catch (Exception e) {
            // HttpClientErrorException (RestTemplate 사용 시) 또는 기타 예외 처리
            String errorMessage = e.getMessage() != null ? e.getMessage() : "";
            String responseBody = "";
            
            // RestTemplate의 HttpClientErrorException에서 응답 본문 추출
            if (e instanceof HttpClientErrorException) {
                responseBody = ((HttpClientErrorException) e).getResponseBodyAsString();
            } else if (e instanceof HttpServerErrorException) {
                responseBody = ((HttpServerErrorException) e).getResponseBodyAsString();
            }
            
            // "데이터가 암호화되지 않았습니다" 메시지 감지
            boolean isUnencryptedData = (errorMessage.contains("데이터가 암호화되지 않았습니다") || 
                                        responseBody.contains("데이터가 암호화되지 않았습니다"));
            
            if (isUnencryptedData) {
                // 암호화되지 않은 데이터는 예외를 던지지 않고 null 반환 (HubCryptoAdapter에서 원본 데이터 반환)
                if (enableLogging) {
                    log.warn("⚠️ 데이터가 암호화되지 않았습니다 (정책 추가 전 데이터)");
                }
                return null; // null 반환 시 HubCryptoAdapter에서 원본 데이터 반환
            }
            
            // 다른 에러는 예외 던지기
            // 에러 로그는 상위 레이어(HubCryptoAdapter)에서 처리하므로 여기서는 DEBUG 레벨만 사용
            if (enableLogging) {
                log.debug("Engine 복호화 실패 (상위 레이어에서 처리): {}", errorMessage);
            }
            
            if (e instanceof HubCryptoException) {
                throw e;
            } else {
                throw new HubConnectionException("Engine 연결 실패: " + errorMessage, e);
            }
        }
    }
    
    /**
     * 배치 복호화 (여러 개의 암호화된 데이터를 일괄 복호화)
     * 
     * @param encryptedDataList 복호화할 암호화된 데이터 목록
     * @param maskPolicyName 마스킹 정책명 (선택사항, 모든 항목에 공통 적용)
     * @param maskPolicyUid 마스킹 정책 UID (선택사항, 모든 항목에 공통 적용)
     * @param includeStats 통계 정보 포함 여부
     * @return 복호화된 데이터 목록 (순서 보장)
     * @throws HubCryptoException 복호화 실패 시
     */
    public java.util.List<String> batchDecrypt(java.util.List<String> encryptedDataList, 
                                                String maskPolicyName, 
                                                String maskPolicyUid, 
                                                boolean includeStats) {
        // 초기화 확인
        initializeIfNeeded();
        
        if (encryptedDataList == null || encryptedDataList.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        
        if (enableLogging) {
            log.info("🔓 Engine 배치 복호화 요청 시작: itemsCount={}, maskPolicyName={}, maskPolicyUid={}", 
                    encryptedDataList.size(), maskPolicyName, maskPolicyUid);
        }
        
        try {
            // Hub 경로 사용 시 예외 발생 (런타임 가드)
            validateNotHubPath();
            
            // Engine의 배치 복호화 API 호출
            String url = hubUrl + apiBasePath + "/decrypt/batch";
            
            // Telemetry: 엔드포인트 추적
            recordEndpointUsage(url);
            
            // 배치 요청 생성
            java.util.Map<String, Object> batchRequest = new java.util.HashMap<>();
            java.util.List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();
            
            for (String encryptedData : encryptedDataList) {
                java.util.Map<String, Object> item = new java.util.HashMap<>();
                item.put("data", encryptedData);
                if (maskPolicyName != null && !maskPolicyName.trim().isEmpty()) {
                    item.put("maskPolicyName", maskPolicyName);
                }
                if (maskPolicyUid != null && !maskPolicyUid.trim().isEmpty()) {
                    item.put("maskPolicyUid", maskPolicyUid);
                }
                items.add(item);
            }
            
            batchRequest.put("items", items);
            // includeStats는 엔진에서 제거되었으므로 전달하지 않음
            
            String requestBody;
            try {
                requestBody = objectMapper.writeValueAsString(batchRequest);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new HubCryptoException("요청 데이터 직렬화 실패: " + e.getMessage());
            }
            
            if (enableLogging) {
                log.info("🔓 Engine 배치 요청 URL: {}", url);
            }
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<String> response;
            try {
                response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            } catch (HttpClientErrorException | HttpServerErrorException e) {
                throw new HubConnectionException("Engine 연결 실패: " + getExceptionStatusCode(e) + " " + e.getResponseBodyAsString(), e);
            } catch (Exception e) {
                throw new HubConnectionException("Engine 연결 실패: " + e.getMessage(), e);
            }
            
            if (enableLogging) {
                log.info("🔓 Engine 배치 응답 상태: {} {}", getStatusCodeString(response), url);
                log.info("🔓 Engine 배치 응답 데이터: {}", response.getBody());
            }
            
            if (is2xxSuccessful(response)) {
                // 엔진 직접 연결: BatchDecryptResponse를 직접 반환 (ApiResponse 래퍼 없음)
                JsonNode rootNode;
                try {
                    rootNode = objectMapper.readTree(response.getBody());
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    log.error("❌ Engine 응답 파싱 실패: 응답 본문={}", response.getBody(), e);
                    throw new HubCryptoException("Engine 응답 파싱 실패: " + e.getMessage());
                }
                
                // results 배열 추출 (최상위 레벨)
                JsonNode resultsNode = rootNode.get("results");
                if (resultsNode == null || !resultsNode.isArray()) {
                    // ApiResponse 래퍼가 있는 경우 (Hub를 통한 경우) 처리
                    JsonNode successNode = rootNode.get("success");
                    if (successNode != null && successNode.asBoolean()) {
                        JsonNode dataNode = rootNode.get("data");
                        if (dataNode != null && !dataNode.isNull()) {
                            resultsNode = dataNode.get("results");
                        }
                    }
                    
                    if (resultsNode == null || !resultsNode.isArray()) {
                        log.error("❌ 배치 복호화 실패: 응답에 results 배열이 없습니다. 응답 본문={}", response.getBody());
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
                            // 복호화 실패 시 원본 데이터 유지
                            JsonNode originalDataNode = resultNode.get("originalData");
                            decryptedList.add(originalDataNode != null ? originalDataNode.asText() : null);
                        }
                    } else {
                        // 실패한 항목은 원본 데이터 유지
                        JsonNode originalDataNode = resultNode.get("originalData");
                        decryptedList.add(originalDataNode != null ? originalDataNode.asText() : null);
                    }
                }
                
                if (enableLogging) {
                    log.info("✅ Engine 배치 복호화 성공: {}개 항목 처리", decryptedList.size());
                }
                
                return decryptedList;
            } else {
                throw new HubCryptoException("배치 복호화 실패: " + getStatusCodeString(response));
            }
            
        } catch (HubCryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new HubCryptoException("배치 복호화 중 오류: " + e.getMessage(), e);
        }
    }
    
    /**
     * 배치 암호화
     * 여러 개의 평문 데이터를 일괄 암호화
     * 
     * @param dataList 암호화할 평문 데이터 목록
     * @param policyList 각 데이터에 적용할 정책명 목록 (dataList와 동일한 크기)
     * @return 암호화된 데이터 목록 (순서는 요청과 동일)
     * @throws HubCryptoException 암호화 실패 시
     */
    public java.util.List<String> batchEncrypt(java.util.List<String> dataList, 
                                                java.util.List<String> policyList) {
        // 초기화 확인
        initializeIfNeeded();
        
        if (dataList == null || dataList.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        
        if (policyList == null || policyList.size() != dataList.size()) {
            throw new HubCryptoException("정책 목록의 크기가 데이터 목록과 일치하지 않습니다");
        }
        
        // 항상 로그 출력 (디버깅용)
        log.info("Engine batchEncrypt called: itemsCount={}, hubUrl={}, apiBasePath={}", 
                dataList.size(), hubUrl, apiBasePath);
        
        if (enableLogging) {
            log.info("🔐 Engine 배치 암호화 요청 시작: itemsCount={}", 
                    dataList.size());
        }
        
        try {
            // Hub 경로 사용 시 예외 발생 (런타임 가드)
            validateNotHubPath();
            
            // Engine의 배치 암호화 API 호출
            String url = hubUrl + apiBasePath + "/encrypt/batch";
            log.debug("Engine batchEncrypt URL: {}", url);
            
            // Telemetry: 엔드포인트 추적
            recordEndpointUsage(url);
            
            // 배치 요청 생성
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
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new HubCryptoException("요청 데이터 직렬화 실패: " + e.getMessage());
            }
            
            if (enableLogging) {
                log.info("🔐 Engine 배치 요청 URL: {}", url);
            }
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<String> response;
            try {
                log.debug("Engine batchEncrypt sending request to: {}", url);
                response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
                log.debug("Engine batchEncrypt response status: {}", getStatusCodeString(response));
            } catch (HttpClientErrorException | HttpServerErrorException e) {
                log.error("Engine batchEncrypt HTTP error: {} {}", getExceptionStatusCode(e), e.getResponseBodyAsString(), e);
                throw new HubConnectionException("Engine 연결 실패: " + getExceptionStatusCode(e) + " " + e.getResponseBodyAsString(), e);
            } catch (Exception e) {
                log.error("Engine batchEncrypt exception: {}", e.getMessage(), e);
                throw new HubConnectionException("Engine 연결 실패: " + e.getMessage(), e);
            }
            
            if (enableLogging) {
                log.info("🔐 Engine 배치 응답 상태: {} {}", getStatusCodeString(response), url);
            }
            
            if (is2xxSuccessful(response)) {
                // Engine 응답은 ApiResponse<BatchEncryptResponse> 형태
                JsonNode rootNode;
                try {
                    rootNode = objectMapper.readTree(response.getBody());
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new HubCryptoException("Engine 응답 파싱 실패: " + e.getMessage());
                }
                
                // ApiResponse의 success 확인
                JsonNode successNode = rootNode.get("success");
                if (successNode == null || !successNode.asBoolean()) {
                    JsonNode messageNode = rootNode.get("message");
                    String errorMessage = messageNode != null && !messageNode.isNull() ? messageNode.asText() : "배치 암호화 실패";
                    throw new HubCryptoException("배치 암호화 실패: " + errorMessage);
                }
                
                // data 필드 추출
                JsonNode dataNode = rootNode.get("data");
                if (dataNode == null || dataNode.isNull()) {
                    throw new HubCryptoException("배치 암호화 실패: 응답에 data 필드가 없습니다");
                }
                
                // results 배열 추출
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
                            // 암호화 실패 시 원본 데이터 유지
                            JsonNode originalDataNode = resultNode.get("originalData");
                            encryptedList.add(originalDataNode != null ? originalDataNode.asText() : null);
                        }
                    } else {
                        // 실패한 항목은 원본 데이터 유지
                        JsonNode originalDataNode = resultNode.get("originalData");
                        encryptedList.add(originalDataNode != null ? originalDataNode.asText() : null);
                    }
                }
                
                if (enableLogging) {
                    log.info("✅ Engine 배치 암호화 성공: {}개 항목 처리", encryptedList.size());
                }
                
                return encryptedList;
            } else {
                throw new HubCryptoException("배치 암호화 실패: " + getStatusCodeString(response));
            }
            
        } catch (HubCryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new HubCryptoException("배치 암호화 중 오류: " + e.getMessage(), e);
        }
    }
    
    /**
     * 데이터가 암호화된 형태인지 확인
     * 
     * 주의: 이 메서드는 형식 검증만 수행하며, 실제 tag 무결성 검증은 복호화 시점에 수행됩니다.
     * AES-GCM 복호화 시 tag가 맞지 않으면 자동으로 실패합니다.
     * 
     * @param data 확인할 데이터
     * @return 암호화된 데이터인지 여부
     */
    public boolean isEncryptedData(String data) {
        if (data == null || data.isEmpty()) {
            return false;
        }
        
        // 디버그 로그 (암호화 실패 디버깅용)
        if (enableLogging && log.isDebugEnabled()) {
            log.debug("🔍 isEncryptedData 체크: dataLength={}, preview={}", 
                    data.length(), 
                    data.length() > 50 ? data.substring(0, 50) + "..." : data);
        }
        
        // 부분암호화 형식 처리: "[평문]::ENC::[암호문]"
        String checkPart = data;
        if (data.contains("::ENC::")) {
            int idx = data.indexOf("::ENC::");
            checkPart = data.substring(idx + "::ENC::".length());
        }
        
        // 새 형식 접두사 기반 감지 및 구조 검증
        if (checkPart.startsWith("hub:")) {
            // hub:{policyUuid}:{base64(iv+ciphertext+tag)}
            // 구조: 최소 3개 부분 (hub, policyUuid, base64Data)
            String[] parts = checkPart.split(":", 3);
            if (parts.length >= 3) {
                String policyUuid = parts[1];
                String base64Data = parts[2];
                // Policy UUID 형식 검증 (36자 UUID 형식, 대소문자 모두 허용)
                if (policyUuid.length() == 36 && policyUuid.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) {
                    // Base64 데이터 최소 길이 검증 (IV 12 + Tag 16 = 최소 28 bytes, Base64로 약 38 chars)
                    try {
                        byte[] decoded = java.util.Base64.getDecoder().decode(base64Data);
                        // IV(12) + Tag(16) = 최소 28 bytes
                        return decoded.length >= 28;
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                }
            }
            return false;
        } else if (checkPart.startsWith("kms:")) {
            // kms:{policyUuid}:{base64(edk)}:{base64(iv+ciphertext+tag)}
            // 구조: 최소 4개 부분 (kms, policyUuid, edk, base64Data)
            String[] parts = checkPart.split(":", 4);
            if (parts.length >= 4) {
                String policyUuid = parts[1];
                String base64Data = parts[3];
                // Policy UUID 형식 검증 (대소문자 모두 허용)
                if (policyUuid.length() == 36 && policyUuid.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) {
                    // Base64 데이터 최소 길이 검증
                    try {
                        byte[] decoded = java.util.Base64.getDecoder().decode(base64Data);
                        return decoded.length >= 28; // IV(12) + Tag(16)
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                }
            }
            return false;
        } else if (checkPart.startsWith("vault:")) {
            // vault:{keyAlias}:v{version}:{data}
            // 구조: 최소 4개 부분 (vault, keyAlias, version, data)
            String[] parts = checkPart.split(":", 4);
            return parts.length >= 4 && parts[2].startsWith("v");
        }
        
        // 레거시 형식: Base64 형식이고 최소 길이 + Policy UUID 형식 검증
        // 최소 길이: PolicyUUID(36) + IV(12) + Tag(16) = 64 bytes
        // Base64 인코딩 시 약 86 chars (64 * 4/3 = 85.33, 패딩 포함)
        try {
            byte[] decoded = java.util.Base64.getDecoder().decode(checkPart);
            // 최소 64 bytes (PolicyUUID 36 + IV 12 + Tag 16)
            if (decoded.length >= 64) {
                // Policy UUID 형식 검증 (첫 36 bytes가 UUID 형식인지 확인)
                // UUID 형식: 8-4-4-4-12 (총 36자, 하이픈 포함)
                if (decoded.length >= 36) {
                    try {
                        String uuidCandidate = new String(decoded, 0, 36, java.nio.charset.StandardCharsets.UTF_8);
                        // UUID 형식 검증: 8-4-4-4-12 (하이픈 포함)
                        boolean isValidUuid = uuidCandidate.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
                        if (enableLogging && log.isDebugEnabled()) {
                            log.debug("🔍 레거시 형식 체크: decodedLength={}, uuidCandidate={}, isValidUuid={}, isEncrypted={}", 
                                    decoded.length, uuidCandidate, isValidUuid, isValidUuid);
                        }
                        return isValidUuid; // UUID 형식이 맞아야 암호화된 데이터
                    } catch (Exception e) {
                        // UTF-8 디코딩 실패 = 암호화된 데이터가 아님
                        if (enableLogging && log.isDebugEnabled()) {
                            log.debug("🔍 UUID 추출 실패 (평문 데이터): {}", e.getMessage());
                        }
                        return false;
                    }
                }
            }
            // 길이가 64 bytes 미만 = 암호화된 데이터가 아님
            if (enableLogging && log.isDebugEnabled()) {
                log.debug("🔍 레거시 형식 체크: decodedLength={} < 64 (평문 데이터)", decoded.length);
            }
            return false;
        } catch (IllegalArgumentException e) {
            // Base64 디코딩 실패 = 평문 데이터
            if (enableLogging && log.isDebugEnabled()) {
                log.debug("🔍 Base64 디코딩 실패 (평문 데이터): {}", e.getMessage());
            }
            return false;
        }
    }
    
    /**
     * Hub 경로 사용 여부 검증 (런타임 가드)
     * @throws IllegalStateException Hub 경로가 감지된 경우
     */
    private void validateNotHubPath() {
        if (apiBasePath != null && (apiBasePath.contains("/hub/api") || apiBasePath.equals(HUB_API_PATH))) {
            String errorMsg = String.format(
                "Hub 직접 암복호화 경로는 사용할 수 없습니다. Engine 경로(/api)만 허용됩니다. " +
                "현재 경로: %s. Hub를 통한 암복호화는 제거되었습니다. Engine에 직접 연결하세요.",
                apiBasePath
            );
            log.error("❌ {}", errorMsg);
            throw new IllegalStateException(errorMsg);
        }
    }
    
    /**
     * Telemetry: 암복호화 엔드포인트 추적
     * @param endpoint 사용된 엔드포인트 URL
     */
    private void recordEndpointUsage(String endpoint) {
        this.lastUsedEndpoint = endpoint;
        this.endpointUsageCount++;
        
        // 첫 사용 시 또는 100회마다 로깅
        if (enableLogging && (endpointUsageCount == 1 || endpointUsageCount % 100 == 0)) {
            log.info("📊 Telemetry: 암복호화 엔드포인트 사용 - endpoint={}, 사용 횟수={}", 
                    endpoint, endpointUsageCount);
        }
    }
    
    /**
     * 마지막으로 사용된 엔드포인트 조회 (Telemetry)
     * @return 마지막 사용 엔드포인트 URL
     */
    public String getLastUsedEndpoint() {
        return lastUsedEndpoint;
    }
    
    /**
     * 엔드포인트 사용 횟수 조회 (Telemetry)
     * @return 사용 횟수
     */
    public long getEndpointUsageCount() {
        return endpointUsageCount;
    }
}
