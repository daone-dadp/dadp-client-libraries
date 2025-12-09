package com.dadp.hub.crypto;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * 암복호화 라이브러리 설정
 * 
 * 환경 변수만 사용 (Wrapper와 동일):
 * - DADP_CRYPTO_BASE_URL: 암복호화 URL (엔진 또는 Gateway)
 * - API 경로는 항상 /api 사용
 * 
 * @author DADP Development Team
 * @version 1.0.0
 * @since 2025-01-01
 */
@Configuration
public class HubCryptoConfig {
    
    /**
     * 자동 설정된 HubCryptoService Bean 생성
     * 환경 변수 DADP_CRYPTO_BASE_URL만 사용 (Wrapper와 동일)
     */
    @Bean
    @ConditionalOnMissingBean
    public HubCryptoService hubCryptoService() {
        // 환경 변수 DADP_CRYPTO_BASE_URL만 사용 (암복호화 URL)
        String cryptoUrl = System.getenv("DADP_CRYPTO_BASE_URL");
        if (cryptoUrl == null || cryptoUrl.trim().isEmpty()) {
            cryptoUrl = "http://localhost:9003";  // 기본값: 엔진
        }
        
        // API 경로는 항상 /api 사용 (엔진/Gateway 모두 동일)
        String apiPath = "/api";
        
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HubCryptoConfig.class);
        log.info("🔔 HubCryptoService 생성: cryptoUrl={}, apiPath={}", cryptoUrl, apiPath);
        
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
