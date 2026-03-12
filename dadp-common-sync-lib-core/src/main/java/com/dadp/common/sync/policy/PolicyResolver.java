package com.dadp.common.sync.policy;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;

/**
 * 정책 리졸버
 * 
 * 테이블.컬럼 → 정책명 자동 매핑을 수행합니다.
 * 규칙 기반, 카탈로그 기반, 허용리스트 기반 매핑을 지원합니다.
 * 
 * Hub가 다운되어도 동작할 수 있도록 영구 저장소를 사용합니다.
 * 
 * @author DADP Development Team
 * @version 5.0.9
 * @since 2025-11-07
 */
public class PolicyResolver {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(PolicyResolver.class);
    
    // 싱글톤 인스턴스 (기본 경로 사용)
    private static volatile PolicyResolver defaultInstance = null;
    private static final Object singletonLock = new Object();
    
    // 캐시: 테이블.컬럼 → 정책명
    private final Map<String, String> policyCache = new ConcurrentHashMap<>();

    // 정책 속성 캐시: policyName → PolicyAttributes (useIv/usePlain)
    private final Map<String, PolicyAttributes> policyAttributeCache = new ConcurrentHashMap<>();

    // 현재 정책 버전 (instanceId 단위 전역 버전)
    private volatile Long currentVersion = null;
    
    // 영구 저장소 (Hub 다운 시에도 사용)
    private final PolicyMappingStorage storage;
    
    /**
     * 기본 저장 디렉토리 조회
     * 시스템 프로퍼티 또는 환경 변수에서 읽고, 없으면 기본값 사용
     * 
     * @return 저장 디렉토리 경로
     */
    private static String getDefaultStorageDir() {
        // 1. 시스템 프로퍼티 확인 (dadp.storage.dir)
        String storageDir = System.getProperty("dadp.storage.dir");
        if (storageDir != null && !storageDir.trim().isEmpty()) {
            return storageDir;
        }
        
        // 2. 환경 변수 확인 (DADP_STORAGE_DIR)
        storageDir = System.getenv("DADP_STORAGE_DIR");
        if (storageDir != null && !storageDir.trim().isEmpty()) {
            return storageDir;
        }
        
        // 3. 기본값 사용 (앱 구동 위치/.dadp-wrapper)
        return System.getProperty("user.dir") + "/.dadp-wrapper";
    }
    
    /**
     * 싱글톤 인스턴스 조회 (기본 경로 사용)
     * 기본 경로는 시스템 프로퍼티(dadp.storage.dir) 또는 환경 변수(DADP_STORAGE_DIR)로 설정 가능
     * 
     * @return 싱글톤 PolicyResolver 인스턴스
     */
    public static PolicyResolver getInstance() {
        if (defaultInstance == null) {
            synchronized (singletonLock) {
                if (defaultInstance == null) {
                    defaultInstance = new PolicyResolver();
                }
            }
        }
        return defaultInstance;
    }
    
    /**
     * 기본 생성자 (영구 저장소 자동 초기화)
     * 기본 경로는 시스템 프로퍼티(dadp.storage.dir) 또는 환경 변수(DADP_STORAGE_DIR)로 설정 가능
     */
    public PolicyResolver() {
        this(getDefaultStorageDir(), "policy-mappings.json");
    }
    
    /**
     * 커스텀 저장소 경로 지정
     * 
     * @param storageDir 저장 디렉토리
     * @param fileName 파일명
     */
    public PolicyResolver(String storageDir, String fileName) {
        this.storage = new PolicyMappingStorage(storageDir, fileName);
        // 저장된 매핑 정보 로드 (Hub 다운 시에도 사용)
        loadMappingsFromStorage();
    }
    
    /**
     * PolicyMappingStorage를 직접 받는 생성자
     */
    public PolicyResolver(PolicyMappingStorage storage) {
        this.storage = storage;
        // 저장된 매핑 정보 로드 (Hub 다운 시에도 사용)
        loadMappingsFromStorage();
    }
    
