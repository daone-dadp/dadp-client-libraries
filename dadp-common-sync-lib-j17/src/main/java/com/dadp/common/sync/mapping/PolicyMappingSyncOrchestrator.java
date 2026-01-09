package com.dadp.common.sync.mapping;

import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;
import com.dadp.common.sync.config.HubIdManager;
import com.dadp.common.sync.policy.PolicyResolver;
import com.dadp.common.sync.schema.SchemaStorage;
import java.util.Map;

/**
 * 정책 매핑 동기화 오케스트레이터
 * 
 * checkMappingChange() 플로우를 관리합니다.
 * - 304: 아무것도 안함
 * - 200: 갱신 (정책 매핑, url, 버전 등)
 * - 404: 등록 콜백 호출
 * 
 * AOP와 Wrapper 모두에서 사용 가능하도록 설계되었습니다.
 * 
 * @author DADP Development Team
 * @version 5.2.0
 * @since 2026-01-07
 */
public class PolicyMappingSyncOrchestrator {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(PolicyMappingSyncOrchestrator.class);
    
    private final HubIdManager hubIdManager;
    private volatile MappingSyncService mappingSyncService;  // hubId 변경 시 재생성 필요
    private final PolicyResolver policyResolver;
    private final SchemaStorage schemaStorage;
    private final SyncCallbacks callbacks;
    
    /**
     * 동기화 콜백 인터페이스
     */
    public interface SyncCallbacks {
        /**
         * 등록 필요 시 호출 (404 또는 hubId 없음)
         */
        void onRegistrationNeeded();
        
        /**
         * 재등록 발생 시 호출 (스키마 재전송 필요)
         * 
         * @param newHubId 새로운 hubId
         */
        void onReregistration(String newHubId);
        
        /**
         * 엔드포인트 동기화 후 호출 (암복호화 어댑터 업데이트 등)
         * 
         * @param endpointData 엔드포인트 데이터
         */
        void onEndpointSynced(Object endpointData);
    }
    
    /**
     * 생성자
     * 
     * @param hubIdManager HubId 관리자
     * @param mappingSyncService 매핑 동기화 서비스
     * @param policyResolver 정책 리졸버
     * @param schemaStorage 스키마 저장소 (null 가능)
     * @param callbacks 동기화 콜백
     */
    public PolicyMappingSyncOrchestrator(
            HubIdManager hubIdManager,
            MappingSyncService mappingSyncService,
            PolicyResolver policyResolver,
            SchemaStorage schemaStorage,
            SyncCallbacks callbacks) {
        this.hubIdManager = hubIdManager;
        this.mappingSyncService = mappingSyncService;
        this.policyResolver = policyResolver;
        this.schemaStorage = schemaStorage;
        this.callbacks = callbacks;
    }
    
    /**
     * MappingSyncService 업데이트 (hubId 변경 시 호출)
     * 
     * @param newMappingSyncService 새로운 MappingSyncService 인스턴스
     */
    public void updateMappingSyncService(MappingSyncService newMappingSyncService) {
        this.mappingSyncService = newMappingSyncService;
        log.info("🔄 PolicyMappingSyncOrchestrator의 MappingSyncService 업데이트 완료");
    }
    
