package com.dadp.jdbc.mapping;

import com.dadp.common.sync.http.HttpClientAdapter;
import com.dadp.common.sync.http.Java8HttpClientAdapterFactory;
import com.dadp.jdbc.policy.PolicyResolver;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;

/**
 * 매핑 동기화 서비스
 * 
 * Proxy에서 Hub로부터 정책 매핑 정보를 가져와서 PolicyResolver에 저장합니다.
 * Java 버전에 따라 적절한 HTTP 클라이언트를 자동으로 선택합니다.
 * 
 * @deprecated 이 클래스는 더 이상 사용되지 않습니다.
 *             대신 {@link com.dadp.common.sync.mapping.MappingSyncService}를 사용하세요.
 * 
 * @author DADP Development Team
 * @version 4.0.0
 * @since 2025-11-07
 */
@Deprecated
public class MappingSyncService {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(MappingSyncService.class);
    
    private final String hubUrl;
    private final String hubId;  // Hub가 발급한 고유 ID (X-DADP-TENANT 헤더에 사용)
    private final String alias;  // 사용자가 설정한 instanceId (별칭, 검색/표시용)
    private final String datasourceId;  // Datasource ID (재등록을 위해 필요)
    private final HttpClientAdapter httpClient;
    private final ObjectMapper objectMapper;
    private final PolicyResolver policyResolver;
    
    public MappingSyncService(String hubUrl, String hubId, String alias, String datasourceId, PolicyResolver policyResolver) {
        this.hubUrl = hubUrl;
        this.hubId = hubId;
        this.alias = alias;
        this.datasourceId = datasourceId;
        // Java 8용 HTTP 클라이언트 사용 (공통 인터페이스)
        this.httpClient = Java8HttpClientAdapterFactory.create(5000, 10000);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.policyResolver = policyResolver;
    }
    
    /**
     * Hub에서 매핑 변경 여부 확인 (경량 요청)
     * Proxy의 currentVersion을 함께 전달하여 Hub가 동기화 상태(currentVersion/hubVersion)를 업데이트할 수 있게 함.
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
     *
     * @param version Proxy가 가진 현재 매핑 버전 (null이면 미전달)
     * @param reregisteredHubId 재등록된 hubId를 저장할 배열 (재등록 발생 시 새 hubId 저장)
     * @return 변경사항이 있으면 true, 없으면 false
     */
    public boolean checkMappingChange(Long version, String[] reregisteredHubId) {
        try {
            // 공통 라이브러리로 통합하면서 파라미터 이름을 instanceId로 통일
            // V1 API 사용: /hub/api/v1/proxy/mappings/check
            String checkPath = "/hub/api/v1/proxy/mappings/check";
            String checkUrl = hubUrl + checkPath + "?instanceId=" + hubId;
            
            // alias와 datasourceId 추가 (재등록을 위해)
            if (alias != null && !alias.trim().isEmpty()) {
                checkUrl += "&alias=" + java.net.URLEncoder.encode(alias, "UTF-8");
            }
            if (datasourceId != null && !datasourceId.trim().isEmpty()) {
                checkUrl += "&datasourceId=" + java.net.URLEncoder.encode(datasourceId, "UTF-8");
            }
            
            log.trace("Hub mapping change check URL: {}", checkUrl);
            
            // 헤더에 버전 포함 (Hub는 헤더에서 버전을 읽음)
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            if (version != null) {
                headers.put("X-Current-Version", String.valueOf(version));
            }
            
            // Java 버전에 따라 적절한 HTTP 클라이언트 사용
            URI uri = URI.create(checkUrl);
            HttpClientAdapter.HttpResponse response = httpClient.get(uri, headers);
            
            int statusCode = response.getStatusCode();
            String responseBody = response.getBody();
            
            if (statusCode >= 200 && statusCode < 300 && responseBody != null) {
                // ApiResponse<Map<String, Object>> 형태로 파싱
                CheckMappingChangeResponse checkResponse = objectMapper.readValue(responseBody, CheckMappingChangeResponse.class);
                if (checkResponse != null && checkResponse.isSuccess() && checkResponse.getData() != null) {
                    // data가 Map인 경우 (재등록 정보 포함)
                    if (checkResponse.getData() instanceof java.util.Map) {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> dataMap = (java.util.Map<String, Object>) checkResponse.getData();
                        Boolean hasChange = (Boolean) dataMap.get("hasChange");
                        Boolean reregistered = (Boolean) dataMap.get("reregistered");
                        
                        // 재등록 발생 시 로그 출력 및 hubId 저장
                        if (reregistered != null && reregistered && reregisteredHubId != null) {
                            String newHubId = (String) dataMap.get("hubId");
                            if (newHubId != null) {
                                reregisteredHubId[0] = newHubId;
                                log.info("Re-registration occurred at Hub: hubId={}", newHubId);
                            } else {
                                log.info("Re-registration occurred at Hub (no hubId info)");
                            }
                        }
                        
                        return hasChange != null && hasChange;
                    } else if (checkResponse.getData() instanceof Boolean) {
                        // 하위 호환성: data가 Boolean인 경우
                        Boolean dataValue = (Boolean) checkResponse.getData();
                        return dataValue != null && dataValue;
                    }
                }
            }
            return false;
        } catch (IOException e) {
            log.warn("Mapping change check failed: {}", e.getMessage());
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
            log.trace("Loading policy snapshot from Hub: hubId={}, currentVersion={}",
                hubId, currentVersion);
            
            // V1 API 사용: /hub/api/v1/proxy/policies
            String policiesPath = "/hub/api/v1/proxy/policies";
            String policiesUrl = hubUrl + policiesPath + "?instanceId=" + hubId;
            if (currentVersion != null) {
                policiesUrl += "&version=" + currentVersion;
            }
            
            URI uri = URI.create(policiesUrl);
            HttpClientAdapter.HttpResponse response = httpClient.get(uri);
            
            int statusCode = response.getStatusCode();
            
            // 304 Not Modified: 변경 없음
            if (statusCode == 304) {
                log.trace("Policy snapshot unchanged (version={})", currentVersion);
                return 0;
            }
            
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
                            String key = mapping.getDatasourceId() + ":" + 
                                        mapping.getSchemaName() + "." + 
                                        mapping.getTableName() + "." + 
                                        mapping.getColumnName();
                            policyMap.put(key, mapping.getPolicyName());
                            log.info("Policy mapping loaded: {} -> {}", key, mapping.getPolicyName());
                        } else {
                            log.debug("Policy mapping skipped: enabled={}, policyName={}, datasourceId={}, schema={}, table={}, column={}",
                                    mapping.isEnabled(), mapping.getPolicyName(), 
                                    mapping.getDatasourceId(), mapping.getSchemaName(), 
                                    mapping.getTableName(), mapping.getColumnName());
                        }
                    }
                    
