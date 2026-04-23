package com.dadp.crypto.core;

public class CoreCryptoException extends RuntimeException {
    public CoreCryptoException(String message) {
        super(message);
    }

    public CoreCryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
