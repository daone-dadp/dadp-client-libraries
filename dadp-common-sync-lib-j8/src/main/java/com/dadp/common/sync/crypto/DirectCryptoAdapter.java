package com.dadp.common.sync.crypto;

import com.dadp.hub.crypto.HubCryptoService;
import com.dadp.common.sync.config.EndpointStorage;
import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;

/**
 * 직접 암복호화 어댑터 (공통 라이브러리)
 * 
 * Hub를 거치지 않고 Engine/Gateway에 직접 암복호화 요청을 수행합니다.
 * 엔드포인트 정보는 영구 저장소에서 로드하여 사용합니다.
 * 
 * Wrapper와 AOP 모두에서 사용하는 공통 컴포넌트입니다.
 * 
 * @author DADP Development Team
 * @version 5.0.5
 * @since 2025-12-31
 */
public class DirectCryptoAdapter {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(DirectCryptoAdapter.class);
    
    private final boolean failOpen;
    private volatile boolean endpointAvailable = true; // 엔드포인트 연결 가능 여부
    
    // 현재 사용 중인 HubCryptoService (Engine/Gateway 직접 연결)
    private volatile HubCryptoService currentCryptoService;
    
    // 현재 설정된 cryptoUrl (중복 초기화 방지용)
    private volatile String currentCryptoUrl;
    
    public DirectCryptoAdapter(boolean failOpen) {
        this.failOpen = failOpen;
        log.debug("✅ 직접 암복호화 어댑터 생성: failOpen={}", failOpen);
    }
    
    /**
     * 엔드포인트 정보 설정 및 HubCryptoService 초기화
     * 
     * @param endpointData 엔드포인트 데이터 (cryptoUrl만 사용)
     */
    public void setEndpointData(EndpointStorage.EndpointData endpointData) {
        if (endpointData == null) {
            log.warn("⚠️ 엔드포인트 데이터가 null입니다");
            return;
        }
        
        try {
            // cryptoUrl만 사용
            String cryptoUrl = endpointData.getCryptoUrl();
            if (cryptoUrl == null || cryptoUrl.trim().isEmpty()) {
                log.warn("⚠️ cryptoUrl이 없습니다");
                return;
            }
            
            // 이미 같은 cryptoUrl로 초기화되어 있으면 다시 초기화하지 않음
            String trimmedCryptoUrl = cryptoUrl.trim();
            if (currentCryptoService != null && currentCryptoUrl != null && currentCryptoUrl.equals(trimmedCryptoUrl)) {
                log.trace("✅ 암복호화 서비스가 이미 초기화되어 있음: cryptoUrl={}", trimmedCryptoUrl);
                return;
            }
            
            // apiBasePath는 기본값 "/api" 사용
            String apiBasePath = "/api";
            
            // DADP 통합 로그 설정에 따라 HubCryptoService의 enableLogging 설정
            Boolean enableLogging = DadpLoggerFactory.isLoggingEnabled();
            
            // cryptoUrl로 암복호화 서비스 초기화
            this.currentCryptoService = HubCryptoService.createInstance(trimmedCryptoUrl, apiBasePath, 5000, enableLogging);
            this.currentCryptoUrl = trimmedCryptoUrl;
            log.info("✅ 암복호화 서비스 초기화: cryptoUrl={}, apiBasePath={}, enableLogging={}", trimmedCryptoUrl, apiBasePath, enableLogging);
            
            endpointAvailable = true;
            
        } catch (Exception e) {
            log.error("❌ 암복호화 서비스 초기화 실패: {}", e.getMessage(), e);
            endpointAvailable = false;
        }
    }
    
    /**
     * 엔드포인트 연결 가능 여부 확인
     */
    public boolean isEndpointAvailable() {
        return endpointAvailable && currentCryptoService != null;
    }
    
