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
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Collection;

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
    
    @Autowired(required = false)
    private ApplicationContext applicationContext;
    
    // EntityManager는 런타임에 리플렉션으로 가져오기 (JPA가 있는 경우에만)
    private Object entityManager;
    
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
            // ① 트랜잭션 경계 안에서 FlushMode를 COMMIT으로 설정 (JPA 레벨, Session 없어도 가능)
            Object em = getTransactionalEntityManager();
            if (em != null) {
                try {
                    Class<?> flushModeTypeClass = Class.forName("jakarta.persistence.FlushModeType");
                    Object commitFlushMode = flushModeTypeClass.getEnumConstants()[0]; // COMMIT
                    for (Object constant : flushModeTypeClass.getEnumConstants()) {
                        if (constant.toString().equals("COMMIT")) {
                            commitFlushMode = constant;
                            break;
                        }
                    }
                    Method setFlushModeMethod = em.getClass().getMethod("setFlushMode", flushModeTypeClass);
                    setFlushModeMethod.invoke(em, commitFlushMode);
                    log.debug("✅ FlushMode COMMIT 설정 완료");
                } catch (Exception e) {
                    log.debug("⚠️ FlushMode 설정 실패 (무시): {}", e.getMessage());
                }
            }
            
            // 원본 메서드 실행
            Object result = joinPoint.proceed();
            
            if (result == null) {
                return result;
            }
            
            // 반환값 복호화/마스킹 처리
            Object decryptedResult = processDecryption(result, decryptAnnotation);
            
            // ② 복호화 후 엔티티를 readOnly로 설정하고 detach
            handleResultForReadOnly(decryptedResult, em);
            
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
        
        // Collection 타입인 경우 (List, Set 등) 각 요소에 대해 재귀적으로 암호화
        if (obj instanceof Collection) {
            Collection<?> collection = (Collection<?>) obj;
            for (Object item : collection) {
                if (item != null) {
                    processEncryption(item, encryptAnnotation);
                }
            }
            return obj;
        }
        
        // 객체인 경우 필드별 암호화
        List<FieldDetector.FieldInfo> fields = FieldDetector.detectEncryptFields(
            obj, encryptAnnotation.fields(), encryptAnnotation.fieldTypes());
        
        for (FieldDetector.FieldInfo fieldInfo : fields) {
            // @EncryptField가 없는 필드는 암호화하지 않음 (name 필드 등)
            if (fieldInfo.getEncryptField() == null) {
                log.debug("필드 {}는 @EncryptField가 없어 암호화하지 않습니다", fieldInfo.getFieldName());
                continue;
            }
            
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
        
        // Optional 타입인 경우 내부 값을 추출하여 복호화
        // 먼저 내부 값을 detach한 후 복호화하여 UPDATE 방지
        if (obj instanceof java.util.Optional) {
            java.util.Optional<?> optional = (java.util.Optional<?>) obj;
            if (optional.isPresent()) {
                Object value = optional.get();
                // 복호화 전에 먼저 detach하여 변경 감지 방지
                detachEntities(value);
                Object decryptedValue = processDecryption(value, decryptAnnotation);
                return java.util.Optional.ofNullable(decryptedValue);
            } else {
                return java.util.Optional.empty();
            }
        }
        
        // String 타입인 경우 Hub에 전달 (암호화 여부와 상관없이)
        if (obj instanceof String) {
            String data = (String) obj;
            
            // 마스킹 정책 정보 추출
            String maskPolicyName = decryptAnnotation.maskPolicyName();
            String maskPolicyUid = decryptAnnotation.maskPolicyUid();
            if (maskPolicyName != null && maskPolicyName.trim().isEmpty()) {
                maskPolicyName = null;
            }
            if (maskPolicyUid != null && maskPolicyUid.trim().isEmpty()) {
                maskPolicyUid = null;
            }
            
            // Hub에 전달 (Hub가 암호화 여부를 판단하고 처리)
            // CryptoService.decrypt()가 null이면 원본 데이터를 반환하므로 여기서는 그냥 반환
            String result = cryptoService.decrypt(data, maskPolicyName, maskPolicyUid);
            
            if (decryptAnnotation.enableLogging()) {
                log.info("🔓 Hub 처리 완료: {} → {} (maskPolicyName={}, maskPolicyUid={})", 
                        data.substring(0, Math.min(20, data.length())) + "...", 
                        result != null ? result.substring(0, Math.min(10, result.length())) + "..." : "null",
                        maskPolicyName, maskPolicyUid);
            }
            return result;
        }
        
        // Collection 타입인 경우 (List, Set 등) 각 요소에 대해 재귀적으로 복호화
        if (obj instanceof Collection) {
            Collection<?> collection = (Collection<?>) obj;
            for (Object item : collection) {
                if (item != null) {
                    processDecryption(item, decryptAnnotation);
                }
            }
            return obj;
        }
        
        // 객체인 경우 필드별 복호화
        List<FieldDetector.FieldInfo> fields = FieldDetector.detectDecryptFields(
            obj, decryptAnnotation.fields(), decryptAnnotation.fieldTypes());
        
        // 마스킹 정책 정보 추출
        String maskPolicyName = decryptAnnotation.maskPolicyName();
        String maskPolicyUid = decryptAnnotation.maskPolicyUid();
        if (maskPolicyName != null && maskPolicyName.trim().isEmpty()) {
            maskPolicyName = null;
        }
        if (maskPolicyUid != null && maskPolicyUid.trim().isEmpty()) {
            maskPolicyUid = null;
        }
        
        // fields가 지정된 경우, 지정된 필드명 목록 생성
        Set<String> specifiedFieldNames = new HashSet<>();
        if (decryptAnnotation.fields().length > 0) {
            specifiedFieldNames.addAll(Arrays.asList(decryptAnnotation.fields()));
        }
        
        for (FieldDetector.FieldInfo fieldInfo : fields) {
            Object fieldValue = fieldInfo.getValue(obj);
            if (fieldValue instanceof String) {
                String data = (String) fieldValue;
                
                // 마스킹 정책 결정 (필드 레벨 우선, 없으면 메서드 레벨)
                String fieldMaskPolicyName = null;
                String fieldMaskPolicyUid = null;
                
                if (fieldInfo.getDecryptField() != null) {
                    String fieldMaskName = fieldInfo.getDecryptField().maskPolicyName();
                    String fieldMaskUid = fieldInfo.getDecryptField().maskPolicyUid();
                    if (fieldMaskName != null && !fieldMaskName.trim().isEmpty()) {
                        fieldMaskPolicyName = fieldMaskName;
                    }
                    if (fieldMaskUid != null && !fieldMaskUid.trim().isEmpty()) {
                        fieldMaskPolicyUid = fieldMaskUid;
                    }
                } else if (specifiedFieldNames.isEmpty() || specifiedFieldNames.contains(fieldInfo.getFieldName())) {
                    fieldMaskPolicyName = maskPolicyName;
                    fieldMaskPolicyUid = maskPolicyUid;
                }
                
                // DB에서 조회한 암호화 데이터 + 정책명 + 마스크 정책명 → Hub → 복호화/마스킹된 데이터
                String result = cryptoService.decrypt(data, fieldMaskPolicyName, fieldMaskPolicyUid);
                if (result == null) {
                    result = data; // 복호화 실패 시 원본 데이터 유지
                }
                fieldInfo.setValue(obj, result);
                
                if (decryptAnnotation.enableLogging()) {
                    log.info("🔓 필드 Hub 처리 완료: {}.{} = {} → {} (maskPolicyName={}, maskPolicyUid={})", 
                            obj.getClass().getSimpleName(), fieldInfo.getFieldName(),
                            data.substring(0, Math.min(20, data.length())) + "...", 
                            result != null ? result.substring(0, Math.min(10, result.length())) + "..." : "null",
                            fieldMaskPolicyName, fieldMaskPolicyUid);
                }
            }
        }
        
        // 필드 값을 변경했지만, handleResultForReadOnly에서 처리하므로 여기서는 detach 불필요
        // (중복 detach 방지)
        
        return obj;
    }
    
    /**
     * 복호화 결과를 readOnly로 설정하고 detach 처리
     */
    private void handleResultForReadOnly(Object result, Object em) {
        if (result == null || em == null) {
            return;
        }
        
        // Stream으로 변환하여 처리
        java.util.stream.Stream<Object> stream;
        if (result instanceof Collection) {
            stream = ((Collection<?>) result).stream().map(e -> (Object) e);
        } else if (result instanceof java.util.Optional) {
            java.util.Optional<?> opt = (java.util.Optional<?>) result;
            stream = opt.isPresent() ? java.util.stream.Stream.of(opt.get()) : java.util.stream.Stream.empty();
        } else {
            stream = java.util.stream.Stream.of(result);
        }
        
        stream.forEach(entity -> {
            if (entity == null) {
                return;
            }
            
            // JPA 엔티티인지 확인
            if (!isJpaEntity(entity)) {
                return;
            }
            
            Class<?> entityClass = entity.getClass();
            
            // ① Hibernate Session으로 readOnly 설정 시도
            Object session = getHibernateSession(em, entity);
            if (session != null) {
                try {
                    Method setReadOnlyMethod = session.getClass().getMethod("setReadOnly", Object.class, boolean.class);
                    setReadOnlyMethod.invoke(session, entity, true);
                    log.debug("✅ 엔티티 readOnly 설정 성공: {}", entityClass.getSimpleName());
                } catch (Exception e) {
                    log.debug("⚠️ setReadOnly 실패 (무시): {}", e.getMessage());
                }
            }
            
            // ② 최후의 보루: detach 1회
            try {
                Method detachMethod = em.getClass().getMethod("detach", Object.class);
                detachMethod.invoke(em, entity);
                log.debug("✅ 엔티티 detach 성공: {}", entityClass.getSimpleName());
            } catch (Exception e) {
                log.debug("⚠️ 엔티티 detach 실패 (무시): {}", e.getMessage());
            }
        });
    }
    
    /**
     * JPA 엔티티인지 확인
     */
    private boolean isJpaEntity(Object obj) {
        if (obj == null) {
            return false;
        }
        
        Class<?> entityClass = obj.getClass();
        
        // javax.persistence.Entity 확인
        try {
            Class<?> javaxEntity = Class.forName("javax.persistence.Entity");
            Annotation annotation = entityClass.getAnnotation((Class<? extends Annotation>) javaxEntity);
            if (annotation != null) {
                return true;
            }
        } catch (ClassNotFoundException | ClassCastException e) {
            // javax.persistence가 없는 경우
        }
        
        // jakarta.persistence.Entity 확인
        try {
            Class<?> jakartaEntity = Class.forName("jakarta.persistence.Entity");
            Annotation annotation = entityClass.getAnnotation((Class<? extends Annotation>) jakartaEntity);
            if (annotation != null) {
                return true;
            }
        } catch (ClassNotFoundException | ClassCastException e) {
            // jakarta.persistence가 없는 경우
        }
        
        return false;
    }
    
    /**
     * Hibernate Session을 안전하게 획득 (3가지 경로 시도)
     */
    private Object getHibernateSession(Object em, Object entity) {
        if (em == null) {
            return null;
        }
        
        // 경로 1: 현재 EntityManager에서 unwrap
        try {
            Method unwrapMethod = em.getClass().getMethod("unwrap", Class.class);
            Class<?> sessionClass = Class.forName("org.hibernate.Session");
            Object session = unwrapMethod.invoke(em, sessionClass);
            if (session != null) {
                log.debug("✅ Hibernate Session 획득 성공 (경로 1: unwrap)");
                return session;
            }
        } catch (Exception e) {
            log.debug("⚠️ Session unwrap 실패 (경로 1): {}", e.getMessage());
        }
        
        // 경로 2: EntityManagerFactory에서 SessionFactory 획득 후 getCurrentSession
        try {
            if (applicationContext != null) {
                Object emf = applicationContext.getBean("entityManagerFactory");
                if (emf != null) {
                    Method unwrapMethod = emf.getClass().getMethod("unwrap", Class.class);
                    Class<?> sessionFactoryClass = Class.forName("org.hibernate.SessionFactory");
                    Object sessionFactory = unwrapMethod.invoke(emf, sessionFactoryClass);
                    if (sessionFactory != null) {
                        Method getCurrentSessionMethod = sessionFactory.getClass().getMethod("getCurrentSession");
                        Object session = getCurrentSessionMethod.invoke(sessionFactory);
                        if (session != null) {
                            log.debug("✅ Hibernate Session 획득 성공 (경로 2: SessionFactory.getCurrentSession)");
                            return session;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("⚠️ SessionFactory.getCurrentSession 실패 (경로 2): {}", e.getMessage());
        }
        
        // 경로 3: JpaContext 사용 (Spring Data JPA가 있는 경우)
        try {
            if (applicationContext != null) {
                Object jpaContext = applicationContext.getBean("jpaContext");
                if (jpaContext != null) {
                    Class<?> entityClass = entity instanceof Class ? (Class<?>) entity : entity.getClass();
                    Method getEntityManagerMethod = jpaContext.getClass().getMethod("getEntityManagerByManagedType", Class.class);
                    Object managedEm = getEntityManagerMethod.invoke(jpaContext, entityClass);
                    if (managedEm != null) {
                        Method unwrapMethod = managedEm.getClass().getMethod("unwrap", Class.class);
                        Class<?> sessionClass = Class.forName("org.hibernate.Session");
                        Object session = unwrapMethod.invoke(managedEm, sessionClass);
                        if (session != null) {
                            log.debug("✅ Hibernate Session 획득 성공 (경로 3: JpaContext)");
                            return session;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("⚠️ JpaContext 사용 실패 (경로 3): {}", e.getMessage());
        }
        
        return null;
    }
    
    /**
     * 복호화 후 엔티티를 세션에서 분리하여 UPDATE 방지 (레거시 메서드, 호환성 유지)
     */
    private void detachEntities(Object obj) {
        if (obj == null) {
            return;
        }
        
        // Optional 타입인 경우 내부 값을 추출하여 처리
        if (obj instanceof java.util.Optional) {
            java.util.Optional<?> optional = (java.util.Optional<?>) obj;
            if (optional.isPresent()) {
                detachEntities(optional.get());
            }
            return;
        }
        
        try {
            // JPA 엔티티인지 확인
            Class<?> entityClass = obj.getClass();
            boolean isEntity = false;
            
            // javax.persistence.Entity 확인
            try {
                Class<?> javaxEntity = Class.forName("javax.persistence.Entity");
                Annotation annotation = entityClass.getAnnotation((Class<? extends Annotation>) javaxEntity);
                if (annotation != null) {
                    isEntity = true;
                }
            } catch (ClassNotFoundException | ClassCastException e) {
                // javax.persistence가 없는 경우
            }
            
            // jakarta.persistence.Entity 확인
            if (!isEntity) {
                try {
                    Class<?> jakartaEntity = Class.forName("jakarta.persistence.Entity");
                    Annotation annotation = entityClass.getAnnotation((Class<? extends Annotation>) jakartaEntity);
                    if (annotation != null) {
                        isEntity = true;
                    }
                } catch (ClassNotFoundException | ClassCastException e) {
                    // jakarta.persistence가 없는 경우
                }
            }
            
            if (isEntity) {
                // EntityManager를 리플렉션으로 가져오기
                Object em = getTransactionalEntityManager();
                if (em != null) {
                    try {
                        // Hibernate Session으로 unwrap하여 readOnly 설정
                        try {
                            // EntityManager.unwrap(Session.class) 호출
                            Method unwrapMethod = em.getClass().getMethod("unwrap", Class.class);
                            Class<?> sessionClass = Class.forName("org.hibernate.Session");
                            Object session = unwrapMethod.invoke(em, sessionClass);
                            
                            if (session != null) {
                                // session.setReadOnly(obj, true) - 이 인스턴스는 flush 대상 제외
                                Method setReadOnlyMethod = session.getClass().getMethod("setReadOnly", Object.class, boolean.class);
                                setReadOnlyMethod.invoke(session, obj, true);
                                log.debug("✅ 엔티티 readOnly 설정 성공: {}", entityClass.getSimpleName());
                                
                                // FlushMode를 MANUAL로 설정하여 자동 flush 방지
                                try {
                                    Class<?> flushModeClass = Class.forName("org.hibernate.FlushMode");
                                    Object manualFlushMode = flushModeClass.getField("MANUAL").get(null);
                                    Method setFlushModeMethod = session.getClass().getMethod("setHibernateFlushMode", flushModeClass);
                                    setFlushModeMethod.invoke(session, manualFlushMode);
                                    log.debug("✅ FlushMode MANUAL 설정 완료");
                                } catch (Exception e) {
                                    log.debug("⚠️ FlushMode 설정 실패 (무시): {}", e.getMessage());
                                }
                            }
                        } catch (Exception e) {
                            log.debug("⚠️ Hibernate Session unwrap 실패 (JPA만 사용): {}", e.getMessage());
                        }
                        
                        // entityManager.detach(obj) 호출 (추가 안전장치)
                        try {
                            Method detachMethod = em.getClass().getMethod("detach", Object.class);
                            detachMethod.invoke(em, obj);
                            log.debug("✅ 엔티티 세션 분리 성공: {}", entityClass.getSimpleName());
                        } catch (Exception e) {
                            log.debug("⚠️ 엔티티 detach 실패 (무시): {}", e.getMessage());
                        }
                    } catch (Exception e) {
                        log.warn("⚠️ 엔티티 readOnly 설정 실패: {} - {}", entityClass.getSimpleName(), e.getMessage());
                    }
                } else {
                    log.debug("⚠️ EntityManager를 찾을 수 없어 엔티티 세션 분리 실패: {}", entityClass.getSimpleName());
                }
            } else {
                log.debug("JPA 엔티티가 아님: {}", entityClass.getSimpleName());
            }
        } catch (Exception e) {
            // JPA가 없는 환경에서는 무시
            log.trace("엔티티 세션 분리 실패: {}", e.getMessage());
        }
        
        // Collection 타입인 경우 각 요소에 대해 재귀적으로 처리
        if (obj instanceof Collection) {
            Collection<?> collection = (Collection<?>) obj;
            for (Object item : collection) {
                if (item != null) {
                    detachEntities(item);
                }
            }
        }
    }
    
    /**
     * 트랜잭션에 바인딩된 EntityManager를 획득 (중요: createEntityManager() 사용 금지)
     */
    private Object getTransactionalEntityManager() {
        if (applicationContext == null) {
            log.debug("ApplicationContext가 없어 EntityManager를 가져올 수 없습니다");
            return null;
        }
        
        // ⚠️ 주의: EntityManagerFactory.createEntityManager()는 트랜잭션에 바인딩되지 않은 새 인스턴스를 생성
        // Spring의 EntityManager는 프록시이며, 트랜잭션 경계 내에서만 실제 Session에 접근 가능
        
        try {
            // 방법 1: 직접 EntityManager 빈 찾기 (가장 안전)
            try {
                Object em = applicationContext.getBean("entityManager");
                if (em != null) {
                    log.debug("✅ EntityManager 빈 찾기 성공");
                    return em;
                }
            } catch (Exception e) {
                log.debug("entityManager 빈을 찾을 수 없습니다: {}", e.getMessage());
            }
            
            // 방법 2: 타입으로 찾기 (Jakarta Persistence 우선)
            try {
                Class<?> entityManagerType = Class.forName("jakarta.persistence.EntityManager");
                Object em = applicationContext.getBean(entityManagerType);
                if (em != null) {
                    log.debug("✅ Jakarta EntityManager 타입으로 찾기 성공");
                    return em;
                }
            } catch (Exception e) {
                log.debug("Jakarta EntityManager 타입으로 찾기 실패: {}", e.getMessage());
            }
            
            // 방법 3: javax.persistence (하위 호환성)
            try {
                Class<?> entityManagerType = Class.forName("javax.persistence.EntityManager");
                Object em = applicationContext.getBean(entityManagerType);
                if (em != null) {
                    log.debug("✅ javax EntityManager 타입으로 찾기 성공");
                    return em;
                }
            } catch (Exception e) {
                log.debug("javax EntityManager 타입으로 찾기 실패: {}", e.getMessage());
            }
            
        } catch (Exception e) {
            log.debug("EntityManager 가져오기 실패: {}", e.getMessage());
        }
        
        return null;
    }
}
