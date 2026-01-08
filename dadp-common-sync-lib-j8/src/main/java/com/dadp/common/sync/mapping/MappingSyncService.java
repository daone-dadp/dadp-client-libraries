package com.dadp.common.sync.mapping;

import com.dadp.common.sync.http.HttpClientAdapter;
import com.dadp.common.sync.http.Java8HttpClientAdapterFactory;
import com.dadp.common.sync.policy.PolicyResolver;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;

/**
 * 매핑 동기화 서비스
 * 
 * Hub로부터 정책 매핑 정보를 가져와서 PolicyResolver에 저장합니다.
 * Java 버전에 따라 적절한 HTTP 클라이언트를 자동으로 선택합니다.
 * 
 * WRAPPER와 AOP 모두 사용 가능하도록 설계되었습니다.
 * - WRAPPER: apiBasePath = "/hub/api/v1/proxy", datasourceId 사용
 * - AOP: apiBasePath = "/hub/api/v1/aop", datasourceId = null
 * 
 * @author DADP Development Team
 * @version 5.0.4
 * @since 2025-12-31
 */
public class MappingSyncService {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(MappingSyncService.class);
    
    private final String hubUrl;
    private final String hubId;  // Hub가 발급한 고유 ID (X-DADP-TENANT 헤더에 사용)
    private final String alias;  // 사용자가 설정한 instanceId (별칭, 검색/표시용)
    private final String apiBasePath;  // API Base Path: "/hub/api/v1/proxy" 또는 "/hub/api/v1/aop"
    private final String datasourceId;  // Datasource ID (재등록을 위해 필요)
    private final HttpClientAdapter httpClient;
    private final ObjectMapper objectMapper;
    private final PolicyResolver policyResolver;
    
    public MappingSyncService(String hubUrl, String hubId, String alias, String datasourceId, PolicyResolver policyResolver) {
        this(hubUrl, hubId, alias, datasourceId, "/hub/api/v1/proxy", policyResolver);
    }
    
    public MappingSyncService(String hubUrl, String hubId, String alias, String datasourceId, String apiBasePath, PolicyResolver policyResolver) {
        this.hubUrl = hubUrl;
        this.hubId = hubId;
        this.alias = alias;
        this.datasourceId = datasourceId;
        this.apiBasePath = apiBasePath != null ? apiBasePath : "/hub/api/v1/proxy";
        // Java 버전에 따라 적절한 HTTP 클라이언트 자동 선택
        this.httpClient = Java8HttpClientAdapterFactory.create(5000, 10000);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.policyResolver = policyResolver;
    }
    
    /**
     * Hub에서 매핑 변경 여부 확인 (경량 요청)
     * Proxy의 version을 함께 전달하여 Hub가 동기화 상태를 업데이트할 수 있게 함.
     * 미등록 상태 감지를 위해 alias와 datasourceId도 함께 전달합니다.
     *
     * @param version Proxy가 가진 현재 매핑 버전 (null이면 미전달)
     * @return 변경사항이 있으면 true, 없으면 false
     */
    public boolean checkMappingChange(Long version) {
        return checkMappingChange(version, null);
    }
    
