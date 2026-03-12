package com.dadp.aop.metadata.detector;

import com.dadp.aop.annotation.EncryptField;
import com.dadp.common.sync.entity.EntityDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * JPA 기반 엔티티 감지기 (Jakarta Persistence)
 * 
 * EntityManagerFactory의 Metamodel을 사용하여 엔티티를 감지합니다.
 * JPA가 있는 환경에서 사용됩니다.
 * 
 * @author DADP Development Team
 * @version 5.4.0
 * @since 2026-01-09
 */
public class JpaEntityDetector implements EntityDetector {
    
    private static final Logger log = LoggerFactory.getLogger(JpaEntityDetector.class);
    
    private final EntityManagerFactory entityManagerFactory;
    
    public JpaEntityDetector(@Nullable EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }
    
    @Override
    public String getDetectorType() {
        return "jpa";
    }
    
    @Override
    public boolean canDetect() {
        return entityManagerFactory != null;
    }
    
    @Override
    public List<EntityMetadata> detectEntities() {
        List<EntityMetadata> entities = new ArrayList<>();
        
        if (entityManagerFactory == null) {
            log.debug("EntityManagerFactory not available, skipping JPA entity detection.");
            return entities;
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
                String schemaName = extractSchemaName(clazz);
                
                // 필드 스캔
                List<FieldMetadata> fields = new ArrayList<>();
                Field[] declaredFields = clazz.getDeclaredFields();
                for (Field field : declaredFields) {
                    EncryptField encryptField = field.getAnnotation(EncryptField.class);
                    if (encryptField == null) {
                        continue;
                    }
                    
                    // 컬럼명 추출
                    String columnName = getColumnName(field);
                    String policyName = encryptField.policy();
                    
                    fields.add(new FieldMetadata(
                        field.getName(),
                        columnName,
                        policyName,
                        null  // columnType은 JPA에서 직접 추출하기 어려움
                    ));
                }
                
                if (!fields.isEmpty()) {
                    entities.add(new EntityMetadata(clazz, tableName, schemaName, fields));
                    log.debug("JPA entity detected: {} (table: {}, fields: {})",
                        clazz.getSimpleName(), tableName, fields.size());
                }
            }

            log.info("JPA entity detection completed: {} entities, {} encrypted fields",
                entities.size(),
                entities.stream().mapToInt(e -> e.getFields().size()).sum());

        } catch (Exception e) {
            log.error("JPA entity detection failed", e);
        }
        
        return entities;
    }
    
    /**
     * 테이블명 추출
     */
    private String extractTableName(Class<?> clazz) {
        Table table = clazz.getAnnotation(Table.class);
        if (table != null && !table.name().isEmpty()) {
            return table.name();
        }
        return clazz.getSimpleName().toLowerCase();
    }
    
    /**
     * 스키마명 추출
     */
    private String extractSchemaName(Class<?> clazz) {
        Table table = clazz.getAnnotation(Table.class);
        if (table != null && !table.schema().isEmpty()) {
            return table.schema();
        }
        return null;
    }
    
    /**
     * 컬럼명 추출
     */
    private String getColumnName(Field field) {
        Column column = field.getAnnotation(Column.class);
        if (column != null && !column.name().isEmpty()) {
            return column.name();
        }
        return field.getName();
    }
}
