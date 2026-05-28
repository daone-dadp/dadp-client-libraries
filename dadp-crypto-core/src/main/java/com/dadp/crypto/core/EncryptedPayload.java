package com.dadp.crypto.core;

final class EncryptedPayload {
    private final String policyCode;
    private final byte[] iv;
    private final byte[] ciphertext;
    private final byte[] tag;

    EncryptedPayload(String policyCode, byte[] iv, byte[] ciphertext, byte[] tag) {
        this.policyCode = policyCode;
        this.iv = iv;
        this.ciphertext = ciphertext;
        this.tag = tag;
    }

    String getPolicyCode() {
        return policyCode;
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