    /**
     * 정책 매핑 변경 확인 및 동기화
     * 
     * 플로우:
     * 1. hubId 확인 → 없으면 등록 콜백 호출
     * 2. 버전 체크
     * 3. 재등록 처리
     * 4. 정책 매핑 동기화
     * 5. 엔드포인트 동기화
     */
    public void checkMappingChange() {
        // hubId가 없으면 등록 수행
        if (!hubIdManager.hasHubId()) {
            log.info("🔄 hubId가 없어 등록 수행");
            if (callbacks != null) {
                callbacks.onRegistrationNeeded();
            }
            return;
        }
        
        try {
            // 현재 버전 확인 (PolicyResolver에서 캐싱된 버전 사용)
            Long currentVersion = policyResolver.getCurrentVersion();
            log.trace("📋 Hub 버전 확인 요청: 현재 버전={}", currentVersion);
            
            // 재등록 감지용 배열
            String[] reregisteredHubId = new String[1];
            
            // Hub에서 버전 변경 여부 확인
            boolean hasChange = mappingSyncService.checkMappingChange(currentVersion, reregisteredHubId);
            
            // 404 응답 처리: NEED_REGISTRATION이면 재등록 필요
            if (reregisteredHubId[0] != null && "NEED_REGISTRATION".equals(reregisteredHubId[0])) {
                log.info("🔄 Hub에서 hubId를 찾을 수 없음 (404), 등록 수행");
                if (callbacks != null) {
                    callbacks.onRegistrationNeeded();
                }
                return;
            }
            
            // 재등록 처리
            boolean isReregistered = reregisteredHubId[0] != null;
            if (isReregistered) {
                String reregisteredHubIdValue = reregisteredHubId[0];
                log.info("🔄 재등록 발생: hubId={}, 스키마 재전송", reregisteredHubIdValue);
                
                // hubId 업데이트 (HubIdManager를 통해 저장 및 콜백 자동 호출)
                hubIdManager.setHubId(reregisteredHubIdValue, true);
                
                // 재등록 콜백 호출 (스키마 재전송 등)
                if (callbacks != null) {
                    callbacks.onReregistration(reregisteredHubIdValue);
                }
                
                // 재등록 후에는 정책 매핑을 강제로 동기화
                hasChange = true;
                currentVersion = 0L;
            }
            
            // 버전 체크 결과에 따라 처리
            if (hasChange) {
                // 200 OK: 버전 변경 -> 갱신 (정책 매핑, url, 버전 등)
                log.info("🔄 정책 매핑 변경 감지, Hub에서 최신 정보 로드 시작");
                
                // 1. 정책 매핑 동기화 및 버전 업데이트 (영구저장소에 자동 저장됨, 캐시도 업데이트됨)
                int loadedCount = mappingSyncService.syncPolicyMappingsAndUpdateVersion(currentVersion);
                
                // 2. 저장된 스키마의 정책명 업데이트
                updateSchemaPolicyNames();
                
                // 3. 엔드포인트 동기화 콜백 호출 (버전 변경 시 정책 매핑, url, 버전 모두 동기화)
                // 엔드포인트 정보는 정책 스냅샷 응답에서 받아옴
                if (callbacks != null) {
                    try {
                        Map<String, Object> endpointInfo = mappingSyncService.getLastEndpointInfo();
                        callbacks.onEndpointSynced(endpointInfo); // 정책 스냅샷에서 받은 엔드포인트 정보 전달
                    } catch (Exception e) {
                        log.warn("⚠️ 엔드포인트 동기화 콜백 호출 실패: {}", e.getMessage());
                    }
                }
                
                log.info("✅ 정책 매핑 동기화 완료: {}개 매핑", loadedCount);
            } else {
                // 304 Not Modified: 버전 동일 -> 아무것도 하지 않음
                log.trace("⏭️ 정책 매핑 변경 없음 (version={}, 304 Not Modified)", currentVersion);
            }
            
        } catch (IllegalStateException e) {
            // 404로 인한 재등록 필요 예외 처리
            String errorMessage = e.getMessage();
            if (errorMessage != null && errorMessage.contains("404")) {
                log.info("🔄 Hub에서 hubId를 찾을 수 없음 (404), 등록 수행");
                if (callbacks != null) {
                    callbacks.onRegistrationNeeded();
                }
                return;
            }
            log.warn("⚠️ 버전 체크 실패: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("⚠️ 버전 체크 실패: {}", e.getMessage());
        }
    }
    
    /**
     * 저장된 스키마의 정책명 업데이트
     */
    private void updateSchemaPolicyNames() {
        if (schemaStorage == null) {
            return;
        }
        
        try {
            // PolicyResolver에서 모든 매핑 가져오기
            Map<String, String> policyMappings = policyResolver.getAllMappings();
            
            // SchemaStorage에서 정책명 업데이트
            int updatedCount = schemaStorage.updatePolicyNames(policyMappings);
            if (updatedCount > 0) {
                log.debug("📋 스키마 정책명 업데이트 완료: {}개", updatedCount);
            }
        } catch (Exception e) {
            log.warn("⚠️ 스키마 정책명 업데이트 실패: {}", e.getMessage());
        }
    }
}

