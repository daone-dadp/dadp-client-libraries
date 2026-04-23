package com.dadp.wrapper.crypto;

/**
 * Resolved local crypto material from Hub internal key APIs.
 */
public final class KeyMaterial {

    private final KeyMetadata metadata;
    private final String keyData;

    public KeyMaterial(KeyMetadata metadata, String keyData) {
        if (metadata == null) {
            throw new IllegalArgumentException("metadata is required");
        }
        if (keyData == null || keyData.trim().isEmpty()) {
            throw new IllegalArgumentException("keyData is required");
        }
        this.metadata = metadata;
        this.keyData = keyData;
    }

    public KeyMetadata getMetadata() {
        return metadata;
    }

    public String getKeyData() {
        return keyData;
    }
}
