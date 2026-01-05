package com.dadp.hub.crypto;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * 암복호화 라이브러리 설정
 * 
 * HubCryptoService는 기본값(http://localhost:9003)을 사용합니다.
 * 실제 사용 시에는 DirectCryptoAdapter를 통해 EndpointStorage에서 URL을 가져옵니다.
 * 
 * @author DADP Development Team
 * @version 1.3.0
 * @since 2025-01-01
 */
@Configuration
public class HubCryptoConfig {
    
    /**
     * 자동 설정된 HubCryptoService Bean 생성
     * 기본값을 사용하며, 실제 사용 시에는 DirectCryptoAdapter를 통해 동적으로 URL을 설정합니다.
     */
    @Bean
    @ConditionalOnMissingBean
    public HubCryptoService hubCryptoService() {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HubCryptoConfig.class);
        
        // 기본값 사용 (실제 사용 시 DirectCryptoAdapter가 EndpointStorage에서 URL을 가져옴)
        String cryptoUrl = "http://localhost:9003";  // 기본값: 엔진
        String apiPath = null;  // API 경로는 HubCryptoService가 자동으로 감지
        
        log.info("🔔 HubCryptoService 생성: cryptoUrl={}, apiPath={} (자동 감지, DirectCryptoAdapter에서 동적 URL 설정)", cryptoUrl, apiPath);
        
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
