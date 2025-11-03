package com.dadp.aop.aspect;

import com.dadp.aop.annotation.Encrypt;
import com.dadp.aop.annotation.Decrypt;
import com.dadp.aop.service.CryptoService;
import com.dadp.aop.util.FieldDetector;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 암복호화 AOP Aspect
 * 
 * @Encrypt, @Decrypt 어노테이션이 적용된 메서드의 반환값을 자동으로 암복호화합니다.
 * 
 * @author DADP Development Team
 * @version 2.0.0
 * @since 2025-01-01
 */
@Aspect
@Component
public class EncryptionAspect {
    
    private static final Logger log = LoggerFactory.getLogger(EncryptionAspect.class);
    
    @Autowired
    private CryptoService cryptoService;
    
    /**
     * @Encrypt 어노테이션이 적용된 메서드 처리
     */
    @Around("@annotation(com.dadp.aop.annotation.Encrypt)")
    public Object handleEncrypt(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Encrypt encryptAnnotation = method.getAnnotation(Encrypt.class);
        
        log.debug("🔒 암호화 AOP 시작: {}.{}", 
                 method.getDeclaringClass().getSimpleName(), method.getName());
        
        try {
            // 원본 메서드 실행
            Object result = joinPoint.proceed();
            
            if (result == null) {
                return result;
            }
            
            // 반환값 암호화 처리
            Object encryptedResult = processEncryption(result, encryptAnnotation);
            
            log.debug("✅ 암호화 AOP 완료: {}.{}", 
                     method.getDeclaringClass().getSimpleName(), method.getName());
            
            return encryptedResult;
            
        } catch (Exception e) {
            log.error("❌ 암호화 AOP 실패: {}.{} - {}", 
                     method.getDeclaringClass().getSimpleName(), method.getName(), e.getMessage());
            
            if (encryptAnnotation.fallbackToOriginal()) {
                log.warn("원본 데이터로 폴백: {}.{}", 
                        method.getDeclaringClass().getSimpleName(), method.getName());
                return joinPoint.proceed();
            } else {
                throw e;
            }
        }
    }
    
    /**
     * @Decrypt 어노테이션이 적용된 메서드 처리
     */
    @Around("@annotation(com.dadp.aop.annotation.Decrypt)")
    public Object handleDecrypt(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Decrypt decryptAnnotation = method.getAnnotation(Decrypt.class);
        
        log.debug("🔓 복호화 AOP 시작: {}.{}", 
                 method.getDeclaringClass().getSimpleName(), method.getName());
        
        try {
            // 원본 메서드 실행
            Object result = joinPoint.proceed();
            
            if (result == null) {
                return result;
            }
            
            // 반환값 복호화 처리
            Object decryptedResult = processDecryption(result, decryptAnnotation);
            
            log.debug("✅ 복호화 AOP 완료: {}.{}", 
                     method.getDeclaringClass().getSimpleName(), method.getName());
            
            return decryptedResult;
            
        } catch (Exception e) {
            log.error("❌ 복호화 AOP 실패: {}.{} - {}", 
                     method.getDeclaringClass().getSimpleName(), method.getName(), e.getMessage());
            
            if (decryptAnnotation.fallbackToOriginal()) {
                log.warn("원본 데이터로 폴백: {}.{}", 
                        method.getDeclaringClass().getSimpleName(), method.getName());
                return joinPoint.proceed();
            } else {
                throw e;
            }
        }
    }
    
    /**
     * 암호화 처리
     */
    private Object processEncryption(Object obj, Encrypt encryptAnnotation) {
        if (obj == null) {
            return obj;
        }
        
        // String 타입인 경우 직접 암호화
        if (obj instanceof String) {
            String data = (String) obj;
            if (cryptoService.isEncryptedData(data)) {
                log.debug("이미 암호화된 데이터입니다: {}", data.substring(0, Math.min(20, data.length())) + "...");
                return data;
            }
            
            String encryptedData = cryptoService.encrypt(data, encryptAnnotation.policy());
            if (encryptAnnotation.enableLogging()) {
                log.info("🔒 데이터 암호화 완료: {} → {}", 
                        data.substring(0, Math.min(10, data.length())) + "...", 
                        encryptedData.substring(0, Math.min(20, encryptedData.length())) + "...");
            }
            return encryptedData;
        }
        
        // 객체인 경우 필드별 암호화
        List<FieldDetector.FieldInfo> fields = FieldDetector.detectEncryptFields(
            obj, encryptAnnotation.fields(), encryptAnnotation.fieldTypes());
        
        for (FieldDetector.FieldInfo fieldInfo : fields) {
            Object fieldValue = fieldInfo.getValue(obj);
            if (fieldValue instanceof String) {
                String data = (String) fieldValue;
                if (cryptoService.isEncryptedData(data)) {
                    log.debug("필드 {}는 이미 암호화된 데이터입니다", fieldInfo.getFieldName());
                    continue;
                }
                
                String policy = encryptAnnotation.policy();
                if (fieldInfo.getEncryptField() != null) {
                    policy = fieldInfo.getEncryptField().policy();
                }
                
                String encryptedData = cryptoService.encrypt(data, policy);
                fieldInfo.setValue(obj, encryptedData);
                
                if (encryptAnnotation.enableLogging()) {
                    log.info("🔒 필드 암호화 완료: {}.{} = {} → {}", 
                            obj.getClass().getSimpleName(), fieldInfo.getFieldName(),
                            data.substring(0, Math.min(10, data.length())) + "...", 
                            encryptedData.substring(0, Math.min(20, encryptedData.length())) + "...");
                }
            }
        }
        
        return obj;
    }
    
    /**
     * 복호화 처리
     */
    private Object processDecryption(Object obj, Decrypt decryptAnnotation) {
        if (obj == null) {
            return obj;
        }
        
        // String 타입인 경우 직접 복호화
        if (obj instanceof String) {
            String data = (String) obj;
            if (!cryptoService.isEncryptedData(data)) {
                log.debug("암호화되지 않은 데이터입니다: {}", data.substring(0, Math.min(20, data.length())) + "...");
                return data;
            }
            
            String decryptedData = cryptoService.decrypt(data);
            if (decryptAnnotation.enableLogging()) {
                log.info("🔓 데이터 복호화 완료: {} → {}", 
                        data.substring(0, Math.min(20, data.length())) + "...", 
                        decryptedData.substring(0, Math.min(10, decryptedData.length())) + "...");
            }
            return decryptedData;
        }
        
        // 객체인 경우 필드별 복호화
        List<FieldDetector.FieldInfo> fields = FieldDetector.detectDecryptFields(
            obj, decryptAnnotation.fields(), decryptAnnotation.fieldTypes());
        
        for (FieldDetector.FieldInfo fieldInfo : fields) {
            Object fieldValue = fieldInfo.getValue(obj);
            if (fieldValue instanceof String) {
                String data = (String) fieldValue;
                if (!cryptoService.isEncryptedData(data)) {
                    log.debug("필드 {}는 암호화되지 않은 데이터입니다", fieldInfo.getFieldName());
                    continue;
                }
                
                String decryptedData = cryptoService.decrypt(data);
                fieldInfo.setValue(obj, decryptedData);
                
                if (decryptAnnotation.enableLogging()) {
                    log.info("🔓 필드 복호화 완료: {}.{} = {} → {}", 
                            obj.getClass().getSimpleName(), fieldInfo.getFieldName(),
                            data.substring(0, Math.min(20, data.length())) + "...", 
                            decryptedData.substring(0, Math.min(10, decryptedData.length())) + "...");
                }
            }
        }
        
        return obj;
    }
}
