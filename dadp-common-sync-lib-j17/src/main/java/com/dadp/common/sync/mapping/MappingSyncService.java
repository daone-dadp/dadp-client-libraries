package com.dadp.common.sync.mapping;

import com.dadp.common.sync.policy.PolicyResolver;
import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import com.dadp.common.sync.policy.PolicyResolver;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 매핑 동기화 서비스
 * 
 * Hub로부터 정책 매핑 정보를 가져와서 PolicyResolver에 저장합니다.
 * Spring RestTemplate을 사용하여 HTTP 통신을 수행합니다.
 * 
 * WRAPPER와 AOP 모두 사용 가능하도록 설계되었습니다.
 * - WRAPPER: apiBasePath = "/hub/api/v1/proxy", datasourceId 사용
 * - AOP: apiBasePath = "/hub/api/v1/aop", datasourceId = null
 * 
 * @author DADP Development Team
 * @version 5.0.4
 * @since 2025-12-30
 */
public class MappingSyncService {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(MappingSyncService.class);
    
    private final String hubUrl;
    private final String hubId;  // Hub가 발급한 고유 ID (X-DADP-TENANT 헤더에 사용)
    private final String alias;  // 사용자가 설정한 instanceId (별칭, 검색/표시용)
    private final String datasourceId;  // Datasource ID (WRAPPER용, AOP는 null)
    private final String apiBasePath;   // API 기본 경로 ("/hub/api/v1/aop" 또는 "/hub/api/v1/proxy")
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final PolicyResolver policyResolver;
    private volatile Map<String, Object> lastEndpointInfo;  // 마지막으로 받은 엔드포인트 정보
    
    public MappingSyncService(String hubUrl, String hubId, String alias, 
                             String datasourceId, String apiBasePath,
                             PolicyResolver policyResolver) {
        this.hubUrl = hubUrl;
        this.hubId = hubId;
        this.alias = alias;
        this.datasourceId = datasourceId;
        this.apiBasePath = apiBasePath;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.policyResolver = policyResolver;
    }
    
    /**
     * Hub에서 매핑 변경 여부 확인 (경량 요청)
     * 
     * @param version 현재 매핑 버전 (null이면 미전달)
     * @return 변경사항이 있으면 true, 없으면 false
     */
    public boolean checkMappingChange(Long version) {
        return checkMappingChange(version, null);
    }
    
    /**
     * Hub에서 매핑 변경 여부 확인 (경량 요청)
     * 
     * @param version 현재 매핑 버전 (null이면 미전달)
     * @param reregisteredHubId 재등록된 hubId를 저장할 배열 (재등록 발생 시 새 hubId 저장)
     * @return 변경사항이 있으면 true, 없으면 false
     */
    public boolean checkMappingChange(Long version, String[] reregisteredHubId) {
        // checkUrl은 catch 블록에서도 사용하기 위해 메서드 시작 부분에서 선언
        String checkUrl = null;
        try {
            // hubId 필수 검증
            if (hubId == null || hubId.trim().isEmpty()) {
                log.warn("Cannot check mapping changes: hubId is missing");
                throw new IllegalStateException("hubId is required. Please perform instance registration first.");
            }
            
            // version 필수 검증 (영구저장소에서 불러오지 못하면 0으로 초기화)
            if (version == null) {
                version = 0L;
            }
            
            // Query 파라미터 없음, 헤더의 hubId만 사용
            String checkPath = apiBasePath + "/mappings/check";
            checkUrl = hubUrl + checkPath;
            
            log.trace("Hub mapping change check URL: {}", checkUrl);
            
            // 헤더에 hubId와 버전 필수 포함
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-DADP-TENANT", hubId);  // hubId 필수
            headers.set("X-Current-Version", String.valueOf(version));  // version 필수
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            
            // Spring RestTemplate 사용
            ResponseEntity<CheckMappingChangeResponse> response;
            try {
                response = restTemplate.exchange(
                    checkUrl, HttpMethod.GET, entity, CheckMappingChangeResponse.class);
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                // 404 Not Found: hubId를 찾을 수 없음 -> 재등록 필요 (예외가 아닌 정상 응답 코드)
                if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                    log.warn("Hub returned 404 for hubId={}, re-registration required", hubId);
                    // 404는 특별한 값으로 표시하기 위해 reregisteredHubId 배열에 특별한 값 설정
                    if (reregisteredHubId != null) {
                        reregisteredHubId[0] = "NEED_REGISTRATION"; // 재등록 필요 표시
                    }
                    return false; // false 반환하여 재등록 처리 유도
                }
                // 다른 4xx/5xx 에러는 예외로 처리
                throw e;
            }
            
            // 304 Not Modified: 버전 동일 -> 동기화 불필요
            if (response.getStatusCode() == HttpStatus.NOT_MODIFIED) {
                return false;
            }
            
            // 200 OK: 버전 변경 -> 동기화 필요 (무조건 true 반환)
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                CheckMappingChangeResponse checkResponse = response.getBody();
                if (checkResponse.isSuccess() && checkResponse.getData() != null) {
                    // data가 Map인 경우 (재등록 정보 포함)
                    if (checkResponse.getData() instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> dataMap = (Map<String, Object>) checkResponse.getData();
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
                    }
                }
                // 200 OK를 받으면 무조건 true 반환 (갱신 필요)
                return true;
            }
            
