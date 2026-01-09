package com.dadp.aop.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 필드 레벨 암호화 어노테이션
 * 
 * 이 어노테이션이 적용된 필드를 자동으로 암호화합니다.
 * 
 * @author DADP Development Team
 * @version 2.0.0
 * @since 2025-01-01
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface EncryptField {
    
    /**
     * 암호화 정책명
     * 기본값: "dadp"
     * 
     * @deprecated 이 파라미터는 더 이상 사용되지 않습니다. 
     * 정책은 Hub에서 스키마(테이블.컬럼)와 매핑하여 관리됩니다.
     * 기존 코드와의 호환성을 위해 무시되고 기본 정책 "dadp"가 사용됩니다.
     * IDE에서 이 파라미터를 사용하려고 하면 "제공되지 않는 파라미터"라는 툴팁이 표시됩니다.
     */
    @Deprecated
    String policy() default "dadp";
    
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
}