    /**
     * 영구 저장소에서 매핑 정보 로드
     */
    private void loadMappingsFromStorage() {
        Map<String, String> storedMappings = storage.loadMappings();
        if (!storedMappings.isEmpty()) {
            policyCache.putAll(storedMappings);
            // 저장된 버전 정보도 로드
            Long storedVersion = storage.loadVersion();
            if (storedVersion != null) {
                this.currentVersion = storedVersion;
            } else {
                // 버전이 없으면 0으로 초기화 (첫 실행 시)
                this.currentVersion = 0L;
                log.debug("No version info in persistent storage, initializing to 0");
            }
            // 정책 속성도 로드
            Map<String, PolicyAttributes> storedAttributes = storage.loadPolicyAttributes();
            if (storedAttributes != null && !storedAttributes.isEmpty()) {
                policyAttributeCache.putAll(storedAttributes);
                log.debug("Policy attributes loaded from persistent storage: {} entries", storedAttributes.size());
            }
            log.debug("Policy mappings loaded from persistent storage: {} mappings, version={}",
                    storedMappings.size(), this.currentVersion);
        } else {
            // 매핑이 없어도 버전은 0으로 초기화 (첫 실행 시)
            this.currentVersion = 0L;
            log.debug("No policy mappings in persistent storage (will load from Hub), initializing version=0");
        }
    }
    
    /**
     * 정책명 조회
     * 
     * @param datasourceId 데이터소스 ID (NEW)
     * @param schemaName 스키마명 (NEW)
     * @param tableName 테이블명
     * @param columnName 컬럼명
     * @return 정책명 (없으면 null)
     */
    public String resolvePolicy(String datasourceId, String schemaName, String tableName, String columnName) {
        // 통일된 키 형식: datasourceId : schemaName.tableName.columnName
        String key;
        if (datasourceId != null && !datasourceId.trim().isEmpty()) {
            key = datasourceId + ":" + schemaName + "." + tableName + "." + columnName;
        } else {
            // datasourceId가 없으면 schema.table.column 형식 (하위 호환성)
            if (schemaName != null && !schemaName.trim().isEmpty()) {
                key = schemaName + "." + tableName + "." + columnName;
            } else {
                key = tableName + "." + columnName;
            }
        }
        
        // 디버깅: 정책 조회 시도 키 로그 출력
        log.trace("Policy lookup: key={}, datasourceId={}, schemaName={}, tableName={}, columnName={}",
                key, datasourceId, schemaName, tableName, columnName);
        
        // Hub에서 로드한 매핑 정보만 사용 (캐시에서 조회)
        String policy = policyCache.get(key);
        
        if (policy != null) {
            log.trace("Policy cache hit: {} -> {}", key, policy);
            return policy;
        }

        // 이미 대문자로 저장된 정책 매핑도 찾을 수 있도록 소문자로 변환한 키로도 조회 시도
        String lowerKey = key.toLowerCase();
        if (!lowerKey.equals(key)) {
            policy = policyCache.get(lowerKey);
            if (policy != null) {
                log.trace("Policy cache hit (lowercase): {} -> {}", lowerKey, policy);
                return policy;
            }
        }
        
        // 하위 호환성: datasourceId가 없으면 기존 형식 시도
        if (datasourceId == null || datasourceId.trim().isEmpty()) {
            if (schemaName != null && !schemaName.trim().isEmpty()) {
                String fallbackKey = schemaName + "." + tableName + "." + columnName;
                policy = policyCache.get(fallbackKey);
                if (policy != null) {
                    log.trace("Policy cache hit (fallback): {} -> {}", fallbackKey, policy);
                    return policy;
                }
                // fallback 키도 소문자로 변환해서 조회 시도
                String fallbackLowerKey = fallbackKey.toLowerCase();
                if (!fallbackLowerKey.equals(fallbackKey)) {
                    policy = policyCache.get(fallbackLowerKey);
                    if (policy != null) {
                        log.trace("Policy cache hit (fallback lowercase): {} -> {}", fallbackLowerKey, policy);
                        return policy;
                    }
                }
            }
            String fallbackKey2 = tableName + "." + columnName;
            policy = policyCache.get(fallbackKey2);
            if (policy != null) {
                log.trace("Policy cache hit (fallback2): {} -> {}", fallbackKey2, policy);
                return policy;
            }
            // fallback2 키도 소문자로 변환해서 조회 시도
            String fallback2LowerKey = fallbackKey2.toLowerCase();
            if (!fallback2LowerKey.equals(fallbackKey2)) {
                policy = policyCache.get(fallback2LowerKey);
                if (policy != null) {
                    log.trace("Policy cache hit (fallback2 lowercase): {} -> {}", fallback2LowerKey, policy);
                    return policy;
                }
            }
        }
        
        // 정책 매핑이 없으면 null 반환 (로그 출력 없음: 암호화 비대상일 수 있음)
        return null;
    }
    
