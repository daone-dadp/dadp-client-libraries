package com.dadp.common.sync.crypto;

import com.dadp.hub.crypto.HubCryptoService;
import com.dadp.common.sync.config.EndpointStorage;
import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;
import com.dadp.wrapper.crypto.UnsupportedCryptoMaterialException;
import com.dadp.wrapper.crypto.WrapperLocalCryptoService;

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
    private volatile CryptoProfileRecorder profileRecorder;
    private volatile String singleTransportMode = "json";
    private volatile String engineTransport = "http";
    private volatile Integer engineBinaryPort = 9104;
    private volatile String cryptoMode = "remote";
    private volatile boolean localFallbackRemote = true;
    private volatile WrapperLocalCryptoService localCryptoService;
    private volatile String localHubBaseUrl;
    private volatile Integer localTimeoutMillis = 30000;
    private volatile String localHubAuthId;
    private volatile String localHubAuthSecret;
    
    public DirectCryptoAdapter(boolean failOpen) {
        this.failOpen = failOpen;
        log.trace("Direct crypto adapter created: failOpen={}", failOpen);
    }
    
    /**
     * 엔드포인트 정보 설정 및 HubCryptoService 초기화
     * 
     * @param endpointData 엔드포인트 데이터 (cryptoUrl만 사용)
     */
    public void setEndpointData(EndpointStorage.EndpointData endpointData) {
        if (endpointData == null) {
            log.warn("Endpoint data is null");
            return;
        }
        
        try {
            // cryptoUrl만 사용
            String cryptoUrl = endpointData.getCryptoUrl();
            if (cryptoUrl == null || cryptoUrl.trim().isEmpty()) {
                log.warn("cryptoUrl is missing");
                return;
            }
            
            // 이미 같은 cryptoUrl로 초기화되어 있으면 다시 초기화하지 않음
            String trimmedCryptoUrl = cryptoUrl.trim();
            if (currentCryptoService != null && currentCryptoUrl != null && currentCryptoUrl.equals(trimmedCryptoUrl)) {
                log.trace("Crypto service already initialized: cryptoUrl={}", trimmedCryptoUrl);
                return;
            }
            
            // apiBasePath는 기본값 "/api" 사용
            String apiBasePath = "/api";
            
            // DADP 통합 로그 설정에 따라 HubCryptoService의 enableLogging 설정
            Boolean enableLogging = DadpLoggerFactory.isLoggingEnabled();
            
            // cryptoUrl로 암복호화 서비스 초기화
            this.currentCryptoService = HubCryptoService.createInstance(trimmedCryptoUrl, apiBasePath, 5000, enableLogging);
            applySingleTransportMode(this.currentCryptoService);
            applyEngineTransport(this.currentCryptoService);
            applyProfileRecorder(this.currentCryptoService);
            this.currentCryptoUrl = trimmedCryptoUrl;
            log.debug("Crypto service initialized: cryptoUrl={}, apiBasePath={}, enableLogging={}", trimmedCryptoUrl, apiBasePath, enableLogging);
            
            endpointAvailable = true;
            
        } catch (Exception e) {
            log.warn("Crypto service initialization failed: {}", e.getMessage(), e);
            endpointAvailable = false;
        }
    }
    
    /**
     * 엔드포인트 연결 가능 여부 확인
     */
    public boolean isEndpointAvailable() {
        return endpointAvailable && currentCryptoService != null;
    }

    public void setProfileRecorder(CryptoProfileRecorder profileRecorder) {
        this.profileRecorder = profileRecorder;
        applyProfileRecorder(this.currentCryptoService);
    }

    public void setSingleTransportMode(String singleTransportMode) {
        if (singleTransportMode == null || singleTransportMode.trim().isEmpty()) {
            this.singleTransportMode = "json";
        } else {
            this.singleTransportMode = singleTransportMode.trim().toLowerCase();
        }
        applySingleTransportMode(this.currentCryptoService);
    }

    public void setEngineTransport(String engineTransport) {
        if (engineTransport == null || engineTransport.trim().isEmpty()) {
            this.engineTransport = "http";
        } else {
            this.engineTransport = engineTransport.trim().toLowerCase();
        }
        applyEngineTransport(this.currentCryptoService);
    }

    public void setEngineBinaryPort(Integer engineBinaryPort) {
        this.engineBinaryPort = engineBinaryPort != null ? engineBinaryPort : 9104;
        applyEngineTransport(this.currentCryptoService);
    }

    public void setCryptoMode(String cryptoMode, String hubBaseUrl, boolean localFallbackRemote, Integer timeoutMillis) {
        setCryptoMode(cryptoMode, hubBaseUrl, localFallbackRemote, timeoutMillis, null, null);
    }

    public void setCryptoMode(String cryptoMode, String hubBaseUrl, boolean localFallbackRemote,
                              Integer timeoutMillis, String hubAuthId, String hubAuthSecret) {
        String normalized = cryptoMode != null ? cryptoMode.trim().toLowerCase() : "remote";
        if (!"local".equals(normalized)) {
            normalized = "remote";
        }
        this.cryptoMode = normalized;
        this.localFallbackRemote = localFallbackRemote;
        int effectiveTimeout = timeoutMillis != null ? timeoutMillis : 30000;
        if ("local".equals(normalized)) {
            if (this.localCryptoService != null
                    && equalsNullable(this.localHubBaseUrl, hubBaseUrl)
                    && equalsNullable(this.localHubAuthId, hubAuthId)
                    && equalsNullable(this.localHubAuthSecret, hubAuthSecret)
                    && this.localTimeoutMillis != null
                    && this.localTimeoutMillis == effectiveTimeout) {
                return;
            }
            this.localCryptoService = new WrapperLocalCryptoService(hubBaseUrl, effectiveTimeout, hubAuthId, hubAuthSecret);
            this.localHubBaseUrl = hubBaseUrl;
            this.localTimeoutMillis = effectiveTimeout;
            this.localHubAuthId = hubAuthId;
            this.localHubAuthSecret = hubAuthSecret;
            log.info("Wrapper local crypto mode enabled: fallbackRemote={}", localFallbackRemote);
        } else {
            this.localCryptoService = null;
            this.localHubBaseUrl = null;
            this.localTimeoutMillis = effectiveTimeout;
            this.localHubAuthId = null;
            this.localHubAuthSecret = null;
            log.trace("Wrapper crypto mode set to remote");
        }
    }

    private void applySingleTransportMode(HubCryptoService cryptoService) {
        if (cryptoService == null) {
            return;
        }

        try {
            java.lang.reflect.Method method = cryptoService.getClass().getMethod("setSingleTransportMode", String.class);
            method.invoke(cryptoService, singleTransportMode);
        } catch (ReflectiveOperationException ignored) {
            log.trace("HubCryptoService does not expose optional single transport mode hook");
        }
    }

    private void applyEngineTransport(HubCryptoService cryptoService) {
        if (cryptoService == null) {
            return;
        }

        try {
            java.lang.reflect.Method transportMethod = cryptoService.getClass().getMethod("setEngineTransport", String.class);
            transportMethod.invoke(cryptoService, engineTransport);
        } catch (ReflectiveOperationException ignored) {
            log.trace("HubCryptoService does not expose optional engine transport hook");
        }

        try {
            java.lang.reflect.Method portMethod = cryptoService.getClass().getMethod("setEngineBinaryPort", Integer.class);
            portMethod.invoke(cryptoService, engineBinaryPort);
        } catch (ReflectiveOperationException ignored) {
            log.trace("HubCryptoService does not expose optional engine binary port hook");
        }
    }

    private void applyProfileRecorder(HubCryptoService cryptoService) {
        if (cryptoService == null || profileRecorder == null) {
            return;
        }

        try {
            java.lang.reflect.Method method = cryptoService.getClass().getMethod("setProfileRecorder", CryptoProfileRecorder.class);
            method.invoke(cryptoService, profileRecorder);
        } catch (ReflectiveOperationException ignored) {
            log.trace("HubCryptoService does not expose optional profile recorder hook");
        }
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

        if (isLocalCryptoEnabled()) {
            try {
                log.trace("Local encryption request: policy={}, dataLength={}", policyName, data.length());
                String encrypted = localCryptoService.encrypt(data, policyName);
                log.trace("Local encryption completed");
                endpointAvailable = true;
                return encrypted;
            } catch (UnsupportedCryptoMaterialException e) {
                return handleLocalEncryptFallback(data, policyName, e);
            } catch (Exception e) {
                return handleLocalEncryptFallback(data, policyName, e);
            }
        }
        
        if (currentCryptoService == null) {
            log.warn("Crypto service not initialized");
            if (failOpen) {
                return data;
            } else {
                throw new RuntimeException("Crypto service not initialized");
            }
        }

        try {
            log.trace("Direct encryption request: policy={}, dataLength={}", policyName, data != null ? data.length() : 0);
            // 엔진은 includeStats와 무관하게 항상 통계를 자동 수집함
            String encrypted = currentCryptoService.encrypt(data, policyName);
            log.trace("Direct encryption completed");
            endpointAvailable = true;
            return encrypted;
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Direct encryption failed (policy: {}): {}", policyName, errorMsg);
            
            endpointAvailable = false;
            
            if (failOpen) {
                log.debug("Fail-open mode: storing as plaintext");
                return data;
            } else {
                throw new RuntimeException("Encryption failed (Fail-closed mode)", e);
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

        if (isLocalCryptoEnabled()) {
            log.trace("Search encryption uses remote path in local crypto mode: policy={}", policyName);
        }

        if (currentCryptoService == null) {
            log.warn("Crypto service not initialized (encryptForSearch)");
            return data;
        }

        try {
            log.trace("Search encryption request: policy={}, dataLength={}", policyName, data.length());
            String result = currentCryptoService.encryptForSearch(data, policyName);
            log.trace("Search encryption completed");
            endpointAvailable = true;
            return result;
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Search encryption failed (policy: {}): {}, returning plaintext", policyName, errorMsg);
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

        if (isLocalCryptoEnabled() && maskPolicyName == null && maskPolicyUid == null && !includeStats) {
            try {
                log.trace("Local decryption request: dataLength={}, policyName={}",
                        encryptedData.length(), policyName);
                if (!localCryptoService.isEncryptedData(encryptedData)) {
                    log.trace("Data is not encrypted - returning original data");
                    return encryptedData;
                }
                String decrypted = localCryptoService.decrypt(encryptedData, policyName);
                log.trace("Local decryption completed");
                endpointAvailable = true;
                return decrypted;
            } catch (UnsupportedCryptoMaterialException e) {
                return handleLocalDecryptFallback(encryptedData, policyName, e);
            } catch (Exception e) {
                return handleLocalDecryptFallback(encryptedData, policyName, e);
            }
        }
        
        if (currentCryptoService == null) {
            log.warn("Crypto service not initialized");
            if (failOpen) {
                return encryptedData;
            } else {
                throw new RuntimeException("Crypto service not initialized");
            }
        }

        try {
            log.trace("Direct decryption request: dataLength={}, policyName={}, maskPolicyName={}, maskPolicyUid={}",
                    encryptedData != null ? encryptedData.length() : 0, policyName, maskPolicyName, maskPolicyUid);
            // 엔진은 includeStats와 무관하게 항상 통계를 자동 수집함
            String decrypted = currentCryptoService.decrypt(encryptedData, policyName, maskPolicyName, maskPolicyUid, includeStats);
            
            if (decrypted == null) {
                log.trace("Data is not encrypted - returning original data");
                return encryptedData;
            }
            
            log.trace("Direct decryption completed");
            endpointAvailable = true;
            return decrypted;
        } catch (Exception e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Direct decryption failed: {}", errorMessage);
            
            endpointAvailable = false;
            
            if (failOpen) {
                log.debug("Fail-open mode: storing as plaintext");
                return encryptedData;
            } else {
                throw new RuntimeException("Decryption failed (Fail-closed mode)", e);
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
            log.warn("Crypto service not initialized");
            if (failOpen) {
                return encryptedDataList; // 원본 반환
            } else {
                throw new RuntimeException("Crypto service not initialized");
            }
        }

        try {
            log.trace("Batch decryption request: itemsCount={}, maskPolicyName={}, maskPolicyUid={}",
                    encryptedDataList.size(), maskPolicyName, maskPolicyUid);
            return currentCryptoService.batchDecrypt(encryptedDataList, maskPolicyName, maskPolicyUid, includeStats);
        } catch (Exception e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Batch decryption failed: {}", errorMessage);
            
            if (failOpen) {
                return encryptedDataList; // 원본 반환
            } else {
                throw new RuntimeException("Batch decryption failed (Fail-closed mode)", e);
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
            throw new IllegalArgumentException("Policy list size does not match data list size");
        }
        
        if (currentCryptoService == null) {
            log.warn("Crypto service not initialized");
            if (failOpen) {
                return dataList; // 원본 반환
            } else {
                throw new RuntimeException("Crypto service not initialized");
            }
        }

        try {
            log.trace("Batch encryption request: itemsCount={}", dataList.size());
            return currentCryptoService.batchEncrypt(dataList, policyList);
        } catch (Exception e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Batch encryption failed: {}", errorMessage);
            
            if (failOpen) {
                return dataList; // 원본 반환
            } else {
                throw new RuntimeException("Batch encryption failed (Fail-closed mode)", e);
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
        if (isLocalCryptoEnabled()) {
            return localCryptoService.isEncryptedData(data)
                    || (currentCryptoService != null && currentCryptoService.isEncryptedData(data));
        }
        if (currentCryptoService == null) {
            return false;
        }
        return currentCryptoService.isEncryptedData(data);
    }

    private boolean isLocalCryptoEnabled() {
        return "local".equals(cryptoMode) && localCryptoService != null;
    }

    private static boolean equalsNullable(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    private String handleLocalEncryptFallback(String data, String policyName, Exception e) {
        String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (localFallbackRemote) {
            log.debug("Local encryption fallback to remote: policy={}, error={}", policyName, errorMsg);
            return encryptRemote(data, policyName);
        }
        log.warn("Local encryption failed (policy: {}): {}", policyName, errorMsg);
        if (failOpen) {
            return data;
        }
        throw new RuntimeException("Local encryption failed (Fail-closed mode)", e);
    }

    private String handleLocalDecryptFallback(String encryptedData, String policyName, Exception e) {
        String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (localFallbackRemote) {
            log.debug("Local decryption fallback to remote: {}", errorMsg);
            return decryptRemote(encryptedData, policyName, null, null, false);
        }
        log.warn("Local decryption failed: {}", errorMsg);
        if (failOpen) {
            return encryptedData;
        }
        throw new RuntimeException("Local decryption failed (Fail-closed mode)", e);
    }

    private String encryptRemote(String data, String policyName) {
        if (currentCryptoService == null) {
            log.warn("Crypto service not initialized");
            if (failOpen) {
                return data;
            }
            throw new RuntimeException("Crypto service not initialized");
        }

        try {
            log.trace("Direct encryption request: policy={}, dataLength={}", policyName, data != null ? data.length() : 0);
            String encrypted = currentCryptoService.encrypt(data, policyName);
            log.trace("Direct encryption completed");
            endpointAvailable = true;
            return encrypted;
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Direct encryption failed (policy: {}): {}", policyName, errorMsg);
            endpointAvailable = false;
            if (failOpen) {
                log.debug("Fail-open mode: storing as plaintext");
                return data;
            }
            throw new RuntimeException("Encryption failed (Fail-closed mode)", e);
        }
    }

    private String decryptRemote(String encryptedData, String policyName, String maskPolicyName, String maskPolicyUid, boolean includeStats) {
        if (currentCryptoService == null) {
            log.warn("Crypto service not initialized");
            if (failOpen) {
                return encryptedData;
            }
            throw new RuntimeException("Crypto service not initialized");
        }

        try {
            log.trace("Direct decryption request: dataLength={}, policyName={}, maskPolicyName={}, maskPolicyUid={}",
                    encryptedData != null ? encryptedData.length() : 0, policyName, maskPolicyName, maskPolicyUid);
            String decrypted = currentCryptoService.decrypt(encryptedData, policyName, maskPolicyName, maskPolicyUid, includeStats);
            if (decrypted == null) {
                log.trace("Data is not encrypted - returning original data");
                return encryptedData;
            }
            log.trace("Direct decryption completed");
            endpointAvailable = true;
            return decrypted;
        } catch (Exception e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Direct decryption failed: {}", errorMessage);
            endpointAvailable = false;
            if (failOpen) {
                log.debug("Fail-open mode: storing as plaintext");
                return encryptedData;
            }
            throw new RuntimeException("Decryption failed (Fail-closed mode)", e);
        }
    }
}
