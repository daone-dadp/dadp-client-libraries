package com.dadp.common.sync.config;

/**
 * TenantId 저장 콜백 인터페이스 (공통)
 * 
 * Callback retained only for older constructor compatibility.
 * DADP 6 tenant IDs are issued by CLI schema-register, not by schema-sync responses.
 * 
 * @author DADP Development Team
 * @version 5.1.0
 * @since 2026-01-06
 */
public interface TenantIdSaver {
    
    /**
     * Hub에서 받은 tenantId를 영구저장소에 저장
     * 
     * @param tenantId Hub에서 받은 tenantId
     * @param instanceId 인스턴스 ID
     */
    void saveTenantId(String tenantId, String instanceId);
}
