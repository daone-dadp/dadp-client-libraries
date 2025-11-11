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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;

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
    private int timeout;
    private boolean enableLogging;
    private boolean initialized = false;

    /**
     * 생성자
     */
    public HubCryptoService() {
        this.restTemplate = new RestTemplate();
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
    public static HubCryptoService createInstance(String hubUrl, int timeout, boolean enableLogging) {
        HubCryptoService instance = new HubCryptoService();
        instance.hubUrl = hubUrl;
        instance.timeout = timeout;
        instance.enableLogging = enableLogging;
        instance.initialized = true;
        
        if (enableLogging) {
            log.info("✅ HubCryptoService 자동 초기화 완료: hubUrl={}, timeout={}ms", 
                    hubUrl, timeout);
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
        // 초기화 확인
        initializeIfNeeded();
        
        if (enableLogging) {
            log.info("🔐 Hub 암호화 요청 시작: data={}, policy={}", 
                    data != null ? data.substring(0, Math.min(20, data.length())) + "..." : "null", policy);
        }
        
        try {
            String url = hubUrl + "/hub/api/v1/encrypt";
            
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
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<String> response;
            try {
                response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            } catch (HttpClientErrorException | HttpServerErrorException e) {
                throw new HubConnectionException("Hub 연결 실패: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
            } catch (Exception e) {
                throw new HubConnectionException("Hub 연결 실패: " + e.getMessage(), e);
            }
            
            if (enableLogging) {
                log.info("🔐 Hub 응답 상태: {} {}", getStatusCodeString(response), url);
                log.info("🔐 Hub 응답 데이터: {}", response.getBody());
            }
            
            if (is2xxSuccessful(response)) {
                // Hub 응답은 ApiResponse<EncryptResponse> 형태
                // TypeReference로 제네릭 파싱이 실패할 수 있으므로 JsonNode로 먼저 파싱
                JsonNode rootNode;
                try {
                    rootNode = objectMapper.readTree(response.getBody());
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
                    // 에러 로그는 상위 레이어(HubCryptoAdapter)에서 처리하므로 여기서는 DEBUG 레벨만 사용
                    if (enableLogging) {
                        log.debug("Hub 암호화 실패 (상위 레이어에서 처리): {}", errorMsg);
                    }
                    throw new HubCryptoException(errorMsg);
                }
            } else {
                throw new HubCryptoException("Hub API 호출 실패: " + getStatusCodeString(response) + " " + response.getBody());
            }
            
        } catch (Exception e) {
            // 에러 로그는 상위 레이어(HubCryptoAdapter)에서 처리하므로 여기서는 DEBUG 레벨만 사용
            if (enableLogging) {
                log.debug("Hub 암호화 실패 (상위 레이어에서 처리): {}", e.getMessage());
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
        // 초기화 확인
        initializeIfNeeded();
        
        if (enableLogging) {
            log.info("🔓 Hub 복호화 요청 시작: encryptedData={}, maskPolicyName={}, maskPolicyUid={}", 
                    encryptedData != null ? encryptedData.substring(0, Math.min(20, encryptedData.length())) + "..." : "null",
                    maskPolicyName, maskPolicyUid);
        }
        
        try {
            String url = hubUrl + "/hub/api/v1/decrypt";
            
            DecryptRequest request = new DecryptRequest();
            request.setEncryptedData(encryptedData);
            request.setMaskPolicyName(maskPolicyName);
            request.setMaskPolicyUid(maskPolicyUid);
            
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
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<String> response;
            try {
                response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            } catch (HttpClientErrorException | HttpServerErrorException e) {
                throw new HubConnectionException("Hub 연결 실패: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
            } catch (Exception e) {
                throw new HubConnectionException("Hub 연결 실패: " + e.getMessage(), e);
            }
            
            if (enableLogging) {
                log.info("🔓 Hub 응답 상태: {} {}", getStatusCodeString(response), url);
                log.info("🔓 Hub 응답 데이터: {}", response.getBody());
            }
            
            if (is2xxSuccessful(response)) {
                // Hub 응답은 ApiResponse<DecryptResponse> 형태
                // TypeReference로 제네릭 파싱이 실패할 수 있으므로 JsonNode로 먼저 파싱
                JsonNode rootNode;
                try {
                    rootNode = objectMapper.readTree(response.getBody());
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new HubCryptoException("Hub 응답 파싱 실패: " + e.getMessage());
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
                
                // DecryptResponse의 success 확인
                // success가 true이고 decryptedData가 있으면 반환
                if (Boolean.TRUE.equals(decryptResponse.getSuccess()) && decryptResponse.getDecryptedData() != null) {
                    String decryptedData = decryptResponse.getDecryptedData();
                    if (enableLogging) {
                        log.info("✅ Hub 복호화 성공: {} → {}", 
                                encryptedData != null ? encryptedData.substring(0, Math.min(20, encryptedData.length())) + "..." : "null",
                                decryptedData != null ? decryptedData.substring(0, Math.min(10, decryptedData.length())) + "..." : "null");
                    }
                    return decryptedData;
                } else if (decryptResponse.getDecryptedData() != null) {
                    // success가 false여도 decryptedData가 있으면 반환 (평문 데이터에 마스킹 적용된 경우)
                    String decryptedData = decryptResponse.getDecryptedData();
                    if (enableLogging) {
                        log.info("✅ Hub 처리 완료 (마스킹 적용 가능): {} → {}", 
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
                String errorMessage = "Hub API 호출 실패: " + getStatusCodeString(response) + " " + responseBody;
                
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
                    log.debug("Hub 복호화 실패 (상위 레이어에서 처리): {}", errorMessage);
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
                log.debug("Hub 복호화 실패 (상위 레이어에서 처리): {}", errorMessage);
            }
            
            if (e instanceof HubCryptoException) {
                throw e;
            } else {
                throw new HubConnectionException("Hub 연결 실패: " + errorMessage, e);
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
        // Base64 형식이고 길이가 충분히 긴 경우 암호화된 데이터로 간주
        // 실제로는 정책 UUID가 포함되어 있는지 확인해야 하지만, 간단한 휴리스틱 사용
        try {
            // Base64 디코딩 시도
            java.util.Base64.getDecoder().decode(data);
            // Base64 형식이고 길이가 충분히 긴 경우
            return data.length() > 50;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
