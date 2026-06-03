package com.dadp.common.sync.config;

import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;

/**
 * TenantId 관리자
 * 
 * tenantId 캐싱, 영구저장소 로드/저장, 변경 감지 및 콜백 처리를 담당합니다.
 * Used by the JDBC wrapper runtime enrollment flow.
 * 
 * @author DADP Development Team
 * @version 5.2.0
 * @since 2026-01-07
 */
public class TenantIdManager {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(TenantIdManager.class);
    
    private final InstanceConfigStorage configStorage;
    private final String hubUrl;
    private final InstanceIdProvider instanceIdProvider;
    
    // 캐시된 tenantId (volatile로 변경 감지)
    private volatile String cachedTenantId = null;
    private volatile String cachedDatasourceId = null;
    private volatile String cachedRefreshUrl = null;
    private volatile String cachedSchemaSyncUrl = null;
    private volatile String cachedRuntimeVersion = null;
    
    // tenantId 변경 콜백 (각 모듈에서 MappingSyncService 재생성 등 처리)
    private final TenantIdChangeCallback changeCallback;
    
    /**
     * TenantId 변경 콜백 인터페이스
     */
    public interface TenantIdChangeCallback {
        /**
         * tenantId가 변경되었을 때 호출됨
         * 
         * @param oldTenantId 이전 tenantId (null 가능)
         * @param newTenantId 새로운 tenantId
         */
        void onTenantIdChanged(String oldTenantId, String newTenantId);
    }
    
    /**
     * 생성자
     * 
     * @param configStorage 인스턴스 설정 저장소
     * @param hubUrl Hub URL
     * @param instanceIdProvider instanceId 제공자
     * @param changeCallback tenantId 변경 콜백 (null 가능)
     */
    public TenantIdManager(InstanceConfigStorage configStorage, 
                       String hubUrl, 
                       InstanceIdProvider instanceIdProvider,
                       TenantIdChangeCallback changeCallback) {
        this.configStorage = configStorage;
        this.hubUrl = hubUrl;
        this.instanceIdProvider = instanceIdProvider;
        this.changeCallback = changeCallback;
    }
    
    /**
     * 영구저장소에서 tenantId 로드
     * 
     * @return 로드된 tenantId (없으면 null)
     */
    public String loadFromStorage() {
        String instanceId = instanceIdProvider.getInstanceId();
        InstanceConfigStorage.ConfigData config = configStorage.loadConfig(hubUrl, instanceId);
        if (config != null && config.getTenantId() != null && !config.getTenantId().trim().isEmpty()) {
            String loadedTenantId = config.getTenantId();
            this.cachedDatasourceId = trimToNull(config.getDatasourceId());
            this.cachedRefreshUrl = trimToNull(config.getRefreshUrl());
            this.cachedSchemaSyncUrl = trimToNull(config.getSchemaSyncUrl());
            this.cachedRuntimeVersion = trimToNull(config.getRuntimeVersion());
            setTenantId(loadedTenantId, false); // 저장소에서 로드한 것이므로 저장 불필요 (콜백 미호출)
            log.debug("TenantId loaded from persistent storage: tenantId={}, datasourceId={}",
                    loadedTenantId, cachedDatasourceId);
            return loadedTenantId;
        }
        log.debug("No tenantId in persistent storage");
        return null;
    }
    
    /**
     * tenantId 설정 (변경 감지 및 콜백 호출)
     * 
     * @param tenantId 새로운 tenantId
     * @param saveToStorage 영구저장소에 저장할지 여부
     */
    public void setTenantId(String tenantId, boolean saveToStorage) {
        String oldTenantId = this.cachedTenantId;
        
        // tenantId가 변경되었는지 확인
        if (oldTenantId != null && oldTenantId.equals(tenantId)) {
            // 변경 없음
            return;
        }
        
        // tenantId 업데이트
        this.cachedTenantId = tenantId;
        
        // 영구저장소에 저장
        if (saveToStorage && tenantId != null && !tenantId.trim().isEmpty()) {
            String instanceId = instanceIdProvider.getInstanceId();
            configStorage.saveConfig(tenantId, hubUrl, instanceId, null,
                    cachedDatasourceId,
                    cachedRefreshUrl, cachedSchemaSyncUrl, cachedRuntimeVersion);
            log.debug("TenantId saved: tenantId={}", tenantId);
        }
        
        // 변경 콜백 호출: 저장소에서 로드한 초기값(null→tenantId)이 아닐 때만 호출 (풀 커넥션별 중복 콜백 방지)
        boolean isRealChange = saveToStorage || (oldTenantId != null);
        if (changeCallback != null && isRealChange) {
            try {
                changeCallback.onTenantIdChanged(oldTenantId, tenantId);
                log.debug("TenantId change callback invoked: oldTenantId={}, newTenantId={}", oldTenantId, tenantId);
            } catch (Exception e) {
                log.warn("TenantId change callback failed: {}", e.getMessage());
            }
        }
    }
    
