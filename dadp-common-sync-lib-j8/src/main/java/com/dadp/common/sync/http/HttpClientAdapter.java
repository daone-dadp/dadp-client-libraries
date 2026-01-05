package com.dadp.common.sync.http;

import java.io.IOException;
import java.net.URI;

/**
 * HTTP 클라이언트 어댑터 인터페이스
 * 
 * Java 8용 HttpURLConnection 기반 HTTP 클라이언트 구현을 제공합니다.
 * Wrapper와 공통 라이브러리에서 공통으로 사용합니다.
 * 
 * @author DADP Development Team
 * @version 5.0.4
 * @since 2025-12-31
 */
public interface HttpClientAdapter {
    
    /**
     * HTTP GET 요청
     * 
     * @param uri 요청 URI
     * @return HTTP 응답
     * @throws IOException IO 예외
     */
    HttpResponse get(URI uri) throws IOException;
    
    /**
     * HTTP GET 요청 (커스텀 헤더 포함)
     *
     * @param uri 요청 URI
     * @param headers 추가 헤더
     * @return HTTP 응답
     * @throws IOException IO 예외
     */
    default HttpResponse get(URI uri, java.util.Map<String, String> headers) throws IOException {
        return get(uri);
    }
    
    /**
     * HTTP POST 요청
     * 
     * @param uri 요청 URI
     * @param body 요청 본문
     * @return HTTP 응답
     * @throws IOException IO 예외
     */
    HttpResponse post(URI uri, String body) throws IOException;

    /**
     * HTTP POST 요청 (커스텀 헤더 포함)
     *
     * @param uri 요청 URI
     * @param body 요청 본문
     * @param headers 추가 헤더
     * @return HTTP 응답
     * @throws IOException IO 예외
     */
    default HttpResponse post(URI uri, String body, java.util.Map<String, String> headers) throws IOException {
        return post(uri, body);
    }
    
    /**
     * HTTP 응답 인터페이스
     */
    interface HttpResponse {
        /**
         * HTTP 상태 코드
         * 
         * @return 상태 코드
         */
        int getStatusCode();
        
        /**
         * 응답 본문
         * 
         * @return 응답 본문 문자열
         */
        String getBody();
        
        /**
         * 응답 헤더 값 조회
         * 
         * @param name 헤더 이름
         * @return 헤더 값 (없으면 null)
         */
        default String getHeader(String name) {
            return null;
        }
    }
    
    /**
     * HttpClientAdapter 팩토리
     * Java 8용 HttpURLConnection 기반 구현을 반환합니다.
     */
    class Factory {
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
}
