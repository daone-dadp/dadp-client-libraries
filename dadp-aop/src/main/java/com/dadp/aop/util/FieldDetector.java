package com.dadp.aop.util;

import com.dadp.aop.annotation.EncryptField;
import com.dadp.aop.annotation.DecryptField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

/**
 * 필드 자동 감지 및 처리 유틸리티
 * 
 * @author DADP Development Team
 * @version 2.0.0
 * @since 2025-01-01
 */
public class FieldDetector {
    
    private static final Logger log = LoggerFactory.getLogger(FieldDetector.class);
    
    /**
     * 암호화할 필드들을 감지
     * 
     * @param obj 대상 객체
     * @param fieldNames 지정된 필드명들 (빈 배열이면 자동 감지)
     * @param fieldTypes 지정된 필드 타입들
     * @return 암호화할 필드 정보 리스트
     */
    public static List<FieldInfo> detectEncryptFields(Object obj, String[] fieldNames, Class<?>[] fieldTypes) {
        List<FieldInfo> fields = new ArrayList<>();
        
        if (obj == null) {
            return fields;
        }
        
        Class<?> clazz = obj.getClass();
        
        // 지정된 필드명이 있으면 해당 필드들만 처리
        if (fieldNames.length > 0) {
            for (String fieldName : fieldNames) {
                try {
                    Field field = clazz.getDeclaredField(fieldName);
                    if (isTargetFieldType(field, fieldTypes)) {
                        field.setAccessible(true);
                        fields.add(new FieldInfo(field, field.getAnnotation(EncryptField.class)));
                    }
                } catch (NoSuchFieldException e) {
                    log.warn("필드를 찾을 수 없습니다: {}", fieldName);
                }
            }
        } else {
            // 자동 감지: @EncryptField 어노테이션이 있는 필드만 암호화
            Field[] declaredFields = clazz.getDeclaredFields();
            for (Field field : declaredFields) {
                if (isTargetFieldType(field, fieldTypes)) {
                    EncryptField encryptField = field.getAnnotation(EncryptField.class);
                    // @EncryptField가 있는 필드만 암호화 대상
                    if (encryptField != null) {
                        field.setAccessible(true);
                        fields.add(new FieldInfo(field, encryptField));
                        log.debug("암호화 대상 필드 감지: {}.{} (policy={})", 
                                clazz.getSimpleName(), field.getName(), encryptField.policy());
                    } else {
                        log.debug("암호화 제외 필드: {}.{} (@EncryptField 없음)", 
                                clazz.getSimpleName(), field.getName());
                    }
                }
            }
        }
        
        return fields;
    }
    
