package com.dadp.aop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DADP AOP 설정 속성
 * 
 * @author DADP Development Team
 * @version 2.0.0
 * @since 2025-01-01
 */
@ConfigurationProperties(prefix = "dadp.aop")
public class DadpAopProperties {
    
    /**
     * DADP 엔진 기본 URL
     */
    private String engineBaseUrl = "http://localhost:9003";
    
    /**
     * DADP Hub 기본 URL
     */
    private String hubBaseUrl = "http://localhost:9004";
    
    /**
     * AOP 활성화 여부
     */
    private boolean enabled = true;
    
    /**
     * 기본 암호화 정책
     */
    private String defaultPolicy = "dadp";
    
    /**
     * 암호화 실패 시 원본 데이터 반환 여부
     */
    private boolean fallbackToOriginal = true;
    
    /**
     * 로깅 활성화 여부
     */
    private boolean enableLogging = true;
    
    // Getters and Setters
    public String getEngineBaseUrl() {
        return engineBaseUrl;
    }
    
    public void setEngineBaseUrl(String engineBaseUrl) {
        this.engineBaseUrl = engineBaseUrl;
    }
    
    public String getHubBaseUrl() {
        return hubBaseUrl;
    }
    
    public void setHubBaseUrl(String hubBaseUrl) {
        this.hubBaseUrl = hubBaseUrl;
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public String getDefaultPolicy() {
        return defaultPolicy;
    }
    
    public void setDefaultPolicy(String defaultPolicy) {
        this.defaultPolicy = defaultPolicy;
    }
    
    public boolean isFallbackToOriginal() {
        return fallbackToOriginal;
    }
    
    public void setFallbackToOriginal(boolean fallbackToOriginal) {
        this.fallbackToOriginal = fallbackToOriginal;
    }
    
    public boolean isEnableLogging() {
        return enableLogging;
    }
    
    public void setEnableLogging(boolean enableLogging) {
        this.enableLogging = enableLogging;
    }
}
