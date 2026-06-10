package com.dadp.wrapper.crypto;

import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wrapper-side local crypto facade with policy/key material caching.
 */
public class WrapperLocalCryptoService {

    private static final DadpLogger log = DadpLoggerFactory.getLogger(WrapperLocalCryptoService.class);

    private final RuntimeExecutionKeyClient executionKeyClient;
    private final LocalAesGcmCrypto localCrypto;
    private final WrapperCryptoStatsSender statsSender;
    private final PolicyMaterialCacheListener cacheListener;
    private final Map<String, PolicyMaterial> policiesByName = new ConcurrentHashMap<String, PolicyMaterial>();
    private final Map<String, PolicyMaterial> policiesByCode = new ConcurrentHashMap<String, PolicyMaterial>();

    public WrapperLocalCryptoService(String hubBaseUrl, int timeoutMillis) {
        this(hubBaseUrl, timeoutMillis, (String) null, false, "1hour");
    }

    public WrapperLocalCryptoService(String hubBaseUrl, int timeoutMillis, String tenantId) {
        this(hubBaseUrl, timeoutMillis, tenantId, false, "1hour");
    }

    public WrapperLocalCryptoService(String hubBaseUrl, int timeoutMillis,
                                     String tenantId,
                                     boolean cryptoStatsEnabled, String cryptoStatsAggregationLevel) {
        this(hubBaseUrl, timeoutMillis, tenantId, cryptoStatsEnabled, cryptoStatsAggregationLevel, null);
    }

    public WrapperLocalCryptoService(String hubBaseUrl, int timeoutMillis,
                                     String tenantId,
                                     boolean cryptoStatsEnabled, String cryptoStatsAggregationLevel,
                                     PolicyMaterialCacheListener cacheListener) {
        this(hubBaseUrl, timeoutMillis,
                createAuthHeaderProvider(tenantId),
                createStatsSender(hubBaseUrl, timeoutMillis, tenantId,
                        cryptoStatsEnabled, cryptoStatsAggregationLevel),
                cacheListener);
    }

    private WrapperLocalCryptoService(String hubBaseUrl, int timeoutMillis,
                                      HubRuntimeHeaderProvider authHeaderProvider,
                                      WrapperCryptoStatsSender statsSender,
                                      PolicyMaterialCacheListener cacheListener) {
        this(new RuntimeExecutionKeyClient(hubBaseUrl, timeoutMillis, authHeaderProvider),
                new LocalAesGcmCrypto(),
                statsSender,
                cacheListener);
    }

    WrapperLocalCryptoService(RuntimeExecutionKeyClient executionKeyClient,
                              LocalAesGcmCrypto localCrypto) {
        this(executionKeyClient, localCrypto, null);
    }

    WrapperLocalCryptoService(RuntimeExecutionKeyClient executionKeyClient,
                              LocalAesGcmCrypto localCrypto,
                              WrapperCryptoStatsSender statsSender) {
        this(executionKeyClient, localCrypto, statsSender, null);
    }

    WrapperLocalCryptoService(RuntimeExecutionKeyClient executionKeyClient,
                              LocalAesGcmCrypto localCrypto,
                              WrapperCryptoStatsSender statsSender,
                              PolicyMaterialCacheListener cacheListener) {
        if (executionKeyClient == null) {
            throw new IllegalArgumentException("executionKeyClient is required");
        }
        if (localCrypto == null) {
            throw new IllegalArgumentException("localCrypto is required");
        }
        this.executionKeyClient = executionKeyClient;
        this.localCrypto = localCrypto;
        this.statsSender = statsSender;
        this.cacheListener = cacheListener;
    }

    public String encrypt(String data, String policyName) {
        return encryptByPolicyName(data, policyName);
    }

    public String encryptByPolicyCode(String data, String policyCode) {
        if (data == null) {
            return null;
        }
        try {
            PolicyMaterial policy = resolvePolicyByCode(policyCode);
            KeyMaterial keyMaterial = keyMaterial(policy);
            log.trace("Local encrypt material resolved: policyName={}, policyCode={}, algorithm={}, keyAlias={}, keyVersion={}, keyFingerprint={}, usePlain={}, plainStart={}, plainLength={}",
                    policy.getPolicyName(), policy.getPolicyCode(), policy.getAlgorithm(),
                    policy.getKeyAlias(), policy.getKeyVersion(), WrapperLocalCryptoDebug.fingerprint(keyMaterial.getKeyData()),
                    policy.getUsePlain(), policy.getPlainStart(), policy.getPlainLength());
            String encrypted = localCrypto.encrypt(data, policy.getPolicyCode(), policy.getAlgorithm(), keyMaterial,
                    policy.getUsePlain(), policy.getPlainStart(), policy.getPlainLength());
            log.trace("Local encrypt result: policyCode={}, encryptedLength={}, encryptedPrefix={}",
                    policy.getPolicyCode(), encrypted != null ? encrypted.length() : 0, WrapperLocalCryptoDebug.preview(encrypted));
            if (statsSender != null) {
                statsSender.recordEncryptSuccess();
            }
            return encrypted;
        } catch (RuntimeException e) {
            log.trace("Local encrypt failed: policyCode={}, error={}", policyCode, e.getMessage());
            if (statsSender != null) {
                statsSender.recordEncryptFailure();
            }
            throw e;
        }
    }

