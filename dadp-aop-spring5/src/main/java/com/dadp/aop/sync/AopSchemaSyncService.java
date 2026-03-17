package com.dadp.aop.sync;

import com.dadp.aop.metadata.EncryptionMetadataInitializer;
import com.dadp.common.sync.config.InstanceConfigStorage;
import com.dadp.common.sync.policy.PolicyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AOP 스키마 동기화 서비스
 * 
 * EncryptionMetadataInitializer에서 수집한 암호화 필드 정보를 Hub로 전송합니다.
 * Hub는 이 정보를 바탕으로 스키마(테이블.컬럼)와 정책을 매핑할 수 있습니다.
 * 
 * @author DADP Development Team
 * @version 5.0.4
 * @since 2025-12-30
 */
public class AopSchemaSyncService {
    
    private static final Logger log = LoggerFactory.getLogger(AopSchemaSyncService.class);
    
    private final String hubUrl;
    private final String instanceId;  // AOP 인스턴스 ID (별칭)
    private final String hubId;       // Hub가 발급한 고유 ID (null일 수 있음)
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final EncryptionMetadataInitializer metadataInitializer;
    private final InstanceConfigStorage configStorage;  // 영구저장소 (공통 라이브러리 사용)
    private final PolicyResolver policyResolver;  // 버전 정보 조회용
    
    public AopSchemaSyncService(String hubUrl, String instanceId, String hubId,
                                EncryptionMetadataInitializer metadataInitializer,
                                PolicyResolver policyResolver) {
        this.hubUrl = hubUrl;
        this.instanceId = instanceId;
        this.hubId = hubId;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        this.metadataInitializer = metadataInitializer;
        this.policyResolver = policyResolver;
        
        // 영구저장소 초기화 (공통 라이브러리 사용)
        this.configStorage = new InstanceConfigStorage(
            System.getProperty("user.home") + "/.dadp-aop",
            "aop-config.json"
        );
    }
    