    /**
     * Hub에서 매핑 변경 여부 확인 (경량 요청)
     * Proxy의 version을 함께 전달하여 Hub가 동기화 상태를 업데이트할 수 있게 함.
     * 미등록 상태 감지를 위해 alias와 datasourceId도 함께 전달합니다.
     *
     * @param version Proxy가 가진 현재 매핑 버전 (null이면 미전달)
     * @param reregisteredHubId 재등록된 hubId를 저장할 배열 (재등록 발생 시 새 hubId 저장)
     * @return 변경사항이 있으면 true, 없으면 false
     */
    public boolean checkMappingChange(Long version, String[] reregisteredHubId) {
        try {
            // instanceId 파라미터는 alias를 사용 (정책 매핑은 alias 기준으로 동기화)
            // Hub의 checkMappingChange가 alias로 첫 번째 인스턴스를 찾아 정책을 확인
            String instanceIdParam;
            if (alias != null && !alias.trim().isEmpty()) {
                instanceIdParam = alias;  // alias를 instanceId 파라미터로 전달
            } else if (hubId != null && !hubId.trim().isEmpty()) {
                instanceIdParam = hubId;  // alias가 없으면 hubId를 fallback으로 사용
            } else {
                instanceIdParam = "";  // 둘 다 없으면 빈 문자열 (Hub에서 에러 발생)
            }
            String checkUrl = hubUrl + apiBasePath + "/mappings/check?instanceId=" + instanceIdParam;
            
            // alias는 항상 별도 파라미터로 전달 (동일한 별칭을 가진 앱들이 동일한 정책 매핑을 받기 위해)
            if (alias != null && !alias.trim().isEmpty()) {
                checkUrl += "&alias=" + java.net.URLEncoder.encode(alias, "UTF-8");
            }
            if (datasourceId != null && !datasourceId.trim().isEmpty()) {
                checkUrl += "&datasourceId=" + java.net.URLEncoder.encode(datasourceId, "UTF-8");
            }
            
            log.trace("🔗 Hub 매핑 변경 확인 URL: {}", checkUrl);
            
            // 헤더에 hubId와 버전 포함 (Hub는 헤더에서 hubId와 버전을 읽음)
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            if (hubId != null && !hubId.trim().isEmpty()) {
                headers.put("X-DADP-TENANT", hubId);  // Hub가 헤더에서 hubId를 받을 수 있도록
            }
            if (version != null) {
                headers.put("X-Current-Version", String.valueOf(version));  // 버전은 헤더로 전송
            }
            
            // Java 버전에 따라 적절한 HTTP 클라이언트 사용
            URI uri = URI.create(checkUrl);
            HttpClientAdapter.HttpResponse response = httpClient.get(uri, headers);
            
            int statusCode = response.getStatusCode();
            String responseBody = response.getBody();
            
            // 304 Not Modified: 버전 동일 -> 동기화 불필요
            if (statusCode == 304) {
                return false;
            }
            
            // 404 Not Found: hubId를 찾을 수 없음 -> 예외를 던져서 상위에서 등록 처리
            if (statusCode == 404) {
                // 404는 예외로 변환하여 상위에서 처리하도록 함
                throw new IllegalStateException("Hub에서 hubId를 찾을 수 없음 (404 Not Found): 등록이 필요합니다");
            }
            
            // 200 OK: 버전 변경 -> 동기화 필요 (무조건 true 반환)
            if (statusCode >= 200 && statusCode < 300 && responseBody != null) {
                // ApiResponse<Map<String, Object>> 형태로 파싱
                CheckMappingChangeResponse checkResponse = objectMapper.readValue(responseBody, CheckMappingChangeResponse.class);
                if (checkResponse != null && checkResponse.isSuccess() && checkResponse.getData() != null) {
                    // data가 Map인 경우 (재등록 정보 포함)
                    if (checkResponse.getData() instanceof java.util.Map) {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> dataMap = (java.util.Map<String, Object>) checkResponse.getData();
                        Boolean reregistered = (Boolean) dataMap.get("reregistered");
                        
                        // 재등록 발생 시 로그 출력 및 hubId 저장
                        if (reregistered != null && reregistered && reregisteredHubId != null) {
                            String newHubId = (String) dataMap.get("hubId");
                            if (newHubId != null) {
                                reregisteredHubId[0] = newHubId;
                                log.info("🔄 Hub에서 재등록 발생: hubId={}", newHubId);
                            } else {
                                log.info("🔄 Hub에서 재등록 발생 (hubId 정보 없음)");
                            }
                        }
                    }
                }
                // 200 OK를 받으면 무조건 true 반환 (갱신 필요)
                return true;
            }
            
            // 기타 상태 코드는 false 반환
            log.warn("⚠️ 매핑 변경 확인 실패: HTTP {}, URL={}", statusCode, checkUrl);
            return false;
        } catch (IllegalStateException e) {
            // 400 응답으로 인한 초기화 필요 예외는 다시 던짐
            throw e;
        } catch (IOException e) {
            log.warn("⚠️ 매핑 변경 확인 실패: {}", e.getMessage());
            return false; // 실패 시 false 반환 (다음 확인 시 재시도)
        }
    }
    