    private String encryptByPolicyName(String data, String policyName) {
        if (data == null) {
            return null;
        }
        try {
            PolicyMaterial policy = resolvePolicyByName(policyName);
            KeyMaterial keyMaterial = keyMaterial(policy);
            log.trace("Local encrypt material resolved: policyName={}, policyCode={}, algorithm={}, keyAlias={}, keyVersion={}, keyFingerprint={}, usePlain={}, plainStart={}, plainLength={}",
                    policy.getPolicyName(), policy.getPolicyCode(), policy.getAlgorithm(),
                    policy.getKeyAlias(), policy.getKeyVersion(), WrapperLocalCryptoDebug.fingerprint(keyMaterial.getKeyData()),
                    policy.getUsePlain(), policy.getPlainStart(), policy.getPlainLength());
            String encrypted = localCrypto.encrypt(data, policy.getPolicyCode(), policy.getAlgorithm(), keyMaterial,
                    policy.getUsePlain(), policy.getPlainStart(), policy.getPlainLength());
            log.trace("Local encrypt result: policyCode={}, encryptedLength={}, encryptedPrefix={}",
                    policy.getPolicyCode(), encrypted != null ? encrypted.length() : 0, WrapperLocalCryptoDebug.preview(encrypted));
            if (statsSender != null) {
                statsSender.recordEncryptSuccess();
            }
            return encrypted;
        } catch (RuntimeException e) {
            log.trace("Local encrypt failed: policyName={}, error={}", policyName, e.getMessage());
            if (statsSender != null) {
                statsSender.recordEncryptFailure();
            }
            throw e;
        }
    }

    public String decrypt(String encryptedData, String policyName) {
        if (encryptedData == null) {
            return null;
        }
        try {
            String extractedPolicyCode = safeExtractPolicyCode(encryptedData);
            log.trace("Local decrypt request: requestedPolicyName={}, extractedPolicyCode={}, encryptedLength={}, encryptedPrefix={}",
                    policyName, extractedPolicyCode, encryptedData.length(), WrapperLocalCryptoDebug.preview(encryptedData));
            PolicyMaterial policy = resolvePolicyForCiphertext(encryptedData, policyName);
            KeyMaterial keyMaterial = keyMaterial(policy);
            log.trace("Local decrypt material resolved: policyName={}, policyCode={}, algorithm={}, keyAlias={}, keyVersion={}, keyFingerprint={}, usePlain={}, plainStart={}, plainLength={}",
                    policy.getPolicyName(), policy.getPolicyCode(), policy.getAlgorithm(),
                    policy.getKeyAlias(), policy.getKeyVersion(), WrapperLocalCryptoDebug.fingerprint(keyMaterial.getKeyData()),
                    policy.getUsePlain(), policy.getPlainStart(), policy.getPlainLength());
            String decrypted = localCrypto.decrypt(encryptedData, policy.getAlgorithm(), keyMaterial,
                    policy.getPlainStart(), policy.getPlainLength());
            log.trace("Local decrypt result: policyCode={}, decryptedLength={}, decryptedPrefix={}",
                    policy.getPolicyCode(), decrypted != null ? decrypted.length() : 0, WrapperLocalCryptoDebug.preview(decrypted));
            if (statsSender != null) {
                statsSender.recordDecryptSuccess();
            }
            return decrypted;
        } catch (RuntimeException e) {
            log.trace("Local decrypt failed: requestedPolicyName={}, error={}", policyName, e.getMessage());
            if (statsSender != null) {
                statsSender.recordDecryptFailure();
            }
            throw e;
        }
    }