    /**
     * Hub에 스키마 정보 전송
     * 
     * EncryptionMetadataInitializer에서 수집한 암호화 필드 정보를 Hub로 전송합니다.
     * 
     * @return 전송 성공 여부
     */
    public boolean syncSchemasToHub() {
        if (metadataInitializer == null) {
            log.warn("EncryptionMetadataInitializer not available, skipping schema sync.");
            return false;
        }
        
        // Hub에 전송할 URL (catch 블록에서도 사용하기 위해 메서드 시작 부분에서 선언)
        // V1 API 사용: /hub/api/v1/aop/schemas/sync
        String syncPath = "/hub/api/v1/aop/schemas/sync";
        String syncUrl = hubUrl + syncPath;
        
        try {
            log.info("Sending AOP schema info to Hub: hubUrl={}, instanceId={}, hubId={}, URL={}",
                    hubUrl, instanceId, hubId, syncUrl);
            
            // EncryptionMetadataInitializer에서 암호화 필드 정보 수집
            Map<String, String> encryptedColumns = metadataInitializer.getAllEncryptedColumns();
            
            if (encryptedColumns.isEmpty()) {
                log.info("No encrypted fields, skipping schema sync.");
                return true; // 필드가 없는 것은 정상 상태
            }
            
            // Hub API 형식으로 변환
            List<AopSchemaInfo> schemas = new ArrayList<>();
            for (Map.Entry<String, String> entry : encryptedColumns.entrySet()) {
                String key = entry.getKey(); // "table.column" 형식
                String[] parts = key.split("\\.", 2);
                if (parts.length == 2) {
                    String tableName = parts[0];
                    String columnName = parts[1];
                    // schemaName은 기본값 "public" 사용 (AOP는 스키마 개념이 없음)
                    String schemaName = "public";
                    
                    AopSchemaInfo schema = new AopSchemaInfo();
                    schema.setSchemaName(schemaName);
                    schema.setTableName(tableName);
                    schema.setColumnName(columnName);
                    // policy는 deprecated이므로 무시하고 기본값 "dadp" 사용
                    schema.setPolicyName("dadp");
                    schemas.add(schema);
                }
            }
            log.info("Hub schema sync API call: URL={}, instanceId={}, hubId={}, schemas={}",
                    syncUrl, instanceId, hubId, schemas.size());
            
            // 현재 버전 조회 (헤더에 포함)
            Long currentVersion = policyResolver != null ? policyResolver.getCurrentVersion() : null;
            
            AopSchemaSyncRequest request = new AopSchemaSyncRequest();
            request.setInstanceId(instanceId);
            request.setHubId(hubId);
            request.setSchemas(schemas);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // 헤더에 버전 포함 (버전 동기화용)
            if (currentVersion != null) {
                headers.set("X-Current-Version", String.valueOf(currentVersion));
            }
            HttpEntity<AopSchemaSyncRequest> entity = new HttpEntity<>(request, headers);
            
            log.debug("Request body: {}", request);
            ResponseEntity<AopSchemaSyncResponse> response = restTemplate.exchange(
                syncUrl, HttpMethod.POST, entity, AopSchemaSyncResponse.class);
            
            // 304 Not Modified 처리 (버전이 같으면 스키마 데이터 없이 반환)
            if (response.getStatusCode() == org.springframework.http.HttpStatus.NOT_MODIFIED) {
                log.debug("Schema sync not needed (304): version unchanged, currentVersion={}", currentVersion);
                return true;
            }
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                AopSchemaSyncResponse syncResponse = response.getBody();
                if (syncResponse.isSuccess()) {
                    // hubId가 응답에 포함되어 있으면 업데이트
                    if (syncResponse.getData() != null && syncResponse.getData().getHubId() != null) {
                        String newHubId = syncResponse.getData().getHubId();
                        log.info("hubId received from Hub: {}", newHubId);

                        // hubId를 영구저장소에 저장 (공통 라이브러리 사용)
                        boolean saved = configStorage.saveConfig(newHubId, hubUrl, instanceId, null);
                        if (saved) {
                            log.info("hubId saved to persistent storage: hubId={}, path={}",
                                    newHubId, configStorage.getStoragePath());
                        } else {
                            log.warn("hubId save failed: hubId={}", newHubId);
                        }
                    }
                    
                    log.info("AOP schema info sent to Hub: {} fields, URL={}", schemas.size(), syncUrl);
                    return true;
                } else {
                    log.warn("Hub schema sync failed: {}, URL={}", syncResponse.getMessage(), syncUrl);
                    return false;
                }
            } else {
                log.warn("Hub schema sync failed: HTTP {}, URL={}", response.getStatusCode(), syncUrl);
                return false;
            }
            
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.error("Hub schema sync HTTP error: status={}, URL={}, message={}",
                    e.getStatusCode(), syncUrl, e.getMessage());
            if (e.getResponseBodyAsString() != null) {
                log.error("Response body: {}", e.getResponseBodyAsString());
            }
            return false;
        } catch (Exception e) {
            log.error("Hub schema sync failed: URL={}, error={}", syncUrl, e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * AOP 스키마 동기화 요청 DTO
     * 
     * currentVersion은 헤더(X-Current-Version)로 전송되므로 body에 포함하지 않습니다.
     */
    public static class AopSchemaSyncRequest {
        private String instanceId;
        private String hubId;
        private List<AopSchemaInfo> schemas;
        // currentVersion은 헤더(X-Current-Version)로 전송됨
        
        public String getInstanceId() {
            return instanceId;
        }
        
        public void setInstanceId(String instanceId) {
            this.instanceId = instanceId;
        }
        
        public String getHubId() {
            return hubId;
        }
        
        public void setHubId(String hubId) {
            this.hubId = hubId;
        }
        
        public List<AopSchemaInfo> getSchemas() {
            return schemas;
        }
        
        public void setSchemas(List<AopSchemaInfo> schemas) {
            this.schemas = schemas;
        }
    }
    
    /**
     * AOP 스키마 정보 DTO
     */
    public static class AopSchemaInfo {
        private String schemaName;
        private String tableName;
        private String columnName;
        private String policyName;
        
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
    }
    
    /**
     * AOP 스키마 동기화 응답 DTO
     */
    public static class AopSchemaSyncResponse {
        private boolean success;
        private String code;
        private AopSchemaSyncResponseData data;
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
        
        public AopSchemaSyncResponseData getData() {
            return data;
        }
        
        public void setData(AopSchemaSyncResponseData data) {
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
     * AOP 스키마 동기화 응답 데이터 DTO
     */
    public static class AopSchemaSyncResponseData {
        private String hubId;
        
        public String getHubId() {
            return hubId;
        }
        
        public void setHubId(String hubId) {
            this.hubId = hubId;
        }
    }
}