    /**
     * Hub에서 정책 스냅샷을 가져와서 PolicyResolver에 저장
     * 
     * @param currentVersion Proxy가 가지고 있는 현재 버전 (null이면 최신 버전 반환)
     * @return 로드된 매핑 개수
     */
    public int loadPolicySnapshotFromHub(Long currentVersion) {
        try {
            log.trace("🔄 Hub에서 정책 스냅샷 로드 시작: hubId={}, alias={}, currentVersion={}", 
                hubId, alias, currentVersion);
            
            // instanceId 파라미터는 alias를 사용 (정책 매핑은 alias 기준으로 동기화)
            // Hub의 getPolicySnapshotByAlias가 alias로 첫 번째 인스턴스를 찾아 정책을 반환
            String instanceIdParam;
            if (alias != null && !alias.trim().isEmpty()) {
                instanceIdParam = alias;  // alias를 instanceId 파라미터로 전달
            } else if (hubId != null && !hubId.trim().isEmpty()) {
                instanceIdParam = hubId;  // alias가 없으면 hubId를 fallback으로 사용
            } else {
                instanceIdParam = "";  // 둘 다 없으면 빈 문자열 (Hub에서 에러 발생)
            }
            String policiesUrl = hubUrl + apiBasePath + "/policies?instanceId=" + instanceIdParam;
            
            // alias는 항상 별도 파라미터로도 전달 (Hub의 getPolicySnapshotByAlias에서 사용)
            if (alias != null && !alias.trim().isEmpty()) {
                policiesUrl += "&alias=" + java.net.URLEncoder.encode(alias, "UTF-8");
            }
            
            // 헤더에 hubId와 버전 포함 (버전은 헤더로 전송)
            Map<String, String> headers = new HashMap<>();
            if (hubId != null && !hubId.trim().isEmpty()) {
                headers.put("X-DADP-TENANT", hubId);  // Hub가 헤더에서 hubId를 받을 수 있도록
            }
            if (currentVersion != null) {
                headers.put("X-Current-Version", String.valueOf(currentVersion));  // 버전은 헤더로 전송
            }
            
            URI uri = URI.create(policiesUrl);
            HttpClientAdapter.HttpResponse response = httpClient.get(uri, headers);
            
            int statusCode = response.getStatusCode();
            
            // 304 Not Modified: 변경 없음 -> 아무것도 하지 않음 (현재 버전 유지)
            if (statusCode == 304) {
                return 0;
            }
            
            // 404 Not Found: hubId를 찾을 수 없음 (등록되지 않은 hubId) -> 재등록 필요
            // 클라이언트가 404를 받으면 재등록을 시도하도록 함
            if (statusCode == 404) {
                log.debug("📋 Hub에서 hubId를 찾을 수 없음 (등록되지 않은 hubId): alias={}, 다음 주기에서 재등록 시도", alias);
                // 0 반환하여 다음 주기에서 재등록 시도
                return 0;
            }
            
            // 200 OK: 버전 변경 -> 동기화 필요
            if (statusCode >= 200 && statusCode < 300 && response.getBody() != null) {
                PolicySnapshotResponse snapshotResponse = objectMapper.readValue(
                    response.getBody(), PolicySnapshotResponse.class);
                
                if (snapshotResponse != null && snapshotResponse.isSuccess() && snapshotResponse.getData() != null) {
                    PolicySnapshot snapshot = snapshotResponse.getData();
                    
                    // PolicyResolver 형식으로 변환 (datasourceId:schema.table.column → 정책명)
                    Map<String, String> policyMap = new HashMap<>();
                    for (PolicyMapping mapping : snapshot.getMappings()) {
                        // enabled가 true이고 policyName이 있는 경우만 추가
                        if (mapping.isEnabled() && mapping.getPolicyName() != null && !mapping.getPolicyName().trim().isEmpty()) {
                            // WRAPPER: datasourceId:schema.table.column
                            // AOP: schema.table.column
                            String key;
                            if (datasourceId != null && !datasourceId.trim().isEmpty() && mapping.getDatasourceId() != null && !mapping.getDatasourceId().trim().isEmpty()) {
                                key = mapping.getDatasourceId() + ":" + 
                                      mapping.getSchemaName() + "." + 
                                      mapping.getTableName() + "." + 
                                      mapping.getColumnName();
                            } else {
                                // AOP: schema.table.column
                                key = mapping.getSchemaName() + "." + 
                                      mapping.getTableName() + "." + 
                                      mapping.getColumnName();
                            }
                            policyMap.put(key, mapping.getPolicyName());
                            log.info("📋 정책 매핑 로드: {} → {}", key, mapping.getPolicyName());
                        } else {
                            log.debug("⏭️ 정책 매핑 건너뜀: enabled={}, policyName={}, datasourceId={}, schema={}, table={}, column={}", 
                                    mapping.isEnabled(), mapping.getPolicyName(), 
                                    mapping.getDatasourceId(), mapping.getSchemaName(), 
                                    mapping.getTableName(), mapping.getColumnName());
                        }
                    }
                    
                    // PolicyResolver에 반영 (영구 저장소에도 자동 저장됨, 버전 정보 포함)
                    Long snapshotVersion = snapshot.getVersion();
                    if (snapshotVersion == null) {
                        log.warn("⚠️ Hub에서 받은 정책 스냅샷에 버전 정보가 없음 (version=null), 매핑={}개", policyMap.size());
                    }
                    policyResolver.refreshMappings(policyMap, snapshotVersion);
                    
                    log.info("✅ Hub에서 정책 스냅샷 로드 완료: version={}, {}개 매핑 (영구 저장소에 저장됨)", 
                        snapshotVersion, policyMap.size());
                    return policyMap.size();
                }
            }
            
            // 기타 상태 코드
            log.warn("⚠️ Hub에서 정책 스냅샷 로드 실패: HTTP {}", statusCode);
            // Hub 통신 장애는 알림 제거 (받는 주체가 Hub이므로)
            return 0;
            
        } catch (IllegalStateException e) {
            // 400 응답으로 인한 초기화 필요 예외는 다시 던짐
            throw e;
        } catch (IOException e) {
            // 연결 실패는 예측 가능한 문제이므로 WARN 레벨로 처리 (정책 준수)
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (errorMsg.contains("Connection refused") || errorMsg.contains("ConnectException")) {
                log.warn("⚠️ Hub에서 정책 스냅샷 로드 실패: {} (Hub 연결 불가)", errorMsg);
            } else {
                // 예측 불가능한 문제만 ERROR로 처리
                log.error("❌ Hub에서 정책 스냅샷 로드 실패: {}", errorMsg, e);
            }
            // Hub 통신 장애는 알림 제거 (받는 주체가 Hub이므로)
            // Hub 연결 실패 시 영구 저장소에서 로드 시도
            log.info("📂 Hub 연결 실패, 영구 저장소에서 정책 매핑 정보 로드 시도");
            policyResolver.reloadFromStorage();
            return 0;
        }
    }
    
