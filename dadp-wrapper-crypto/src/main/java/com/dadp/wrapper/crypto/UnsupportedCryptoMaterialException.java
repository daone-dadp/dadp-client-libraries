package com.dadp.wrapper.crypto;

/**
 * Signals that local crypto cannot safely handle the material and remote Engine fallback is required.
 */
public final class UnsupportedCryptoMaterialException extends WrapperCryptoException {

    public UnsupportedCryptoMaterialException(String message) {
        super(message);
    }
}
