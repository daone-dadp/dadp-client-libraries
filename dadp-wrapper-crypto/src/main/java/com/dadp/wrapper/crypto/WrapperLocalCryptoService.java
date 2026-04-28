package com.dadp.wrapper.crypto;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wrapper-side local crypto facade with policy/key material caching.
 */
public class WrapperLocalCryptoService {

    private final HubPolicyMaterialClient policyClient;
    private final KeyMaterialResolver keyResolver;
    private final LocalAesGcmCrypto localCrypto;
    private final WrapperCryptoStatsSender statsSender;
    private final Map<String, PolicyMaterial> policiesByName = new ConcurrentHashMap<String, PolicyMaterial>();
    private final Map<String, PolicyMaterial> policiesByUid = new ConcurrentHashMap<String, PolicyMaterial>();
    private final Map<String, KeyMaterial> keys = new ConcurrentHashMap<String, KeyMaterial>();

    public WrapperLocalCryptoService(String hubBaseUrl, int timeoutMillis) {
        this(hubBaseUrl, timeoutMillis, (String) null, (String) null, false, "1hour");
    }

    public WrapperLocalCryptoService(String hubBaseUrl, int timeoutMillis, String hubAuthId, String hubAuthSecret) {
        this(hubBaseUrl, timeoutMillis, hubAuthId, hubAuthSecret, false, "1hour");
    }

    public WrapperLocalCryptoService(String hubBaseUrl, int timeoutMillis,
                                     String hubAuthId, String hubAuthSecret,
                                     boolean cryptoStatsEnabled, String cryptoStatsAggregationLevel) {
        this(hubBaseUrl, timeoutMillis,
                createAuthHeaderProvider(hubAuthId, hubAuthSecret),
                createStatsSender(hubBaseUrl, timeoutMillis, hubAuthId, hubAuthSecret,
                        cryptoStatsEnabled, cryptoStatsAggregationLevel));
    }

    private WrapperLocalCryptoService(String hubBaseUrl, int timeoutMillis,
                                      HubInternalKeyClient.AuthHeaderProvider authHeaderProvider,
                                      WrapperCryptoStatsSender statsSender) {
        this(new HubPolicyMaterialClient(hubBaseUrl, timeoutMillis),
                new KeyMaterialResolver(new HubInternalKeyClient(hubBaseUrl, timeoutMillis, authHeaderProvider)),
                new LocalAesGcmCrypto(),
                statsSender);
    }

    WrapperLocalCryptoService(HubPolicyMaterialClient policyClient,
                              KeyMaterialResolver keyResolver,
                              LocalAesGcmCrypto localCrypto) {
        this(policyClient, keyResolver, localCrypto, null);
    }

    WrapperLocalCryptoService(HubPolicyMaterialClient policyClient,
                              KeyMaterialResolver keyResolver,
                              LocalAesGcmCrypto localCrypto,
                              WrapperCryptoStatsSender statsSender) {
        if (policyClient == null) {
            throw new IllegalArgumentException("policyClient is required");
        }
        if (keyResolver == null) {
            throw new IllegalArgumentException("keyResolver is required");
        }
        if (localCrypto == null) {
            throw new IllegalArgumentException("localCrypto is required");
        }
        this.policyClient = policyClient;
        this.keyResolver = keyResolver;
        this.localCrypto = localCrypto;
        this.statsSender = statsSender;
    }

    public String encrypt(String data, String policyName) {
        if (data == null) {
            return null;
        }
        try {
            PolicyMaterial policy = resolvePolicyByName(policyName);
            String encrypted = localCrypto.encrypt(data, policy.getPolicyUid(), resolveKey(policy),
                    policy.getUsePlain(), policy.getPlainStart(), policy.getPlainLength());
            if (statsSender != null) {
                statsSender.recordEncryptSuccess();
            }
            return encrypted;
        } catch (RuntimeException e) {
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
            PolicyMaterial policy = resolvePolicyForCiphertext(encryptedData, policyName);
            String decrypted = localCrypto.decrypt(encryptedData, resolveKey(policy),
                    policy.getPlainStart(), policy.getPlainLength());
            if (statsSender != null) {
                statsSender.recordDecryptSuccess();
            }
            return decrypted;
        } catch (RuntimeException e) {
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
            LocalAesGcmCrypto.extractPolicyUid(data);
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
        if (cached != null) {
            return cached;
        }
        PolicyMaterial loaded = policyClient.fetchByName(key);
        cachePolicy(loaded);
        return loaded;
    }

    private PolicyMaterial resolvePolicyByUid(String policyUid) {
        String key = policyUid.trim();
        PolicyMaterial cached = policiesByUid.get(key);
        if (cached != null) {
            return cached;
        }
        PolicyMaterial loaded = policyClient.fetchByUid(key);
        cachePolicy(loaded);
        return loaded;
    }

    private PolicyMaterial resolvePolicyForCiphertext(String encryptedData, String policyName) {
        String policyUid = LocalAesGcmCrypto.extractPolicyUid(encryptedData);
        if (policyUid != null && !policyUid.trim().isEmpty()) {
            PolicyMaterial byUid = policiesByUid.get(policyUid);
            if (byUid != null) {
                return byUid;
            }
        }
        if (policyName != null && !policyName.trim().isEmpty()) {
            PolicyMaterial byName = resolvePolicyByName(policyName);
            if (policyUid == null || policyUid.equals(byName.getPolicyUid())) {
                return byName;
            }
        }
        return resolvePolicyByUid(policyUid);
    }

    private KeyMaterial resolveKey(PolicyMaterial policy) {
        String key = policy.getKeyAlias() + ":" + policy.getKeyVersion();
        KeyMaterial cached = keys.get(key);
        if (cached != null) {
            return cached;
        }
        KeyMaterial loaded = keyResolver.resolve(policy.getKeyAlias(), policy.getKeyVersion());
        KeyMaterial previous = keys.putIfAbsent(key, loaded);
        return previous != null ? previous : loaded;
    }

    private void cachePolicy(PolicyMaterial policy) {
        if (policy.getPolicyName() != null && !policy.getPolicyName().trim().isEmpty()) {
            policiesByName.put(policy.getPolicyName(), policy);
        }
        policiesByUid.put(policy.getPolicyUid(), policy);
    }

    private static HubInternalKeyClient.AuthHeaderProvider createAuthHeaderProvider(String hubAuthId, String hubAuthSecret) {
        if (hubAuthId == null || hubAuthId.trim().isEmpty()
                || hubAuthSecret == null || hubAuthSecret.trim().isEmpty()) {
            return null;
        }
        return new HubInternalAuthHeaderProvider(hubAuthId, hubAuthSecret);
    }

    private static WrapperCryptoStatsSender createStatsSender(String hubBaseUrl, int timeoutMillis,
                                                              String hubAuthId, String hubAuthSecret,
                                                              boolean cryptoStatsEnabled,
                                                              String cryptoStatsAggregationLevel) {
        if (!cryptoStatsEnabled) {
            return null;
        }
        return new WrapperCryptoStatsSender(hubBaseUrl, timeoutMillis, hubAuthId, hubAuthSecret,
                cryptoStatsAggregationLevel);
    }

    public void close() {
        if (statsSender != null) {
            statsSender.close();
        }
    }
}