    /**
     * 정책 매핑 동기화 및 버전 업데이트 (AOP와 Wrapper 공통 로직)
     * 
     * 문서 플로우 (docs/design/proxy-sync-optimization-flow.md)에 따라 구현:
     * 1. Hub에서 정책 스냅샷 로드
     * 2. PolicyResolver에 반영 (200 응답 시에만 매핑 및 버전 저장)
     * 3. 동기화 완료 후 Hub에 버전 업데이트
     * 
     * @param currentVersion 현재 버전 (null이면 최신 버전 조회)
     * @return 동기화된 매핑 개수 (0이면 동기화 실패 또는 변경 없음)
     */
    public int syncPolicyMappingsAndUpdateVersion(Long currentVersion) {
        // 1. Hub에서 정책 스냅샷 로드
        int loadedCount = loadPolicySnapshotFromHub(currentVersion);
        Long newVersion = policyResolver.getCurrentVersion();
        
        if (loadedCount > 0) {
            log.info("✅ 정책 매핑 동기화 완료: {}개 매핑 로드, version={}", loadedCount, newVersion);
        } else {
            // 304 응답 시에는 아무것도 하지 않음 (현재 버전 유지)
            log.debug("📋 정책 매핑 변경 없음 또는 로드 실패");
        }
        
        // 2. 동기화 완료 후 Hub에 버전 업데이트
        // 문서 플로우: 동기화 완료 후 즉시 Hub에 currentVersion 업데이트
        // checkMappingChange를 호출하면 Hub가 버전을 확인하고, 이미 동기화되어 있으면 304를 반환
        if (newVersion != null) {
            // checkMappingChange를 호출하여 Hub에 currentVersion 업데이트
            // 버전이 이미 동기화되어 있으면 false 반환 (304 Not Modified)
            // 버전이 변경되어 있으면 true 반환 (200 OK, hasChange=true)
            log.debug("🔄 Hub에 버전 확인 요청: currentVersion={}", newVersion);
            boolean hasChange = checkMappingChange(newVersion, null);
            if (hasChange) {
                log.debug("📋 Hub에서 버전 변경 감지 (다음 동기화 주기에서 처리)");
            } else {
                // false 반환은 "이미 동기화 완료"를 의미 (304 Not Modified 또는 hasChange=false)
                log.debug("✅ Hub 버전 확인 완료: version={} (이미 동기화됨)", newVersion);
            }
        } else {
            log.debug("⏭️ Hub에 버전 업데이트 건너뜀: newVersion={}", newVersion);
        }
        
        return loadedCount;
    }
    