    /**
     * 복호화할 필드들을 감지
     * 
     * @param obj 대상 객체
     * @param fieldNames 지정된 필드명들 (빈 배열이면 자동 감지)
     * @param fieldTypes 지정된 필드 타입들
     * @return 복호화할 필드 정보 리스트
     */
    public static List<FieldInfo> detectDecryptFields(Object obj, String[] fieldNames, Class<?>[] fieldTypes) {
        List<FieldInfo> fields = new ArrayList<>();
        
        if (obj == null) {
            return fields;
        }
        
        Class<?> clazz = obj.getClass();
        
        // 지정된 필드명이 있으면 해당 필드들만 처리
        // 단, @EncryptField가 있는 필드는 항상 복호화 대상이므로 함께 포함
        // 하지만 마스킹 정책은 지정된 필드에만 적용
        if (fieldNames.length > 0) {
            // 지정된 필드들 추가 (마스킹 정책 포함)
            Set<String> specifiedFieldNames = new HashSet<>(Arrays.asList(fieldNames));
            for (String fieldName : fieldNames) {
                try {
                    Field field = clazz.getDeclaredField(fieldName);
                    if (isTargetFieldType(field, fieldTypes)) {
                        field.setAccessible(true);
                        fields.add(new FieldInfo(field, field.getAnnotation(DecryptField.class)));
                    }
                } catch (NoSuchFieldException e) {
                    log.warn("필드를 찾을 수 없습니다: {}", fieldName);
                }
            }
            
            // @EncryptField가 있는 모든 필드도 추가 (지정된 필드와 중복 제거)
            // 단, 지정되지 않은 필드는 마스킹 정책 없이 복호화만 수행
            Field[] declaredFields = clazz.getDeclaredFields();
            for (Field field : declaredFields) {
                if (isTargetFieldType(field, fieldTypes)) {
                    EncryptField encryptField = field.getAnnotation(EncryptField.class);
                    if (encryptField != null) {
                        // 이미 추가된 필드인지 확인
                        boolean alreadyAdded = false;
                        for (FieldInfo existing : fields) {
                            if (existing.getField().getName().equals(field.getName())) {
                                alreadyAdded = true;
                                break;
                            }
                        }
                        if (!alreadyAdded) {
                            // 지정되지 않은 필드는 마스킹 정책 없이 추가 (복호화만 수행)
                            field.setAccessible(true);
                            fields.add(new FieldInfo(field)); // 어노테이션 없는 생성자 사용
                        }
                    }
                }
            }
        } else {
            // 자동 감지: @EncryptField 어노테이션이 있는 필드만 복호화 (암호화된 필드만 복호화)
            // @DecryptField는 마스킹 정책 지정용이므로, 복호화 대상 필드 결정에는 @EncryptField 사용
            Field[] declaredFields = clazz.getDeclaredFields();
            for (Field field : declaredFields) {
                if (isTargetFieldType(field, fieldTypes)) {
                    EncryptField encryptField = field.getAnnotation(EncryptField.class);
                    DecryptField decryptField = field.getAnnotation(DecryptField.class);
                    // @EncryptField가 있는 필드만 복호화 대상 (암호화된 필드이므로)
                    if (encryptField != null) {
                        field.setAccessible(true);
                        fields.add(new FieldInfo(field, decryptField));
                    }
                }
            }
        }
        
        return fields;
    }
    
    /**
     * 필드가 대상 타입인지 확인
     */
    private static boolean isTargetFieldType(Field field, Class<?>[] fieldTypes) {
        if (fieldTypes.length == 0) {
            return true;
        }
        
        for (Class<?> fieldType : fieldTypes) {
            if (fieldType.isAssignableFrom(field.getType())) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 필드 정보 클래스
     */
    public static class FieldInfo {
        private final Field field;
        private final EncryptField encryptField;
        private final DecryptField decryptField;
        
        public FieldInfo(Field field, EncryptField encryptField) {
            this.field = field;
            this.encryptField = encryptField;
            this.decryptField = null;
        }
        
        public FieldInfo(Field field, DecryptField decryptField) {
            this.field = field;
            this.encryptField = null;
            this.decryptField = decryptField;
        }
        
        // 필드만 있고 어노테이션이 없는 경우 (복호화만 수행, 마스킹 정책 없음)
        public FieldInfo(Field field) {
            this.field = field;
            this.encryptField = null;
            this.decryptField = null;
        }
        
        public Field getField() {
            return field;
        }
        
        public EncryptField getEncryptField() {
            return encryptField;
        }
        
        public DecryptField getDecryptField() {
            return decryptField;
        }
        
        public String getFieldName() {
            return field.getName();
        }
        
        public Class<?> getFieldType() {
            return field.getType();
        }
        
        public Object getValue(Object obj) {
            try {
                return field.get(obj);
            } catch (IllegalAccessException e) {
                log.error("필드 값 읽기 실패: {}", field.getName(), e);
                return null;
            }
        }
        
        public void setValue(Object obj, Object value) {
            try {
                field.set(obj, value);
            } catch (IllegalAccessException e) {
                log.error("필드 값 설정 실패: {}", field.getName(), e);
            }
        }
    }
}
