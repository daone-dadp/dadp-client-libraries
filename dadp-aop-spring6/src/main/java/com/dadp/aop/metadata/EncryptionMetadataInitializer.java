package com.dadp.aop.metadata;

import com.dadp.aop.metadata.detector.EntityDetectorFactory;
import com.dadp.common.sync.entity.EntityDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.lang.Nullable;

import jakarta.persistence.EntityManagerFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 암호화 메타데이터 초기화 컴포넌트
 * 
 * 애플리케이션 부팅 시점에 EntityDetector를 사용하여
 * {@code @EncryptField}가 있는 필드를 찾고, {@code @Table}과 {@code @Column} 정보를 조합하여
 * "table.column" 형태로 매핑을 자동 생성합니다.
 * 
 * @author DADP Development Team
 * @version 5.4.0
 * @since 2025-12-03
 */
public class EncryptionMetadataInitializer implements ApplicationListener<ContextRefreshedEvent> {
    
    private static final Logger log = LoggerFactory.getLogger(EncryptionMetadataInitializer.class);
    
    /**
     * 암호화 컬럼 매핑: "table.column" -> 정책명
     */
    private final Map<String, String> encryptedColumns = new HashMap<>();
    
    /**
     * 엔티티 클래스 -> 테이블명 매핑
     */
    private final Map<Class<?>, String> entityToTableMap = new HashMap<>();
    
    private final EntityManagerFactory entityManagerFactory;
    private final String entityDetectorType;
    private final String entityScanBasePackage;
    
    private EntityDetector entityDetector;
    private boolean initialized = false;
    
    /**
     * 스키마 로드 완료 신호 (게이트)
     * 오케스트레이터가 스키마 로드 완료를 기다릴 수 있도록 함
     */
    private final CompletableFuture<Void> schemaLoadedFuture = new CompletableFuture<>();
    
    /**
     * 생성자 (기본 설정)
     * @param emf EntityManagerFactory (nullable)
     */
    public EncryptionMetadataInitializer(@Nullable EntityManagerFactory emf) {
        this(emf, null, null);
    }
    
    /**
     * 생성자 (커스텀 설정)
     * @param emf EntityManagerFactory (nullable)
     * @param entityDetectorType 엔티티 감지기 타입 ("jpa", "reflection", "annotation", "auto")
     * @param entityScanBasePackage 엔티티 스캔 기본 패키지 (nullable)
     */
    public EncryptionMetadataInitializer(@Nullable EntityManagerFactory emf,
                                        @Nullable String entityDetectorType,
                                        @Nullable String entityScanBasePackage) {
        this.entityManagerFactory = emf;
        this.entityDetectorType = entityDetectorType != null ? entityDetectorType : "auto";
        this.entityScanBasePackage = entityScanBasePackage;
        
        // EntityDetector 생성
        this.entityDetector = EntityDetectorFactory.create(
            this.entityDetectorType,
            this.entityManagerFactory,
            this.entityScanBasePackage
        );
    }
    
    /**
     * 컨텍스트가 완전히 로드된 후 초기화
     */
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        log.info("EncryptionMetadataInitializer.onApplicationEvent() called");
        if (!initialized) {
            log.info("EncryptionMetadataInitializer.init() starting");
            init();
            initialized = true;
            log.info("EncryptionMetadataInitializer.init() completed");
        } else {
            log.info("EncryptionMetadataInitializer already initialized");
        }
    }
    
    /**
     * 초기화 메서드
     */
    public void init() {
        log.info("EncryptionMetadataInitializer.init() running...");
        log.info("EntityDetector type: {}", entityDetector.getDetectorType());
        log.info("EntityManagerFactory: {}", entityManagerFactory != null ? "present" : "null");

        if (!entityDetector.canDetect()) {
            log.warn("EntityDetector not available. Skipping metadata scan.");
            schemaLoadedFuture.complete(null);
            return;
        }
        
        try {
            // EntityDetector를 사용하여 엔티티 감지
            java.util.List<EntityDetector.EntityMetadata> entities = entityDetector.detectEntities();
            
            int totalFields = 0;
            for (EntityDetector.EntityMetadata entity : entities) {
                Class<?> clazz = entity.getEntityClass();
                String tableName = entity.getTableName();
                
                // 엔티티 -> 테이블명 매핑 저장
                entityToTableMap.put(clazz, tableName);
                
                // 필드 정보를 암호화 컬럼 매핑으로 변환
                for (EntityDetector.FieldMetadata field : entity.getFields()) {
                    String columnName = field.getColumnName();
                    String key = tableName + "." + columnName;
                    String policy = field.getPolicyName();
                    
                    encryptedColumns.put(key, policy);
                    totalFields++;
                    
                    log.debug("Encrypted column mapping registered: {} -> policy={} (entity: {}.{})",
                            key, policy, clazz.getSimpleName(), field.getFieldName());
                }
            }
            
            // 부팅 시 요약 로그 출력
            logSummary(entities.size(), totalFields);
            
            // 스키마 로드 완료 신호 발행 (성공 또는 실패 모두 완료로 간주)
            // 스키마 정보는 정책 매핑 저장 시 함께 저장됨
            schemaLoadedFuture.complete(null);
            
        } catch (Exception e) {
            log.error("Encryption metadata initialization failed", e);
            // 실패해도 완료 신호 발행 (스키마가 없는 상태로 진행)
            schemaLoadedFuture.complete(null);
        }
    }
    
    /**
     * 부팅 시 요약 로그 출력
     */
    private void logSummary(int entityCount, int fieldCount) {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("Encryption metadata initialization completed");
        log.info("   EntityDetector type: {}", entityDetector.getDetectorType());
        log.info("   Detected entities: {}", entityCount);
        log.info("   Detected encrypted fields: {}", fieldCount);
        log.info("   Schema count: {}", encryptedColumns.size());
        log.info("═══════════════════════════════════════════════════════════");
    }
    
    /**
     * 암호화 정책 조회
     * @param tableColumn "table.column" 형태의 키
     * @return 정책명, 없으면 null
     */
    public String getPolicy(String tableColumn) {
        return encryptedColumns.get(tableColumn);
    }
    
    /**
     * 엔티티 클래스로부터 테이블명 조회
     */
    public String getTableName(Class<?> entityClass) {
        return entityToTableMap.get(entityClass);
    }
    
    /**
     * 모든 암호화 컬럼 매핑 조회
     */
    public Map<String, String> getAllEncryptedColumns() {
        return new HashMap<>(encryptedColumns);
    }
    
    /**
     * 특정 엔티티 클래스의 암호화 필드 정보 조회
     */
    public Map<String, String> getEncryptedColumnsForEntity(Class<?> entityClass) {
        Map<String, String> result = new HashMap<>();
        String tableName = entityToTableMap.get(entityClass);
        if (tableName == null) {
            return result;
        }
        
        String prefix = tableName + ".";
        for (Map.Entry<String, String> entry : encryptedColumns.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        
        return result;
    }
    
    /**
     * 스키마 로드 완료를 기다림
     * 오케스트레이터가 스키마 로드 완료 후 다음 단계를 진행할 수 있도록 함
     * 
     * @return CompletableFuture 스키마 로드 완료 시 완료됨
     */
    public CompletableFuture<Void> awaitLoaded() {
        return schemaLoadedFuture;
    }
    
    /**
     * 스키마 로드 완료 여부 확인
     * 
     * @return 완료되었으면 true
     */
    public boolean isLoaded() {
        return schemaLoadedFuture.isDone();
    }
}