    /**
     * Hub에서 정책 매핑 정보를 가져와서 PolicyResolver에 저장 (하위 호환성)
     * 
     * @return 로드된 매핑 개수
     * @deprecated loadPolicySnapshotFromHub(Long) 사용 권장
     */
    @Deprecated
    public int loadMappingsFromHub() {
        try {
            log.trace("🔄 Hub에서 정책 매핑 정보 로드 시작: hubId={}", hubId);
            
            // 공통 라이브러리로 통합하면서 파라미터 이름을 instanceId로 통일
            // hubId가 null이면 alias 사용 (AOP 초기 등록 시나리오)
            String instanceId = (hubId != null && !hubId.trim().isEmpty()) ? hubId : alias;
            String mappingsUrl = hubUrl + apiBasePath + "/mappings?instanceId=" + instanceId;
            log.trace("🔗 Hub 매핑 조회 URL: {}", mappingsUrl);
            
            // Java 버전에 따라 적절한 HTTP 클라이언트 사용
            URI uri = URI.create(mappingsUrl);
            HttpClientAdapter.HttpResponse response = httpClient.get(uri);
            
            int statusCode = response.getStatusCode();
            String responseBody = response.getBody();
            
            if (statusCode >= 200 && statusCode < 300 && responseBody != null) {
                MappingListResponse mappingResponse = objectMapper.readValue(responseBody, MappingListResponse.class);
                
                if (mappingResponse != null && mappingResponse.isSuccess() && mappingResponse.getData() != null) {
                    List<EncryptionMapping> mappings = mappingResponse.getData();
                    
                    // PolicyResolver 형식으로 변환 (database.table.column → 정책명)
                    Map<String, String> policyMap = new HashMap<>();
                    for (EncryptionMapping mapping : mappings) {
                        // enabled가 true인 경우만 추가
                        if (mapping.isEnabled()) {
                            // database.table.column 형식의 키 생성
                            String key;
                            if (mapping.getDatabaseName() != null && !mapping.getDatabaseName().trim().isEmpty()) {
                                key = mapping.getDatabaseName() + "." + mapping.getTableName() + "." + mapping.getColumnName();
                            } else {
                                // databaseName이 없으면 table.column 형식 (하위 호환성)
                                key = mapping.getTableName() + "." + mapping.getColumnName();
                            }
                            policyMap.put(key, mapping.getPolicyName());
                            log.trace("📋 매핑 로드: {} → {}", key, mapping.getPolicyName());
                        }
                    }
                    
                    // PolicyResolver에 반영 (영구 저장소에도 자동 저장됨)
                    policyResolver.refreshMappings(policyMap);
                    
                    log.info("✅ Hub에서 정책 매핑 정보 로드 완료: {}개 매핑 (영구 저장소에 저장됨)", policyMap.size());
                    return policyMap.size();
                } else {
                    log.warn("⚠️ Hub에서 정책 매핑 정보 로드 실패: 응답 없음 또는 실패");
                    return 0;
                }
            } else {
                log.warn("⚠️ Hub에서 정책 매핑 정보 로드 실패: HTTP {}", statusCode);
                return 0;
            }
            
        } catch (IOException e) {
            // 연결 실패는 예측 가능한 문제이므로 WARN 레벨로 처리 (정책 준수)
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (errorMsg.contains("Connection refused") || errorMsg.contains("ConnectException")) {
                log.warn("⚠️ Hub에서 정책 매핑 정보 로드 실패: {} (Hub 연결 불가)", errorMsg);
            } else {
                // 예측 불가능한 문제만 ERROR로 처리
                log.error("❌ Hub에서 정책 매핑 정보 로드 실패: {}", errorMsg, e);
            }
            // Hub 연결 실패 시 영구 저장소에서 로드 시도
            log.info("📂 Hub 연결 실패, 영구 저장소에서 정책 매핑 정보 로드 시도");
            policyResolver.reloadFromStorage();
            // 로드 실패해도 계속 진행 (Fail-open)
            return 0;
        }
    }
    
