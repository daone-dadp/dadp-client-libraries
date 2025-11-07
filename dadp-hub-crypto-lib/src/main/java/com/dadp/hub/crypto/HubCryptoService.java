package com.dadp.hub.crypto;

import com.dadp.hub.crypto.dto.*;
import com.dadp.hub.crypto.exception.HubCryptoException;
import com.dadp.hub.crypto.exception.HubConnectionException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.csh.utils.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Hub 암복호화 서비스
 * 
 * Hub와의 암복호화 통신을 담당하는 핵심 서비스입니다.
 * JDK 내장 HttpClient를 사용하여 Spring 의존성 없이 동작합니다.
 * 
 * @author DADP Development Team
 * @version 2.0.0
 * @since 2025-01-01
 */
public class HubCryptoService {
    
    private static final Logger log = LoggerFactory.getLogger(HubCryptoService.class);
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private String hubBaseUrl;
    private int timeout;
    private boolean enableLogging;
    private boolean initialized = false;

    /**
     * 생성자
     */
    public HubCryptoService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 자동 초기화 메서드 - Spring Bean이 아닌 경우 사용
     */
    public static HubCryptoService createInstance() {
        return createInstance("http://localhost:9004", 5000, true);
    }

    /**
     * 자동 초기화 메서드 - 커스텀 설정으로 생성
     */
    public static HubCryptoService createInstance(String hubBaseUrl, int timeout, boolean enableLogging) {
        HubCryptoService instance = new HubCryptoService();
        instance.hubBaseUrl = hubBaseUrl;
        instance.timeout = timeout;
        instance.enableLogging = enableLogging;
        instance.initialized = true;
        
        if (enableLogging) {
            log.info("✅ HubCryptoService 자동 초기화 완료: hubBaseUrl={}, timeout={}ms", 
                    hubBaseUrl, timeout);
        }
        
        return instance;
    }

