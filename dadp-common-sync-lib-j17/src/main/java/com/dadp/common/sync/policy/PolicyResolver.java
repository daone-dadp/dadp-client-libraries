package com.dadp.common.sync.policy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 정책 리졸버
 * 
 * 테이블.컬럼 → 정책명 자동 매핑을 수행합니다.
 * Hub가 다운되어도 동작할 수 있도록 영구 저장소를 사용합니다.
 * 
 * WRAPPER와 AOP 모두 사용 가능하도록 설계되었습니다.
 * - WRAPPER: datasourceId:schema.table.column 형식 사용
 * - AOP: schema.table.column 또는 table.column 형식 사용
 * 
 * @author DADP Development Team
 * @version 5.0.4
 * @since 2025-12-30
 */
public class PolicyResolver {
    
    private static final Logger log = LoggerFactory.getLogger(PolicyResolver.class);
    
    // 캐시: key → 정책명
    // WRAPPER: datasourceId:schema.table.column
    // AOP: schema.table.column 또는 table.column
    private final Map<String, String> policyCache = new ConcurrentHashMap<>();
    
    // 현재 정책 버전
    private volatile Long currentVersion = null;
    
    // 영구 저장소 (Hub 다운 시에도 사용)
    private final PolicyMappingStorage storage;
    
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
            // 저장된 버전 정보도 로드
            Long storedVersion = storage.loadVersion();
            if (storedVersion != null) {
                this.currentVersion = storedVersion;
            }
            log.info("📂 영구 저장소에서 정책 매핑 로드 완료: {}개 매핑, version={}", 
                    storedMappings.size(), storedVersion);
        } else {
            log.debug("📋 영구 저장소에 정책 매핑 정보 없음 (Hub에서 로드 예정)");
        }
    }
    
    /**
     * 정책명 조회 (WRAPPER용)
     * datasourceId:schema.table.column 형식
     * 
     * @param datasourceId 데이터소스 ID (WRAPPER용, AOP는 null)
     * @param schemaName 스키마명
     * @param tableName 테이블명
     * @param columnName 컬럼명
     * @return 정책명 (없으면 null)
     */
    public String resolvePolicy(String datasourceId, String schemaName, String tableName, String columnName) {
        if (datasourceId != null && !datasourceId.trim().isEmpty()) {
            String key = datasourceId + ":" + schemaName + "." + tableName + "." + columnName;
            String policy = policyCache.get(key);
            if (policy != null) {
                log.debug("✅ 정책 캐시 적중: {} → {}", key, policy);
                return policy;
            }
        }
        
        // Fallback: schema.table.column
        return resolvePolicy(schemaName, tableName, columnName);
    }
    
    /**
     * 정책명 조회 (AOP용)
     * schema.table.column 또는 table.column 형식
     * 
     * @param schemaName 스키마명
     * @param tableName 테이블명
     * @param columnName 컬럼명
     * @return 정책명 (없으면 null)
     */
    public String resolvePolicy(String schemaName, String tableName, String columnName) {
        // 우선순위 1: schema.table.column
        if (schemaName != null && !schemaName.trim().isEmpty()) {
            String key1 = schemaName + "." + tableName + "." + columnName;
            String policy = policyCache.get(key1);
            if (policy != null) {
                log.debug("✅ 정책 캐시 적중: {} → {}", key1, policy);
                return policy;
            } else {
                log.debug("📋 정책 캐시 미적중: {} (캐시 크기: {})", key1, policyCache.size());
                // 디버그: 캐시에 있는 모든 키 출력 (매핑이 있는 경우)
                if (log.isTraceEnabled() && !policyCache.isEmpty()) {
                    log.trace("📋 캐시에 있는 모든 정책 매핑 키: {}", policyCache.keySet());
                }
            }
        }
        
        // 우선순위 2: table.column (하위 호환성)
        String key2 = tableName + "." + columnName;
        String policy = policyCache.get(key2);
        if (policy != null) {
            log.debug("✅ 정책 캐시 적중 (fallback): {} → {}", key2, policy);
            return policy;
        } else {
            log.debug("📋 정책 캐시 미적중 (fallback): {}", key2);
        }
        
        // 정책 매핑이 없으면 null 반환
        return null;
    }
    
    /**
     * 정책 매핑 캐시 갱신
     * Hub API로부터 최신 매핑 정보를 받아 캐시를 갱신하고 영구 저장소에 저장합니다.
     * 
     * @param mappings 정책 매핑 맵 (key → 정책명)
     * @param version 정책 버전 (null 가능)
     */
    public void refreshMappings(Map<String, String> mappings, Long version) {
        log.info("🔄 정책 매핑 캐시 갱신 시작: {}개 매핑, version={}", mappings.size(), version);
        if (!mappings.isEmpty()) {
            log.info("📋 캐시에 저장될 정책 매핑 키 목록: {}", mappings.keySet());
        }
        policyCache.clear();
        policyCache.putAll(mappings);
        
        // 버전 정보 저장
        if (version != null) {
            this.currentVersion = version;
        }
        
        // 영구 저장소에 저장 (Hub 다운 시에도 사용 가능하도록)
        boolean saved = storage.saveMappings(mappings, version);
        if (saved) {
            log.info("💾 정책 매핑 정보 영구 저장 완료: {}개 매핑, version={}", mappings.size(), version);
        } else {
            log.warn("⚠️ 정책 매핑 정보 영구 저장 실패 (메모리 캐시만 사용)");
        }
        
        log.trace("✅ 정책 매핑 캐시 갱신 완료");
    }
    
    /**
     * 정책 매핑 캐시 갱신 (버전 없음)
     * 
     * @param mappings 정책 매핑 맵 (key → 정책명)
     */
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
     * @param key 매핑 키 (datasourceId:schema.table.column 또는 schema.table.column)
     * @param policyName 정책명
     */
    public void addMapping(String key, String policyName) {
        policyCache.put(key, policyName);
        log.trace("➕ 정책 매핑 추가: {} → {}", key, policyName);
    }
    
    /**
     * 정책 매핑 캐시에서 제거
     * 
     * @param key 매핑 키
     */
    public void removeMapping(String key) {
        policyCache.remove(key);
        log.trace("➖ 정책 매핑 제거: {}", key);
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
            Long storedVersion = storage.loadVersion();
            if (storedVersion != null) {
                this.currentVersion = storedVersion;
            }
            log.info("📂 영구 저장소에서 정책 매핑 재로드 완료: {}개 매핑, version={}", 
                    storedMappings.size(), storedVersion);
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

