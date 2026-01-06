package com.dadp.common.sync.schema;

import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;

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
        String syncUrl = hubUrl + apiBasePath + "/schemas/sync";
        
        // AOP 스키마 동기화 요청 DTO 생성
        AopSchemaSyncRequest request = new AopSchemaSyncRequest();
        request.setInstanceId(instanceId);
        request.setHubId(hubId);
        request.setSchemas(convertToAopSchemaInfo(schemas));
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (currentVersion != null) {
            headers.set("X-Current-Version", String.valueOf(currentVersion));
        }
        HttpEntity<AopSchemaSyncRequest> entity = new HttpEntity<>(request, headers);
        
        log.debug("📤 요청 본문: {}", request);
        ResponseEntity<AopSchemaSyncResponse> response = restTemplate.exchange(
            syncUrl, HttpMethod.POST, entity, AopSchemaSyncResponse.class);
        
        // 304 Not Modified 처리
        if (response.getStatusCode() == HttpStatus.NOT_MODIFIED) {
            log.debug("✅ 스키마 동기화 불필요 (304): 버전이 동일함, currentVersion={}", currentVersion);
            return true;
        }
        
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            AopSchemaSyncResponse syncResponse = response.getBody();
            if (syncResponse.isSuccess()) {
                log.info("✅ Hub에 AOP 스키마 정보 전송 완료: {}개 필드", schemas.size());
                return true;
            } else {
                log.warn("⚠️ Hub 스키마 동기화 실패: {}, URL={}", syncResponse.getMessage(), syncUrl);
                return false;
            }
        } else {
            log.warn("⚠️ Hub 스키마 동기화 실패: HTTP {}, URL={}", response.getStatusCode(), syncUrl);
            return false;
        }
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
     */
    public static class AopSchemaSyncRequest {
        private String instanceId;
        private String hubId;
        private List<AopSchemaInfo> schemas;
        
        public String getInstanceId() { return instanceId; }
        public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
        public String getHubId() { return hubId; }
        public void setHubId(String hubId) { this.hubId = hubId; }
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