    /**
     * 매핑 변경 확인 응답 DTO
     */
    public static class CheckMappingChangeResponse {
        private boolean success;
        private Object data;  // Boolean 또는 Map<String, Object>
        private String message;
        
        public boolean isSuccess() {
            return success;
        }
        
        public void setSuccess(boolean success) {
            this.success = success;
        }
        
        public Object getData() {
            return data;
        }
        
        public void setData(Object data) {
            this.data = data;
        }
        
        public String getMessage() {
            return message;
        }
        
        public void setMessage(String message) {
            this.message = message;
        }
    }
    
    /**
     * 정책 스냅샷 응답 DTO
     */
    public static class PolicySnapshotResponse {
        private boolean success;
        private PolicySnapshot data;
        private String message;
        
        public boolean isSuccess() {
            return success;
        }
        
        public void setSuccess(boolean success) {
            this.success = success;
        }
        
        public PolicySnapshot getData() {
            return data;
        }
        
        public void setData(PolicySnapshot data) {
            this.data = data;
        }
        
        public String getMessage() {
            return message;
        }
        
        public void setMessage(String message) {
            this.message = message;
        }
    }
    
    /**
     * 정책 스냅샷 DTO
     */
    public static class PolicySnapshot {
        private Long version;
        private String updatedAt;
        private List<PolicyMapping> mappings;
        
        public Long getVersion() {
            return version;
        }
        
        public void setVersion(Long version) {
            this.version = version;
        }
        
        public String getUpdatedAt() {
            return updatedAt;
        }
        
        public void setUpdatedAt(String updatedAt) {
            this.updatedAt = updatedAt;
        }
        
        public List<PolicyMapping> getMappings() {
            return mappings;
        }
        
        public void setMappings(List<PolicyMapping> mappings) {
            this.mappings = mappings;
        }
    }
    
    /**
     * 정책 매핑 DTO
     */
    public static class PolicyMapping {
        private String datasourceId;
        private String schemaName;
        private String tableName;
        private String columnName;
        private String policyName;
        private boolean enabled = true; // 기본값은 true (하위 호환성)
        
        public String getDatasourceId() {
            return datasourceId;
        }
        
        public void setDatasourceId(String datasourceId) {
            this.datasourceId = datasourceId;
        }
        
        public String getSchemaName() {
            return schemaName;
        }
        
        public void setSchemaName(String schemaName) {
            this.schemaName = schemaName;
        }
        
        public String getTableName() {
            return tableName;
        }
        
        public void setTableName(String tableName) {
            this.tableName = tableName;
        }
        
        public String getColumnName() {
            return columnName;
        }
        
        public void setColumnName(String columnName) {
            this.columnName = columnName;
        }
        
        public String getPolicyName() {
            return policyName;
        }
        
        public void setPolicyName(String policyName) {
            this.policyName = policyName;
        }
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
    
    /**
     * 매핑 목록 응답 DTO
     */
    public static class MappingListResponse {
        private boolean success;
        private List<EncryptionMapping> data;
        private String message;
        
        public boolean isSuccess() {
            return success;
        }
        
        public void setSuccess(boolean success) {
            this.success = success;
        }
        
        public List<EncryptionMapping> getData() {
            return data;
        }
        
        public void setData(List<EncryptionMapping> data) {
            this.data = data;
        }
        
        public String getMessage() {
            return message;
        }
        
        public void setMessage(String message) {
            this.message = message;
        }
    }
    
    /**
     * 암호화 매핑 DTO
     */
    public static class EncryptionMapping {
        private String instanceId;
        private String databaseName;
        private String tableName;
        private String columnName;
        private String policyName;
        private boolean enabled;
        
        public String getInstanceId() {
            return instanceId;
        }
        
        public void setInstanceId(String instanceId) {
            this.instanceId = instanceId;
        }
        
        public String getDatabaseName() {
            return databaseName;
        }
        
        public void setDatabaseName(String databaseName) {
            this.databaseName = databaseName;
        }
        
        public String getTableName() {
            return tableName;
        }
        
        public void setTableName(String tableName) {
            this.tableName = tableName;
        }
        
        public String getColumnName() {
            return columnName;
        }
        
        public void setColumnName(String columnName) {
            this.columnName = columnName;
        }
        
        public String getPolicyName() {
            return policyName;
        }
        
        public void setPolicyName(String policyName) {
            this.policyName = policyName;
        }
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
