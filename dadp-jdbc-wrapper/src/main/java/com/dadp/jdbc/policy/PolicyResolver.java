package com.dadp.jdbc.policy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;

/**
 * 정책 리졸버
 * 
 * 테이블.컬럼 → 정책명 자동 매핑을 수행합니다.
 * 규칙 기반, 카탈로그 기반, 허용리스트 기반 매핑을 지원합니다.
 * 
 * Hub가 다운되어도 동작할 수 있도록 영구 저장소를 사용합니다.
 * 
 * @deprecated 이 클래스는 더 이상 사용되지 않습니다.
 *             대신 {@link com.dadp.common.sync.policy.PolicyResolver}를 사용하세요.
 * 
 * @author DADP Development Team
 * @version 4.8.0
 * @since 2025-11-07
 */
@Deprecated
public class PolicyResolver {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(PolicyResolver.class);
    
    // 캐시: 테이블.컬럼 → 정책명
    private final Map<String, String> policyCache = new ConcurrentHashMap<>();
    
    // 현재 정책 버전 (proxyInstanceId 단위 전역 버전)
    private volatile Long currentVersion = null;
    
    // 영구 저장소 (Hub 다운 시에도 사용)
    private final PolicyMappingStorage storage;
    
    /**
     * 기본 생성자 (영구 저장소 자동 초기화)
     */
    public PolicyResolver() {
        this.storage = new PolicyMappingStorage();
        // 저장된 매핑 정보 로드 (Hub 다운 시에도 사용)
        loadMappingsFromStorage();
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
     * 영구 저장소에서 매핑 정보 로드
     */
    private void loadMappingsFromStorage() {
        Map<String, String> storedMappings = storage.loadMappings();
        if (!storedMappings.isEmpty()) {
            policyCache.putAll(storedMappings);
            log.info("📂 영구 저장소에서 정책 매핑 로드 완료: {}개 매핑", storedMappings.size());
        } else {
            log.debug("📋 영구 저장소에 정책 매핑 정보 없음 (Hub에서 로드 예정)");
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
        log.debug("🔍 정책 조회 시도: key={}, datasourceId={}, schemaName={}, tableName={}, columnName={}", 
                key, datasourceId, schemaName, tableName, columnName);
        
        // Hub에서 로드한 매핑 정보만 사용 (캐시에서 조회)
        String policy = policyCache.get(key);
        
        if (policy != null) {
            log.debug("✅ 정책 캐시 적중: {} → {}", key, policy);
            return policy;
        }
        
        // 하위 호환성: datasourceId가 없으면 기존 형식 시도
        if (datasourceId == null || datasourceId.trim().isEmpty()) {
            if (schemaName != null && !schemaName.trim().isEmpty()) {
                String fallbackKey = schemaName + "." + tableName + "." + columnName;
                policy = policyCache.get(fallbackKey);
                if (policy != null) {
                    log.debug("✅ 정책 캐시 적중 (fallback): {} → {}", fallbackKey, policy);
                    return policy;
                }
            }
            String fallbackKey2 = tableName + "." + columnName;
            policy = policyCache.get(fallbackKey2);
            if (policy != null) {
                log.debug("✅ 정책 캐시 적중 (fallback2): {} → {}", fallbackKey2, policy);
                return policy;
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
     * @param mappings 정책 매핑 맵 (테이블.컬럼 → 정책명)
     * @param version 정책 버전 (null 가능)
     */
    public void refreshMappings(Map<String, String> mappings, Long version) {
        log.trace("🔄 정책 매핑 캐시 갱신 시작: {}개 매핑, version={}", mappings.size(), version);
        policyCache.clear();
        policyCache.putAll(mappings);
        
        // 버전 정보 저장
        if (version != null) {
            this.currentVersion = version;
        }
        
        // 영구 저장소에 저장 (Hub 다운 시에도 사용 가능하도록)
        boolean saved = storage.saveMappings(mappings);
        if (saved) {
            log.info("💾 정책 매핑 정보 영구 저장 완료: {}개 매핑, version={}", mappings.size(), version);
        } else {
            log.warn("⚠️ 정책 매핑 정보 영구 저장 실패 (메모리 캐시만 사용)");
        }
        
        log.trace("✅ 정책 매핑 캐시 갱신 완료");
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
     * 정책 버전 설정
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
        log.trace("➕ 정책 매핑 추가: {} → {}", key, policyName);
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
        log.trace("➖ 정책 매핑 제거: {}", key);
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
        log.trace("🧹 정책 매핑 캐시 초기화");
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
            log.info("📂 영구 저장소에서 정책 매핑 재로드 완료: {}개 매핑", storedMappings.size());
        } else {
            log.warn("⚠️ 영구 저장소에 정책 매핑 정보 없음");
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
}

