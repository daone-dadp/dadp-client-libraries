package com.dadp.aop.util;

import com.dadp.aop.annotation.EncryptField;
import com.dadp.aop.annotation.DecryptField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
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
            // 자동 감지: @EncryptField 어노테이션이 있거나 String 타입인 필드들
            Field[] declaredFields = clazz.getDeclaredFields();
            for (Field field : declaredFields) {
                if (isTargetFieldType(field, fieldTypes)) {
                    EncryptField encryptField = field.getAnnotation(EncryptField.class);
                    if (encryptField != null || String.class.equals(field.getType())) {
                        field.setAccessible(true);
                        fields.add(new FieldInfo(field, encryptField));
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
        if (fieldNames.length > 0) {
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
        } else {
            // 자동 감지: @DecryptField 어노테이션이 있거나 String 타입인 필드들
            Field[] declaredFields = clazz.getDeclaredFields();
            for (Field field : declaredFields) {
                if (isTargetFieldType(field, fieldTypes)) {
                    DecryptField decryptField = field.getAnnotation(DecryptField.class);
                    if (decryptField != null || String.class.equals(field.getType())) {
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
