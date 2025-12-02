package com.dadp.hub.crypto;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Hub 암복호화 라이브러리 설정
 * 
 * @author DADP Development Team
 * @version 1.0.0
 * @since 2025-01-01
 */
@Configuration
@ConfigurationProperties(prefix = "hub.crypto")
public class HubCryptoConfig {
    
    private String baseUrl;
    private String apiBasePath = "/hub/api/v1";  // 기본값: Hub 경로, Engine 사용 시 "/api"로 설정
    private int timeout = 5000;
    private int retryCount = 3;
    private boolean enableLogging = true;
    private String defaultPolicy = "dadp";
    
    /**
     * 자동 설정된 HubCryptoService Bean 생성
     */
    @Bean
    @ConditionalOnMissingBean
    public HubCryptoService hubCryptoService() {
        return HubCryptoService.createInstance(getBaseUrl(), getApiBasePath(), timeout, enableLogging);
    }
    
    /**
     * RestTemplate 빈 생성
     */
    @Bean
    @ConditionalOnMissingBean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
    
    // Getters and Setters
    public String getBaseUrl() {
        // 환경 변수 DADP_HUB_BASE_URL 우선 사용
        String envHubUrl = System.getenv("DADP_HUB_BASE_URL");
        if (envHubUrl != null && !envHubUrl.trim().isEmpty()) {
            return envHubUrl;
        }
        // 설정 파일에서 읽은 값 사용 (dadp.hub-base-url 또는 hub.crypto.base-url)
        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            return baseUrl;
        }
        // 기본값
        return "http://localhost:9004";
    }
    
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
    
    public String getApiBasePath() {
        // 환경 변수 HUB_CRYPTO_API_BASE_PATH 우선 사용
        String envApiBasePath = System.getenv("HUB_CRYPTO_API_BASE_PATH");
        if (envApiBasePath != null && !envApiBasePath.trim().isEmpty()) {
            return envApiBasePath;
        }
        // 설정 파일에서 읽은 값 사용
        if (apiBasePath != null && !apiBasePath.trim().isEmpty()) {
            return apiBasePath;
        }
        // 기본값: Hub 경로
        return "/hub/api/v1";
    }
    
    public void setApiBasePath(String apiBasePath) {
        this.apiBasePath = apiBasePath;
    }
    
    public int getTimeout() {
        return timeout;
    }
    
    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }
    
    public int getRetryCount() {
        return retryCount;
    }
    
    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }
    
    public boolean isEnableLogging() {
        return enableLogging;
    }
    
    public void setEnableLogging(boolean enableLogging) {
        this.enableLogging = enableLogging;
    }
    
    public String getDefaultPolicy() {
        return defaultPolicy;
    }
    
    public void setDefaultPolicy(String defaultPolicy) {
        this.defaultPolicy = defaultPolicy;
    }
}
