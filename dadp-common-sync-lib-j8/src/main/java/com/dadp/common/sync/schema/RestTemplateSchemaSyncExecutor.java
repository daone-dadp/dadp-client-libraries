package com.dadp.common.sync.schema;

import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * RestTemplate 기반 스키마 동기화 실행 구현체 (Java 8+)
 * 
 * AOP에서 사용하는 RestTemplate 기반 구현입니다.
 * 
 * @author DADP Development Team
 * @version 5.2.0
 * @since 2026-01-07
 */
public class RestTemplateSchemaSyncExecutor implements SchemaSyncExecutor {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(RestTemplateSchemaSyncExecutor.class);
    
    private final String hubUrl;
    private final String apiBasePath;  // "/hub/api/v1/aop" 또는 "/hub/api/v1/proxy"
    private final String instanceType;  // "PROXY" 또는 "AOP" (새 API 사용 시 필수)
    private final RestTemplate restTemplate;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    
    public RestTemplateSchemaSyncExecutor(String hubUrl, String apiBasePath, RestTemplate restTemplate) {
        this(hubUrl, apiBasePath, null, restTemplate);
    }
    
    public RestTemplateSchemaSyncExecutor(String hubUrl, String apiBasePath, String instanceType, RestTemplate restTemplate) {
        this.hubUrl = hubUrl;
        this.apiBasePath = apiBasePath;
        this.instanceType = instanceType;
        this.restTemplate = restTemplate;
        this.objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
    }
    
    @Override
    public boolean syncToHub(List<SchemaMetadata> schemas, String hubId, String instanceId, Long currentVersion) throws Exception {
        // AOP는 복수형(/schemas/sync), Wrapper는 단수형(/schema/sync)
        boolean isAop = apiBasePath != null && apiBasePath.contains("/aop");
        String syncPath = isAop ? "/schemas/sync" : "/schema/sync";
        String syncUrl = hubUrl + apiBasePath + syncPath;
        
        // hubId 필수 검증
        if (hubId == null || hubId.trim().isEmpty()) {
            log.warn("Cannot perform schema sync without hubId");
            throw new IllegalStateException("hubId is required. Please perform instance registration first.");
        }
        
        // AOP는 AopSchemaSyncRequest 사용, Wrapper는 SchemaSyncRequest 사용
        Object request;
        if (isAop) {
            AopSchemaSyncRequest aopRequest = new AopSchemaSyncRequest();
            aopRequest.setInstanceId(instanceId);  // instanceId 포함 (hubId가 없을 때 자동 생성용)
            aopRequest.setSchemas(convertToAopSchemaInfo(schemas));
            request = aopRequest;
        } else {
            SchemaSyncRequest schemaRequest = new SchemaSyncRequest();
            schemaRequest.setInstanceId(instanceId);  // instanceId 포함 (hubId가 없을 때 자동 생성용)
            schemaRequest.setSchemas(schemas);
            request = schemaRequest;
        }
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-DADP-TENANT", hubId);  // hubId 필수
        /**
         * X-Instance-Id 헤더 추가
         * 
         * Hub가 헤더에서 instanceId(별칭)를 받아 스키마를 alias로 직접 저장할 수 있도록 함.
         * instanceId는 요청 바디에도 포함되지만, 헤더에서도 전달하여 Hub가 별도 조회 없이 사용할 수 있음.
         * 
         * @param instanceId 인스턴스 별칭 (null이거나 비어있으면 헤더에 추가하지 않음)
         */
        if (instanceId != null && !instanceId.trim().isEmpty()) {
            headers.set("X-Instance-Id", instanceId);
        }
        if (currentVersion != null) {
            headers.set("X-Current-Version", String.valueOf(currentVersion));
        }
        // Wrapper 사용 시 X-Instance-Type 헤더 추가
        if (!isAop && instanceType != null && !instanceType.trim().isEmpty()) {
            headers.set("X-Instance-Type", instanceType);
        }
        HttpEntity<Object> entity = new HttpEntity<Object>(request, headers);
        
        log.debug("Request body: {}", request);
        ResponseEntity<Map<String, Object>> response;
        try {
            org.springframework.core.ParameterizedTypeReference<Map<String, Object>> typeRef = 
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {};
            response = restTemplate.exchange(syncUrl, HttpMethod.POST, entity, typeRef);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // 404 Not Found: hubId를 찾을 수 없음 (등록되지 않은 hubId) -> 예외를 다시 던져서 상위에서 등록 처리
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                log.warn("Hub returned 404 for hubId={}, re-registration required", hubId);
                throw e; // 예외를 다시 던져서 상위에서 인스턴스 등록 API 호출
            }
            throw e;
        }
        
        // 304 Not Modified 처리
        if (response.getStatusCode() == HttpStatus.NOT_MODIFIED) {
            log.debug("Schema sync not needed (304): version unchanged, currentVersion={}", currentVersion);
            return true;
        }
        
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            Map<String, Object> responseBody = response.getBody();
            Boolean success = (Boolean) responseBody.get("success");
            if (Boolean.TRUE.equals(success)) {
                log.debug("AOP schema info sent to Hub: {} fields, hubId={}", schemas.size(), hubId);
                return true;
            } else {
                String message = (String) responseBody.get("message");
                log.warn("Hub schema sync failed: {}, URL={}", message != null ? message : "Unknown error", syncUrl);
                return false;
            }
        } else {
            log.warn("Hub schema sync failed: HTTP {}, URL={}", response.getStatusCode(), syncUrl);
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
        java.util.ArrayList<AopSchemaInfo> result = new java.util.ArrayList<AopSchemaInfo>();
        for (SchemaMetadata schema : schemas) {
            AopSchemaInfo info = new AopSchemaInfo();
            info.setSchemaName(schema.getSchemaName() != null ? schema.getSchemaName() : "public");
            info.setTableName(schema.getTableName());
            info.setColumnName(schema.getColumnName());
            info.setPolicyName(schema.getPolicyName() != null ? schema.getPolicyName() : "dadp");
            result.add(info);
        }
        return result;
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

