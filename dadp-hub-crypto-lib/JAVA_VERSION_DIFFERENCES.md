# dadp-hub-crypto-lib Java 버전별 차이점

## 개요

`dadp-hub-crypto-lib`는 Java 8, Java 11, Java 17 버전별로 별도의 artifact로 제공됩니다.

## 버전별 비교표

| 항목 | Java 17 (원본) | Java 11 | Java 8 |
|------|---------------|---------|--------|
| **ArtifactId** | `dadp-hub-crypto-lib` | `dadp-hub-crypto-lib-java11` | `dadp-hub-crypto-lib-java8` |
| **Java 버전** | 17 | 11 | 1.8 |
| **Spring Boot** | 3.2.12 | 2.7.18 | 2.7.18 |
| **HTTP 클라이언트** | `java.net.http.HttpClient` | `java.net.http.HttpClient` | `RestTemplate` |
| **Jackson** | 2.15.2 | 2.15.3 | 2.13.5 |
| **SLF4J** | 2.0.9 | 1.7.36 | 1.7.36 |
| **JUnit** | 5.10.0 | 5.10.0 | 5.9.3 |
| **Parent POM** | `dadp-client-libraries` | 없음 (독립) | 없음 (독립) |

## 주요 차이점

### 1. HTTP 클라이언트 구현

#### Java 17 & Java 11
```java
// java.net.http.HttpClient 사용 (Java 11+)
private final HttpClient httpClient;

public HubCryptoService() {
    this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
}

// 사용 예시
HttpRequest httpRequest = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofMillis(timeout))
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        .build();

HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
```

**장점:**
- JDK 내장 라이브러리 (추가 의존성 없음)
- 비동기 지원
- Spring 의존성 없음

#### Java 8
```java
// RestTemplate 사용 (Java 8 호환)
private final RestTemplate restTemplate;

public HubCryptoService() {
    this.restTemplate = new RestTemplate();
}

// 사용 예시
HttpHeaders headers = new HttpHeaders();
headers.setContentType(MediaType.APPLICATION_JSON);
HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
```

**이유:**
- `java.net.http.HttpClient`는 Java 11부터 사용 가능
- Java 8에서는 `RestTemplate` 사용 (Spring Web 의존성 필요)

### 2. Spring Boot 버전

#### Java 17
- **Spring Boot 3.2.12**
  - Java 17+ 필수
  - `jakarta.*` 패키지 사용 (Java EE → Jakarta EE)
  - 최신 기능 및 성능 개선

#### Java 11 & Java 8
- **Spring Boot 2.7.18**
  - Java 8+ 지원 (마지막 Java 8 지원 버전)
  - `javax.*` 패키지 사용
  - 안정적이고 검증된 버전

### 3. 의존성 버전

#### Jackson
- **Java 17**: 2.15.2
- **Java 11**: 2.15.3
- **Java 8**: 2.13.5 (Java 8 호환 마지막 버전)

#### SLF4J
- **Java 17**: 2.0.9 (최신)
- **Java 11 & Java 8**: 1.7.36 (안정 버전)

#### JUnit
- **Java 17 & Java 11**: 5.10.0
- **Java 8**: 5.9.3

### 4. Parent POM

#### Java 17
- `dadp-client-libraries` parent POM 사용
- 공통 설정 상속

#### Java 11 & Java 8
- 독립적인 POM (parent 없음)
- Maven Central 배포를 위해 독립적으로 구성
- `dependencyManagement`로 Spring Boot BOM 직접 관리

## 코드 차이점

### HubCryptoService 구현

#### Java 17 & Java 11
```java
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

private final HttpClient httpClient;

// HTTP 요청
HttpRequest httpRequest = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofMillis(timeout))
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        .build();

HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
int statusCode = response.statusCode();
String body = response.body();
```

#### Java 8
```java
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

private final RestTemplate restTemplate;

// HTTP 요청
HttpHeaders headers = new HttpHeaders();
headers.setContentType(MediaType.APPLICATION_JSON);
HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
int statusCode = response.getStatusCode().value();
String body = response.getBody();
```

### 예외 처리

#### Java 17 & Java 11
```java
try {
    response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
} catch (java.io.IOException | InterruptedException e) {
    throw new HubConnectionException("Hub 연결 실패: " + e.getMessage(), e);
}
```

#### Java 8
```java
try {
    response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
} catch (HttpClientErrorException | HttpServerErrorException e) {
    throw new HubConnectionException("Hub 연결 실패: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
} catch (Exception e) {
    throw new HubConnectionException("Hub 연결 실패: " + e.getMessage(), e);
}
```

## 사용 가이드

### Maven 의존성

#### Java 17
```xml
<dependency>
    <groupId>io.github.daone-dadp</groupId>
    <artifactId>dadp-hub-crypto-lib</artifactId>
    <version>1.0.0</version>
</dependency>
```

#### Java 11
```xml
<dependency>
    <groupId>io.github.daone-dadp</groupId>
    <artifactId>dadp-hub-crypto-lib-java11</artifactId>
    <version>1.0.0</version>
</dependency>
```

#### Java 8
```xml
<dependency>
    <groupId>io.github.daone-dadp</groupId>
    <artifactId>dadp-hub-crypto-lib-java8</artifactId>
    <version>1.0.0</version>
</dependency>
```

## 선택 가이드

### Java 17 사용 시
- ✅ 최신 기능 및 성능
- ✅ Spring Boot 3.x 사용 가능
- ✅ `java.net.http.HttpClient` (추가 의존성 없음)

### Java 11 사용 시
- ✅ Java 11+ 환경
- ✅ Spring Boot 2.7.x 사용
- ✅ `java.net.http.HttpClient` (추가 의존성 없음)

### Java 8 사용 시
- ✅ 레거시 시스템 지원
- ✅ Spring Boot 2.7.x 사용
- ⚠️ `RestTemplate` 사용 (Spring Web 의존성 필요)

## 주의사항

1. **Java 8 버전은 Spring Web 의존성 필요**
   - `RestTemplate`을 사용하므로 `spring-boot-starter-web` 또는 `spring-web` 필요

2. **버전 동기화**
   - 모든 버전의 기능은 동일하지만, 구현 방식이 다름
   - API는 동일하므로 코드 변경 없이 버전만 교체 가능

3. **성능**
   - Java 17/11: `java.net.http.HttpClient` (비동기 지원, 더 빠름)
   - Java 8: `RestTemplate` (동기식, 안정적)

