package com.dadp.hub.crypto.exception;

/** Temporary crypto transport failure only. Never authorization, TLS or data validation. */
public final class TransientCryptoException extends HubConnectionException {
    public TransientCryptoException(String message) { super(message); }
    public TransientCryptoException(String message, Throwable cause) { super(message, cause); }
}
