package com.dadp.aop.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 메서드 레벨 복호화 어노테이션
 * 
 * 이 어노테이션이 적용된 메서드의 반환값(DB 조회 결과)을 자동으로 복호화합니다.
 * 파라미터는 복호화하지 않습니다 (DB 조회 메서드이므로 파라미터는 일반적으로 ID나 검색 조건).
 * 
 * 사용 예시:
 * - 리포지토리 조회 메서드: {@code @Decrypt List<User> findAll();}
 * - 서비스 조회 메서드: {@code @Decrypt User findById(Long id);}
 * 
 * @author DADP Development Team
 * @version 2.1.0
 * @since 2025-01-01
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Decrypt {
    
    /**
     * 복호화할 필드명들
     * 빈 배열이면 자동 감지
     */
    String[] fields() default {};
    
    /**
     * 복호화할 필드 타입들
     * 기본값: String 타입만
     */
    Class<?>[] fieldTypes() default {String.class};
    
    /**
     * 복호화 실패 시 원본 데이터 반환 여부
     * 기본값: true
     */
    boolean fallbackToOriginal() default true;
    
    /**
     * 복호화 로그 출력 여부
     * 기본값: false (명시적으로 true로 설정해야 로그 출력)
     */
    boolean enableLogging() default false;
    
    /**
     * 마스킹 정책명 (복호화 시 마스킹 적용을 위해 사용)
     * 빈 문자열이면 마스킹 미적용
     */
    String maskPolicyName() default "";
    
    /**
     * 마스킹 정책 UID (복호화 시 마스킹 적용을 위해 사용)
     * 빈 문자열이면 마스킹 미적용
     * maskPolicyName과 maskPolicyUid 중 하나만 지정하면 됨
     */
    String maskPolicyUid() default "";
    
    /**
     * 상세 로그 출력 여부 (AOP 레벨)
     * true일 경우 AOP에서 암복호화 수행 및 결과에 대한 상세 로그를 출력합니다.
     * 엔진 통계 수집과는 무관하며, AOP 자체의 로깅 기능입니다.
     * 기본값: false
     */
    boolean includeStats() default false;
}
