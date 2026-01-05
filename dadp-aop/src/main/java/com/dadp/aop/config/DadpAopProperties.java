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
     * Hub 기본 URL (스키마 동기화 및 정책 매핑 동기화용)
     * Wrapper와 동일하게 context-path를 포함하지 않음 (예: http://dadp-hub:9004)
     * 
     * 설정 방법:
     * - 환경변수 DADP_HUB_BASE_URL (최우선)
     * - application.properties: dadp.hub-base-url 또는 dadp.aop.hub-base-url
     * 
     * 주의: hubUrl에는 context-path(/hub)를 포함하지 않습니다.
     * API 경로는 코드에서 직접 /hub/api/v1/... 형태로 추가합니다.
     */
    private String hubBaseUrl;
    
    /**
     * AOP 설정 내부 클래스
     */
    public static class AopConfig {
        /**
         * DADP 엔진 기본 URL
         * 
         * @deprecated 이 필드는 더 이상 사용되지 않습니다.
         *             엔진 URL은 Hub에서 동기화됩니다 (EndpointSyncService를 통해).
         *             HubCryptoService는 EndpointStorage에서 자동으로 엔진 URL을 가져옵니다.
         *             이 필드는 하위 호환성을 위해 유지되지만, 실제로는 사용되지 않습니다.
         */
        @Deprecated
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
        /**
         * DADP 엔진 기본 URL 조회
         * 
         * @deprecated 이 메서드는 더 이상 사용되지 않습니다.
         *             엔진 URL은 Hub에서 동기화됩니다 (EndpointSyncService를 통해).
         *             HubCryptoService는 EndpointStorage에서 자동으로 엔진 URL을 가져옵니다.
         *             이 메서드는 하위 호환성을 위해 유지되지만, 실제로는 사용되지 않습니다.
         * 
         * @return 엔진 URL (사용되지 않음)
         */
        @Deprecated
        public String getEngineBaseUrl() {
            // 설정 파일에서 읽은 값 사용
            if (engineBaseUrl != null && !engineBaseUrl.trim().isEmpty()) {
                return engineBaseUrl;
            }
            
            // 기본값 (Engine 직접 연결)
            // 실제로는 EndpointStorage에서 가져온 값을 사용해야 하지만,
            // 이 메서드는 하위 호환성을 위해 유지 (실제 사용은 HubCryptoConfig에서 EndpointStorage 사용)
            return "http://localhost:9003";
        }
        
        /**
         * DADP 엔진 기본 URL 설정
         * 
         * @deprecated 이 메서드는 더 이상 사용되지 않습니다.
         *             엔진 URL은 Hub에서 동기화됩니다 (EndpointSyncService를 통해).
         *             이 메서드는 하위 호환성을 위해 유지되지만, 실제로는 사용되지 않습니다.
         * 
         * @param engineBaseUrl 엔진 URL (사용되지 않음)
         */
        @Deprecated
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
    /**
     * DADP 엔진 기본 URL 조회
     * 
     * @deprecated 이 메서드는 더 이상 사용되지 않습니다.
     *             엔진 URL은 Hub에서 동기화됩니다 (EndpointSyncService를 통해).
     *             HubCryptoService는 EndpointStorage에서 자동으로 엔진 URL을 가져옵니다.
     *             이 메서드는 하위 호환성을 위해 유지되지만, 실제로는 사용되지 않습니다.
     * 
     * @return 엔진 URL (사용되지 않음)
     */
    @Deprecated
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
     * Hub 기본 URL 조회 (Wrapper와 동일한 방식)
     * 
     * 우선순위:
     * 1. 환경 변수 DADP_HUB_BASE_URL (최우선)
     * 2. 설정 파일 dadp.hub-base-url 또는 dadp.aop.hub-base-url
     * 
     * 주의: hubUrl에는 context-path(/hub)를 포함하지 않습니다.
     * API 경로는 코드에서 직접 /hub/api/v1/... 형태로 추가합니다.
     * 
     * @return Hub URL (예: http://dadp-hub:9004), 없으면 null
     */
    public String getHubBaseUrl() {
        // 1. 환경 변수 DADP_HUB_BASE_URL 우선 사용 (Wrapper와 동일)
        String envHubUrl = System.getenv("DADP_HUB_BASE_URL");
        if (envHubUrl != null && !envHubUrl.trim().isEmpty()) {
            return envHubUrl.trim();
        }
        // 2. 설정 파일에서 읽은 값 사용
        if (hubBaseUrl != null && !hubBaseUrl.trim().isEmpty()) {
            return hubBaseUrl.trim();
        }
        // 기본값 없음
        return null;
    }
    
    /**
     * Hub 기본 URL 설정
     */
    public void setHubBaseUrl(String hubBaseUrl) {
        this.hubBaseUrl = hubBaseUrl;
    }
}
