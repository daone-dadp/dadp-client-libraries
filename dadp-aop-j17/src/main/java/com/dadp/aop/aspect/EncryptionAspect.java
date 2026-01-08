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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 암복호화 AOP Aspect
 * 
 * {@code @Encrypt}, {@code @Decrypt} 어노테이션이 적용된 메서드의 반환값을 자동으로 암복호화합니다.
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
    private com.dadp.aop.config.DadpAopProperties dadpAopProperties;
    
    /**
     * 배치 처리 최소 크기 임계값 조회
     * 환경변수 DADP_AOP_BATCH_MIN_SIZE 또는 설정 파일에서 읽음
     */
    private int getBatchMinSize() {
        if (dadpAopProperties != null) {
            return dadpAopProperties.getAop().getBatchMinSize();
        }
        // 기본값: 100개 필드 데이터
        // 실측 결과: 60개 필드 데이터 기준 배치 처리(1.3초)가 개별 처리(0.44초)보다 느림
        String envMinSize = System.getenv("DADP_AOP_BATCH_MIN_SIZE");
        if (envMinSize != null && !envMinSize.trim().isEmpty()) {
            try {
                return Integer.parseInt(envMinSize.trim());
            } catch (NumberFormatException e) {
                // 파싱 실패 시 기본값 사용
            }
        }
        return 100;
    }
    
    /**
     * 배치 처리 최대 크기 제한 조회
     * 환경변수 DADP_AOP_BATCH_MAX_SIZE 또는 설정 파일에서 읽음
     */
    private int getBatchMaxSize() {
        if (dadpAopProperties != null) {
            return dadpAopProperties.getAop().getBatchMaxSize();
        }
        // 기본값: 10,000개 필드 데이터
        String envMaxSize = System.getenv("DADP_AOP_BATCH_MAX_SIZE");
        if (envMaxSize != null && !envMaxSize.trim().isEmpty()) {
            try {
                return Integer.parseInt(envMaxSize.trim());
            } catch (NumberFormatException e) {
                // 파싱 실패 시 기본값 사용
            }
        }
        return 10000;
    }
    
    
    /**
     * 로깅이 활성화된 경우에만 DEBUG 레벨 로그 출력
     */
    private void debugIfEnabled(boolean enabled, String message, Object... args) {
        if (enabled) {
            log.debug(message, args);
        }
    }
    
    /**
     * 로깅이 활성화된 경우에만 INFO 레벨 로그 출력
     */
    private void infoIfEnabled(boolean enabled, String message, Object... args) {
        if (enabled) {
            log.info(message, args);
        }
    }
    
    /**
     * 로깅이 활성화된 경우에만 WARN 레벨 로그 출력
     */
    private void warnIfEnabled(boolean enabled, String message, Object... args) {
        if (enabled) {
            log.warn(message, args);
        }
    }
    
    @Autowired(required = false)
    private ApplicationContext applicationContext;
    
    @Autowired(required = false)
    private com.dadp.aop.metadata.EncryptionMetadataInitializer encryptionMetadataInitializer;
    
    @Autowired(required = false)
    private com.dadp.common.sync.policy.PolicyResolver policyResolver;
    
    // EntityManager는 런타임에 리플렉션으로 가져오기 (JPA가 있는 경우에만)
    private Object entityManager;
    
    /**
     * {@code @Encrypt} 어노테이션이 적용된 메서드 처리
     * 
     * 메서드 실행 전에 파라미터를 암호화하고, 실행 후 반환값도 암호화합니다.
     * 
     * @param joinPoint AOP 조인 포인트
     * @return 암호화 처리된 메서드 실행 결과
     * @throws Throwable 메서드 실행 중 발생한 예외
     */
    @Around("@annotation(com.dadp.aop.annotation.Encrypt)")
    public Object handleEncrypt(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Encrypt encryptAnnotation = method.getAnnotation(Encrypt.class);
        
        String methodName = method.getName();
        Class<?> declaringClass = method.getDeclaringClass();
        
        // 서비스 메서드인지 리포지토리 메서드인지 구분
        boolean isRepositoryMethod = isRepositoryMethod(declaringClass);
        
        infoIfEnabled(encryptAnnotation.enableLogging(), "✅ [트리거 확인] handleEncrypt 트리거됨: {}.{} (isRepository={})",
                 declaringClass.getSimpleName(), methodName, isRepositoryMethod);
        
        try {
            // 메서드 시그니처에서 파라미터 타입 확인 (복수/단수 판단)
            Class<?>[] paramTypes = method.getParameterTypes();
            boolean hasIterableParam = false;
            for (Class<?> paramType : paramTypes) {
                if (Iterable.class.isAssignableFrom(paramType) || Collection.class.isAssignableFrom(paramType)) {
                    hasIterableParam = true;
                    break;
                }
            }
            
            // saveAll 메서드인지 확인 (메서드 이름으로 판단)
            boolean isSaveAllMethod = "saveAll".equals(methodName) && hasIterableParam;
            
            // 메서드 파라미터 암호화 (저장 전에 암호화하기 위해)
            Object[] args = joinPoint.getArgs();
            if (args != null && (hasIterableParam || isSaveAllMethod)) {
                // 복수인 경우: AOP가 배치 처리하여 Spring Data JPA에 넘김
                for (int i = 0; i < args.length; i++) {
                    if (args[i] != null && args[i] instanceof Collection && !(args[i] instanceof String)) {
                        // Collection을 배치 암호화 처리
                        @SuppressWarnings("unchecked")
                        Collection<Object> collection = (Collection<Object>) args[i];
                        infoIfEnabled(encryptAnnotation.enableLogging(), "🔒 saveAll 배치 암호화 시작: size={}", collection.size());
                        processCollectionEncryption(collection, encryptAnnotation, isRepositoryMethod);
                    } else if (args[i] != null && args[i] instanceof Iterable && !(args[i] instanceof String)) {
                        // Iterable을 List로 변환하여 배치 암호화 처리
                        Iterable<?> iterable = (Iterable<?>) args[i];
                        List<Object> list = new ArrayList<>();
                        for (Object item : iterable) {
                            list.add(item);
                        }
                        infoIfEnabled(encryptAnnotation.enableLogging(), "🔒 saveAll 배치 암호화 시작: size={}", list.size());
                        processCollectionEncryption(list, encryptAnnotation, isRepositoryMethod);
                        // 원본 타입 유지
                        if (args[i] instanceof List) {
                            args[i] = list;
                        } else if (args[i] instanceof java.util.Set) {
                            args[i] = new java.util.HashSet<>(list);
                        } else {
                            args[i] = list;
                        }
                    }
                }
            } else if (args != null) {
                // 단수인 경우: Spring Data JPA가 처리하게 둠 (기본 처리만)
                for (int i = 0; i < args.length; i++) {
                    if (args[i] != null) {
                        Object encryptedArg = processEncryption(args[i], encryptAnnotation, isRepositoryMethod);
                        if (encryptedArg != args[i]) {
                            args[i] = encryptedArg;
                        }
                    }
                }
            }
            
            // 원본 메서드 실행
            Object result = joinPoint.proceed(args);
            
            // 저장 후 반환값은 이미 DB에 저장된 상태이므로 암호화 처리하지 않음
            // 파라미터 암호화만으로 충분함
            return result;
            
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
     * {@code @Decrypt} 어노테이션이 적용된 메서드 처리
     * 
     * 메서드 실행 후 반환값(DB 조회 결과)을 복호화합니다.
     * 파라미터는 복호화하지 않습니다 (DB 조회 메서드이므로 파라미터는 일반적으로 ID나 검색 조건).
     * 
     * @param joinPoint AOP 조인 포인트
     * @return 복호화 처리된 메서드 실행 결과
     * @throws Throwable 메서드 실행 중 발생한 예외
     */
    @Around("@annotation(com.dadp.aop.annotation.Decrypt)")
    public Object handleDecrypt(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Decrypt decryptAnnotation = method.getAnnotation(Decrypt.class);
        
        infoIfEnabled(decryptAnnotation.enableLogging(), "✅ [트리거 확인] handleDecrypt 트리거됨: {}.{}", 
                 method.getDeclaringClass().getSimpleName(), method.getName());
        
        try {
            // 메서드 시그니처에서 반환 타입 확인 (복수/단수 판단)
            Class<?> returnType = method.getReturnType();
            boolean isCollectionReturn = Collection.class.isAssignableFrom(returnType) || 
                                       Iterable.class.isAssignableFrom(returnType);
            
            // Stream 타입 체크 (우선 처리 필요)
            boolean isStreamType = isStreamType(returnType);
            
            // Page/Slice 타입 체크 (Spring Data의 페이징 타입)
            boolean isPageType = false;
            boolean isSliceType = false;
            try {
                Class<?> pageClass = Class.forName("org.springframework.data.domain.Page");
                Class<?> sliceClass = Class.forName("org.springframework.data.domain.Slice");
                isPageType = pageClass.isAssignableFrom(returnType);
                isSliceType = sliceClass.isAssignableFrom(returnType);
            } catch (ClassNotFoundException e) {
                // Spring Data가 없는 환경 (드물지만 안전을 위해)
            }
            
            // @Query(nativeQuery) 감지 (로깅/모니터링용, 복호화는 건너뛰지 않음)
            boolean isNativeQuery = detectNativeQuery(method);
            if (isNativeQuery) {
                debugIfEnabled(decryptAnnotation.enableLogging(), 
                    "📝 네이티브 쿼리 감지: {}.{}", 
                    method.getDeclaringClass().getSimpleName(), method.getName());
            }
            
            // ① 트랜잭션 경계 안에서 FlushMode를 COMMIT으로 설정 (JPA 레벨, Session 없어도 가능)
            Object em = getTransactionalEntityManager();
            if (em != null) {
                try {
                    Class<?> flushModeTypeClass = Class.forName("jakarta.persistence.FlushModeType");
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Object[] enumConstants = flushModeTypeClass.getEnumConstants();
                    Object commitFlushMode = enumConstants[0]; // COMMIT
                    for (Object constant : enumConstants) {
                        if (constant.toString().equals("COMMIT")) {
                            commitFlushMode = constant;
                            break;
                        }
                    }
                    Method setFlushModeMethod = em.getClass().getMethod("setFlushMode", flushModeTypeClass);
                    setFlushModeMethod.invoke(em, commitFlushMode);
                    debugIfEnabled(decryptAnnotation.enableLogging(), "✅ FlushMode COMMIT 설정 완료");
                } catch (Exception e) {
                    debugIfEnabled(decryptAnnotation.enableLogging(), "⚠️ FlushMode 설정 실패 (무시): {}", e.getMessage());
                }
            }
            
            // 원본 메서드 실행 (DB 조회)
            Object result = joinPoint.proceed();
            
            if (result == null) {
                return result;
            }
            
            // ① 복호화 전에 먼저 엔티티를 detach하여 Hibernate 변경 추적 차단
            // 복호화 시 필드 값을 변경하면 Hibernate가 이를 감지하여 UPDATE 쿼리를 실행할 수 있음
            handleResultForReadOnly(result, em);
            
            // ② 반환값 복호화/마스킹 처리 (DB에서 조회한 암호화된 데이터를 복호화)
            // detach 후 복호화하므로 Hibernate가 변경을 감지하지 않음
            Object decryptedResult;
            if (isStreamType && result != null) {
                // Stream 타입인 경우: Stream → List → 복호화 → Stream 변환 (우선 처리)
                decryptedResult = handleStreamDecryption(result, decryptAnnotation, em);
            } else if (isPageType && result != null) {
                // Page 타입인 경우: content를 추출하여 복호화 후 다시 Page로 감싸기
                decryptedResult = processPageDecryption(result, decryptAnnotation, em);
            } else if (isSliceType && result != null) {
                // Slice 타입인 경우: content를 추출하여 복호화 후 다시 Slice로 감싸기
                decryptedResult = processSliceDecryption(result, decryptAnnotation, em);
            } else if (isCollectionReturn && result instanceof Collection) {
                // 복수인 경우: AOP가 배치 처리하여 Spring Data JPA의 내부 처리 막음
                @SuppressWarnings("unchecked")
                Collection<Object> collection = (Collection<Object>) result;
                decryptedResult = processCollectionDecryption(collection, decryptAnnotation, em);
            } else {
                // 단수인 경우: Spring Data JPA가 처리하게 둠 (기본 처리만)
                decryptedResult = processDecryption(result, decryptAnnotation, em);
            }
            
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
     * 리포지토리 메서드인지 확인
     * 
     * @param declaringClass 메서드가 선언된 클래스
     * @return 리포지토리 메서드이면 true, 서비스 메서드이면 false
     */
    private boolean isRepositoryMethod(Class<?> declaringClass) {
        // @Repository 어노테이션이 있는지 확인
        if (declaringClass.isAnnotationPresent(org.springframework.stereotype.Repository.class)) {
            return true;
        }
        
        // 클래스 이름에 "Repository"가 포함되어 있는지 확인
        String className = declaringClass.getSimpleName();
        if (className.contains("Repository") || className.endsWith("Repo")) {
            return true;
        }
        
        // 패키지 경로에 "repository"가 포함되어 있는지 확인
        String packageName = declaringClass.getPackage() != null ? declaringClass.getPackage().getName() : "";
        if (packageName.toLowerCase().contains("repository")) {
            return true;
        }
        
        // JpaRepository를 상속하는지 확인
        try {
            Class<?>[] interfaces = declaringClass.getInterfaces();
            for (Class<?> iface : interfaces) {
                if (iface.getName().contains("Repository") || 
                    iface.getName().contains("JpaRepository") ||
                    iface.getName().contains("CrudRepository")) {
                    return true;
                }
            }
        } catch (Exception e) {
            // 리플렉션 오류 시 무시
        }
        
        return false;
    }
    
    /**
     * 암호화 처리
     * 
     * @param obj 암호화할 객체
     * @param encryptAnnotation @Encrypt 어노테이션
     * @param isRepositoryMethod 리포지토리 메서드인지 여부
     */
    private Object processEncryption(Object obj, Encrypt encryptAnnotation, boolean isRepositoryMethod) {
        if (obj == null) {
            return obj;
        }
        
        // String 타입인 경우 처리
        if (obj instanceof String) {
            // 리포지토리가 아닌 메서드에서는 암호화하지 않음
            if (!isRepositoryMethod) {
                debugIfEnabled(encryptAnnotation.enableLogging(), 
                    "⚠️ @Encrypt는 리포지토리 메서드에서만 사용할 수 있습니다.");
                return obj;
            }
            
            // 리포지토리 메서드인 경우에만 String을 직접 암호화
            // (일반적으로 리포지토리에서는 엔티티 객체를 받지만, 일부 특수한 경우를 위해 유지)
            String data = (String) obj;
            if (cryptoService.isEncryptedData(data)) {
                debugIfEnabled(encryptAnnotation.enableLogging(), "이미 암호화된 데이터입니다: {}", data.substring(0, Math.min(20, data.length())) + "...");
                return data;
            }
            
            // 정책 조회: PolicyResolver 우선, 없으면 null 전달 (Wrapper와 동일)
            // String 타입은 직접 암호화하는 경우이므로 정책 매핑 정보가 없음
            // Engine에서 정책명이 null이면 자동으로 "dadp"로 처리
            String policy = null;
            
            // @Encrypt 어노테이션의 policy()는 deprecated이므로 무시
            // includeStats는 AOP 로깅용이며, 엔진에는 전달하지 않음 (엔진은 항상 자동으로 통계 수집)
            String encryptedData = cryptoService.encrypt(data, policy);
            
            // enableLogging: 기본 로그 출력
            infoIfEnabled(encryptAnnotation.enableLogging(), "🔒 데이터 암호화 완료: {} → {}", 
                        data.substring(0, Math.min(10, data.length())) + "...", 
                        encryptedData.substring(0, Math.min(20, encryptedData.length())) + "...");
            
            // includeStats: 상세 로그 출력 (AOP 레벨에서만, 엔진에 요구하지 않음)
            // enableLogging이 true일 때만 출력
            if (encryptAnnotation.includeStats() && encryptAnnotation.enableLogging()) {
                log.info("📊 [통계] 암호화 수행: policy={}, inputLength={}, outputLength={}, inputPreview={}, outputPreview={}", 
                        encryptAnnotation.policy(),
                        data.length(),
                        encryptedData.length(),
                        data.substring(0, Math.min(20, data.length())) + (data.length() > 20 ? "..." : ""),
                        encryptedData.substring(0, Math.min(30, encryptedData.length())) + (encryptedData.length() > 30 ? "..." : ""));
            }
            
            return encryptedData;
        }
        
        // Collection 또는 Iterable 타입인 경우 개별 처리
        // saveAll()의 파라미터는 Iterable이지만, 실제로는 List(Collection)를 전달
        boolean isCollection = (obj instanceof Collection) || 
                              (obj != null && Collection.class.isAssignableFrom(obj.getClass()));
        boolean isIterable = (obj instanceof Iterable) && !(obj instanceof Collection);
        
        if (isCollection) {
            Collection<?> collection = (Collection<?>) obj;
            if (collection.isEmpty()) {
                return obj;
            }
            
            // 배치 처리: 동일한 필드(동일한 정책)의 데이터를 수집하여 배치 암호화
            return processCollectionEncryption(collection, encryptAnnotation, isRepositoryMethod);
        } else if (isIterable) {
            // Iterable이지만 Collection이 아닌 경우: List로 변환하여 처리
            Iterable<?> iterable = (Iterable<?>) obj;
            List<Object> list = new ArrayList<>();
            for (Object item : iterable) {
                list.add(item);
            }
            if (list.isEmpty()) {
                return obj;
            }
            return processCollectionEncryption(list, encryptAnnotation, isRepositoryMethod);
        }
        
        // 객체인 경우 필드별 개별 암호화
        List<FieldDetector.FieldInfo> fields = FieldDetector.detectEncryptFields(
            obj, encryptAnnotation.fields(), encryptAnnotation.fieldTypes());
        
        // 각 필드별로 개별 암호화
        for (FieldDetector.FieldInfo fieldInfo : fields) {
            // @EncryptField가 없는 필드는 암호화하지 않음 (name 필드 등)
            if (fieldInfo.getEncryptField() == null) {
                continue;
            }
            
            Object fieldValue = fieldInfo.getValue(obj);
            if (fieldValue instanceof String) {
                String data = (String) fieldValue;
                if (cryptoService.isEncryptedData(data)) {
                    continue;
                }
                
                // 정책 조회: PolicyResolver 우선, 없으면 기본 정책 "dadp" 사용
                String policy = null;
                
                // 1. PolicyResolver를 사용하여 정책 조회 (table.column 기반)
                if (policyResolver != null && encryptionMetadataInitializer != null) {
                    String tableName = encryptionMetadataInitializer.getTableName(obj.getClass());
                    if (tableName != null) {
                        // AOP는 스키마 개념이 없으므로 "public" 사용
                        String schemaName = "public";
                        // AOP는 datasourceId가 없음
                        String datasourceId = null;
                        String columnName = fieldInfo.getFieldName();
                        
                        // 정책 조회 시도 전 로그
                        log.debug("🔍 정책 매핑 조회 시도: schema={}, table={}, column={}", 
                                schemaName, tableName, columnName);
                        
                        policy = policyResolver.resolvePolicy(datasourceId, schemaName, tableName, columnName);
                        
                        if (policy != null && !policy.trim().isEmpty()) {
                            log.debug("✅ 정책 매핑 조회 성공: {}.{} → {} (조회 키: {}.{}.{})", 
                                    obj.getClass().getSimpleName(), columnName, policy, 
                                    schemaName, tableName, columnName);
                        } else {
                            log.debug("📋 정책 매핑 조회 실패: {}.{} (조회 키: {}.{}.{})", 
                                    obj.getClass().getSimpleName(), columnName, 
                                    schemaName, tableName, columnName);
                        }
                    } else {
                        log.debug("⚠️ 테이블명을 찾을 수 없음: {}", obj.getClass().getSimpleName());
                    }
                } else {
                    log.debug("⚠️ PolicyResolver 또는 EncryptionMetadataInitializer가 없음");
                }
                
                // 2. PolicyResolver에서 정책을 찾지 못한 경우 null 전달 (Wrapper와 동일)
                // Engine에서 정책명이 null이면 자동으로 "dadp"로 처리
                if (policy == null || policy.trim().isEmpty()) {
                    log.debug("📋 정책 매핑 없음, null 전달 (Engine에서 자동으로 dadp 처리): {}.{}", 
                            obj.getClass().getSimpleName(), fieldInfo.getFieldName());
                    policy = null; // null로 명시적으로 설정
                }
                
                try {
                    String encryptedData = cryptoService.encrypt(data, policy);
                    if (encryptedData != null) {
                        fieldInfo.setValue(obj, encryptedData);
                        
                        infoIfEnabled(encryptAnnotation.enableLogging(), "🔒 필드 암호화 완료: {}.{} = {} → {}", 
                                    obj.getClass().getSimpleName(), fieldInfo.getFieldName(),
                                    data.substring(0, Math.min(10, data.length())) + "...", 
                                    encryptedData.substring(0, Math.min(20, encryptedData.length())) + "...");
                    }
                } catch (Exception e) {
                    log.error("❌ 필드 암호화 실패: {}.{} - {}", 
                            obj.getClass().getSimpleName(), fieldInfo.getFieldName(), e.getMessage());
                }
            }
        }
        
        return obj;
    }
    
    /**
     * 복호화 처리
     */
    private Object processDecryption(Object obj, Decrypt decryptAnnotation, Object em) {
        if (obj == null) {
            return obj;
        }
        
        // Collection 타입인 경우 배치 처리
        boolean isCollection = (obj instanceof Collection) || 
                              (obj != null && Collection.class.isAssignableFrom(obj.getClass()));
        
        infoIfEnabled(decryptAnnotation.enableLogging(), "processDecryption: objType={}, isCollection={}, size={}", 
                obj.getClass().getName(), isCollection, 
                isCollection ? ((Collection<?>) obj).size() : -1);
        
        if (isCollection) {
            Collection<?> collection = (Collection<?>) obj;
            if (collection.isEmpty()) {
                infoIfEnabled(decryptAnnotation.enableLogging(), "processDecryption: Collection이 비어있음");
                return obj;
            }
            
            infoIfEnabled(decryptAnnotation.enableLogging(), "processDecryption: 배치 복호화 시작 - Collection size={}", collection.size());
            // 배치 처리: 동일한 필드의 데이터를 수집하여 배치 복호화
            // (복호화는 데이터 안에 정책 정보가 포함되어 있어 모든 데이터를 한번에 보내면 됨)
            return processCollectionDecryption(collection, decryptAnnotation, null);
        }
        
        // Optional 타입인 경우 내부 값을 추출하여 복호화
        // 먼저 내부 값을 detach한 후 복호화하여 UPDATE 방지
        if (obj instanceof java.util.Optional) {
            java.util.Optional<?> optional = (java.util.Optional<?>) obj;
            if (optional.isPresent()) {
                Object value = optional.get();
                // 복호화 전에 먼저 detach하여 변경 감지 방지
                detachEntities(value);
                Object decryptedValue = processDecryption(value, decryptAnnotation, null);
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
            // includeStats는 AOP 로깅용이며, 엔진에는 전달하지 않음 (엔진은 항상 자동으로 통계 수집)
            String result = cryptoService.decrypt(data, maskPolicyName, maskPolicyUid);
            
            // enableLogging: 기본 로그 출력
            infoIfEnabled(decryptAnnotation.enableLogging(), "🔓 Hub 처리 완료: {} → {} (maskPolicyName={}, maskPolicyUid={})", 
                        data.substring(0, Math.min(20, data.length())) + "...", 
                        result != null ? result.substring(0, Math.min(10, result.length())) + "..." : "null",
                        maskPolicyName, maskPolicyUid);
            
            // includeStats: 상세 로그 출력 (AOP 레벨에서만, 엔진에 요구하지 않음)
            // enableLogging이 true일 때만 출력
            if (decryptAnnotation.includeStats() && decryptAnnotation.enableLogging()) {
                log.info("📊 [통계] 복호화 수행: inputLength={}, outputLength={}, maskPolicyName={}, maskPolicyUid={}, inputPreview={}, outputPreview={}", 
                        data.length(),
                        result != null ? result.length() : 0,
                        maskPolicyName,
                        maskPolicyUid,
                        data.substring(0, Math.min(30, data.length())) + (data.length() > 30 ? "..." : ""),
                        result != null ? (result.substring(0, Math.min(20, result.length())) + (result.length() > 20 ? "..." : "")) : "null");
            }
            
            return result;
        }
        
        
        // 객체인 경우 필드별 개별 복호화
        List<FieldDetector.FieldInfo> fields = FieldDetector.detectDecryptFields(
            obj, decryptAnnotation.fields(), decryptAnnotation.fieldTypes());
        
        if (fields.isEmpty()) {
            return obj;
        }
        
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
        
        // 복호화 전 원본 값 저장 (복원용)
        Map<FieldDetector.FieldInfo, String> originalValues = new HashMap<>();
        
        // 각 필드별로 개별 복호화
        for (FieldDetector.FieldInfo fieldInfo : fields) {
            Object fieldValue = fieldInfo.getValue(obj);
            if (fieldValue instanceof String) {
                String data = (String) fieldValue;
                
                // 원본 값 저장 (복원용)
                originalValues.put(fieldInfo, data);
                
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
                
                // 개별 복호화 수행
                // includeStats는 AOP 로깅용이며, 엔진에는 전달하지 않음 (엔진은 항상 자동으로 통계 수집)
                String result = cryptoService.decrypt(data, fieldMaskPolicyName, fieldMaskPolicyUid);
                if (result != null) {
                    // 복호화된 값을 임시로 설정 (사용자가 접근 가능)
                    fieldInfo.setValue(obj, result);
                    
                    infoIfEnabled(decryptAnnotation.enableLogging(), "🔓 필드 Hub 처리 완료: {}.{} = {} → {} (maskPolicyName={}, maskPolicyUid={})", 
                                obj.getClass().getSimpleName(), fieldInfo.getFieldName(),
                                data.substring(0, Math.min(20, data.length())) + "...", 
                                result.substring(0, Math.min(10, result.length())) + "...",
                                fieldMaskPolicyName, fieldMaskPolicyUid);
                }
            }
        }
        
        // 복호화 완료: 복호화된 값이 필드에 설정되어 사용자가 접근 가능
        // read-only 조회이므로 저장되지 않음
        
        return obj;
    }
    
    /**
     * 배치 복호화를 위한 내부 클래스
     */
    /**
     * 암호화 항목 정보
     */
    @SuppressWarnings("unused")
    private static class EncryptItemInfo {
        Object item;
        List<Integer> fieldIndices; // allDataList의 인덱스 (-1이면 암호화하지 않음)
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
        boolean isCollection = (result instanceof Collection) || 
                              Collection.class.isAssignableFrom(result.getClass());
        if (isCollection) {
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
            @SuppressWarnings("unchecked")
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
            @SuppressWarnings("unchecked")
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
                @SuppressWarnings("unchecked")
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
                    @SuppressWarnings("unchecked")
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
        boolean isCollection = (obj instanceof Collection) || 
                              (obj != null && Collection.class.isAssignableFrom(obj.getClass()));
        if (isCollection) {
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
    
    /**
     * Collection 암호화 배치 처리
     * 동일한 필드(동일한 정책)의 데이터를 수집하여 배치 암호화 수행
     * 
     * 예: saveAll(List<User>) 호출 시
     * - 모든 User의 email 필드(동일 정책)를 수집
     * - batchEncrypt(emailList, policyList) 한 번 호출
     * - 결과를 각 User 객체에 설정
     */
    private Object processCollectionEncryption(Collection<?> collection, Encrypt encryptAnnotation, boolean isRepositoryMethod) {
        if (collection.isEmpty()) {
            return collection;
        }
        
        // 배치 처리 비활성화 옵션 확인 (테스트용)
        // 환경변수 우선, 없으면 System Property 확인
        String disableBatch = System.getenv("DADP_AOP_DISABLE_BATCH");
        if (disableBatch == null) {
            disableBatch = System.getProperty("DADP_AOP_DISABLE_BATCH");
        }
        boolean forceIndividual = disableBatch != null && 
                ("true".equalsIgnoreCase(disableBatch.trim()) || "1".equals(disableBatch.trim()));
        
        if (forceIndividual) {
            infoIfEnabled(encryptAnnotation.enableLogging(), "🔒 배치 처리 비활성화됨 - 개별 처리로 암호화: {}개 항목", collection.size());
            // 개별 처리로 폴백
            for (Object item : collection) {
                if (item != null) {
                    processEncryption(item, encryptAnnotation, isRepositoryMethod);
                }
            }
            return collection;
        }
        
        // 첫 번째 항목으로부터 필드 정보 얻기
        Object firstItem = collection.iterator().next();
        if (firstItem == null) {
            // null 항목이 있으면 개별 처리로 폴백
            for (Object item : collection) {
                if (item != null) {
                    processEncryption(item, encryptAnnotation, isRepositoryMethod);
                }
            }
            return collection;
        }
        
        List<FieldDetector.FieldInfo> fields = FieldDetector.detectEncryptFields(
            firstItem, encryptAnnotation.fields(), encryptAnnotation.fieldTypes());
        
        if (fields.isEmpty()) {
            return collection;
        }
        
        // Collection을 List로 변환 (인덱스 접근 필요)
        List<Object> itemList = new ArrayList<>(collection);
        
        // 각 필드별로 배치 암호화 수행
        for (FieldDetector.FieldInfo fieldInfo : fields) {
            if (fieldInfo.getEncryptField() == null) {
                continue;
            }
            
            String policy = encryptAnnotation.policy();
            if (fieldInfo.getEncryptField() != null) {
                policy = fieldInfo.getEncryptField().policy();
            }
            
            // 동일한 정책을 사용하는 필드의 데이터 수집
            long collectStartTime = System.currentTimeMillis();
            List<String> dataList = new ArrayList<>();
            List<Integer> indexList = new ArrayList<>(); // null이 아닌 항목의 인덱스
            
            for (int i = 0; i < itemList.size(); i++) {
                Object item = itemList.get(i);
                if (item == null) {
                    continue;
                }
                
                Object fieldValue = fieldInfo.getValue(item);
                if (fieldValue instanceof String) {
                    String data = (String) fieldValue;
                    if (!cryptoService.isEncryptedData(data)) {
                        dataList.add(data);
                        indexList.add(i);
                    }
                }
            }
            long collectTime = System.currentTimeMillis() - collectStartTime;
            
            if (dataList.isEmpty()) {
                continue;
            }
            
            // 작은 데이터셋은 개별 처리로 폴백 (배치 오버헤드가 더 큼)
            // 또는 배치 처리 비활성화 옵션이 켜져 있으면 무조건 개별 처리
            // forceIndividual은 메서드 시작 부분에서 이미 선언됨
            int batchMinSize = forceIndividual ? Integer.MAX_VALUE : getBatchMinSize();
            if (dataList.size() < batchMinSize) {
                if (forceIndividual) {
                    infoIfEnabled(encryptAnnotation.enableLogging(), "🔒 배치 처리 비활성화됨 - 개별 처리로 암호화: {}개 필드 데이터 ({}개 항목)", 
                            dataList.size(), itemList.size());
                } else {
                    debugIfEnabled(encryptAnnotation.enableLogging(), "🔒 소규모 데이터 암호화: {}개 필드 데이터 ({}개 항목) - 개별 처리로 폴백", 
                            dataList.size(), itemList.size());
                }
                // 개별 처리로 폴백
                for (int i = 0; i < indexList.size(); i++) {
                    int index = indexList.get(i);
                    Object item = itemList.get(index);
                    if (item != null && i < dataList.size()) {
                        String data = dataList.get(i);
                        String encrypted = cryptoService.encrypt(data, policy);
                        fieldInfo.setValue(item, encrypted);
                    }
                }
                continue;
            }
            
            // 배치 암호화 수행 (동일한 정책이므로 policyList는 모두 동일)
            try {
                List<String> policyList = new ArrayList<>();
                for (int i = 0; i < dataList.size(); i++) {
                    policyList.add(policy);
                }
                
                long engineStartTime = System.currentTimeMillis();
                List<String> encryptedDataList = cryptoService.batchEncrypt(dataList, policyList);
                long engineTime = System.currentTimeMillis() - engineStartTime;
                
                // 결과를 각 항목에 설정 (순서 보장)
                long matchStartTime = System.currentTimeMillis();
                for (int i = 0; i < indexList.size(); i++) {
                    int index = indexList.get(i);
                    Object item = itemList.get(index);
                    if (item != null && i < encryptedDataList.size()) {
                        fieldInfo.setValue(item, encryptedDataList.get(i));
                    }
                }
                long matchTime = System.currentTimeMillis() - matchStartTime;
                
                long totalTime = collectTime + engineTime + matchTime;
                infoIfEnabled(encryptAnnotation.enableLogging(), 
                    "🔒 배치 필드 암호화 완료: {}.{} ({}개 항목, 정책: {}) - 수집: {}ms, 엔진: {}ms, 매칭: {}ms, 총: {}ms", 
                    firstItem.getClass().getSimpleName(), fieldInfo.getFieldName(),
                    dataList.size(), policy, collectTime, engineTime, matchTime, totalTime);
                    
            } catch (Exception e) {
                log.error("❌ 배치 필드 암호화 실패: {}.{} - {}", 
                    firstItem.getClass().getSimpleName(), fieldInfo.getFieldName(), e.getMessage());
                // 실패 시 개별 처리로 폴백
                for (int index : indexList) {
                    Object item = itemList.get(index);
                    if (item != null) {
                        processEncryption(item, encryptAnnotation, isRepositoryMethod);
                    }
                }
            }
        }
        
        return collection;
    }
    
    /**
     * Collection 복호화 배치 처리
     * 모든 필드의 데이터를 수집하여 배치 복호화 수행
     * 
     * 간단한 구현: 데이터만 List로 보내고 결과를 순서대로 매칭
     * 정책/마스크 정보는 엔진에서 데이터 안에 포함된 정보로 처리
     */
    private Object processCollectionDecryption(Collection<?> collection, Decrypt decryptAnnotation, Object em) {
        infoIfEnabled(decryptAnnotation.enableLogging(), "🔓 processCollectionDecryption 시작: size={}", collection.size());
        
        if (collection.isEmpty()) {
            return collection;
        }
        
        // Collection을 List로 변환
        List<Object> itemList = new ArrayList<>(collection);
        
        // 🔥 중요: 복호화 전에 모든 엔티티를 detach하여 read-only 조회에서 UPDATE 방지
        // 복호화로 인한 필드 변경이 Hibernate의 dirty 체크를 트리거하지 않도록 함
        if (em != null) {
            int detachCount = 0;
            for (Object item : itemList) {
                if (item != null && isJpaEntity(item)) {
                    try {
                        Method detachMethod = em.getClass().getMethod("detach", Object.class);
                        detachMethod.invoke(em, item);
                        detachCount++;
                        log.debug("✅ Collection 엔티티 detach 성공: {}", item.getClass().getSimpleName());
                    } catch (Exception e) {
                        log.debug("⚠️ Collection 엔티티 detach 실패 (무시): {}", e.getMessage());
                    }
                }
            }
            infoIfEnabled(decryptAnnotation.enableLogging(), "✅ Collection 엔티티 detach 완료: {}개 항목 중 {}개 detach (복호화 전)", itemList.size(), detachCount);
        }
        
        // 첫 번째 항목으로부터 필드 정보 얻기
        Object firstItem = itemList.stream().filter(item -> item != null).findFirst().orElse(null);
        if (firstItem == null) {
            return collection;
        }
        
        List<FieldDetector.FieldInfo> fields = FieldDetector.detectDecryptFields(
            firstItem, decryptAnnotation.fields(), decryptAnnotation.fieldTypes());
        
        if (fields.isEmpty()) {
            return collection;
        }
        
        // 모든 필드의 데이터를 수집 (원본 값도 함께 저장)
        long collectStartTime = System.currentTimeMillis();
        List<String> allDataList = new ArrayList<>();
        List<FieldMapping> fieldMappings = new ArrayList<>(); // (itemIndex, fieldInfo, dataIndex)
        Map<FieldMapping, String> originalValues = new HashMap<>(); // 원본 암호화 값 저장 (복원용)
        
        for (int itemIndex = 0; itemIndex < itemList.size(); itemIndex++) {
            Object item = itemList.get(itemIndex);
            if (item == null) {
                continue;
            }
            
            for (FieldDetector.FieldInfo fieldInfo : fields) {
                Object fieldValue = fieldInfo.getValue(item);
                if (fieldValue instanceof String) {
                    String data = (String) fieldValue;
                    allDataList.add(data);
                    FieldMapping mapping = new FieldMapping(itemIndex, fieldInfo, allDataList.size() - 1);
                    fieldMappings.add(mapping);
                    // 원본 암호화 값 저장 (복원용)
                    originalValues.put(mapping, data);
                }
            }
        }
        long collectTime = System.currentTimeMillis() - collectStartTime;
        
        if (allDataList.isEmpty()) {
            return collection;
        }
        
        // 배치 처리 비활성화 옵션 확인 (테스트용)
        // 환경변수 우선, 없으면 System Property 확인
        String disableBatch = System.getenv("DADP_AOP_DISABLE_BATCH");
        if (disableBatch == null) {
            disableBatch = System.getProperty("DADP_AOP_DISABLE_BATCH");
        }
        boolean forceIndividual = disableBatch != null && 
                ("true".equalsIgnoreCase(disableBatch.trim()) || "1".equals(disableBatch.trim()));
        
        // 작은 데이터셋은 개별 처리로 폴백 (배치 오버헤드가 더 큼)
        // 또는 배치 처리 비활성화 옵션이 켜져 있으면 무조건 개별 처리
        int batchMinSize = forceIndividual ? Integer.MAX_VALUE : getBatchMinSize();
        if (allDataList.size() < batchMinSize) {
            if (forceIndividual) {
                infoIfEnabled(decryptAnnotation.enableLogging(), "🔓 배치 처리 비활성화됨 - 개별 처리로 복호화: {}개 필드 데이터 ({}개 항목)", 
                        allDataList.size(), itemList.size());
            } else {
                debugIfEnabled(decryptAnnotation.enableLogging(), "🔓 소규모 데이터 복호화: {}개 필드 데이터 ({}개 항목) - 개별 처리로 폴백", 
                        allDataList.size(), itemList.size());
            }
            // 개별 처리로 폴백
            for (Object item : itemList) {
                if (item != null) {
                    processDecryption(item, decryptAnnotation, em);
                }
            }
            return collection;
        }
        
        // 대량 데이터 처리 시 경고 로그
        int batchMaxSize = getBatchMaxSize();
        if (allDataList.size() > batchMaxSize) {
            warnIfEnabled(decryptAnnotation.enableLogging(), "⚠️ 대량 데이터 복호화 감지: {}개 필드 데이터 ({}개 항목) - 청크 단위로 분할 처리합니다.", 
                    allDataList.size(), itemList.size());
        }
        
        // 배치 복호화 수행 (대량 데이터는 청크 단위로 분할 처리)
        try {
            long engineStartTime = System.currentTimeMillis();
            long totalEngineTime = 0;
            long totalMatchTime = 0;
            int chunkCount = 0;
            
            // 청크 단위로 나누어 처리
            for (int chunkStart = 0; chunkStart < allDataList.size(); chunkStart += batchMaxSize) {
                int chunkEnd = Math.min(chunkStart + batchMaxSize, allDataList.size());
                List<String> chunkDataList = allDataList.subList(chunkStart, chunkEnd);
                
                chunkCount++;
                if (chunkCount > 1) {
                    debugIfEnabled(decryptAnnotation.enableLogging(), "🔓 청크 {} 처리 중: {} ~ {} / {}", 
                            chunkCount, chunkStart, chunkEnd - 1, allDataList.size());
                }
                
                // 청크 단위 배치 복호화 수행
                long chunkEngineStart = System.currentTimeMillis();
                List<String> decryptedChunkList = cryptoService.batchDecrypt(
                    chunkDataList, null, null, false);
                long chunkEngineTime = System.currentTimeMillis() - chunkEngineStart;
                totalEngineTime += chunkEngineTime;
                
                // 결과를 순서대로 각 항목에 설정
                // fieldMappings는 dataIndex 순서대로 저장되어 있으므로 직접 접근 가능
                long chunkMatchStart = System.currentTimeMillis();
                for (int i = 0; i < chunkDataList.size(); i++) {
                    int dataIndex = chunkStart + i;
                    if (dataIndex < fieldMappings.size() && i < decryptedChunkList.size()) {
                        FieldMapping mapping = fieldMappings.get(dataIndex);
                        String decrypted = decryptedChunkList.get(i);
                        if (decrypted != null) {
                            Object item = itemList.get(mapping.itemIndex);
                            if (item != null) {
                                mapping.fieldInfo.setValue(item, decrypted);
                            }
                        }
                    }
                }
                long chunkMatchTime = System.currentTimeMillis() - chunkMatchStart;
                totalMatchTime += chunkMatchTime;
            }
            
            long engineTime = totalEngineTime;
            long matchTime = totalMatchTime;
            long totalTime = collectTime + engineTime + matchTime;
            
            infoIfEnabled(decryptAnnotation.enableLogging(), 
                "🔓 배치 복호화 완료: {}개 항목, {}개 필드 데이터 ({}개 청크) - 수집: {}ms, 엔진: {}ms, 매칭: {}ms, 총: {}ms", 
                itemList.size(), allDataList.size(), chunkCount, collectTime, engineTime, matchTime, totalTime);
            
            // 복호화 완료: 복호화된 값이 필드에 설정되어 사용자가 접근 가능
            // read-only 조회이므로 저장되지 않음
                
        } catch (Exception e) {
            log.error("❌ 배치 복호화 실패: {}", e.getMessage(), e);
            // 실패 시 개별 처리로 폴백
            for (Object item : itemList) {
                if (item != null) {
                    processDecryption(item, decryptAnnotation, em);
                }
            }
        }
        
        return collection;
    }
    
    /**
     * Page 타입 복호화 처리
     * 
     * Page의 content를 추출하여 복호화한 후, 복호화된 content로 새로운 Page를 생성하여 반환합니다.
     */
    private Object processPageDecryption(Object pageObj, Decrypt decryptAnnotation, Object em) {
        try {
            // Page 인터페이스 메서드 호출을 위한 리플렉션
            Method getContentMethod = pageObj.getClass().getMethod("getContent");
            @SuppressWarnings("unchecked")
            List<Object> content = (List<Object>) getContentMethod.invoke(pageObj);
            
            if (content == null || content.isEmpty()) {
                return pageObj;
            }
            
            // content 복호화
            @SuppressWarnings("unchecked")
            Collection<Object> collection = (Collection<Object>) content;
            Collection<Object> decryptedContent = (Collection<Object>) processCollectionDecryption(collection, decryptAnnotation, em);
            
            // 복호화된 content로 새로운 Page 생성
            // PageImpl 생성자를 사용하여 새로운 Page 인스턴스 생성
            Class<?> pageImplClass = Class.forName("org.springframework.data.domain.PageImpl");
            Class<?> pageableClass = Class.forName("org.springframework.data.domain.Pageable");
            
            // 원본 Page에서 Pageable과 total 추출
            Method getPageableMethod = pageObj.getClass().getMethod("getPageable");
            Method getTotalElementsMethod = pageObj.getClass().getMethod("getTotalElements");
            
            Object pageable = getPageableMethod.invoke(pageObj);
            long totalElements = ((Number) getTotalElementsMethod.invoke(pageObj)).longValue();
            
            // PageImpl 생성자: List, Pageable, long
            java.lang.reflect.Constructor<?> constructor = pageImplClass.getConstructor(
                List.class, pageableClass, long.class
            );
            
            Object decryptedPage = constructor.newInstance(decryptedContent, pageable, totalElements);
            
            infoIfEnabled(decryptAnnotation.enableLogging(), "✅ Page 복호화 완료: content size={}, total={}", 
                    decryptedContent.size(), totalElements);
            
            return decryptedPage;
            
        } catch (Exception e) {
            log.error("❌ Page 복호화 실패: {}", e.getMessage(), e);
            // 실패 시 원본 반환
            return pageObj;
        }
    }
    
    /**
     * Slice 타입 복호화 처리
     * 
     * Slice의 content를 추출하여 복호화한 후, 복호화된 content로 새로운 Slice를 생성하여 반환합니다.
     */
    private Object processSliceDecryption(Object sliceObj, Decrypt decryptAnnotation, Object em) {
        try {
            // Slice 인터페이스 메서드 호출을 위한 리플렉션
            Method getContentMethod = sliceObj.getClass().getMethod("getContent");
            @SuppressWarnings("unchecked")
            List<Object> content = (List<Object>) getContentMethod.invoke(sliceObj);
            
            if (content == null || content.isEmpty()) {
                return sliceObj;
            }
            
            // content 복호화
            @SuppressWarnings("unchecked")
            Collection<Object> collection = (Collection<Object>) content;
            Collection<Object> decryptedContent = (Collection<Object>) processCollectionDecryption(collection, decryptAnnotation, em);
            
            // 복호화된 content로 새로운 Slice 생성
            // PageImpl을 Slice로 사용 (Slice는 Page의 슈퍼 인터페이스)
            Class<?> pageImplClass = Class.forName("org.springframework.data.domain.PageImpl");
            Class<?> pageableClass = Class.forName("org.springframework.data.domain.Pageable");
            
            // 원본 Slice에서 Pageable 추출
            Method getPageableMethod = sliceObj.getClass().getMethod("getPageable");
            Method hasNextMethod = sliceObj.getClass().getMethod("hasNext");
            
            Object pageable = getPageableMethod.invoke(sliceObj);
            boolean hasNext = (Boolean) hasNextMethod.invoke(sliceObj);
            
            // PageImpl 생성자: List, Pageable, long (hasNext를 고려하여 total 계산)
            long totalElements = hasNext ? (decryptedContent.size() + 1) : decryptedContent.size();
            java.lang.reflect.Constructor<?> constructor = pageImplClass.getConstructor(
                List.class, pageableClass, long.class
            );
            
            Object decryptedSlice = constructor.newInstance(decryptedContent, pageable, totalElements);
            
            infoIfEnabled(decryptAnnotation.enableLogging(), "✅ Slice 복호화 완료: content size={}, hasNext={}", 
                    decryptedContent.size(), hasNext);
            
            return decryptedSlice;
            
        } catch (Exception e) {
            log.error("❌ Slice 복호화 실패: {}", e.getMessage(), e);
            // 실패 시 원본 반환
            return sliceObj;
        }
    }
    
    /**
     * 필드 매핑 정보 (배치 복호화용)
     */
    private static class FieldMapping {
        int itemIndex;
        FieldDetector.FieldInfo fieldInfo;
        int dataIndex;
        
        FieldMapping(int itemIndex, FieldDetector.FieldInfo fieldInfo, int dataIndex) {
            this.itemIndex = itemIndex;
            this.fieldInfo = fieldInfo;
            this.dataIndex = dataIndex;
        }
    }
    
    /**
     * Stream 타입인지 확인
     * 
     * @param returnType 반환 타입
     * @return Stream 타입이면 true
     */
    private boolean isStreamType(Class<?> returnType) {
        try {
            Class<?> streamClass = Class.forName("java.util.stream.Stream");
            return streamClass.isAssignableFrom(returnType);
        } catch (ClassNotFoundException e) {
            // Java 8+ 환경에서는 Stream이 항상 존재하지만, 안전을 위해 예외 처리
            return false;
        }
    }
    
    /**
     * Stream<T> 반환 타입 복호화 처리
     * 
     * Stream을 List로 수집 → 엔티티 detach → 복호화 → 다시 Stream으로 변환
     * 
     * 주의: Stream은 한 번만 소비 가능하므로, AOP에서 collect()하는 순간 이미 소비됨.
     * 반환되는 Stream은 in-memory Stream이며, JPA의 lazy-stream이 아님.
     * 대량 데이터 조회 시 메모리 사용량이 증가할 수 있음.
     * 
     * 중요: read-only 트랜잭션에서 UPDATE 방지를 위해 복호화 전에 엔티티를 detach합니다.
     * 복호화로 인한 필드 변경이 Hibernate의 dirty 체크를 트리거하지 않도록 합니다.
     * 
     * @param result 원본 Stream
     * @param decryptAnnotation @Decrypt 어노테이션
     * @param em EntityManager (엔티티 detach용)
     * @return 복호화된 Stream
     */
    @SuppressWarnings("unchecked")
    private Object handleStreamDecryption(Object result, Decrypt decryptAnnotation, Object em) {
        try {
            java.util.stream.Stream<Object> stream = (java.util.stream.Stream<Object>) result;
            
            // Stream 전체를 리스트로 수집 (한 번만 소비 가능하므로 먼저 수집)
            List<Object> list = stream.collect(Collectors.toList());
            
            if (list.isEmpty()) {
                return java.util.stream.Stream.empty();
            }
            
            // 🔥 중요: 복호화 전에 모든 엔티티를 detach하여 read-only 트랜잭션에서 UPDATE 방지
            // 복호화로 인한 필드 변경이 Hibernate의 dirty 체크를 트리거하지 않도록 함
            if (em != null) {
                for (Object entity : list) {
                    if (entity != null && isJpaEntity(entity)) {
                        try {
                            Method detachMethod = em.getClass().getMethod("detach", Object.class);
                            detachMethod.invoke(em, entity);
                            debugIfEnabled(decryptAnnotation.enableLogging(), 
                                "✅ Stream 엔티티 detach 완료: {}", entity.getClass().getSimpleName());
                        } catch (Exception e) {
                            debugIfEnabled(decryptAnnotation.enableLogging(), 
                                "⚠️ Stream 엔티티 detach 실패 (무시): {}", e.getMessage());
                        }
                    }
                }
                infoIfEnabled(decryptAnnotation.enableLogging(), 
                    "✅ Stream 엔티티 detach 완료: {}개 항목 (복호화 전)", list.size());
            }
            
            // 기존 Collection 배치 복호화 로직 재사용 (이미 detach된 엔티티는 dirty로 마킹되지 않음)
            Collection<Object> decryptedList = 
                    (Collection<Object>) processCollectionDecryption(list, decryptAnnotation, em);
            
            // 복호화된 List를 다시 Stream으로 반환 (in-memory Stream)
            infoIfEnabled(decryptAnnotation.enableLogging(), 
                "✅ Stream 복호화 완료: {}개 항목 (in-memory Stream으로 변환)", 
                decryptedList.size());
            
            return decryptedList.stream();
            
        } catch (Exception e) {
            log.error("❌ Stream 복호화 실패: {}", e.getMessage(), e);
            // 실패 시 원본 반환 (이미 소비된 Stream이므로 빈 Stream 반환)
            return java.util.stream.Stream.empty();
        }
    }
    
    /**
     * @Query(nativeQuery) 어노테이션 감지
     * 
     * 로깅/모니터링용으로만 사용하며, 복호화는 건너뛰지 않음.
     * nativeQuery든 JPQL이든 반환값 처리 방식은 동일함.
     * 
     * @param method 메서드
     * @return nativeQuery이면 true
     */
    private boolean detectNativeQuery(Method method) {
        try {
            // Spring Data JPA의 @Query 어노테이션 확인
            Class<?> queryClass = Class.forName("org.springframework.data.jpa.repository.Query");
            Annotation queryAnnotation = method.getAnnotation((Class<? extends Annotation>) queryClass);
            
            if (queryAnnotation == null) {
                return false;
            }
            
            // nativeQuery 속성 확인
            Method nativeQueryMethod = queryClass.getMethod("nativeQuery");
            return (Boolean) nativeQueryMethod.invoke(queryAnnotation);
            
        } catch (ClassNotFoundException e) {
            // Spring Data JPA가 없는 환경 (드물지만 안전을 위해)
            return false;
        } catch (NoSuchMethodException | java.lang.reflect.InvocationTargetException | IllegalAccessException e) {
            // @Query 어노테이션이 있지만 nativeQuery 속성을 확인할 수 없는 경우
            debugIfEnabled(true, "⚠️ @Query 어노테이션 확인 실패: {}", e.getMessage());
            return false;
        }
    }
}
