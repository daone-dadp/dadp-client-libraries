package com.dadp.common.sync.http;

/**
 * Java 8용 HttpClientAdapter 팩토리
 * 
 * HttpURLConnection 기반 구현을 반환합니다.
 * 
 * @author DADP Development Team
 * @version 5.0.9
 * @since 2026-01-06
 */
public class Java8HttpClientAdapterFactory {
    /**
     * HttpClientAdapter 인스턴스 생성
     * 
     * @param connectTimeout 연결 타임아웃(밀리초)
     * @param readTimeout 읽기 타임아웃(밀리초)
     * @return HttpClientAdapter 인스턴스 (Java 8용 HttpURLConnection 구현)
     */
    public static HttpClientAdapter create(int connectTimeout, int readTimeout) {
        return new Java8HttpClientAdapter(connectTimeout, readTimeout);
    }
}

