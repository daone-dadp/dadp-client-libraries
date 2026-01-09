package com.dadp.common.sync.schema;

import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * HubId를 ThreadLocal에 저장하여 상위 메서드에서 접근 가능하도록 하는 헬퍼 클래스
 */
class HubIdHolder {
    private static final ThreadLocal<String> hubIdThreadLocal = new ThreadLocal<>();
    
    static void setHubId(String hubId) {
        hubIdThreadLocal.set(hubId);
    }
    
    static String getHubId() {
        return hubIdThreadLocal.get();
    }
    
    static void clear() {
        hubIdThreadLocal.remove();
    }
}

/**
 * RestTemplate 기반 스키마 동기화 실행 구현체 (Java 17+)
 * 
 * AOP에서 사용하는 RestTemplate 기반 구현입니다.
 * 
 * @author DADP Development Team
 * @version 5.1.0
 * @since 2026-01-06
 */
public class RestTemplateSchemaSyncExecutor implements SchemaSyncExecutor {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(RestTemplateSchemaSyncExecutor.class);
    
    private final String hubUrl;
    private final String apiBasePath;  // "/hub/api/v1/aop"
    private final RestTemplate restTemplate;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    
    public RestTemplateSchemaSyncExecutor(String hubUrl, String apiBasePath, RestTemplate restTemplate) {
        this.hubUrl = hubUrl;
        this.apiBasePath = apiBasePath;
        this.restTemplate = restTemplate;
        this.objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
    }
    
    @Override
    public boolean syncToHub(List<SchemaMetadata> schemas, String hubId, String instanceId, Long currentVersion) throws Exception {
        String syncUrl = hubUrl + apiBasePath + "/schema/sync";
        log.info("🔗 스키마 동기화 URL 생성: hubUrl={}, apiBasePath={}, syncUrl={}", hubUrl, apiBasePath, syncUrl);
        
        // hubId 필수 검증
        if (hubId == null || hubId.trim().isEmpty()) {
            log.warn("⚠️ hubId가 없어 스키마 동기화를 수행할 수 없습니다.");
            throw new IllegalStateException("hubId가 필요합니다. 먼저 인스턴스 등록을 수행하세요.");
        }
        
        // AOP 스키마 동기화 요청 DTO 생성
        // Body에 instanceId와 스키마 정보 포함 (hubId는 헤더로 전송)
        AopSchemaSyncRequest request = new AopSchemaSyncRequest();
        request.setInstanceId(instanceId);  // instanceId 포함 (hubId가 없을 때 자동 생성용)
        request.setSchemas(convertToAopSchemaInfo(schemas));
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-DADP-TENANT", hubId);  // hubId 필수
        if (currentVersion != null) {
            headers.set("X-Current-Version", String.valueOf(currentVersion));
        }
        HttpEntity<AopSchemaSyncRequest> entity = new HttpEntity<>(request, headers);
        
        log.debug("📤 요청 본문: {}", request);
        log.info("📤 RestTemplate.exchange() 호출: syncUrl={}, HttpMethod=POST", syncUrl);
        ResponseEntity<Map<String, Object>> response;
        try {
            org.springframework.core.ParameterizedTypeReference<Map<String, Object>> typeRef = 
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {};
            response = restTemplate.exchange(syncUrl, HttpMethod.POST, entity, typeRef);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // 404 Not Found: hubId를 찾을 수 없음 (등록되지 않은 hubId) -> 예외를 다시 던져서 상위에서 등록 처리
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                log.warn("⚠️ Hub에서 hubId를 찾을 수 없음 (404): hubId={}, 재등록이 필요합니다.", hubId);
                throw e; // 예외를 다시 던져서 상위에서 인스턴스 등록 API 호출
            }
            throw e;
        }
        
        // 304 Not Modified 처리
        if (response.getStatusCode() == HttpStatus.NOT_MODIFIED) {
            log.debug("✅ 스키마 동기화 불필요 (304): 버전이 동일함, currentVersion={}", currentVersion);
            return true;
        }
        
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            Map<String, Object> responseBody = response.getBody();
            Boolean success = (Boolean) responseBody.get("success");
            if (Boolean.TRUE.equals(success)) {
                log.info("✅ Hub에 AOP 스키마 정보 전송 완료: {}개 필드, hubId={}", schemas.size(), hubId);
                return true;
            } else {
                String message = (String) responseBody.get("message");
                log.warn("⚠️ Hub 스키마 동기화 실패: {}, URL={}", message != null ? message : "Unknown error", syncUrl);
                return false;
            }
        } else {
            log.warn("⚠️ Hub 스키마 동기화 실패: HTTP {}, URL={}", response.getStatusCode(), syncUrl);
            return false;
        }
    }
    
    @Override
    public String getReceivedHubId() {
        return HubIdHolder.getHubId();
    }
    
    @Override
    public void clearReceivedHubId() {
        HubIdHolder.clear();
    }
    
    /**
     * 공통 SchemaMetadata를 AOP SchemaInfo로 변환
     */
    private List<AopSchemaInfo> convertToAopSchemaInfo(List<SchemaMetadata> schemas) {
        return schemas.stream()
            .map(schema -> {
                AopSchemaInfo info = new AopSchemaInfo();
                info.setSchemaName(schema.getSchemaName() != null ? schema.getSchemaName() : "public");
                info.setTableName(schema.getTableName());
                info.setColumnName(schema.getColumnName());
                info.setPolicyName(schema.getPolicyName() != null ? schema.getPolicyName() : "dadp");
                return info;
            })
            .collect(java.util.stream.Collectors.toList());
    }
    
    /**
     * AOP 스키마 동기화 요청 DTO
     * 
     * Body에 instanceId와 스키마 정보 포함
     * hubId는 헤더(X-DADP-TENANT)로 전송됨
     */
    public static class AopSchemaSyncRequest {
        private String instanceId;  // 인스턴스 별칭 (hubId가 없을 때 자동 생성용)
        private List<AopSchemaInfo> schemas;
        
        public String getInstanceId() { return instanceId; }
        public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
        
        public List<AopSchemaInfo> getSchemas() { return schemas; }
        public void setSchemas(List<AopSchemaInfo> schemas) { this.schemas = schemas; }
    }
    
    /**
     * AOP 스키마 정보 DTO
     */
    public static class AopSchemaInfo {
        private String schemaName;
        private String tableName;
        private String columnName;
        private String policyName;
        
        public String getSchemaName() { return schemaName; }
        public void setSchemaName(String schemaName) { this.schemaName = schemaName; }
        public String getTableName() { return tableName; }
        public void setTableName(String tableName) { this.tableName = tableName; }
        public String getColumnName() { return columnName; }
        public void setColumnName(String columnName) { this.columnName = columnName; }
        public String getPolicyName() { return policyName; }
        public void setPolicyName(String policyName) { this.policyName = policyName; }
    }
    
    /**
     * AOP 스키마 동기화 응답 DTO
     */
    public static class AopSchemaSyncResponse {
        private boolean success;
        private AopSchemaSyncResponseData data;
        private String message;
        
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public AopSchemaSyncResponseData getData() { return data; }
        public void setData(AopSchemaSyncResponseData data) { this.data = data; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
    
    /**
     * AOP 스키마 동기화 응답 데이터 DTO
     */
    public static class AopSchemaSyncResponseData {
        private String hubId;
        
        public String getHubId() { return hubId; }
        public void setHubId(String hubId) { this.hubId = hubId; }
    }
}