            // 기타 상태 코드는 false 반환
            log.warn("Mapping change check failed: HTTP {}, URL={}", response.getStatusCode(), checkUrl);
            return false;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // 404는 이미 위에서 처리했으므로 여기서는 다른 4xx/5xx 에러만 처리
            log.warn("Mapping change check failed: status={}, hubUrl={}, URL={}, message={}",
                    e.getStatusCode(), hubUrl, checkUrl, e.getMessage());
            if (e.getResponseBodyAsString() != null) {
                log.warn("Response body: {}", e.getResponseBodyAsString());
            }
            return false; // 실패 시 false 반환 (다음 확인 시 재시도)
        } catch (Exception e) {
            log.warn("Mapping change check failed: hubUrl={}, URL={}, error={}", hubUrl, checkUrl, e.getMessage());
            return false; // 실패 시 false 반환 (다음 확인 시 재시도)
        }
    }
    
    /**
     * Hub에서 정책 스냅샷을 가져와서 PolicyResolver에 저장
     * 
     * @param currentVersion 현재 버전 (null이면 최신 버전 반환)
     * @return 로드된 매핑 개수
     */
    public int loadPolicySnapshotFromHub(Long currentVersion) {
        // policiesUrl은 catch 블록에서도 사용하기 위해 메서드 시작 부분에서 선언
        String policiesUrl = null;
        try {
            log.trace("Loading policy snapshot from Hub: hubId={}, alias={}, currentVersion={}",
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
            String policiesPath = apiBasePath + "/policies";
            policiesUrl = hubUrl + policiesPath + "?instanceId=" + instanceIdParam;
            
            // alias는 항상 별도 파라미터로도 전달 (Hub의 getPolicySnapshotByAlias에서 사용)
            if (alias != null && !alias.trim().isEmpty()) {
                policiesUrl += "&alias=" + URLEncoder.encode(alias, StandardCharsets.UTF_8);
            }
            
            // 헤더에 hubId와 버전 포함 (버전은 헤더로 전송)
            HttpHeaders headers = new HttpHeaders();
            headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
            if (hubId != null && !hubId.trim().isEmpty()) {
                headers.set("X-DADP-TENANT", hubId);  // Hub가 헤더에서 hubId를 받을 수 있도록
            }
            if (currentVersion != null) {
                headers.set("X-Current-Version", String.valueOf(currentVersion));  // 버전은 헤더로 전송
            }
            HttpEntity<?> entity = new HttpEntity<>(headers);
            
            ResponseEntity<PolicySnapshotResponse> response;
            try {
                response = restTemplate.exchange(
                    policiesUrl, HttpMethod.GET, entity, PolicySnapshotResponse.class);
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                // 404 Not Found: hubId를 찾을 수 없음 -> 재등록 필요 (예외가 아닌 정상 응답 코드)
                if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                    log.warn("Hub returned 404 for hubId={}, re-registration required", hubId);
                    return -1; // -1을 반환하여 재등록 필요를 표시
                }
                // 다른 4xx/5xx 에러는 예외로 처리
                throw e;
            }
            
            // 304 Not Modified: 변경 없음 -> 아무것도 하지 않음 (현재 버전 유지)
            if (response.getStatusCode() == HttpStatus.NOT_MODIFIED) {
                return 0;
            }
            
            // 200 OK: 버전 변경 -> 동기화 필요
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                PolicySnapshotResponse snapshotResponse = response.getBody();
                
                if (snapshotResponse.isSuccess() && snapshotResponse.getData() != null) {
                    PolicySnapshot snapshot = snapshotResponse.getData();
                    
                    // PolicyResolver 형식으로 변환
                    Map<String, String> policyMap = new HashMap<>();
                    for (PolicyMapping mapping : snapshot.getMappings()) {
                        // enabled가 true이고 policyName이 있는 경우만 추가
                        if (mapping.isEnabled() && mapping.getPolicyName() != null && 
                            !mapping.getPolicyName().trim().isEmpty()) {
                            
                            // WRAPPER: datasourceId:schema.table.column
                            // AOP: schema.table.column
                            String key;
                            if (datasourceId != null && !datasourceId.trim().isEmpty()) {
                                key = datasourceId + ":" + 
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
                            log.trace("Policy mapping loaded: {} -> {} (schema={}, table={}, column={})",
                                    key, mapping.getPolicyName(), 
                                    mapping.getSchemaName(), mapping.getTableName(), mapping.getColumnName());
                        } else {
                            log.trace("Skipping policy mapping: enabled={}, policyName={}, schema={}, table={}, column={}",
                                    mapping.isEnabled(), mapping.getPolicyName(), 
                                    mapping.getSchemaName(), mapping.getTableName(), mapping.getColumnName());
                        }
                    }
                    
                    // 정책 속성(useIv/usePlain) 추출 - policyAttributes 맵에서 우선, 없으면 매핑에서 fallback
                    Map<String, PolicyResolver.PolicyAttributes> attributeMap = new HashMap<>();
                    Map<String, Map<String, Object>> snapshotAttrs = snapshot.getPolicyAttributes();
                    if (snapshotAttrs != null && !snapshotAttrs.isEmpty()) {
                        for (Map.Entry<String, Map<String, Object>> entry : snapshotAttrs.entrySet()) {
                            String pn = entry.getKey();
                            Map<String, Object> attrs = entry.getValue();
                            Boolean useIv = attrs.get("useIv") instanceof Boolean ? (Boolean) attrs.get("useIv") : null;
                            Boolean usePlain = attrs.get("usePlain") instanceof Boolean ? (Boolean) attrs.get("usePlain") : null;
                            attributeMap.put(pn, new PolicyResolver.PolicyAttributes(useIv, usePlain));
                        }
                    } else {
                        // fallback: 개별 매핑에서 추출 (하위 호환성)
                        for (PolicyMapping mapping : snapshot.getMappings()) {
                            if (mapping.isEnabled() && mapping.getPolicyName() != null
                                    && !mapping.getPolicyName().trim().isEmpty()) {
                                String pn = mapping.getPolicyName();
                                if (!attributeMap.containsKey(pn)) {
                                    attributeMap.put(pn, new PolicyResolver.PolicyAttributes(
                                            mapping.getUseIv(), mapping.getUsePlain()));
                                }
                            }
                        }
                    }

                    // PolicyResolver에 반영 (영구 저장소에도 자동 저장됨, 버전 정보 포함)
                    Long snapshotVersion = snapshot.getVersion();
                    if (snapshotVersion == null) {
                        log.warn("Policy snapshot from Hub has no version info (version=null), mappings={}", policyMap.size());
                    }
                    if (!attributeMap.isEmpty()) {
                        policyResolver.refreshMappings(policyMap, attributeMap, snapshotVersion);
                    } else {
                        policyResolver.refreshMappings(policyMap, snapshotVersion);
                    }

                    // 엔드포인트 정보 저장 (정책 스냅샷 응답에 포함된 경우)
                    Map<String, Object> endpointInfo = snapshot.getEndpoint();
                    if (endpointInfo != null && !endpointInfo.isEmpty()) {
                        // 엔드포인트 정보를 저장 (PolicyMappingSyncOrchestrator에서 사용)
                        this.lastEndpointInfo = endpointInfo;
                        log.trace("Endpoint info received from policy snapshot: {}", endpointInfo);
                    }

                    log.info("Policy snapshot loaded from Hub: version={}, {} mappings, {} policy attributes (saved to persistent storage)",
                        snapshotVersion, policyMap.size(), attributeMap.size());
                    return policyMap.size();
                }
            }
            
            log.warn("Failed to load policy snapshot from Hub: HTTP {}, hubUrl={}, URL={}",
                    response.getStatusCode(), hubUrl, policiesUrl);
            return 0;
            
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // 404는 이미 위에서 처리했으므로 여기서는 다른 4xx/5xx 에러만 처리
            log.warn("Failed to load policy snapshot from Hub: status={}, hubUrl={}, URL={}, message={}",
                    e.getStatusCode(), hubUrl, policiesUrl, e.getMessage());
            if (e.getResponseBodyAsString() != null) {
                log.warn("Response body: {}", e.getResponseBodyAsString());
            }
            // Hub 연결 실패 시 영구 저장소에서 로드 시도
            log.debug("Hub connection failed, attempting to load policy mappings from persistent storage");
            policyResolver.reloadFromStorage();
            return 0;
        } catch (IllegalStateException e) {
            // 400 응답으로 인한 초기화 필요 예외는 다시 던짐
            throw e;
        } catch (Exception e) {
            // 연결 실패는 예측 가능한 문제이므로 WARN 레벨로 처리
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (errorMsg.contains("Connection refused") || errorMsg.contains("ConnectException")) {
                log.warn("Failed to load policy snapshot from Hub: hubUrl={}, URL={}, {} (connection refused)",
                        hubUrl, policiesUrl, errorMsg);
            } else {
                // Hub 통신 실패는 예측 가능한 문제이므로 WARN 레벨로 처리
                log.warn("Failed to load policy snapshot from Hub: hubUrl={}, URL={}, error={}",
                        hubUrl, policiesUrl, errorMsg);
            }
            // Hub 연결 실패 시 영구 저장소에서 로드 시도
            log.debug("Hub connection failed, attempting to load policy mappings from persistent storage");
            policyResolver.reloadFromStorage();
            return 0;
        }
    }
    
    /**
     * 마지막으로 받은 엔드포인트 정보 반환
     */
    public Map<String, Object> getLastEndpointInfo() {
        return lastEndpointInfo;
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
        try {
            // 1. Hub에서 정책 스냅샷 로드
            int loadedCount = loadPolicySnapshotFromHub(currentVersion);
            
            // 404 응답 처리: -1이 반환되면 재등록 필요
            if (loadedCount == -1) {
                log.warn("Hub returned 404, re-registration required");
                // 예외를 던져서 PolicyMappingSyncOrchestrator에서 재등록 처리
                throw new IllegalStateException("hubId not found on Hub (404 Not Found): re-registration is required.");
            }
            
            Long newVersion = policyResolver.getCurrentVersion();
            
            if (loadedCount > 0) {
                log.info("Policy mapping sync completed: {} mappings loaded, version={}", loadedCount, newVersion);
            } else {
                // 304 응답 시에는 아무것도 하지 않음 (현재 버전 유지)
                log.debug("No policy mapping changes or load failed");
            }
            
            // 2. 동기화 완료 후 Hub에 버전 업데이트
            // 문서 플로우: 동기화 완료 후 즉시 Hub에 currentVersion 업데이트
            // checkMappingChange를 호출하면 Hub가 버전을 확인하고, 이미 동기화되어 있으면 304를 반환
            if (newVersion != null) {
                // checkMappingChange를 호출하여 Hub에 currentVersion 업데이트
                // 버전이 이미 동기화되어 있으면 false 반환 (304 Not Modified)
                // 버전이 변경되어 있으면 true 반환 (200 OK, hasChange=true)
                log.debug("Requesting version check from Hub: currentVersion={}", newVersion);
                boolean hasChange = checkMappingChange(newVersion, null);
                if (hasChange) {
                    log.debug("Version change detected at Hub (will be processed in next sync cycle)");
                } else {
                    // false 반환은 "이미 동기화 완료"를 의미 (304 Not Modified 또는 hasChange=false)
                    log.debug("Hub version check completed: version={} (already synced)", newVersion);
                }
            } else {
                log.debug("Skipping Hub version update: newVersion={}", newVersion);
            }
            
            return loadedCount;
        } catch (IllegalStateException e) {
            // 404로 인한 재등록 필요 예외는 다시 던짐
            if (e.getMessage() != null && e.getMessage().contains("404")) {
                throw e;
            }
            log.warn("Policy mapping sync failed: {}", e.getMessage());
            return 0;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // 다른 4xx/5xx 에러 처리
            log.warn("Policy mapping sync failed: HTTP {}, message={}", e.getStatusCode(), e.getMessage());
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

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getCode() { return code; }

        public void setCode(String code) { this.code = code; }
        
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

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getCode() { return code; }

        public void setCode(String code) { this.code = code; }
        
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
        private Map<String, Object> endpoint;  // 엔드포인트 정보 (cryptoUrl 등)
        
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
        
        public Map<String, Object> getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(Map<String, Object> endpoint) {
            this.endpoint = endpoint;
        }

        private Map<String, Map<String, Object>> policyAttributes;

        public Map<String, Map<String, Object>> getPolicyAttributes() {
            return policyAttributes;
        }

        public void setPolicyAttributes(Map<String, Map<String, Object>> policyAttributes) {
            this.policyAttributes = policyAttributes;
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
        private Boolean useIv;     // 정책 속성: IV 사용 여부 (null이면 기본값 true)
        private Boolean usePlain;  // 정책 속성: 부분암호화 여부 (null이면 기본값 false)

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

