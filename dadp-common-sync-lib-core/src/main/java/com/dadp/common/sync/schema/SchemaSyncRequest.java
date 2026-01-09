package com.dadp.common.sync.schema;

import java.util.List;

/**
 * 스키마 동기화 요청 DTO (공통)
 * 
 * AOP와 Wrapper 모두에서 사용하는 공통 DTO입니다.
 * hubId가 Hub에 없을 때 자동으로 인스턴스를 생성하기 위해 instanceId를 포함합니다.
 * hubId는 헤더(X-DADP-TENANT)로 전송됩니다.
 * 
 * @author DADP Development Team
 * @version 5.4.0
 * @since 2026-01-08
 */
public class SchemaSyncRequest {
    private String instanceId;  // 인스턴스 별칭 (hubId가 없을 때 자동 생성용)
    private List<SchemaMetadata> schemas;
    // currentVersion은 헤더(X-Current-Version)로 전송됨
    // hubId는 헤더(X-DADP-TENANT)로 전송됨
    
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