    /**
     * 암호화
     * 
     * @param data 평문 데이터
     * @param policyName 정책명
     * @return 암호화된 데이터 (실패 시 failOpen 모드에 따라 원본 반환 또는 예외)
     */
    public String encrypt(String data, String policyName) {
        if (data == null) {
            return null;
        }
        
        if (currentCryptoService == null) {
            log.warn("⚠️ 암복호화 서비스가 초기화되지 않았습니다");
            if (failOpen) {
                return data;
            } else {
                throw new RuntimeException("암복호화 서비스가 초기화되지 않았습니다");
            }
        }
        
        try {
            log.debug("🔐 직접 암호화 요청: policy={}, dataLength={}", policyName, data != null ? data.length() : 0);
            // 엔진은 includeStats와 무관하게 항상 통계를 자동 수집함
            String encrypted = currentCryptoService.encrypt(data, policyName);
            log.debug("✅ 직접 암호화 완료");
            endpointAvailable = true;
            return encrypted;
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("⚠️ 직접 암호화 실패 (정책: {}): {}", policyName, errorMsg);
            
            endpointAvailable = false;
            
            if (failOpen) {
                log.debug("Fail-open 모드: 평문으로 저장");
                return data;
            } else {
                throw new RuntimeException("암호화 실패 (Fail-closed 모드)", e);
            }
        }
    }
    
    /**
     * 검색용 암호화
     * Engine이 정책(useIv/usePlain)에 따라 암호화 또는 평문을 반환한다.
     *
     * @param data 검색할 데이터 (평문)
     * @param policyName 정책명
     * @return 암호문 또는 평문 (Engine이 결정). 실패 시 평문 반환.
     */
    public String encryptForSearch(String data, String policyName) {
        if (data == null) {
            return null;
        }

        if (currentCryptoService == null) {
            log.warn("암복호화 서비스가 초기화되지 않았습니다 (encryptForSearch)");
            return data;
        }

        try {
            log.debug("검색용 암호화 요청: policy={}, dataLength={}", policyName, data.length());
            String result = currentCryptoService.encryptForSearch(data, policyName);
            log.debug("검색용 암호화 완료");
            endpointAvailable = true;
            return result;
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("검색용 암호화 실패 (정책: {}): {}, 평문 반환", policyName, errorMsg);
            return data;
        }
    }

    /**
     * 복호화
     *
     * @param encryptedData 암호화된 데이터 (또는 일반 텍스트)
     * @return 복호화된 데이터 (실패 시 failOpen 모드에 따라 원본 반환 또는 예외)
     */
    public String decrypt(String encryptedData) {
        return decrypt(encryptedData, null, null, null, false);
    }

    /**
     * 복호화 (정책명 포함 - FPE 등 prefix 없는 암호문 복호화용)
     *
     * @param encryptedData 암호화된 데이터 (또는 일반 텍스트)
     * @param policyName 암호화 정책명 (선택사항, FPE 복호화 시 필수)
     * @return 복호화된 데이터 (실패 시 failOpen 모드에 따라 원본 반환 또는 예외)
     */
    public String decrypt(String encryptedData, String policyName) {
        return decrypt(encryptedData, policyName, null, null, false);
    }

    /**
     * 복호화 (마스킹 정책 포함)
     *
     * @param encryptedData 암호화된 데이터 (또는 일반 텍스트)
     * @param maskPolicyName 마스킹 정책명 (선택사항)
     * @param maskPolicyUid 마스킹 정책 UID (선택사항)
     * @return 복호화된 데이터 (실패 시 failOpen 모드에 따라 원본 반환 또는 예외)
     */
    public String decrypt(String encryptedData, String maskPolicyName, String maskPolicyUid) {
        return decrypt(encryptedData, null, maskPolicyName, maskPolicyUid, false);
    }

    /**
     * 복호화 (정책명 + 마스킹 정책 + 통계 정보)
     *
     * @param encryptedData 암호화된 데이터 (또는 일반 텍스트)
     * @param policyName 암호화 정책명 (선택사항, FPE 복호화 시 필수)
     * @param maskPolicyName 마스킹 정책명 (선택사항)
     * @param maskPolicyUid 마스킹 정책 UID (선택사항)
     * @param includeStats 통계 정보 포함 여부
     * @return 복호화된 데이터 (실패 시 failOpen 모드에 따라 원본 반환 또는 예외)
     */
    public String decrypt(String encryptedData, String policyName, String maskPolicyName, String maskPolicyUid, boolean includeStats) {
        if (encryptedData == null) {
            return null;
        }
        
        if (currentCryptoService == null) {
            log.warn("⚠️ 암복호화 서비스가 초기화되지 않았습니다");
            if (failOpen) {
                return encryptedData;
            } else {
                throw new RuntimeException("암복호화 서비스가 초기화되지 않았습니다");
            }
        }
        
        try {
            log.debug("🔓 직접 복호화 요청: dataLength={}, policyName={}, maskPolicyName={}, maskPolicyUid={}",
                    encryptedData != null ? encryptedData.length() : 0, policyName, maskPolicyName, maskPolicyUid);
            // 엔진은 includeStats와 무관하게 항상 통계를 자동 수집함
            String decrypted = currentCryptoService.decrypt(encryptedData, policyName, maskPolicyName, maskPolicyUid, includeStats);
            
            if (decrypted == null) {
                log.debug("데이터가 암호화되지 않았습니다 - 원본 데이터 반환");
                return encryptedData;
            }
            
            log.debug("✅ 직접 복호화 완료");
            endpointAvailable = true;
            return decrypted;
        } catch (Exception e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("⚠️ 직접 복호화 실패: {}", errorMessage);
            
            endpointAvailable = false;
            
            if (failOpen) {
                log.debug("Fail-open 모드: 평문으로 저장");
                return encryptedData;
            } else {
                throw new RuntimeException("복호화 실패 (Fail-closed 모드)", e);
            }
        }
    }
    
