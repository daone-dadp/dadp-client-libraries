package com.dadp.aop.config;

import com.dadp.aop.aspect.EncryptionAspect;
import com.dadp.aop.annotation.EncryptField;
import com.dadp.aop.metadata.EncryptionMetadataInitializer;
import com.dadp.aop.service.AopNotificationService;
import com.dadp.aop.service.CryptoService;
import com.dadp.hub.crypto.HubCryptoService;
import com.dadp.hub.crypto.HubNotificationClient;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestTemplate;

/**
 * DADP AOP 자동 설정 클래스 (Spring Boot 3.x 스타일)
 * 
 * <p>Spring Boot 3.x의 새로운 자동 설정 방식을 사용합니다.</p>
 * <p>META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports 파일에 등록되어 있습니다.</p>
 * 
 * @author DADP Development Team
 * @version 3.0.0
 * @since 2025-01-01
 */
@AutoConfiguration(after = HibernateJpaAutoConfiguration.class)
@EnableAspectJAutoProxy
@ConditionalOnClass({ EncryptionAspect.class, EntityManagerFactory.class, EncryptField.class })
@ConditionalOnProperty(prefix = "dadp.aop", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DadpAopProperties.class)
@Import(com.dadp.hub.crypto.HubCryptoConfig.class)
public class DadpAopAutoConfiguration {
    
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DadpAopAutoConfiguration.class);
    
    public DadpAopAutoConfiguration() {
        log.info("🔔 DadpAopAutoConfiguration 생성자 호출됨");
    }
    
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
    public EncryptionAspect encryptionAspect(CryptoService cryptoService, DadpAopProperties properties) {
        EncryptionAspect aspect = new EncryptionAspect();
        // properties는 @Autowired로 주입됨
        return aspect;
    }
    
    /**
     * RestTemplate 빈 등록
     */
    @Bean
    @ConditionalOnMissingBean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
    
    /**
     * Hub 알림 클라이언트 빈 등록
     * Hub URL이 설정된 경우에만 생성됩니다.
     */
    @Bean
    @ConditionalOnMissingBean
    @Nullable
    public HubNotificationClient hubNotificationClient(DadpAopProperties properties) {
        String hubBaseUrl = properties.getHubBaseUrl();
        if (hubBaseUrl == null || hubBaseUrl.trim().isEmpty()) {
            log.debug("Hub Base URL이 설정되지 않아 알림 클라이언트를 생성하지 않습니다.");
            return null;
        }
        
        try {
            HubNotificationClient client = HubNotificationClient.createInstance(hubBaseUrl, 5000, true);
            log.info("✅ Hub 알림 클라이언트 초기화 완료: hubBaseUrl={}", hubBaseUrl);
            return client;
        } catch (Exception e) {
            log.warn("⚠️ Hub 알림 클라이언트 초기화 실패: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * AOP 알림 서비스 빈 등록
     * HubNotificationClient가 없어도 빈은 생성됩니다 (나중에 알림 전송 시 체크).
     */
    @Bean
    @ConditionalOnMissingBean
    public AopNotificationService aopNotificationService(@Nullable HubNotificationClient hubNotificationClient,
                                                          org.springframework.core.env.Environment environment) {
        // HubNotificationClient가 없어도 빈은 생성 (나중에 알림 전송 시 체크)
        return new AopNotificationService(hubNotificationClient, environment);
    }
    
    /**
     * 암호화 메타데이터 초기화 컴포넌트 등록
     * JPA 메타데이터를 스캔하여 {@code @EncryptField}가 있는 필드를 자동으로 찾고
     * {@code @Table}과 {@code @Column} 정보를 조합하여 "table.column" 형태로 매핑을 생성합니다.
     * 
     * HibernateJpaAutoConfiguration 이후에 실행되어 EntityManagerFactory가 준비된 상태에서 초기화됩니다.
     */
    @Bean
    public EncryptionMetadataInitializer encryptionMetadataInitializer(
            @Nullable EntityManagerFactory entityManagerFactory) {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DadpAopAutoConfiguration.class);
        log.info("🔔 DadpAopAutoConfiguration.encryptionMetadataInitializer() 빈 생성 중...");
        log.info("🔔 EntityManagerFactory: {}", entityManagerFactory != null ? "존재함" : "null");
        EncryptionMetadataInitializer initializer = new EncryptionMetadataInitializer(entityManagerFactory);
        log.info("🔔 EncryptionMetadataInitializer 빈 생성 완료");
        return initializer;
    }
    
}
