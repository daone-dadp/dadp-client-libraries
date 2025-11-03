package com.dadp.hub.crypto.exception;

/**
 * Hub 연결 관련 예외
 * 
 * @author DADP Development Team
 * @version 1.0.0
 * @since 2025-01-01
 */
public class HubConnectionException extends HubCryptoException {
    
    public HubConnectionException(String message) {
        super(message);
    }
    
    public HubConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
