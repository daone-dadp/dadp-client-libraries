package com.dadp.common.sync.config;

/**
 * HubId 저장 콜백 인터페이스 (공통)
 * 
 * Hub에서 받은 hubId를 영구저장소에 저장하기 위한 콜백 인터페이스입니다.
 * 각 최종 모듈(aop8, aop17, wrapper8, wrapper17)에서 구현합니다.
 * 
 * @author DADP Development Team
 * @version 5.1.0
 * @since 2026-01-06
 */
public interface HubIdSaver {
    
    /**
     * Hub에서 받은 hubId를 영구저장소에 저장
     * 
     * @param hubId Hub에서 받은 hubId
     * @param instanceId 인스턴스 ID
     */
    void saveHubId(String hubId, String instanceId);
}