    /**
     * 정책명 조회 (하위 호환성: databaseName, tableName, columnName 형식)
     * 
     * @param databaseName 데이터베이스/스키마명 (null 가능)
     * @param tableName 테이블명
     * @param columnName 컬럼명
     * @return 정책명 (없으면 null)
     * @deprecated datasourceId와 schemaName을 포함한 resolvePolicy(String, String, String, String) 사용 권장
     */
    @Deprecated
    public String resolvePolicy(String databaseName, String tableName, String columnName) {
        return resolvePolicy(null, databaseName != null ? databaseName : "", tableName, columnName);
    }
    
    /**
     * 정책명 조회 (하위 호환성: table.column 형식)
     * 
     * @param tableName 테이블명
     * @param columnName 컬럼명
     * @return 정책명 (없으면 null)
     * @deprecated databaseName을 포함한 resolvePolicy(String, String, String) 사용 권장
     */
    @Deprecated
    public String resolvePolicy(String tableName, String columnName) {
        return resolvePolicy(null, tableName, columnName);
    }
    
    /**
     * 규칙 기반 정책 매핑
     * 컬럼명 패턴으로 매핑 (email, phone 등)
     */
    private String resolveByRules(String tableName, String columnName) {
        String columnLower = columnName.toLowerCase();
        
        // 이메일 패턴
        if (columnLower.contains("email") || columnLower.contains("mail")) {
            return "dadp";
        }
        
        // 전화번호 패턴
        if (columnLower.contains("phone") || columnLower.contains("tel") || columnLower.contains("mobile")) {
            return "dadp";
        }
        
        // 주민등록번호/주민번호 패턴
        if (columnLower.contains("ssn") || columnLower.contains("rrn") || columnLower.contains("resident")) {
            return "pii";
        }
        
        // 이름 패턴
        if (columnLower.contains("name") && !columnLower.contains("username")) {
            return "dadp";
        }
        
        // 주소 패턴
        if (columnLower.contains("address") || columnLower.contains("addr")) {
            return "dadp";
        }
        
        return null;
    }
    
    /**
     * 정책 매핑 캐시 갱신
     * Hub API로부터 최신 매핑 정보를 받아 캐시를 갱신하고 영구 저장소에 저장합니다.
     * 
     * @param mappings 정책 매핑 맵 (테이블.컬럼 → 정책명, null 가능)
     *                 키가 스키마 정보(table.column)이고, 값이 null이면 스키마는 있지만 정책이 없는 상태
     * @param version 정책 버전 (null 가능)
     */
    public void refreshMappings(Map<String, String> mappings, Long version) {
        log.trace("Policy mapping cache refresh started: {} mappings, version={}", mappings.size(), version);
        policyCache.clear();
        policyCache.putAll(mappings);
        
        // 버전 정보 저장 (version이 null이면 0으로 초기화)
        // 재등록 후 버전이 0으로 초기화되므로 0도 유효한 버전으로 처리
        if (version != null) {
            this.currentVersion = version;
            log.debug("Policy version updated: version={}", version);
        } else {
            // Hub에서 버전을 받지 못한 경우 0으로 초기화 (첫 실행 시나 재등록 시)
            this.currentVersion = 0L;
            log.debug("No version info received from Hub (version=null), initializing to 0");
        }
        
        // 영구 저장소에 저장 (Hub 다운 시에도 사용 가능하도록)
        // 버전이 null이어도 매핑 정보는 저장 (버전은 별도로 저장)
        boolean saved = storage.saveMappings(mappings, version);
        if (saved) {
            log.debug("Policy mappings persisted: {} mappings, version={}", mappings.size(), version);
        } else {
            log.warn("Policy mappings persistence failed (using memory cache only)");
        }
        
        log.trace("Policy mapping cache refresh completed");
    }
    
