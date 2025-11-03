package com.dadp.aop.config;

import com.dadp.aop.aspect.EncryptionAspect;
import com.dadp.aop.service.CryptoService;
import com.dadp.hub.crypto.HubCryptoService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestTemplate;

/**
 * DADP AOP 자동 설정 클래스
 * 
 * @author DADP Development Team
 * @version 2.0.0
 * @since 2025-01-01
 */
@Configuration
@EnableAspectJAutoProxy
@ConditionalOnClass(EncryptionAspect.class)
@EnableConfigurationProperties(DadpAopProperties.class)
@Import(com.dadp.hub.crypto.HubCryptoConfig.class)
public class DadpAopAutoConfiguration {
    
    /**
     * 암복호화 서비스 빈 등록
     */
    @Bean
    @ConditionalOnMissingBean
    public CryptoService cryptoService(DadpAopProperties properties) {
        return new CryptoService();
    }
    
    /**
     * 암복호화 AOP Aspect 빈 등록
     */
    @Bean
    @ConditionalOnMissingBean
    public EncryptionAspect encryptionAspect(CryptoService cryptoService) {
        return new EncryptionAspect();
    }
    
    /**
     * RestTemplate 빈 등록
     */
    @Bean
    @ConditionalOnMissingBean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
