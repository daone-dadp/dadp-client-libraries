package com.dadp.hub.crypto;

import com.dadp.hub.crypto.dto.*;
import com.dadp.hub.crypto.exception.HubCryptoException;
import com.dadp.hub.crypto.exception.HubConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Hub 암복호화 서비스
 * 
 * Hub와의 암복호화 통신을 담당하는 핵심 서비스입니다.
 * 
 * @author DADP Development Team
 * @version 1.0.0
 * @since 2025-01-01
 */
@Service
public class HubCryptoService {
    
    private static final Logger log = LoggerFactory.getLogger(HubCryptoService.class);
    
    @Autowired
    private RestTemplate restTemplate;
    
    @Value("${hub.crypto.base-url:http://localhost:9004}")
    private String hubBaseUrl;
    
    @Value("${hub.crypto.timeout:5000}")
    private int timeout;
    
    @Value("${hub.crypto.enable-logging:true}")
    private boolean enableLogging;

    private boolean initialized = false;

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
        instance.restTemplate = new RestTemplate();
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
        return initialized && restTemplate != null;
    }

    /**
     * 런타임 초기화 (필요시)
     */
    public void initializeIfNeeded() {
        if (!isInitialized()) {
            this.restTemplate = new RestTemplate();
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
            String url = hubBaseUrl + "/hub/api/v1/encrypt";
            
            EncryptRequest request = new EncryptRequest();
            request.setData(data);
            request.setPolicyName(policy);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<EncryptRequest> entity = new HttpEntity<>(request, headers);
            
            if (enableLogging) {
                log.info("🔐 Hub 요청 URL: {}", url);
                log.info("🔐 Hub 요청 데이터: {}", request);
            }
            
            ResponseEntity<EncryptResponse> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, EncryptResponse.class);
            
            if (enableLogging) {
                log.info("🔐 Hub 응답 상태: {}", response.getStatusCode());
                log.info("🔐 Hub 응답 데이터: {}", response.getBody());
            }
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                EncryptResponse encryptResponse = response.getBody();
                if (encryptResponse.isSuccess() && encryptResponse.getData() != null) {
                    String encryptedData = encryptResponse.getData().getEncryptedData();
                    if (enableLogging) {
                        log.info("✅ Hub 암호화 성공: {} → {}", 
                                data != null ? data.substring(0, Math.min(10, data.length())) + "..." : "null",
                                encryptedData != null ? encryptedData.substring(0, Math.min(20, encryptedData.length())) + "..." : "null");
                    }
                    return encryptedData;
                } else {
                    throw new HubCryptoException("암호화 실패: " + encryptResponse.getMessage());
                }
            } else {
                throw new HubCryptoException("Hub API 호출 실패: " + response.getStatusCode());
            }
            
        } catch (Exception e) {
            if (enableLogging) {
                log.error("❌ Hub 암호화 실패: {}", e.getMessage(), e);
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
            String url = hubBaseUrl + "/hub/api/v1/decrypt";
            
            DecryptRequest request = new DecryptRequest();
            request.setEncryptedData(encryptedData);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<DecryptRequest> entity = new HttpEntity<>(request, headers);
            
            if (enableLogging) {
                log.info("🔓 Hub 요청 URL: {}", url);
                log.info("🔓 Hub 요청 데이터: {}", request);
            }
            
            ResponseEntity<DecryptResponse> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, DecryptResponse.class);
            
            if (enableLogging) {
                log.info("🔓 Hub 응답 상태: {}", response.getStatusCode());
                log.info("🔓 Hub 응답 데이터: {}", response.getBody());
            }
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                DecryptResponse decryptResponse = response.getBody();
                if (decryptResponse.isSuccess() && decryptResponse.getData() != null) {
                    String decryptedData = decryptResponse.getData().getDecryptedData();
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
                throw new HubCryptoException("Hub API 호출 실패: " + response.getStatusCode());
            }
            
        } catch (Exception e) {
            if (enableLogging) {
                log.error("❌ Hub 복호화 실패: {}", e.getMessage(), e);
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
