package com.dadp.hub.crypto;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * 암복호화 라이브러리 설정
 * 
 * 환경 변수 사용:
 * - DADP_CRYPTO_BASE_URL: 암복호화 URL 직접 지정 (필수, Engine URL)
 * - API 경로는 자동 감지 (/hub/api/v1 또는 /api)
 * 
 * 동작 방식:
 * 1. DADP_CRYPTO_BASE_URL 환경변수 확인
 * 2. 없으면 기본값 사용 (http://localhost:9003)
 * 
 * @author DADP Development Team
 * @version 1.2.0
 * @since 2025-01-01
 */
@Configuration
public class HubCryptoConfig {
    
    /**
     * 자동 설정된 HubCryptoService Bean 생성
     * DADP_CRYPTO_BASE_URL 환경변수로 Engine URL을 직접 지정합니다.
     */
    @Bean
    @ConditionalOnMissingBean
    public HubCryptoService hubCryptoService() {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HubCryptoConfig.class);
        
        // 1. DADP_CRYPTO_BASE_URL 환경변수 확인 (직접 지정)
        String cryptoUrl = System.getenv("DADP_CRYPTO_BASE_URL");
        
        // 2. 없으면 기본값 사용
        if (cryptoUrl == null || cryptoUrl.trim().isEmpty()) {
            cryptoUrl = "http://localhost:9003";  // 기본값: 엔진
            log.warn("⚠️ DADP_CRYPTO_BASE_URL이 설정되지 않아 기본값 사용: {}", cryptoUrl);
        } else {
            cryptoUrl = cryptoUrl.trim();
            log.info("✅ DADP_CRYPTO_BASE_URL 사용: {}", cryptoUrl);
        }
        
        // API 경로는 HubCryptoService가 자동으로 감지 (null 전달)
        String apiPath = null;
        
        log.info("🔔 HubCryptoService 생성: cryptoUrl={}, apiPath={} (자동 감지)", cryptoUrl, apiPath);
        
        return HubCryptoService.createInstance(cryptoUrl, apiPath, 5000, true);
    }
    
    /**
     * RestTemplate 빈 생성
     */
    @Bean
    @ConditionalOnMissingBean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
