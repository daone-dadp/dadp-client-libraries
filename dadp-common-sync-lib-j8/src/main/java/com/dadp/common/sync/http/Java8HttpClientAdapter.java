package com.dadp.common.sync.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Java 8용 HTTP 클라이언트 어댑터
 * HttpURLConnection을 사용합니다.
 * 
 * @author DADP Development Team
 * @version 3.0.5
 */
class Java8HttpClientAdapter implements HttpClientAdapter {
    
    private final int connectTimeout;
    private final int readTimeout;
    
    public Java8HttpClientAdapter(int connectTimeout, int readTimeout) {
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }
    
    @Override
    public HttpClientAdapter.HttpResponse get(URI uri) throws IOException {
        return get(uri, null);
    }
    
    @Override
    public HttpClientAdapter.HttpResponse get(URI uri, java.util.Map<String, String> headers) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Content-Type", "application/json");
        
        // 추가 헤더
        if (headers != null) {
            for (java.util.Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
        }
        
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        
        return readResponse(conn);
    }
    
    @Override
    public HttpClientAdapter.HttpResponse post(URI uri, String body) throws IOException {
        return post(uri, body, null);
    }

    @Override
    public HttpClientAdapter.HttpResponse post(URI uri, String body, java.util.Map<String, String> headers) throws IOException {
        // URI를 URL로 변환 (오타 방지를 위해 명시적 변환)
        java.net.URL url;
        try {
            url = uri.toURL();
        } catch (java.net.MalformedURLException e) {
            throw new IOException("Invalid URI: " + uri.toString(), e);
        }
        
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        if (headers != null) {
            for (java.util.Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
        }
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        conn.setDoOutput(true);
        
        // 요청 본문 전송
        try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), "UTF-8")) {
            writer.write(body);
            writer.flush();
        }
        
        return readResponse(conn);
    }
    
    private HttpClientAdapter.HttpResponse readResponse(HttpURLConnection conn) throws IOException {
        int statusCode = conn.getResponseCode();
        String responseBody = null;
        
        // 응답 본문 읽기
        if (statusCode >= 200 && statusCode < 300) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                responseBody = readAll(reader);
            }
        } else {
            // 에러 응답 읽기
            if (conn.getErrorStream() != null) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getErrorStream(), "UTF-8"))) {
                    responseBody = readAll(reader);
                }
            }
        }
        
        // 응답 헤더 읽기
        final java.util.Map<String, String> headers = new java.util.HashMap<>();
        for (int i = 0; ; i++) {
            String key = conn.getHeaderFieldKey(i);
            if (key == null) {
                break;
            }
            String value = conn.getHeaderField(i);
            if (value != null) {
                headers.put(key, value);
            }
        }
        
        final int finalStatusCode = statusCode;
        final String finalBody = responseBody;
        
        return new HttpClientAdapter.HttpResponse() {
            @Override
            public int getStatusCode() {
                return finalStatusCode;
            }
            
            @Override
            public String getBody() {
                return finalBody;
            }
            
            @Override
            public String getHeader(String name) {
                return headers.get(name);
            }
        };
    }
    
    private String readAll(BufferedReader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }
}
