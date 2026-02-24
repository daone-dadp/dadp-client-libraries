package com.dadp.common.sync.http;

import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;

/**
 * Java 8용 HTTP 클라이언트 어댑터
 * HttpURLConnection을 사용합니다.
 * 
 * SSL 설정: DADP Root CA만 신뢰
 * - Manager, Hub, Engine, Wrapper, Nginx 모두 DADP에서 발급한 인증서만 검증
 * - DADP Root CA로 서명된 모든 인증서(서버 인증서 + Intermediate CA 체인)를 검증
 * 
 * @author DADP Development Team
 * @version 3.0.8
 */
class Java8HttpClientAdapter implements HttpClientAdapter {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(Java8HttpClientAdapter.class);
    
    private final int connectTimeout;
    private final int readTimeout;
    private final String caCertPath;
    
    /**
     * DADP CA 인증서 경로 가져오기 (인스턴스 변수만 사용, 시스템 프로퍼티 사용 안 함)
     */
    private String getDadpCaCertPath() {
        return caCertPath != null && !caCertPath.trim().isEmpty() ? caCertPath.trim() : null;
    }
    
    /**
     * PEM 형식 인증서를 X.509 인증서로 변환
     */
    private static X509Certificate pemToCertificate(String pem) throws Exception {
        String certContent = pem.replace("-----BEGIN CERTIFICATE-----", "")
                                .replace("-----END CERTIFICATE-----", "")
                                .replaceAll("\\s", "");
        byte[] certBytes = Base64.getDecoder().decode(certContent);
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) certFactory.generateCertificate(new java.io.ByteArrayInputStream(certBytes));
    }
    
    /**
     * DADP CA 인증서만 신뢰하는 SSLContext 생성
     */
    private SSLContext createDadpCaSSLContext() {
        String certPath = getDadpCaCertPath();
        if (caCertPath == null) {
            log.debug("DADP CA 인증서 경로가 설정되지 않음");
            return null;
        }
        
        if (certPath == null) {
            log.debug("DADP CA 인증서 경로가 설정되지 않음");
            return null;
        }
        
        try {
            // 인증서 파일 존재 확인
            java.nio.file.Path certFilePath = Paths.get(certPath);
            if (!Files.exists(certFilePath)) {
                log.warn("DADP CA 인증서 파일이 존재하지 않습니다: path={}", certPath);
                return null;
            }
            
            // PEM 파일 읽기
            String pem = new String(Files.readAllBytes(certFilePath), "UTF-8");
            if (pem == null || pem.trim().isEmpty()) {
                log.warn("DADP CA 인증서 파일이 비어있습니다: path={}", certPath);
                return null;
            }
            
            X509Certificate caCert = pemToCertificate(pem);
            
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("dadp-root-ca", caCert);
            
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            );
            trustManagerFactory.init(trustStore);
            
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagerFactory.getTrustManagers(), new java.security.SecureRandom());
            
            log.debug("DADP Root CA SSLContext 생성 완료: path={}, subject={}", 
                certPath, caCert.getSubjectX500Principal().getName());
            
            return sslContext;
        } catch (Exception e) {
            String errorMessage = e.getMessage();
            if (errorMessage == null || errorMessage.trim().isEmpty()) {
                errorMessage = e.getClass().getSimpleName();
            }
            log.error("DADP CA 인증서로 SSLContext 생성 실패: path={}, error={}", 
                certPath, errorMessage);
            return null;
        }
    }
    
    /**
     * HTTPS 연결인 경우 SSL 설정 적용
     * DADP Root CA만 신뢰하여 DADP에서 발급한 인증서만 검증
     */
    private void configureSSL(HttpURLConnection conn) {
        if (conn instanceof HttpsURLConnection) {
            HttpsURLConnection httpsConn = (HttpsURLConnection) conn;
            
            String certPath = getDadpCaCertPath();
            log.info("SSL 설정 시작: url={}, caCertPath={}", conn.getURL(), certPath);
            
            if (certPath == null) {
                log.error("DADP CA 인증서 경로가 설정되지 않았습니다.");
                return;
            }
            
            SSLContext sslContext = createDadpCaSSLContext();
            if (sslContext != null) {
                httpsConn.setSSLSocketFactory(sslContext.getSocketFactory());
                httpsConn.setHostnameVerifier((hostname, session) -> {
                    log.debug("호스트명 검증: hostname={}", hostname);
                    return true;
                });
                log.info("DADP Root CA SSL 설정 적용 완료: url={}", conn.getURL());
            } else {
                log.error("DADP CA 인증서로 SSLContext 생성 실패: url={}, caCertPath={}", 
                    conn.getURL(), certPath);
            }
        } else {
            log.debug("HTTP 연결 (SSL 설정 불필요): url={}", conn.getURL());
        }
    }
    
    public Java8HttpClientAdapter(int connectTimeout, int readTimeout) {
        this(connectTimeout, readTimeout, null);
    }
    
    public Java8HttpClientAdapter(int connectTimeout, int readTimeout, String caCertPath) {
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.caCertPath = caCertPath;
    }
    
    @Override
    public HttpClientAdapter.HttpResponse get(URI uri) throws IOException {
        return get(uri, null);
    }
    
    @Override
    public HttpClientAdapter.HttpResponse get(URI uri, java.util.Map<String, String> headers) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        
        // HTTPS 연결인 경우 SSL 설정 적용
        configureSSL(conn);
        
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
        
        // HTTPS 연결인 경우 SSL 설정 적용
        configureSSL(conn);
        
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