    public boolean isEncryptedData(String data) {
        if (data == null) {
            return false;
        }
        try {
            LocalAesGcmCrypto.extractPolicyCode(data);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private PolicyMaterial resolvePolicyByName(String policyName) {
        if (policyName == null || policyName.trim().isEmpty()) {
            throw new IllegalArgumentException("policyName is required for local crypto");
        }
        String key = policyName.trim();
        PolicyMaterial cached = policiesByName.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && !cached.isExpired(now)) {
            log.trace("Local policy cache hit by name: policyName={}, policyCode={}, algorithm={}, keyAlias={}, keyVersion={}",
                    cached.getPolicyName(), cached.getPolicyCode(), cached.getAlgorithm(),
                    cached.getKeyAlias(), cached.getKeyVersion());
            return cached;
        }
        log.trace("Local execution-key cache miss by name: policyName={}", key);
        PolicyMaterial loaded = executionKeyClient.resolveByPolicyName(key).toPolicyMaterial(key);
        cachePolicy(loaded);
        return loaded;
    }

    private PolicyMaterial resolvePolicyByCode(String policyCode) {
        String key = policyCode.trim();
        PolicyMaterial cached = policiesByCode.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && !cached.isExpired(now)) {
            log.trace("Local policy cache hit by code: policyCode={}, policyName={}, algorithm={}, keyAlias={}, keyVersion={}",
                    cached.getPolicyCode(), cached.getPolicyName(), cached.getAlgorithm(),
                    cached.getKeyAlias(), cached.getKeyVersion());
            return cached;
        }
        log.trace("Local execution-key cache miss by code: policyCode={}", key);
        PolicyMaterial loaded = executionKeyClient.resolveByPolicyCode(key).toPolicyMaterial(null);
        cachePolicy(loaded);
        return loaded;
    }

    private PolicyMaterial resolvePolicyForCiphertext(String encryptedData, String policyName) {
        String policyCode = LocalAesGcmCrypto.extractPolicyCode(encryptedData);
        if (policyCode != null && !policyCode.trim().isEmpty()) {
            PolicyMaterial byCode = policiesByCode.get(policyCode);
            if (byCode != null && !byCode.isExpired(System.currentTimeMillis())) {
                return byCode;
            }
            return resolvePolicyByCode(policyCode);
        }
        if (policyName != null && !policyName.trim().isEmpty()) {
            PolicyMaterial byName = resolvePolicyByName(policyName);
            if (policyCode == null || policyCode.equals(byName.getPolicyCode())) {
                return byName;
            }
        }
        return resolvePolicyByCode(policyCode);
    }

    private KeyMaterial keyMaterial(PolicyMaterial policy) {
        return new KeyMaterial(
                new KeyMetadata(policy.getKeyAlias(), policy.getKeyVersion(), "HUB", null, null, policy.getAlgorithm()),
                policy.getExecutionKeyBase64());
    }

    private void cachePolicy(PolicyMaterial policy) {
        log.trace("Local policy cached: policyName={}, policyCode={}, policyVersion={}, algorithm={}, keyAlias={}, keyVersion={}, expiresAtMillis={}, usePlain={}, plainStart={}, plainLength={}",
                policy.getPolicyName(), policy.getPolicyCode(), policy.getPolicyVersion(), policy.getAlgorithm(),
                policy.getKeyAlias(), policy.getKeyVersion(),
                policy.getExpiresAtMillis(),
                policy.getUsePlain(), policy.getPlainStart(), policy.getPlainLength());
        if (policy.getPolicyName() != null && !policy.getPolicyName().trim().isEmpty()) {
            policiesByName.put(policy.getPolicyName(), policy);
        }
        policiesByCode.put(policy.getPolicyCode(), policy);
        if (cacheListener != null) {
            try {
                cacheListener.onPolicyMaterialCached(policy);
            } catch (RuntimeException e) {
                log.warn("Local policy material persistence callback failed: policyCode={}, error={}",
                        policy.getPolicyCode(), e.getMessage());
            }
        }
    }

    private static String safeExtractPolicyCode(String encryptedData) {
        try {
            return LocalAesGcmCrypto.extractPolicyCode(encryptedData);
        } catch (RuntimeException e) {
            return null;
        }
    }


    private static HubRuntimeHeaderProvider createAuthHeaderProvider(String tenantId) {
        if (tenantId != null && !tenantId.trim().isEmpty()) {
            return new HubTenantHeaderProvider(tenantId);
        }
        return null;
    }

    private static WrapperCryptoStatsSender createStatsSender(String hubBaseUrl, int timeoutMillis,
                                                              String tenantId,
                                                              boolean cryptoStatsEnabled,
                                                              String cryptoStatsAggregationLevel) {
        if (!cryptoStatsEnabled) {
            return null;
        }
        return new WrapperCryptoStatsSender(hubBaseUrl, timeoutMillis, tenantId, cryptoStatsAggregationLevel);
    }

    public void close() {
        if (statsSender != null) {
            statsSender.close();
        }
    }
}