    /**
     * 배치 복호화
     * 
     * @param encryptedDataList 복호화할 암호화된 데이터 목록
     * @param maskPolicyName 마스킹 정책명 (선택사항)
     * @param maskPolicyUid 마스킹 정책 UID (선택사항)
     * @param includeStats 통계 정보 포함 여부
     * @return 복호화된 데이터 목록 (순서 보장)
     */
    public java.util.List<String> batchDecrypt(java.util.List<String> encryptedDataList, 
                                                String maskPolicyName, 
                                                String maskPolicyUid, 
                                                boolean includeStats) {
        if (encryptedDataList == null || encryptedDataList.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        
        if (currentCryptoService == null) {
            log.warn("⚠️ 암복호화 서비스가 초기화되지 않았습니다");
            if (failOpen) {
                return encryptedDataList; // 원본 반환
            } else {
                throw new RuntimeException("암복호화 서비스가 초기화되지 않았습니다");
            }
        }
        
        try {
            log.debug("🔓 배치 복호화 요청: itemsCount={}, maskPolicyName={}, maskPolicyUid={}", 
                    encryptedDataList.size(), maskPolicyName, maskPolicyUid);
            return currentCryptoService.batchDecrypt(encryptedDataList, maskPolicyName, maskPolicyUid, includeStats);
        } catch (Exception e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("⚠️ 배치 복호화 실패: {}", errorMessage);
            
            if (failOpen) {
                return encryptedDataList; // 원본 반환
            } else {
                throw new RuntimeException("배치 복호화 실패 (Fail-closed 모드)", e);
            }
        }
    }
    
    /**
     * 배치 암호화
     * 
     * @param dataList 암호화할 평문 데이터 목록
     * @param policyList 각 데이터에 적용할 정책명 목록 (dataList와 동일한 크기)
     * @return 암호화된 데이터 목록 (순서 보장)
     */
    public java.util.List<String> batchEncrypt(java.util.List<String> dataList, 
                                                java.util.List<String> policyList) {
        if (dataList == null || dataList.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        
        if (policyList == null || policyList.size() != dataList.size()) {
            throw new IllegalArgumentException("정책 목록의 크기가 데이터 목록과 일치하지 않습니다");
        }
        
        if (currentCryptoService == null) {
            log.warn("⚠️ 암복호화 서비스가 초기화되지 않았습니다");
            if (failOpen) {
                return dataList; // 원본 반환
            } else {
                throw new RuntimeException("암복호화 서비스가 초기화되지 않았습니다");
            }
        }
        
        try {
            log.debug("🔐 배치 암호화 요청: itemsCount={}", dataList.size());
            return currentCryptoService.batchEncrypt(dataList, policyList);
        } catch (Exception e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("⚠️ 배치 암호화 실패: {}", errorMessage);
            
            if (failOpen) {
                return dataList; // 원본 반환
            } else {
                throw new RuntimeException("배치 암호화 실패 (Fail-closed 모드)", e);
            }
        }
    }
    
    /**
     * 데이터가 암호화된 형태인지 확인
     * 
     * @param data 확인할 데이터
     * @return 암호화된 데이터인지 여부
     */
    public boolean isEncryptedData(String data) {
        if (currentCryptoService == null) {
            return false;
        }
        return currentCryptoService.isEncryptedData(data);
    }
}

