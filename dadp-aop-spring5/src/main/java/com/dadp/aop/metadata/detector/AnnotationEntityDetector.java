package com.dadp.aop.metadata.detector;

import com.dadp.aop.annotation.EncryptField;
import com.dadp.common.sync.entity.EntityDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.lang.Nullable;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 어노테이션 스캔 기반 엔티티 감지기
 * 
 * Spring의 ClassPathScanningCandidateComponentProvider를 사용하여
 * @Entity 어노테이션이 있는 클래스를 찾고, 리플렉션으로 암호화 필드를 감지합니다.
 * 
 * @author DADP Development Team
 * @version 5.4.0
 * @since 2026-01-09
 */
public class AnnotationEntityDetector implements EntityDetector {
    
    private static final Logger log = LoggerFactory.getLogger(AnnotationEntityDetector.class);
    
    private final String basePackage;
    
    /**
     * 생성자 (기본 패키지 스캔)
     */
    public AnnotationEntityDetector() {
        this(null);
    }
    
    /**
     * 생성자 (지정된 패키지 스캔)
     * 
     * @param basePackage 스캔할 기본 패키지 (null이면 전체 클래스패스 스캔)
     */
    public AnnotationEntityDetector(@Nullable String basePackage) {
        this.basePackage = basePackage;
    }
    
    @Override
    public String getDetectorType() {
        return "annotation";
    }
    
    @Override
    public boolean canDetect() {
        // 어노테이션 스캔은 항상 사용 가능
        return true;
    }
    
    @Override
    public List<EntityMetadata> detectEntities() {
        List<EntityMetadata> entities = new ArrayList<>();
        
        try {
            // Spring의 ClassPathScanningCandidateComponentProvider 사용
            ClassPathScanningCandidateComponentProvider scanner = 
                new ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
            
            // 스캔할 패키지 결정
            Set<BeanDefinition> candidates;
            if (basePackage != null && !basePackage.trim().isEmpty()) {
                candidates = scanner.findCandidateComponents(basePackage);
            } else {
                // 기본 패키지 스캔 (애플리케이션 패키지 추정)
                candidates = scanner.findCandidateComponents("");
            }
            
            for (BeanDefinition candidate : candidates) {
                try {
                    Class<?> clazz = Class.forName(candidate.getBeanClassName());
                    
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
                            field.getType().getSimpleName()
                        ));
                    }
                    
                    if (!fields.isEmpty()) {
                        entities.add(new EntityMetadata(clazz, tableName, schemaName, fields));
                        log.debug("Annotation entity detected: {} (table: {}, fields: {})",
                            clazz.getSimpleName(), tableName, fields.size());
                    }
                } catch (ClassNotFoundException e) {
                    log.warn("Class load failed: {}", candidate.getBeanClassName(), e);
                }
            }

            log.info("Annotation entity detection completed: {} entities, {} encrypted fields",
                entities.size(),
                entities.stream().mapToInt(e -> e.getFields().size()).sum());

        } catch (Exception e) {
            log.error("Annotation entity detection failed", e);
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
