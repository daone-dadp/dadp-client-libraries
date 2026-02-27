package com.dadp.hub.crypto.exception;

/**
 * Hub 암복호화 관련 예외
 *
 * Wrapper 전용 - Spring 의존성 없음
 *
 * @author DADP Development Team
 * @version 5.5.5
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
