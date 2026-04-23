package com.dadp.wrapper.crypto;

public class WrapperCryptoException extends RuntimeException {

    public WrapperCryptoException(String message) {
        super(message);
    }

    public WrapperCryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
