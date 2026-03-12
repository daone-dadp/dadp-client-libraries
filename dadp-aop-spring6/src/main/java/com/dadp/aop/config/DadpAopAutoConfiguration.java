package com.dadp.aop.config;

import com.dadp.aop.aspect.EncryptionAspect;
import com.dadp.aop.annotation.EncryptField;
import com.dadp.aop.metadata.EncryptionMetadataInitializer;
import com.dadp.aop.service.AopNotificationService;
import com.dadp.aop.service.CryptoService;
import com.dadp.aop.sync.AopBootstrapOrchestrator;
import com.dadp.aop.sync.AopPolicyMappingSyncService;
import com.dadp.aop.sync.AopSchemaSyncServiceV2;
import com.dadp.common.sync.config.EndpointStorage;
import com.dadp.common.sync.config.InstanceConfigStorage;
import com.dadp.common.sync.endpoint.EndpointSyncService;
import com.dadp.common.sync.mapping.MappingSyncService;
import com.dadp.common.sync.policy.PolicyMappingStorage;
import com.dadp.common.sync.policy.PolicyResolver;
import com.dadp.common.sync.crypto.DirectCryptoAdapter;
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
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestTemplate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

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
        log.info("DadpAopAutoConfiguration constructor called");
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
                log.info("DirectCryptoAdapter initialized: cryptoUrl={}", endpointData.getCryptoUrl());
            } else {
                log.warn("EndpointStorage has no Engine URL. Will be updated automatically when EndpointSyncService synchronizes.");
            }
        } else {
            log.warn("EndpointStorage not available. Will be updated automatically when EndpointSyncService synchronizes.");
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
            log.debug("Hub Base URL not configured, skipping notification client creation.");
            return null;
        }
        
        try {
            HubNotificationClient client = HubNotificationClient.createInstance(hubBaseUrl, 5000, true);
            log.info("Hub notification client initialized: hubBaseUrl={}", hubBaseUrl);
            return client;
        } catch (Exception e) {
            log.warn("Hub notification client initialization failed: {}", e.getMessage());
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
     * EntityDetector를 사용하여 {@code @EncryptField}가 있는 필드를 자동으로 찾고
     * {@code @Table}과 {@code @Column} 정보를 조합하여 "table.column" 형태로 매핑을 생성합니다.
     * 
     * HibernateJpaAutoConfiguration 이후에 실행되어 EntityManagerFactory가 준비된 상태에서 초기화됩니다.
     */
    @Bean
    public EncryptionMetadataInitializer encryptionMetadataInitializer(
            @Nullable EntityManagerFactory entityManagerFactory,
            DadpAopProperties properties) {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DadpAopAutoConfiguration.class);
        log.info("DadpAopAutoConfiguration.encryptionMetadataInitializer() bean creation in progress...");
        log.info("EntityManagerFactory: {}", entityManagerFactory != null ? "present" : "null");
        
        // 설정에서 EntityDetector 타입 및 기본 패키지 읽기
        String entityDetectorType = properties.getAop().getEntityDetectorType();
        String entityScanBasePackage = properties.getAop().getEntityScanBasePackage();
        
        log.info("EntityDetector type: {}", entityDetectorType);
        if (entityScanBasePackage != null) {
            log.info("Entity scan base package: {}", entityScanBasePackage);
        }
        
        EncryptionMetadataInitializer initializer = new EncryptionMetadataInitializer(
            entityManagerFactory,
            entityDetectorType,
            entityScanBasePackage
        );
        log.info("EncryptionMetadataInitializer bean created");
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
        log.info("PolicyResolver initialized: storageDir={}, fileName={}", storageDir, fileName);
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
        log.info("EndpointStorage initialized: storageDir={}, fileName={}", storageDir, fileName);
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
            log.info("hubId loaded from persistent storage: hubId={}", hubId);
            return hubId;
        } else {
            log.info("hubId not found. Will register with Hub: hubUrl={}, instanceId={}", hubUrl, instanceId);
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
            log.warn("Hub URL not configured, skipping MappingSyncService creation.");
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
        
        // V1 API 경로: /hub/api/v1/aop
        String apiBasePath = "/hub/api/v1/aop";
        
        log.info("MappingSyncService initialized: hubUrl={}, instanceId={}, hubId={}, apiBasePath={}",
                hubUrl, instanceId, hubId != null ? hubId : "(none)", apiBasePath);
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
            log.warn("Hub URL not configured, skipping EndpointSyncService creation.");
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
        
        log.info("EndpointSyncService initialized: hubUrl={}, instanceId={}, hubId={}, storageDir={}",
                hubUrl, instanceId, hubId != null ? hubId : "(none)", storageDir);
        return new EndpointSyncService(hubUrl, hubId, instanceId, storageDir, fileName);
    }
    
    /**
     * AopSchemaSyncService 빈 등록
     * EncryptionMetadataInitializer에서 수집한 스키마 정보를 Hub로 전송합니다.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "dadp", name = "hub-base-url")
    public AopSchemaSyncServiceV2 aopSchemaSyncService(DadpAopProperties properties,
                                                     EncryptionMetadataInitializer metadataInitializer,
                                                     PolicyResolver policyResolver,
                                                     Environment environment) {
        String hubUrl = properties.getHubBaseUrl();
        if (hubUrl == null || hubUrl.trim().isEmpty()) {
            log.warn("Hub URL not configured, skipping AopSchemaSyncService creation. hubUrl={}, env DADP_HUB_BASE_URL={}",
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
        
        log.info("AopSchemaSyncService initialized (V2): hubUrl={}, instanceId={}, hubId={}",
                hubUrl, instanceId, hubId != null ? hubId : "(none)");
        // 공통 라이브러리 기반 V2 사용
        return new AopSchemaSyncServiceV2(hubUrl, instanceId, hubId, metadataInitializer, policyResolver);
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
            @Nullable AopSchemaSyncServiceV2 aopSchemaSyncService,
            PolicyResolver policyResolver,
            DirectCryptoAdapter directCryptoAdapter,
            EndpointStorage endpointStorage,
            DadpAopProperties properties,
            Environment environment,
            EncryptionMetadataInitializer metadataInitializer) {
        if (mappingSyncService == null) {
            log.warn("MappingSyncService not available, skipping AopPolicyMappingSyncService creation.");
            return null;
        }
        
        AopPolicyMappingSyncService syncService = 
            new AopPolicyMappingSyncService(mappingSyncService, endpointSyncService, aopSchemaSyncService,
                                           policyResolver, directCryptoAdapter, endpointStorage,
                                           properties, environment, metadataInitializer);
        // Hub URL이 설정되어 있으면 활성화
        syncService.setEnabled(true);
        log.info("AopPolicyMappingSyncService initialized (30-second periodic sync enabled)");
        return syncService;
    }
    
    /**
     * AOP 부팅 플로우 오케스트레이터 빈 등록
     * ApplicationReadyEvent 이후 단일 진입점에서 전체 부팅 플로우를 수행합니다.
     */
    @Bean
    @ConditionalOnMissingBean(name = "aopBootstrapOrchestrator")
    @ConditionalOnProperty(prefix = "dadp", name = "hub-base-url")
    public AopBootstrapOrchestrator aopBootstrapOrchestrator(
            EncryptionMetadataInitializer metadataInitializer,
            @Nullable MappingSyncService mappingSyncService,
            @Nullable EndpointSyncService endpointSyncService,
            @Nullable AopSchemaSyncServiceV2 aopSchemaSyncService,
            PolicyResolver policyResolver,
            DirectCryptoAdapter directCryptoAdapter,
            EndpointStorage endpointStorage,
            DadpAopProperties properties,
            Environment environment,
            AopPolicyMappingSyncService policyMappingSyncService) {
        log.info("AopBootstrapOrchestrator bean registered");
        return new AopBootstrapOrchestrator(
            metadataInitializer,
            mappingSyncService,
            endpointSyncService,
            aopSchemaSyncService,
            policyResolver,
            directCryptoAdapter,
            endpointStorage,
            properties,
            environment,
            policyMappingSyncService);
    }
    
}
