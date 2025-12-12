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
     * AOP 설정
     */
    private AopConfig aop = new AopConfig();
    
    /**
     * Hub 기본 URL (알림 전송용)
     */
    private String hubBaseUrl;
    
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
         * 기본 암호화 정책 (기본값: "dadp" 하드코딩)
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
        
        /**
         * 배치 처리 최소 크기 임계값
         * 이 값보다 작으면 개별 처리로 폴백 (배치 오버헤드가 더 큼)
         * 환경변수: DADP_AOP_BATCH_MIN_SIZE
         * 기본값: 100 (100개 필드 데이터 이상일 때만 배치 처리)
         * 
         * 실측 결과:
         * - 60개 필드 데이터: 배치 처리(1.3초) > 개별 처리(0.44초)
         * - 100개 필드 데이터 이상: 배치 처리 이점 발생 예상
         */
        private int batchMinSize = 100;
        
        /**
         * 배치 처리 최대 크기 제한
         * 한 번에 처리할 수 있는 최대 필드 데이터 개수
         * 초과 시 청크 단위로 나누어 처리
         * 환경변수: DADP_AOP_BATCH_MAX_SIZE
         * 기본값: 10000 (10,000개 필드 데이터)
         */
        private int batchMaxSize = 10000;
        
        /**
         * 배치 처리 임계값 (이 값보다 작으면 개별 처리)
         * 기본값: 10000 (10000건 이상일 때만 배치 처리)
         * @deprecated batchMinSize와 batchMaxSize로 대체됨
         */
        @Deprecated
        private int batchThreshold = 10000;
        
        // Getters and Setters
        public String getEngineBaseUrl() {
            // 환경 변수 DADP_CRYPTO_BASE_URL 우선 사용 (암복호화용)
            String envCryptoUrl = System.getenv("DADP_CRYPTO_BASE_URL");
            if (envCryptoUrl != null && !envCryptoUrl.trim().isEmpty()) {
                return envCryptoUrl;
            }
            // 설정 파일에서 읽은 값 사용
            if (engineBaseUrl != null && !engineBaseUrl.trim().isEmpty()) {
                return engineBaseUrl;
            }
            // 기본값 (Engine 직접 연결)
            return "http://localhost:9003";
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
            // 환경 변수 DADP_CRYPTO_DEFAULT_POLICY 우선 사용
            String envPolicy = System.getenv("DADP_CRYPTO_DEFAULT_POLICY");
            if (envPolicy != null && !envPolicy.trim().isEmpty()) {
                return envPolicy;
            }
            // 설정 파일에서 읽은 값 사용
            if (defaultPolicy != null && !defaultPolicy.trim().isEmpty()) {
                return defaultPolicy;
            }
            // 기본값: "dadp" (하드코딩)
            return "dadp";
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
        
        public int getBatchMinSize() {
            // 환경 변수 DADP_AOP_BATCH_MIN_SIZE 우선 사용
            String envMinSize = System.getenv("DADP_AOP_BATCH_MIN_SIZE");
            if (envMinSize != null && !envMinSize.trim().isEmpty()) {
                try {
                    return Integer.parseInt(envMinSize.trim());
                } catch (NumberFormatException e) {
                    // 파싱 실패 시 기본값 사용
                }
            }
            // 설정 파일에서 읽은 값 사용
            return batchMinSize;
        }
        
        public void setBatchMinSize(int batchMinSize) {
            this.batchMinSize = batchMinSize;
        }
        
        public int getBatchMaxSize() {
            // 환경 변수 DADP_AOP_BATCH_MAX_SIZE 우선 사용
            String envMaxSize = System.getenv("DADP_AOP_BATCH_MAX_SIZE");
            if (envMaxSize != null && !envMaxSize.trim().isEmpty()) {
                try {
                    return Integer.parseInt(envMaxSize.trim());
                } catch (NumberFormatException e) {
                    // 파싱 실패 시 기본값 사용
                }
            }
            // 설정 파일에서 읽은 값 사용
            return batchMaxSize;
        }
        
        public void setBatchMaxSize(int batchMaxSize) {
            this.batchMaxSize = batchMaxSize;
        }
        
        @Deprecated
        public int getBatchThreshold() {
            return batchThreshold;
        }
        
        @Deprecated
        public void setBatchThreshold(int batchThreshold) {
            this.batchThreshold = batchThreshold;
        }
    }
    
    // Getters and Setters
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
    
    /**
     * Hub 기본 URL 조회 (알림 전송용)
     * 환경 변수 DADP_HUB_BASE_URL 우선 사용
     */
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
        // 기본값 없음 (알림은 선택적 기능)
        return null;
    }
    
    /**
     * Hub 기본 URL 설정
     */
    public void setHubBaseUrl(String hubBaseUrl) {
        this.hubBaseUrl = hubBaseUrl;
    }
}
