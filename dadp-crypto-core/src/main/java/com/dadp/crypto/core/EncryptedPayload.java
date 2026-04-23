package com.dadp.crypto.core;

final class EncryptedPayload {
    private final String policyUid;
    private final byte[] iv;
    private final byte[] ciphertext;
    private final byte[] tag;

    EncryptedPayload(String policyUid, byte[] iv, byte[] ciphertext, byte[] tag) {
        this.policyUid = policyUid;
        this.iv = iv;
        this.ciphertext = ciphertext;
        this.tag = tag;
    }

    String getPolicyUid() {
        return policyUid;
    }

    byte[] getIv() {
        return iv;
    }

    byte[] getCiphertext() {
        return ciphertext;
    }

    byte[] getTag() {
        return tag;
    }
}
