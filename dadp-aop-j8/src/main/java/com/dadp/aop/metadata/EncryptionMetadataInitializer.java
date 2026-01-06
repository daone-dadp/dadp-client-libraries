package com.dadp.aop.metadata;

import com.dadp.aop.annotation.EncryptField;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EntityManagerFactory;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.Metamodel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.lang.Nullable;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * 암호화 메타데이터 초기화 컴포넌트
 * 
 * 애플리케이션 부팅 시점에 JPA 메타데이터를 스캔하여
 * {@code @EncryptField}가 있는 필드를 찾고, {@code @Table}과 {@code @Column} 정보를 조합하여
 * "table.column" 형태로 매핑을 자동 생성합니다.
 * 
 * @author DADP Development Team
 * @version 3.0.0
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
    
    private boolean initialized = false;
    
    /**
     * 생성자
     * @param emf EntityManagerFactory (nullable)
     */
    public EncryptionMetadataInitializer(@Nullable EntityManagerFactory emf) {
        this.entityManagerFactory = emf;
    }
    
    /**
     * 컨텍스트가 완전히 로드된 후 초기화
     */
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        log.info("🔔 EncryptionMetadataInitializer.onApplicationEvent() 호출됨");
        if (!initialized) {
            log.info("🔔 EncryptionMetadataInitializer.init() 시작");
            init();
            initialized = true;
            log.info("🔔 EncryptionMetadataInitializer.init() 완료");
        } else {
            log.info("🔔 EncryptionMetadataInitializer는 이미 초기화됨");
        }
    }
    
    /**
     * 초기화 메서드
     */
    public void init() {
        log.info("🔔 EncryptionMetadataInitializer.init() 실행 중...");
        log.info("🔔 EntityManagerFactory: {}", entityManagerFactory != null ? "존재함" : "null");
        
        if (entityManagerFactory == null) {
            log.warn("⚠️ EntityManagerFactory가 없습니다. JPA 메타데이터 스캔을 건너뜁니다.");
            return;
        }
        
        try {
            Metamodel metamodel = entityManagerFactory.getMetamodel();
            
            for (EntityType<?> entity : metamodel.getEntities()) {
                Class<?> clazz = entity.getJavaType();
                
                // @Entity 어노테이션이 있는지 확인
                if (!clazz.isAnnotationPresent(Entity.class)) {
                    continue;
                }
                
                // 테이블명 추출
                String tableName = extractTableName(clazz);
                entityToTableMap.put(clazz, tableName);
                
                // 필드 스캔
                Field[] declaredFields = clazz.getDeclaredFields();
                for (Field field : declaredFields) {
                    EncryptField encryptField = field.getAnnotation(EncryptField.class);
                    if (encryptField == null) {
                        continue;
                    }
                    
                    // 컬럼명 추출
                    String columnName = getColumnName(field);
                    String key = tableName + "." + columnName;
                    String policy = encryptField.policy();
                    
                    encryptedColumns.put(key, policy);
                    
                    log.info("🔐 암호화 컬럼 매핑 등록: {} -> policy={} (엔티티: {}.{})", 
                            key, policy, clazz.getSimpleName(), field.getName());
                }
            }
            
            log.info("✅ 암호화 메타데이터 초기화 완료: {}개 컬럼 매핑", encryptedColumns.size());
            
        } catch (Exception e) {
            log.error("❌ 암호화 메타데이터 초기화 실패", e);
        }
    }
    
    /**
     * 테이블명 추출
     * {@code @Table} 어노테이션이 있으면 name 속성 사용, 없으면 엔티티 클래스명 사용
     */
    private String extractTableName(Class<?> clazz) {
        javax.persistence.Table table = clazz.getAnnotation(javax.persistence.Table.class);
        if (table != null && !table.name().isEmpty()) {
            return table.name();
        }
        // {@code @Table}이 없으면 엔티티 클래스명을 소문자로 변환
        return clazz.getSimpleName().toLowerCase();
    }
    
    /**
     * 컬럼명 추출
     * {@code @Column} 어노테이션이 있으면 name 속성 사용, 없으면 필드명 사용
     */
    private String getColumnName(Field field) {
        Column column = field.getAnnotation(Column.class);
        if (column != null && !column.name().isEmpty()) {
            return column.name();
        }
        return field.getName();
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
}

