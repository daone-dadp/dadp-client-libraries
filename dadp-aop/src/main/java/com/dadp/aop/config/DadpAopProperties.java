package com.dadp.aop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DADP AOP 설정 속성
 * 
 * @author DADP Development Team
 * @version 2.0.0
 * @since 2025-01-01
 */
@ConfigurationProperties(prefix = "dadp")
public class DadpAopProperties {
    
    /**
     * DADP Hub 기본 URL (환경 변수 DADP_HUB_BASE_URL 사용)
     */
    private String hubBaseUrl;
    
    /**
     * AOP 설정
     */
    private AopConfig aop = new AopConfig();
    
    /**
     * AOP 설정 내부 클래스
     */
    public static class AopConfig {
        /**
         * DADP 엔진 기본 URL
         */
        private String engineBaseUrl = "http://localhost:9003";
        
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
    
    // Getters and Setters
    public String getHubBaseUrl() {
        // 환경 변수 DADP_HUB_BASE_URL 우선 사용
        String envHubUrl = System.getenv("DADP_HUB_BASE_URL");
        if (envHubUrl != null && !envHubUrl.trim().isEmpty()) {
            return envHubUrl;
        }
        // 설정 파일에서 읽은 값 사용
        if (hubBaseUrl != null && !hubBaseUrl.trim().isEmpty()) {
            return hubBaseUrl;
        }
        // 기본값
        return "http://localhost:9004";
    }
    
    public void setHubBaseUrl(String hubBaseUrl) {
        this.hubBaseUrl = hubBaseUrl;
    }
    
    public AopConfig getAop() {
        return aop;
    }
    
    public void setAop(AopConfig aop) {
        this.aop = aop;
    }
    
    // 편의 메서드 (기존 코드 호환성)
    public String getEngineBaseUrl() {
        return aop.getEngineBaseUrl();
    }
    
    public boolean isEnabled() {
        return aop.isEnabled();
    }
    
    public String getDefaultPolicy() {
        return aop.getDefaultPolicy();
    }
    
    public boolean isFallbackToOriginal() {
        return aop.isFallbackToOriginal();
    }
    
    public boolean isEnableLogging() {
        return aop.isEnableLogging();
    }
}