    /**
     * 정책 매핑 캐시 갱신 (정책 속성 포함)
     * Hub가 정책 스냅샷에 useIv/usePlain을 포함하여 내려줄 때 사용합니다.
     *
     * @param mappings 정책 매핑 맵 (테이블.컬럼 → 정책명)
     * @param attributes 정책 속성 맵 (정책명 → PolicyAttributes)
     * @param version 정책 버전
     */
    public void refreshMappings(Map<String, String> mappings, Map<String, PolicyAttributes> attributes, Long version) {
        refreshMappings(mappings, version);

        // 정책 속성 캐시 갱신
        if (attributes != null && !attributes.isEmpty()) {
            policyAttributeCache.clear();
            policyAttributeCache.putAll(attributes);
            log.debug("Policy attributes cache refreshed: {} policies", attributes.size());

            // 영구 저장소에도 저장
            storage.saveMappings(mappings, attributes, version);
        }
    }

    /**
     * 검색용 암호화가 필요한지 판단 (로컬 캐시 기반)
     *
     * useIv=false AND usePlain=false → 고정 IV 전체 암호화 → Engine 호출 필요 (true)
     * 그 외 → 검색 암호화 불필요 (false)
     *
     * 속성이 캐시에 없으면 기본값(useIv=true, usePlain=false)을 적용하여 false 반환.
     * 이는 구버전 Hub에서 속성이 내려오지 않는 경우와 호환됩니다.
     *
     * @param policyName 정책명
     * @return true: Engine 호출 필요 (고정 IV 전체 암호화), false: 평문 반환 (Engine 호출 불필요)
     */
    public boolean isSearchEncryptionNeeded(String policyName) {
        if (policyName == null) {
            return false;
        }
        PolicyAttributes attrs = policyAttributeCache.get(policyName);
        if (attrs == null) {
            // 속성 없음 → 기본값(useIv=true, usePlain=false) → 검색 암호화 불필요
            return false;
        }
        boolean useIv = attrs.getUseIv() != null ? attrs.getUseIv() : true;
        boolean usePlain = attrs.getUsePlain() != null ? attrs.getUsePlain() : false;
        // 고정 IV + 전체 암호화 → 검색 암호화 필요
        return !useIv && !usePlain;
    }

    /**
     * 정책 매핑 캐시 갱신 (하위 호환성: 버전 없음)
     *
     * @param mappings 정책 매핑 맵 (테이블.컬럼 → 정책명)
     * @deprecated refreshMappings(Map, Long) 사용 권장
     */
    @Deprecated
    public void refreshMappings(Map<String, String> mappings) {
        refreshMappings(mappings, null);
    }
    
    
    /**
     * 현재 정책 버전 조회
     * 
     * @return 정책 버전 (없으면 null)
     */
    public Long getCurrentVersion() {
        return currentVersion;
    }
    
    /**
     * 정책 버전 설정 (메모리만 업데이트, 영구저장소 저장은 refreshMappings에서 수행)
     * 
     * @param version 정책 버전
     */
    public void setCurrentVersion(Long version) {
        this.currentVersion = version;
    }
    
