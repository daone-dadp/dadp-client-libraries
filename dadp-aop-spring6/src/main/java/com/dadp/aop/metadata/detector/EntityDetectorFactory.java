package com.dadp.aop.metadata.detector;

import com.dadp.common.sync.entity.EntityDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import jakarta.persistence.EntityManagerFactory;

/**
 * EntityDetector 팩토리 (Jakarta Persistence)
 * 
 * 설정에 따라 적절한 EntityDetector 구현체를 생성합니다.
 * 
 * @author DADP Development Team
 * @version 5.4.0
 * @since 2026-01-09
 */
public class EntityDetectorFactory {
    
    private static final Logger log = LoggerFactory.getLogger(EntityDetectorFactory.class);
    
    /**
     * EntityDetector 생성
     * 
     * @param type 감지기 타입 ("jpa", "reflection", "annotation", "auto")
     * @param entityManagerFactory EntityManagerFactory (JPA용, nullable)
     * @param basePackage 스캔할 기본 패키지 (reflection/annotation용, nullable)
     * @return EntityDetector 인스턴스
     */
    public static EntityDetector create(String type, 
                                       @Nullable EntityManagerFactory entityManagerFactory,
                                       @Nullable String basePackage) {
        if (type == null || type.trim().isEmpty() || "auto".equalsIgnoreCase(type)) {
            return createAuto(entityManagerFactory, basePackage);
        }
        
        switch (type.toLowerCase()) {
            case "jpa":
                return createJpa(entityManagerFactory);
            case "reflection":
                return createReflection(basePackage);
            case "annotation":
                return createAnnotation(basePackage);
            default:
                log.warn("⚠️ 알 수 없는 감지기 타입: {} (auto로 폴백)", type);
                return createAuto(entityManagerFactory, basePackage);
        }
    }
    
    /**
     * JPA EntityDetector 생성
     */
    private static EntityDetector createJpa(@Nullable EntityManagerFactory entityManagerFactory) {
        JpaEntityDetector detector = new JpaEntityDetector(entityManagerFactory);
        if (!detector.canDetect()) {
            log.warn("⚠️ JPA EntityDetector를 사용할 수 없습니다 (EntityManagerFactory 없음). Reflection으로 폴백합니다.");
            return createReflection(null);
        }
        return detector;
    }
    
    /**
     * Reflection EntityDetector 생성
     */
    private static EntityDetector createReflection(@Nullable String basePackage) {
        return new ReflectionEntityDetector(basePackage);
    }
    
    /**
     * Annotation EntityDetector 생성
     */
    private static EntityDetector createAnnotation(@Nullable String basePackage) {
        return new AnnotationEntityDetector(basePackage);
    }
    
    /**
     * Auto-detection: 적절한 감지기 자동 선택
     * 
     * 우선순위:
     * 1. JPA가 있으면 JPA 사용
     * 2. 없으면 Reflection 사용
     */
    private static EntityDetector createAuto(@Nullable EntityManagerFactory entityManagerFactory,
                                             @Nullable String basePackage) {
        // JPA 사용 가능 여부 확인
        if (entityManagerFactory != null) {
            JpaEntityDetector jpaDetector = new JpaEntityDetector(entityManagerFactory);
            if (jpaDetector.canDetect()) {
                log.info("✅ Auto-detection: JPA EntityDetector 선택");
                return jpaDetector;
            }
        }
        
        // JPA가 없으면 Reflection 사용
        log.info("✅ Auto-detection: Reflection EntityDetector 선택 (JPA 없음)");
        return createReflection(basePackage);
    }
}
