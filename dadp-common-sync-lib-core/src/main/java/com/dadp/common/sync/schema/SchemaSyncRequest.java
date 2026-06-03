package com.dadp.common.sync.schema;

import java.util.List;

/**
 * 스키마 동기화 요청 DTO (공통)
 * 
 * Wrapper schema synchronization DTO.
 * instanceId carries the immutable wrapper alias for schema payload context.
 * tenantId는 헤더(X-DADP-Tenant-Id)로 전송됩니다.
 * 
 * @author DADP Development Team
 * @version 5.4.0
 * @since 2026-01-08
 */
public class SchemaSyncRequest {
    private String instanceId;  // wrapper alias
    private List<SchemaMetadata> schemas;
    // currentVersion은 헤더(X-Current-Version)로 전송됨
    // tenantId는 헤더(X-DADP-Tenant-Id)로 전송됨
    
    public String getInstanceId() {
        return instanceId;
    }
    
    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }
    
    public List<SchemaMetadata> getSchemas() {
        return schemas;
    }
    
    public void setSchemas(List<SchemaMetadata> schemas) {
        this.schemas = schemas;
    }
}
