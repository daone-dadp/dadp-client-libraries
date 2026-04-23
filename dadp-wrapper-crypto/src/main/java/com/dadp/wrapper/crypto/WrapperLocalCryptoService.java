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
    private final Map<String, PolicyMaterial> policiesByName = new ConcurrentHashMap<String, PolicyMaterial>();
    private final Map<String, PolicyMaterial> policiesByUid = new ConcurrentHashMap<String, PolicyMaterial>();
    private final Map<String, KeyMaterial> keys = new ConcurrentHashMap<String, KeyMaterial>();

    public WrapperLocalCryptoService(String hubBaseUrl, int timeoutMillis) {
        this(hubBaseUrl, timeoutMillis, null, null);
    }

    public WrapperLocalCryptoService(String hubBaseUrl, int timeoutMillis, String hubAuthId, String hubAuthSecret) {
        this(hubBaseUrl, timeoutMillis, createAuthHeaderProvider(hubAuthId, hubAuthSecret));
    }

    private WrapperLocalCryptoService(String hubBaseUrl, int timeoutMillis,
                                      HubInternalKeyClient.AuthHeaderProvider authHeaderProvider) {
        this(new HubPolicyMaterialClient(hubBaseUrl, timeoutMillis),
                new KeyMaterialResolver(new HubInternalKeyClient(hubBaseUrl, timeoutMillis, authHeaderProvider)),
                new LocalAesGcmCrypto());
    }

    WrapperLocalCryptoService(HubPolicyMaterialClient policyClient,
                              KeyMaterialResolver keyResolver,
                              LocalAesGcmCrypto localCrypto) {
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
    }

    public String encrypt(String data, String policyName) {
        if (data == null) {
            return null;
        }
        PolicyMaterial policy = resolvePolicyByName(policyName);
        return localCrypto.encrypt(data, policy.getPolicyUid(), resolveKey(policy),
                policy.getUsePlain(), policy.getPlainStart(), policy.getPlainLength());
    }

    public String decrypt(String encryptedData, String policyName) {
        if (encryptedData == null) {
            return null;
        }
        PolicyMaterial policy = resolvePolicyForCiphertext(encryptedData, policyName);
        return localCrypto.decrypt(encryptedData, resolveKey(policy),
                policy.getPlainStart(), policy.getPlainLength());
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
}
