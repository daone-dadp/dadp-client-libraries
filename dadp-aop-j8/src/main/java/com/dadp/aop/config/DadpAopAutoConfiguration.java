package com.dadp.aop.config;

import com.dadp.aop.aspect.EncryptionAspect;
import com.dadp.aop.annotation.EncryptField;
import com.dadp.aop.metadata.EncryptionMetadataInitializer;
import com.dadp.aop.service.AopNotificationService;
import com.dadp.aop.service.CryptoService;
import com.dadp.aop.sync.AopPolicyMappingSyncService;
import com.dadp.aop.sync.AopSchemaSyncService;
import com.dadp.common.sync.config.EndpointStorage;
import com.dadp.common.sync.config.InstanceConfigStorage;
import com.dadp.common.sync.endpoint.EndpointSyncService;
import com.dadp.common.sync.mapping.MappingSyncService;
import com.dadp.common.sync.policy.PolicyMappingStorage;
import com.dadp.common.sync.policy.PolicyResolver;
import com.dadp.common.sync.crypto.DirectCryptoAdapter;
import com.dadp.hub.crypto.HubNotificationClient;
import javax.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestTemplate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import com.dadp.common.sync.config.EndpointStorage;
import com.dadp.common.sync.policy.PolicyMappingStorage;
import com.dadp.common.sync.policy.PolicyResolver;
import com.dadp.common.sync.mapping.MappingSyncService;
import com.dadp.common.sync.endpoint.EndpointSyncService;
import com.dadp.aop.sync.AopSchemaSyncService;
import com.dadp.aop.sync.AopPolicyMappingSyncService;

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
@EnableScheduling
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
     * DirectCryptoAdapter 빈 등록 (공통 라이브러리)
     * EndpointStorage에서 Engine URL을 가져와 초기화합니다.
     * EndpointSyncService가 URL을 업데이트할 때 자동으로 갱신됩니다.
     */
    @Bean
    @ConditionalOnMissingBean
    public DirectCryptoAdapter directCryptoAdapter(
            @Nullable EndpointStorage endpointStorage) {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DadpAopAutoConfiguration.class);
        
        // DirectCryptoAdapter 생성 (failOpen=true: 암복호화 실패 시 원본 반환)
        DirectCryptoAdapter adapter = new DirectCryptoAdapter(true);
        
        // EndpointStorage에서 초기 URL 로드
        if (endpointStorage != null) {
            EndpointStorage.EndpointData endpointData = endpointStorage.loadEndpoints();
            if (endpointData != null && endpointData.getCryptoUrl() != null && !endpointData.getCryptoUrl().trim().isEmpty()) {
                adapter.setEndpointData(endpointData);
                log.info("✅ DirectCryptoAdapter 초기화 완료: cryptoUrl={}", endpointData.getCryptoUrl());
            } else {
                log.warn("⚠️ EndpointStorage에 Engine URL이 없습니다. EndpointSyncService가 URL을 동기화하면 자동으로 업데이트됩니다.");
            }
        } else {
            log.warn("⚠️ EndpointStorage가 없습니다. EndpointSyncService가 URL을 동기화하면 자동으로 업데이트됩니다.");
        }
        
        return adapter;
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
    
    /**
     * PolicyResolver 빈 등록
     * 정책 매핑 정보를 관리하고 영구 저장소에 저장합니다.
     */
    @Bean
    @ConditionalOnMissingBean
    public PolicyResolver policyResolver() {
        String storageDir = System.getProperty("user.home") + "/.dadp-aop";
        String fileName = "policy-mappings.json";
        log.info("✅ PolicyResolver 초기화: storageDir={}, fileName={}", storageDir, fileName);
        return new PolicyResolver(storageDir, fileName);
    }
    
    /**
     * EndpointStorage 빈 등록
     * Hub에서 동기화한 Engine URL을 영구 저장소에 저장합니다.
     */
    @Bean
    @ConditionalOnMissingBean
    public EndpointStorage endpointStorage() {
        String storageDir = System.getProperty("user.home") + "/.dadp-aop";
        String fileName = "crypto-endpoints.json";
        log.info("✅ EndpointStorage 초기화: storageDir={}, fileName={}", storageDir, fileName);
        return new EndpointStorage(storageDir, fileName);
    }
    
    /**
     * 영구저장소에서 hubId 로드 (Wrapper의 ProxyConfig와 동일한 로직)
     * 
     * @param hubUrl Hub URL
     * @param instanceId 인스턴스 ID
     * @return hubId, 없으면 null
     */
    private String loadHubIdFromStorage(String hubUrl, String instanceId) {
        InstanceConfigStorage configStorage = new InstanceConfigStorage(
            System.getProperty("user.home") + "/.dadp-aop",
            "aop-config.json"
        );
        
        InstanceConfigStorage.ConfigData config = configStorage.loadConfig(hubUrl, instanceId);
        if (config != null && config.getHubId() != null && !config.getHubId().trim().isEmpty()) {
            String hubId = config.getHubId();
            log.info("📂 영구저장소에서 hubId 로드 완료: hubId={}", hubId);
            return hubId;
        } else {
            log.info("📋 hubId가 없습니다. Hub에서 등록 예정: hubUrl={}, instanceId={}", hubUrl, instanceId);
            return null;
        }
    }
    
    /**
     * MappingSyncService 빈 등록
     * Hub에서 정책 매핑 정보를 동기화합니다.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "dadp", name = "hub-base-url")
    public MappingSyncService mappingSyncService(DadpAopProperties properties,
                                                  PolicyResolver policyResolver,
                                                  Environment environment) {
        String hubUrl = properties.getHubBaseUrl();
        if (hubUrl == null || hubUrl.trim().isEmpty()) {
            log.warn("⚠️ Hub URL이 설정되지 않아 MappingSyncService를 생성하지 않습니다.");
            return null;
        }
        
        // AOP 인스턴스 ID 조회
        String instanceId = System.getenv("DADP_AOP_INSTANCE_ID");
        if (instanceId == null || instanceId.trim().isEmpty()) {
            // Spring application name 사용
            if (environment != null) {
                instanceId = environment.getProperty("spring.application.name", "aop");
            } else {
                instanceId = "aop";
            }
        }
        
        // 영구저장소에서 hubId 로드 (Wrapper의 ProxyConfig와 동일한 로직)
        String hubId = loadHubIdFromStorage(hubUrl, instanceId);
        
        // AOP는 datasourceId가 없음
        String datasourceId = null;
        
        // API 경로: /hub/api/v1/aop
        String apiBasePath = "/hub/api/v1/aop";
        
        log.info("✅ MappingSyncService 초기화: hubUrl={}, instanceId={}, hubId={}, apiBasePath={}", 
                hubUrl, instanceId, hubId != null ? hubId : "(없음)", apiBasePath);
        return new MappingSyncService(hubUrl, hubId, instanceId, datasourceId, apiBasePath, policyResolver);
    }
    
    /**
     * EndpointSyncService 빈 등록
     * Hub에서 엔진 URL 정보를 동기화합니다.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "dadp", name = "hub-base-url")
    public EndpointSyncService endpointSyncService(DadpAopProperties properties,
                                                   Environment environment,
                                                   EndpointStorage endpointStorage) {
        String hubUrl = properties.getHubBaseUrl();
        if (hubUrl == null || hubUrl.trim().isEmpty()) {
            log.warn("⚠️ Hub URL이 설정되지 않아 EndpointSyncService를 생성하지 않습니다.");
            return null;
        }
        
        // AOP 인스턴스 ID 조회
        String instanceId = System.getenv("DADP_AOP_INSTANCE_ID");
        if (instanceId == null || instanceId.trim().isEmpty()) {
            if (environment != null) {
                instanceId = environment.getProperty("spring.application.name", "aop");
            } else {
                instanceId = "aop";
            }
        }
        
        // 영구저장소에서 hubId 로드 (Wrapper의 ProxyConfig와 동일한 로직)
        String hubId = loadHubIdFromStorage(hubUrl, instanceId);
        
        // EndpointStorage의 저장 경로 사용
        String storageDir = System.getProperty("user.home") + "/.dadp-aop";
        String fileName = "crypto-endpoints.json";
        
        log.info("✅ EndpointSyncService 초기화: hubUrl={}, instanceId={}, hubId={}, storageDir={}", 
                hubUrl, instanceId, hubId != null ? hubId : "(없음)", storageDir);
        return new EndpointSyncService(hubUrl, hubId, instanceId, storageDir, fileName);
    }
    
    /**
     * AopSchemaSyncService 빈 등록
     * EncryptionMetadataInitializer에서 수집한 스키마 정보를 Hub로 전송합니다.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "dadp", name = "hub-base-url")
    public AopSchemaSyncService aopSchemaSyncService(DadpAopProperties properties,
                                                     EncryptionMetadataInitializer metadataInitializer,
                                                     PolicyResolver policyResolver,
                                                     Environment environment) {
        String hubUrl = properties.getHubBaseUrl();
        if (hubUrl == null || hubUrl.trim().isEmpty()) {
            log.warn("⚠️ Hub URL이 설정되지 않아 AopSchemaSyncService를 생성하지 않습니다. hubUrl={}, 환경변수 DADP_HUB_BASE_URL={}", 
                    hubUrl, System.getenv("DADP_HUB_BASE_URL"));
            return null;
        }
        
        // AOP 인스턴스 ID 조회
        String instanceId = System.getenv("DADP_AOP_INSTANCE_ID");
        if (instanceId == null || instanceId.trim().isEmpty()) {
            if (environment != null) {
                instanceId = environment.getProperty("spring.application.name", "aop");
            } else {
                instanceId = "aop";
            }
        }
        
        // 영구저장소에서 hubId 로드 (Wrapper의 ProxyConfig와 동일한 로직)
        String hubId = loadHubIdFromStorage(hubUrl, instanceId);
        
        log.info("✅ AopSchemaSyncService 초기화: hubUrl={}, instanceId={}, hubId={}", 
                hubUrl, instanceId, hubId != null ? hubId : "(없음)");
        return new AopSchemaSyncService(hubUrl, instanceId, hubId, metadataInitializer, policyResolver);
    }
    
    /**
     * AopPolicyMappingSyncService 빈 등록
     * 30초 주기로 Hub에서 정책 매핑 정보를 동기화합니다.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "dadp", name = "hub-base-url")
    public AopPolicyMappingSyncService aopPolicyMappingSyncService(
            @Nullable MappingSyncService mappingSyncService,
            @Nullable EndpointSyncService endpointSyncService,
            @Nullable AopSchemaSyncService aopSchemaSyncService,
            PolicyResolver policyResolver,
            DirectCryptoAdapter directCryptoAdapter,
            EndpointStorage endpointStorage,
            DadpAopProperties properties,
            Environment environment) {
        if (mappingSyncService == null) {
            log.warn("⚠️ MappingSyncService가 없어 AopPolicyMappingSyncService를 생성하지 않습니다.");
            return null;
        }
        
        AopPolicyMappingSyncService syncService = 
            new AopPolicyMappingSyncService(mappingSyncService, endpointSyncService, aopSchemaSyncService,
                                           policyResolver, directCryptoAdapter, endpointStorage,
                                           properties, environment);
        // Hub URL이 설정되어 있으면 활성화
        syncService.setEnabled(true);
        log.info("✅ AopPolicyMappingSyncService 초기화 완료 (30초 주기 동기화 활성화)");
        return syncService;
    }
    
    /**
     * 스키마 동기화 초기화 리스너
     * ApplicationReadyEvent를 사용하여 애플리케이션이 완전히 시작된 후 스키마를 Hub로 전송합니다.
     * Wrapper와 달리 AOP는 애플리케이션 시작 시 한 번만 실행합니다.
     */
    @Bean
    @ConditionalOnMissingBean(name = "aopSchemaSyncInitializer")
    @ConditionalOnProperty(prefix = "dadp", name = "hub-base-url")
    public org.springframework.context.ApplicationListener<ApplicationReadyEvent> aopSchemaSyncInitializer(
            @Nullable AopSchemaSyncService aopSchemaSyncService,
            EncryptionMetadataInitializer metadataInitializer) {
        log.info("✅ AopSchemaSyncInitializer 빈 등록: aopSchemaSyncService={}", 
                aopSchemaSyncService != null ? "존재" : "null");
        return new AopSchemaSyncInitializer(aopSchemaSyncService, metadataInitializer);
    }
    
    /**
     * 스키마 동기화 초기화 리스너 클래스
     * ApplicationListener를 구현하여 ApplicationReadyEvent를 처리합니다.
     */
    public static class AopSchemaSyncInitializer implements org.springframework.context.ApplicationListener<ApplicationReadyEvent> {
        private final AopSchemaSyncService aopSchemaSyncService;
        private final EncryptionMetadataInitializer metadataInitializer;
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AopSchemaSyncInitializer.class);
        
        public AopSchemaSyncInitializer(@Nullable AopSchemaSyncService aopSchemaSyncService,
                                       EncryptionMetadataInitializer metadataInitializer) {
            this.aopSchemaSyncService = aopSchemaSyncService;
            this.metadataInitializer = metadataInitializer;
        }
        
        @Override
        public void onApplicationEvent(ApplicationReadyEvent event) {
            if (aopSchemaSyncService == null) {
                log.warn("⚠️ AopSchemaSyncService가 없어 스키마 동기화를 건너뜁니다. (Hub URL이 설정되지 않았거나 빈 값일 수 있습니다. 환경변수 DADP_HUB_BASE_URL 확인 필요)");
                return;
            }
            
            // ApplicationReadyEvent는 애플리케이션이 완전히 시작된 후 발생하므로 안전
            try {
                // 약간의 지연을 두어 EncryptionMetadataInitializer가 완전히 초기화되도록 함
                new Thread(() -> {
                    try {
                        Thread.sleep(2000); // 2초 대기
                        log.info("🔄 초기 스키마 동기화 시작");
                        boolean success = aopSchemaSyncService.syncSchemasToHub();
                        if (success) {
                            log.info("✅ 초기 스키마 동기화 완료");
                        } else {
                            log.warn("⚠️ 초기 스키마 동기화 실패 (나중에 재시도 예정)");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("⚠️ 초기 스키마 동기화 중단");
                    } catch (Exception e) {
                        log.error("❌ 초기 스키마 동기화 실패: {}", e.getMessage(), e);
                    }
                }, "aop-schema-sync-initializer").start();
            } catch (Exception e) {
                log.error("❌ 스키마 동기화 초기화 실패: {}", e.getMessage(), e);
            }
        }
    }
    
}
