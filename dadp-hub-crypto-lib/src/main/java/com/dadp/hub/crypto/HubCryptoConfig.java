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
    
    private String baseUrl = "http://localhost:9004";
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
        return HubCryptoService.createInstance(baseUrl, timeout, enableLogging);
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
        return baseUrl;
    }
    
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
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