    /**
     * 정책 매핑 캐시에 추가
     * 
     * @param databaseName 데이터베이스/스키마명 (null 가능)
     * @param tableName 테이블명
     * @param columnName 컬럼명
     * @param policyName 정책명
     */
    public void addMapping(String databaseName, String tableName, String columnName, String policyName) {
        String key;
        if (databaseName != null && !databaseName.trim().isEmpty()) {
            key = databaseName + "." + tableName + "." + columnName;
        } else {
            key = tableName + "." + columnName;
        }
        policyCache.put(key, policyName);
        log.trace("Policy mapping added: {} -> {}", key, policyName);
    }
    
    /**
     * 정책 매핑 캐시에 추가 (하위 호환성: table.column 형식)
     * 
     * @deprecated databaseName을 포함한 addMapping(String, String, String, String) 사용 권장
     */
    @Deprecated
    public void addMapping(String tableName, String columnName, String policyName) {
        addMapping(null, tableName, columnName, policyName);
    }
    
    /**
     * 정책 매핑 캐시에서 제거
     * 
     * @param databaseName 데이터베이스/스키마명 (null 가능)
     * @param tableName 테이블명
     * @param columnName 컬럼명
     */
    public void removeMapping(String databaseName, String tableName, String columnName) {
        String key;
        if (databaseName != null && !databaseName.trim().isEmpty()) {
            key = databaseName + "." + tableName + "." + columnName;
        } else {
            key = tableName + "." + columnName;
        }
        policyCache.remove(key);
        log.trace("Policy mapping removed: {}", key);
    }
    
    /**
     * 정책 매핑 캐시에서 제거 (하위 호환성: table.column 형식)
     * 
     * @deprecated databaseName을 포함한 removeMapping(String, String, String) 사용 권장
     */
    @Deprecated
    public void removeMapping(String tableName, String columnName) {
        removeMapping(null, tableName, columnName);
    }
    
    /**
     * 정책 매핑 캐시 초기화
     */
    public void clearCache() {
        policyCache.clear();
        log.trace("Policy mapping cache cleared");
    }
    
    /**
     * 영구 저장소에서 매핑 정보 다시 로드
     * Hub 연결 실패 시 호출하여 저장된 정보 사용
     */
    public void reloadFromStorage() {
        Map<String, String> storedMappings = storage.loadMappings();
        if (!storedMappings.isEmpty()) {
            policyCache.clear();
            policyCache.putAll(storedMappings);
            Long storedVersion = storage.loadVersion();
            if (storedVersion != null) {
                this.currentVersion = storedVersion;
            }
            log.debug("Policy mappings reloaded from persistent storage: {} mappings, version={}",
                    storedMappings.size(), storedVersion);
        } else {
            log.warn("No policy mappings in persistent storage");
        }
    }
    
    /**
     * 영구 저장소 경로 조회
     * 
     * @return 저장 경로
     */
    public String getStoragePath() {
        return storage.getStoragePath();
    }
    
    /**
     * 모든 정책 매핑 조회 (스키마 정책명 업데이트용)
     *
     * @return 정책 매핑 맵 (schema.table.column → policyName)
     */
    public Map<String, String> getAllMappings() {
        return new HashMap<>(policyCache);
    }

    /**
     * 정책 속성 (useIv, usePlain)
     *
     * 정책 생성 후 불변이므로 캐시 무효화 불필요.
     * Hub가 정책 스냅샷에 포함하여 전달합니다.
     */
    public static class PolicyAttributes {
        private Boolean useIv;
        private Boolean usePlain;

        public PolicyAttributes() {
        }

        public PolicyAttributes(Boolean useIv, Boolean usePlain) {
            this.useIv = useIv;
            this.usePlain = usePlain;
        }

        public Boolean getUseIv() {
            return useIv;
        }

        public void setUseIv(Boolean useIv) {
            this.useIv = useIv;
        }

        public Boolean getUsePlain() {
            return usePlain;
        }

        public void setUsePlain(Boolean usePlain) {
            this.usePlain = usePlain;
        }
    }
}

