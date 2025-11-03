package com.dadp.hub.crypto.exception;

/**
 * Hub 암복호화 관련 예외
 * 
 * @author DADP Development Team
 * @version 1.0.0
 * @since 2025-01-01
 */
public class HubCryptoException extends RuntimeException {
    
    public HubCryptoException(String message) {
        super(message);
    }
    
    public HubCryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
