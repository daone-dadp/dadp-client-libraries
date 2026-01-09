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
 * @version 2.1.0
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
}
