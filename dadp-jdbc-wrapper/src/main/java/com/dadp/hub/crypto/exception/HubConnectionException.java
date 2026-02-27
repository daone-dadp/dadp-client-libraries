package com.dadp.hub.crypto.exception;

/**
 * Hub 연결 관련 예외
 *
 * Wrapper 전용 - Spring 의존성 없음
 *
 * @author DADP Development Team
 * @version 5.5.5
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