    /**
     * 현재 캐시된 tenantId 조회
     * 
     * @return 캐시된 tenantId (없으면 null)
     */
    public String getCachedTenantId() {
        return cachedTenantId;
    }

    public void setWrapperEnrollment(String tenantId,
                                     String datasourceId,
                                     String refreshUrl,
                                     String schemaSyncUrl,
                                     String runtimeVersion,
                                     boolean saveToStorage) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            return;
        }
        if (cachedTenantId != null && !cachedTenantId.equals(tenantId)) {
            log.warn("Wrapper enrollment ignored due to tenantId mismatch: cachedTenantId={}, requestedTenantId={}", cachedTenantId, tenantId);
            return;
        }
        setTenantId(tenantId, false);
        if (datasourceId != null && !datasourceId.trim().isEmpty()) {
            this.cachedDatasourceId = datasourceId.trim();
        }
        if (refreshUrl != null && !refreshUrl.trim().isEmpty()) {
            this.cachedRefreshUrl = refreshUrl.trim();
        }
        if (schemaSyncUrl != null && !schemaSyncUrl.trim().isEmpty()) {
            this.cachedSchemaSyncUrl = schemaSyncUrl.trim();
        }
        if (runtimeVersion != null && !runtimeVersion.trim().isEmpty()) {
            this.cachedRuntimeVersion = runtimeVersion.trim();
        }
        if (saveToStorage) {
            String instanceId = instanceIdProvider.getInstanceId();
            configStorage.saveConfig(tenantId, hubUrl, instanceId, null,
                    cachedDatasourceId,
                    cachedRefreshUrl, cachedSchemaSyncUrl, cachedRuntimeVersion);
            log.debug("Wrapper enrollment saved: tenantId={}, datasourceId={}", tenantId, cachedDatasourceId);
        }
    }

    public String getCachedDatasourceId() {
        return cachedDatasourceId;
    }

    public String getCachedRefreshUrl() {
        return cachedRefreshUrl;
    }

    public String getCachedSchemaSyncUrl() {
        return cachedSchemaSyncUrl;
    }

    public String getCachedRuntimeVersion() {
        return cachedRuntimeVersion;
    }

    public boolean hasRuntimeEnrollment() {
        return hasTenantId()
                && cachedDatasourceId != null && !cachedDatasourceId.trim().isEmpty()
                && cachedRefreshUrl != null && !cachedRefreshUrl.trim().isEmpty()
                && cachedSchemaSyncUrl != null && !cachedSchemaSyncUrl.trim().isEmpty();
    }
    
    /**
     * tenantId가 있는지 확인
     * 
     * @return tenantId가 있으면 true
     */
    public boolean hasTenantId() {
        return cachedTenantId != null && !cachedTenantId.trim().isEmpty();
    }
    
    /**
     * tenantId 초기화 (테스트용)
     */
    public void clear() {
        String oldTenantId = this.cachedTenantId;
        this.cachedTenantId = null;
        this.cachedDatasourceId = null;
        this.cachedRefreshUrl = null;
        this.cachedSchemaSyncUrl = null;
        this.cachedRuntimeVersion = null;
        if (changeCallback != null && oldTenantId != null) {
            try {
                changeCallback.onTenantIdChanged(oldTenantId, null);
            } catch (Exception e) {
                log.warn("TenantId clear callback failed: {}", e.getMessage());
            }
        }
    }

    private static String trimToNull(String value) {
        return value != null && !value.trim().isEmpty() ? value.trim() : null;
    }
}
