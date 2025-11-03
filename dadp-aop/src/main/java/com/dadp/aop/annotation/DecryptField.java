package com.dadp.aop.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 필드 레벨 복호화 어노테이션
 * 
 * 이 어노테이션이 적용된 필드를 자동으로 복호화합니다.
 * 
 * @author DADP Development Team
 * @version 2.0.0
 * @since 2025-01-01
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface DecryptField {
    
    /**
     * 복호화 실패 시 원본 데이터 반환 여부
     * 기본값: true
     */
    boolean fallbackToOriginal() default true;
    
    /**
     * 복호화 로그 출력 여부
     * 기본값: true
     */
    boolean enableLogging() default true;
}