                    // PolicyResolver에 반영 (영구 저장소에도 자동 저장됨, 버전 정보 포함)
                    policyResolver.refreshMappings(policyMap, snapshot.getVersion());
                    
                    log.info("Policy snapshot loaded from Hub: version={}, {} mappings (persisted to storage)",
                        snapshot.getVersion(), policyMap.size());
                    return policyMap.size();
                }
            }
            
            log.warn("Failed to load policy snapshot from Hub: HTTP {}", statusCode);
            // Hub 통신 장애는 알림 제거 (받는 주체가 Hub이므로)
            return 0;
            
        } catch (IOException e) {
            // 연결 실패는 예측 가능한 문제이므로 WARN 레벨로 처리 (정책 준수)
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (errorMsg.contains("Connection refused") || errorMsg.contains("ConnectException")) {
                log.warn("Failed to load policy snapshot from Hub: {} (Hub unreachable)", errorMsg);
            } else {
                // 예측 불가능한 문제만 ERROR로 처리
                log.error("Failed to load policy snapshot from Hub: {}", errorMsg, e);
            }
            // Hub 통신 장애는 알림 제거 (받는 주체가 Hub이므로)
            // Hub 연결 실패 시 영구 저장소에서 로드 시도
            log.info("Hub connection failed, loading policy mappings from persistent storage");
            policyResolver.reloadFromStorage();
            return 0;
        }
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
            log.trace("Loading policy mappings from Hub: hubId={}", hubId);
            
            // V1 API 사용: /hub/api/v1/proxy/mappings
            String mappingsPath = "/hub/api/v1/proxy/mappings";
            String mappingsUrl = hubUrl + mappingsPath + "?instanceId=" + hubId;
            log.trace("Hub mapping query URL: {}", mappingsUrl);
            
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
                            log.trace("Mapping loaded: {} -> {}", key, mapping.getPolicyName());
                        }
                    }
                    
                    // PolicyResolver에 반영 (영구 저장소에도 자동 저장됨)
                    policyResolver.refreshMappings(policyMap);
                    
                    log.info("Policy mappings loaded from Hub: {} mappings (persisted to storage)", policyMap.size());
                    return policyMap.size();
                } else {
                    log.warn("Failed to load policy mappings from Hub: no response or failure");
                    return 0;
                }
            } else {
                log.warn("Failed to load policy mappings from Hub: HTTP {}", statusCode);
                return 0;
            }
            
        } catch (IOException e) {
            // 연결 실패는 예측 가능한 문제이므로 WARN 레벨로 처리 (정책 준수)
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (errorMsg.contains("Connection refused") || errorMsg.contains("ConnectException")) {
                log.warn("Failed to load policy mappings from Hub: {} (Hub unreachable)", errorMsg);
            } else {
                // 예측 불가능한 문제만 ERROR로 처리
                log.error("Failed to load policy mappings from Hub: {}", errorMsg, e);
            }
            // Hub 연결 실패 시 영구 저장소에서 로드 시도
            log.info("Hub connection failed, loading policy mappings from persistent storage");
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
        private String code;
        private Object data;  // Boolean 또는 Map<String, Object>
        private String message;

        public boolean isSuccess() {
            if (code != null) {
                return "SUCCESS".equals(code);
            }
            return success;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
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
        private String code;
        private PolicySnapshot data;
        private String message;

        public boolean isSuccess() {
            if (code != null) {
                return "SUCCESS".equals(code);
            }
            return success;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
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
        private String code;
        private List<EncryptionMapping> data;
        private String message;

        public boolean isSuccess() {
            if (code != null) {
                return "SUCCESS".equals(code);
            }
            return success;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
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
        private String proxyInstanceId;
        private String databaseName;
        private String tableName;
        private String columnName;
        private String policyName;
        private boolean enabled;
        
        public String getProxyInstanceId() {
            return proxyInstanceId;
        }
        
        public void setProxyInstanceId(String proxyInstanceId) {
            this.proxyInstanceId = proxyInstanceId;
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
