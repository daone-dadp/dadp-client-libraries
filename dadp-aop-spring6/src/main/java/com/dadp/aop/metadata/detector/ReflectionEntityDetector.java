package com.dadp.aop.metadata.detector;

import com.dadp.aop.annotation.EncryptField;
import com.dadp.common.sync.entity.EntityDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 리플렉션 기반 엔티티 감지기 (Jakarta Persistence)
 * 
 * 클래스패스를 스캔하여 @Entity 어노테이션이 있는 클래스를 찾고,
 * 리플렉션을 사용하여 암호화 필드를 감지합니다.
 * JPA가 없는 환경에서도 사용 가능합니다.
 * 
 * @author DADP Development Team
 * @version 5.4.0
 * @since 2026-01-09
 */
public class ReflectionEntityDetector implements EntityDetector {
    
    private static final Logger log = LoggerFactory.getLogger(ReflectionEntityDetector.class);
    
    private final String basePackage;
    private final ClassLoader classLoader;
    
    /**
     * 생성자 (기본 패키지 스캔)
     */
    public ReflectionEntityDetector() {
        this(null);
    }
    
    /**
     * 생성자 (지정된 패키지 스캔)
     * 
     * @param basePackage 스캔할 기본 패키지 (null이면 전체 클래스패스 스캔)
     */
    public ReflectionEntityDetector(@Nullable String basePackage) {
        this.basePackage = basePackage;
        this.classLoader = Thread.currentThread().getContextClassLoader();
    }
    
    @Override
    public String getDetectorType() {
        return "reflection";
    }
    
    @Override
    public boolean canDetect() {
        // 리플렉션은 항상 사용 가능
        return true;
    }
    
    @Override
    public List<EntityMetadata> detectEntities() {
        List<EntityMetadata> entities = new ArrayList<>();
        
        try {
            // 클래스패스에서 엔티티 클래스 찾기
            List<Class<?>> entityClasses = findEntityClasses();
            
            for (Class<?> clazz : entityClasses) {
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
                    log.debug("📋 리플렉션 엔티티 감지: {} (테이블: {}, 필드: {}개)", 
                        clazz.getSimpleName(), tableName, fields.size());
                }
            }
            
            log.info("✅ 리플렉션 엔티티 감지 완료: {}개 엔티티, {}개 암호화 필드", 
                entities.size(), 
                entities.stream().mapToInt(e -> e.getFields().size()).sum());
            
        } catch (Exception e) {
            log.error("❌ 리플렉션 엔티티 감지 실패", e);
        }
        
        return entities;
    }
    
    /**
     * 클래스패스에서 @Entity 어노테이션이 있는 클래스 찾기
     */
    private List<Class<?>> findEntityClasses() throws Exception {
        List<Class<?>> entityClasses = new ArrayList<>();
        
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(classLoader);
        MetadataReaderFactory metadataReaderFactory = new CachingMetadataReaderFactory(resolver);
        
        // 스캔 패턴 결정
        String packageSearchPath = "classpath*:**/*.class";
        if (basePackage != null && !basePackage.trim().isEmpty()) {
            String packagePath = basePackage.replace('.', '/');
            packageSearchPath = "classpath*:" + packagePath + "/**/*.class";
        }
        
        Resource[] resources = resolver.getResources(packageSearchPath);
        
        for (Resource resource : resources) {
            if (!resource.isReadable()) {
                continue;
            }
            
            try {
                MetadataReader metadataReader = metadataReaderFactory.getMetadataReader(resource);
                String className = metadataReader.getClassMetadata().getClassName();
                
                // @Entity 어노테이션 확인 (jakarta.persistence.Entity)
                if (metadataReader.getAnnotationMetadata().hasAnnotation(Entity.class.getName())) {
                    Class<?> clazz = ClassUtils.forName(className, classLoader);
                    entityClasses.add(clazz);
                }
            } catch (Exception e) {
                // 클래스 로드 실패는 무시 (내부 클래스 등)
                log.trace("클래스 로드 실패 (무시): {}", resource, e);
            }
        }
        
        return entityClasses;
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
