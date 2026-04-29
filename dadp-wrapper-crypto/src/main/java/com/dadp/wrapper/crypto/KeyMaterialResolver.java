package com.dadp.wrapper.crypto;

import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;

/**
 * Resolves key metadata and raw key material using the same Hub contract as Engine.
 */
public final class KeyMaterialResolver {

    private static final DadpLogger log = DadpLoggerFactory.getLogger(KeyMaterialResolver.class);

    private final HubInternalKeyClient hubClient;

    public KeyMaterialResolver(HubInternalKeyClient hubClient) {
        if (hubClient == null) {
            throw new IllegalArgumentException("hubClient is required");
        }
        this.hubClient = hubClient;
    }

    public KeyMaterial resolve(String keyAlias, int keyVersion) {
        log.trace("Local key resolve start: keyAlias={}, keyVersion={}", keyAlias, keyVersion);
        KeyMetadata metadata = hubClient.fetchKeyMetadata(keyAlias, keyVersion);
        String provider = metadata.getProvider();
        if (provider != null && !"HUB".equalsIgnoreCase(provider) && !"DB".equalsIgnoreCase(provider)) {
            throw new UnsupportedCryptoMaterialException("Provider requires remote fallback: " + provider);
        }
        String keyData = hubClient.fetchKeyData(keyAlias, keyVersion);
        KeyMaterial material = new KeyMaterial(metadata, keyData);
        log.trace("Local key resolve completed: keyAlias={}, keyVersion={}, provider={}, algorithm={}, keyFingerprint={}",
                metadata.getKeyAlias(), metadata.getKeyVersion(), metadata.getProvider(),
                metadata.getAlgorithm(), WrapperLocalCryptoDebug.fingerprint(keyData));
        return material;
    }
}
