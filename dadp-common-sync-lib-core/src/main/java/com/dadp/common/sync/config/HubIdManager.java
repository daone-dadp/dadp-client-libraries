package com.dadp.common.sync.config;

import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;

/**
 * HubId 관리자
 * 
 * hubId 캐싱, 영구저장소 로드/저장, 변경 감지 및 콜백 처리를 담당합니다.
 * AOP와 Wrapper 모두에서 사용 가능하도록 설계되었습니다.
 * 
 * @author DADP Development Team
 * @version 5.2.0
 * @since 2026-01-07
 */
public class HubIdManager {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(HubIdManager.class);
    
    private final InstanceConfigStorage configStorage;
    private final String hubUrl;
    private final InstanceIdProvider instanceIdProvider;
    
    // 캐시된 hubId (volatile로 변경 감지)
    private volatile String cachedHubId = null;
    
    // hubId 변경 콜백 (각 모듈에서 MappingSyncService 재생성 등 처리)
    private final HubIdChangeCallback changeCallback;
    
    /**
     * HubId 변경 콜백 인터페이스
     */
    public interface HubIdChangeCallback {
        /**
         * hubId가 변경되었을 때 호출됨
         * 
         * @param oldHubId 이전 hubId (null 가능)
         * @param newHubId 새로운 hubId
         */
        void onHubIdChanged(String oldHubId, String newHubId);
    }
    
    /**
     * 생성자
     * 
     * @param configStorage 인스턴스 설정 저장소
     * @param hubUrl Hub URL
     * @param instanceIdProvider instanceId 제공자
     * @param changeCallback hubId 변경 콜백 (null 가능)
     */
    public HubIdManager(InstanceConfigStorage configStorage, 
                       String hubUrl, 
                       InstanceIdProvider instanceIdProvider,
                       HubIdChangeCallback changeCallback) {
        this.configStorage = configStorage;
        this.hubUrl = hubUrl;
        this.instanceIdProvider = instanceIdProvider;
        this.changeCallback = changeCallback;
    }
    
    /**
     * 영구저장소에서 hubId 로드
     * 
     * @return 로드된 hubId (없으면 null)
     */
    public String loadFromStorage() {
        String instanceId = instanceIdProvider.getInstanceId();
        InstanceConfigStorage.ConfigData config = configStorage.loadConfig(hubUrl, instanceId);
        if (config != null && config.getHubId() != null && !config.getHubId().trim().isEmpty()) {
            String loadedHubId = config.getHubId();
            setHubId(loadedHubId, false); // 저장소에서 로드한 것이므로 저장 불필요
            log.info("📋 영구저장소에서 hubId 로드: hubId={}", loadedHubId);
            return loadedHubId;
        }
        log.debug("📋 영구저장소에 hubId 없음");
        return null;
    }
    
    /**
     * hubId 설정 (변경 감지 및 콜백 호출)
     * 
     * @param hubId 새로운 hubId
     * @param saveToStorage 영구저장소에 저장할지 여부
     */
    public void setHubId(String hubId, boolean saveToStorage) {
        String oldHubId = this.cachedHubId;
        
        // hubId가 변경되었는지 확인
        if (oldHubId != null && oldHubId.equals(hubId)) {
            // 변경 없음
            return;
        }
        
        // hubId 업데이트
        this.cachedHubId = hubId;
        
        // 영구저장소에 저장
        if (saveToStorage && hubId != null && !hubId.trim().isEmpty()) {
            String instanceId = instanceIdProvider.getInstanceId();
            configStorage.saveConfig(hubId, hubUrl, instanceId, null);
            log.info("💾 hubId 저장 완료: hubId={}", hubId);
        }
        
        // 변경 콜백 호출
        if (changeCallback != null) {
            try {
                changeCallback.onHubIdChanged(oldHubId, hubId);
                log.info("🔄 hubId 변경 콜백 호출 완료: oldHubId={}, newHubId={}", oldHubId, hubId);
            } catch (Exception e) {
                log.warn("⚠️ hubId 변경 콜백 호출 실패: {}", e.getMessage());
            }
        }
    }
    
    /**
     * 현재 캐시된 hubId 조회
     * 
     * @return 캐시된 hubId (없으면 null)
     */
    public String getCachedHubId() {
        return cachedHubId;
    }
    
    /**
     * hubId가 있는지 확인
     * 
     * @return hubId가 있으면 true
     */
    public boolean hasHubId() {
        return cachedHubId != null && !cachedHubId.trim().isEmpty();
    }
    
    /**
     * hubId 초기화 (테스트용)
     */
    public void clear() {
        String oldHubId = this.cachedHubId;
        this.cachedHubId = null;
        if (changeCallback != null && oldHubId != null) {
            try {
                changeCallback.onHubIdChanged(oldHubId, null);
            } catch (Exception e) {
                log.warn("⚠️ hubId 초기화 콜백 호출 실패: {}", e.getMessage());
            }
        }
    }
}

