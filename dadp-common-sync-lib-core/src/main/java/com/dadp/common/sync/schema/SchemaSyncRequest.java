package com.dadp.common.sync.schema;

import java.util.List;

/**
 * 스키마 동기화 요청 DTO (공통)
 * 
 * AOP와 Wrapper 모두에서 사용하는 공통 DTO입니다.
 * 
 * @author DADP Development Team
 * @version 5.0.9
 * @since 2026-01-06
 */
public class SchemaSyncRequest {
    private String instanceId;
    private String hubId;  // Hub가 발급한 고유 ID (선택, AOP에서 사용)
    private List<SchemaMetadata> schemas;
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
    
    public List<SchemaMetadata> getSchemas() {
        return schemas;
    }
    
    public void setSchemas(List<SchemaMetadata> schemas) {
        this.schemas = schemas;
    }
}