    /**
     * 초기화 상태 확인
     */
    public boolean isInitialized() {
        return initialized;
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
        // 초기화 확인
        initializeIfNeeded();
        
        if (enableLogging) {
            log.info("🔐 Hub 암호화 요청 시작: data={}, policy={}", 
                    data != null ? data.substring(0, Math.min(20, data.length())) + "..." : "null", policy);
        }
        
        try {
            String url = hubBaseUrl + "/api/v1/encrypt";
            
            EncryptRequest request = new EncryptRequest();
            request.setData(data);
            request.setPolicyName(policy);
            
            String requestBody;
            try {
                requestBody = objectMapper.writeValueAsString(request);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new HubCryptoException("요청 데이터 직렬화 실패: " + e.getMessage());
            }
            
            if (enableLogging) {
                log.info("🔐 Hub 요청 URL: {}", url);
                log.info("🔐 Hub 요청 데이터: {}", request);
            }
            
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(timeout))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            
            HttpResponse<String> response;
            try {
                response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException | InterruptedException e) {
                throw new HubConnectionException("Hub 연결 실패: " + e.getMessage(), e);
            }
            
            if (enableLogging) {
                log.info("🔐 Hub 응답 상태: {} {}", response.statusCode(), response.uri());
                log.info("🔐 Hub 응답 데이터: {}", response.body());
            }
            
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                // Hub 응답은 ApiResponse<EncryptResponse> 형태
                // TypeReference로 제네릭 파싱이 실패할 수 있으므로 JsonNode로 먼저 파싱
                JsonNode rootNode;
                try {
                    rootNode = objectMapper.readTree(response.body());
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new HubCryptoException("Hub 응답 파싱 실패: " + e.getMessage());
                }
                
                // ApiResponse의 success 확인
                JsonNode successNode = rootNode.get("success");
                if (successNode == null || !successNode.asBoolean()) {
                    JsonNode messageNode = rootNode.get("message");
                    String errorMessage = messageNode != null && !messageNode.isNull() ? messageNode.asText() : "암호화 실패";
                    throw new HubCryptoException("암호화 실패: " + errorMessage);
                }
                
                // data 필드 추출 및 EncryptResponse로 파싱
                JsonNode dataNode = rootNode.get("data");
                if (dataNode == null || dataNode.isNull()) {
                    throw new HubCryptoException("암호화 실패: 응답에 data 필드가 없습니다");
                }
                
                EncryptResponse encryptResponse;
                try {
                    encryptResponse = objectMapper.treeToValue(dataNode, EncryptResponse.class);
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new HubCryptoException("Hub 응답 data 파싱 실패: " + e.getMessage());
                }
                
                if (encryptResponse == null) {
                    throw new HubCryptoException("암호화 실패: 응답에 data 필드가 없습니다");
                }
                
                if (encryptResponse.getSuccess() != null && encryptResponse.getSuccess() && encryptResponse.getEncryptedData() != null) {
                    String encryptedData = encryptResponse.getEncryptedData();
                    if (enableLogging) {
                        log.info("✅ Hub 암호화 성공: {} → {}", 
                                data != null ? data.substring(0, Math.min(10, data.length())) + "..." : "null",
                                encryptedData != null ? encryptedData.substring(0, Math.min(20, encryptedData.length())) + "..." : "null");
                    }
                    return encryptedData;
                } else {
                    String errorMsg = String.format("암호화 실패: success=%s, encryptedData=%s, message=%s", 
                            encryptResponse.getSuccess(), 
                            encryptResponse.getEncryptedData() != null ? "있음" : "null",
                            encryptResponse.getMessage());
                    log.error("❌ {}", errorMsg);
                    throw new HubCryptoException(errorMsg);
                }
            } else {
                throw new HubCryptoException("Hub API 호출 실패: " + response.statusCode() + " " + response.body());
            }
            
        } catch (Exception e) {
            if (enableLogging) {
                log.error("❌ Hub 암호화 실패: {}", e.getMessage());
            }
            if (e instanceof HubCryptoException) {
                throw e;
            } else {
                throw new HubConnectionException("Hub 연결 실패: " + e.getMessage(), e);
            }
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
        // 초기화 확인
        initializeIfNeeded();
        
        if (enableLogging) {
            log.info("🔓 Hub 복호화 요청 시작: encryptedData={}", 
                    encryptedData != null ? encryptedData.substring(0, Math.min(20, encryptedData.length())) + "..." : "null");
        }
        
        try {
            String url = hubBaseUrl + "/api/v1/decrypt";
            
            DecryptRequest request = new DecryptRequest();
            request.setEncryptedData(encryptedData);
            
            String requestBody;
            try {
                requestBody = objectMapper.writeValueAsString(request);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new HubCryptoException("요청 데이터 직렬화 실패: " + e.getMessage());
            }
            
            if (enableLogging) {
                log.info("🔓 Hub 요청 URL: {}", url);
                log.info("🔓 Hub 요청 데이터: {}", request);
            }
            
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(timeout))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            
            HttpResponse<String> response;
            try {
                response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException | InterruptedException e) {
                throw new HubConnectionException("Hub 연결 실패: " + e.getMessage(), e);
            }
            
            if (enableLogging) {
                log.info("🔓 Hub 응답 상태: {} {}", response.statusCode(), response.uri());
                log.info("🔓 Hub 응답 데이터: {}", response.body());
            }
            
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                // Hub 응답은 ApiResponse<DecryptResponse> 형태
                // TypeReference로 제네릭 파싱이 실패할 수 있으므로 JsonNode로 먼저 파싱
                JsonNode rootNode;
                try {
                    rootNode = objectMapper.readTree(response.body());
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new HubCryptoException("Hub 응답 파싱 실패: " + e.getMessage());
                }
                
                // ApiResponse의 success 확인
                JsonNode successNode = rootNode.get("success");
                if (successNode == null || !successNode.asBoolean()) {
                    JsonNode messageNode = rootNode.get("message");
                    String errorMessage = messageNode != null && !messageNode.isNull() ? messageNode.asText() : "복호화 실패";
                    throw new HubCryptoException("복호화 실패: " + errorMessage);
                }
                
                // data 필드 추출 및 DecryptResponse로 파싱
                JsonNode dataNode = rootNode.get("data");
                if (dataNode == null || dataNode.isNull()) {
                    throw new HubCryptoException("복호화 실패: 응답에 data 필드가 없습니다");
                }
                
                DecryptResponse decryptResponse;
                try {
                    decryptResponse = objectMapper.treeToValue(dataNode, DecryptResponse.class);
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new HubCryptoException("Hub 응답 data 파싱 실패: " + e.getMessage());
                }
                
                if (decryptResponse == null) {
                    throw new HubCryptoException("복호화 실패: 응답에 data 필드가 없습니다");
                }
                
                if (Boolean.TRUE.equals(decryptResponse.getSuccess()) && decryptResponse.getDecryptedData() != null) {
                    String decryptedData = decryptResponse.getDecryptedData();
                    if (enableLogging) {
                        log.info("✅ Hub 복호화 성공: {} → {}", 
                                encryptedData != null ? encryptedData.substring(0, Math.min(20, encryptedData.length())) + "..." : "null",
                                decryptedData != null ? decryptedData.substring(0, Math.min(10, decryptedData.length())) + "..." : "null");
                    }
                    return decryptedData;
                } else {
                    throw new HubCryptoException("복호화 실패: " + decryptResponse.getMessage());
                }
            } else {
                throw new HubCryptoException("Hub API 호출 실패: " + response.statusCode() + " " + response.body());
            }
            
        } catch (Exception e) {
            if (enableLogging) {
                log.error("❌ Hub 복호화 실패: {}", e.getMessage());
            }
            if (e instanceof HubCryptoException) {
                throw e;
            } else {
                throw new HubConnectionException("Hub 연결 실패: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * 데이터가 암호화된 형태인지 확인
     * 
     * @param data 확인할 데이터
     * @return 암호화된 데이터인지 여부
     */
    public boolean isEncryptedData(String data) {
        if (data == null || data.isEmpty()) {
            return false;
        }
        
        // Base64 패턴 확인
        String base64Pattern = "^[A-Za-z0-9+/=]+$";
        if (!data.matches(base64Pattern)) {
            return false;
        }
        
        // 길이 확인 (암호화된 데이터는 보통 50자 이상)
        if (data.length() < 50) {
            return false;
        }
        
        return true;
    }
}
