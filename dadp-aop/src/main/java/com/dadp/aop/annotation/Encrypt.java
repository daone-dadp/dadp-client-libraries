package com.dadp.aop.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 메서드 레벨 암호화 어노테이션
 * 
 * 이 어노테이션이 적용된 메서드의 반환값을 자동으로 암호화합니다.
 * 
 * @author DADP Development Team
 * @version 2.0.0
 * @since 2025-01-01
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Encrypt {
    
    /**
     * 암호화 정책명
     * 기본값: "dadp"
     */
    String policy() default "dadp";
    
    /**
     * 암호화할 필드명들
     * 빈 배열이면 자동 감지
     */
    String[] fields() default {};
    
    /**
     * 암호화할 필드 타입들
     * 기본값: String 타입만
     */
    Class<?>[] fieldTypes() default {String.class};
    
    /**
     * 암호화 실패 시 원본 데이터 반환 여부
     * 기본값: true
     */
    boolean fallbackToOriginal() default true;
    
    /**
     * 암호화 로그 출력 여부
     * 기본값: true
     */
    boolean enableLogging() default true;
    
    /**
     * 상세 로그 출력 여부 (AOP 레벨)
     * true일 경우 AOP에서 암복호화 수행 및 결과에 대한 상세 로그를 출력합니다.
     * 엔진 통계 수집과는 무관하며, AOP 자체의 로깅 기능입니다.
     * 기본값: false
     */
    boolean includeStats() default false;
}
