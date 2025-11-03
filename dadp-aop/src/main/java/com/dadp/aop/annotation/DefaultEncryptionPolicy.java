package com.dadp.aop.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 클래스 레벨 기본 암호화 정책 어노테이션
 * 
 * 이 어노테이션이 적용된 클래스의 모든 @EncryptField에 기본 정책을 적용합니다.
 * 
 * @author DADP Development Team
 * @version 2.0.0
 * @since 2025-01-01
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DefaultEncryptionPolicy {
    
    /**
     * 기본 암호화 정책명
     * 기본값: "dadp"
     */
    String value() default "dadp";
}
