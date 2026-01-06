package com.dadp.common.sync.http;

import java.io.IOException;
import java.net.URI;

/**
 * HTTP 클라이언트 어댑터 인터페이스
 * 
 * Java 버전별 HTTP 클라이언트 구현을 위한 공통 인터페이스입니다.
 * - Java 8: HttpURLConnection 기반 구현
 * - Java 17: Spring RestTemplate 기반 구현
 * 
 * @author DADP Development Team
 * @version 5.0.9
 * @since 2026-01-06
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
}

