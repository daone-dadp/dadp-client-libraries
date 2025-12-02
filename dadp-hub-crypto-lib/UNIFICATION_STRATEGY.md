# dadp-hub-crypto-lib 통일 전략

## 통일 가능 여부

### ✅ Spring Boot 버전 통일: 가능

**Spring Boot 2.7.18**은 Java 8, 11, 17 모두 지원합니다:
- Java 8: ✅ 지원
- Java 11: ✅ 지원  
- Java 17: ✅ 지원

따라서 모든 버전에서 **Spring Boot 2.7.18**을 사용할 수 있습니다.

### ✅ RestTemplate 사용: 가능

**RestTemplate**은 모든 Java 버전에서 사용 가능:
- Java 8: ✅ 사용 가능
- Java 11: ✅ 사용 가능
- Java 17: ✅ 사용 가능

## 통일 전략

### Option 1: 완전 통일 (권장) ⭐

**모든 버전에서 동일한 소스 코드 사용**

1. **Spring Boot 2.7.18로 통일**
2. **RestTemplate 사용**
3. **소스 코드는 동일, 컴파일만 Java 버전별로 다르게**

**장점:**
- ✅ 코드 중복 제거
- ✅ 유지보수 용이
- ✅ 버그 수정 시 한 번만 수정
- ✅ 모든 Java 버전에서 동일한 동작 보장

**단점:**
- ⚠️ Java 17에서 최신 기능 사용 불가 (하지만 RestTemplate은 충분히 안정적)
- ⚠️ `java.net.http.HttpClient`의 비동기 기능 사용 불가 (하지만 동기식이 더 단순)

### Option 2: 현재 방식 유지

**각 Java 버전별로 별도 구현**

**장점:**
- ✅ Java 17에서 최신 기능 활용 가능
- ✅ 각 버전에 최적화된 구현

**단점:**
- ❌ 코드 중복
- ❌ 유지보수 어려움
- ❌ 버그 수정 시 여러 곳 수정 필요

## 통일 구현 방법

### 1. Spring Boot 버전 통일

모든 버전의 `pom.xml`에서:
```xml
<properties>
    <spring-boot.version>2.7.18</spring-boot.version>
</properties>
```

### 2. RestTemplate 사용

모든 버전의 `HubCryptoService.java`에서:
```java
import org.springframework.web.client.RestTemplate;

private final RestTemplate restTemplate;

public HubCryptoService() {
    this.restTemplate = new RestTemplate();
    // ...
}
```

### 3. 소스 코드 공유

**방법 A: 심볼릭 링크 사용 (Linux/Mac)**
```bash
# Java 8, 11, 17 모두 같은 소스 참조
ln -s ../dadp-hub-crypto-lib/src dadp-hub-crypto-lib-java8/src
ln -s ../dadp-hub-crypto-lib/src dadp-hub-crypto-lib-java11/src
```

**방법 B: Maven 프로파일 사용 (권장)**
```xml
<profiles>
    <profile>
        <id>java8</id>
        <properties>
            <maven.compiler.source>1.8</maven.compiler.source>
            <maven.compiler.target>1.8</maven.compiler.target>
        </properties>
    </profile>
    <profile>
        <id>java11</id>
        <properties>
            <maven.compiler.source>11</maven.compiler.source>
            <maven.compiler.target>11</maven.compiler.target>
        </properties>
    </profile>
    <profile>
        <id>java17</id>
        <properties>
            <maven.compiler.source>17</maven.compiler.source>
            <maven.compiler.target>17</maven.compiler.target>
        </properties>
    </profile>
</profiles>
```

**방법 C: 단일 프로젝트, 빌드 시 Java 버전별 JAR 생성**
- 하나의 프로젝트에서 여러 Java 버전으로 빌드
- Maven 플러그인으로 Java 버전별 JAR 생성

## 권장 사항

### ✅ Option 1: 완전 통일 (권장)

**이유:**
1. **코드 유지보수성**: 한 곳만 수정하면 모든 버전에 반영
2. **버그 수정 용이**: 버그 발견 시 한 번만 수정
3. **테스트 간소화**: 하나의 코드만 테스트
4. **RestTemplate 충분**: 동기식이지만 안정적이고 검증됨
5. **Spring Boot 2.7.18 안정적**: Java 8~17 모두 지원하는 검증된 버전

**구현:**
- Spring Boot 2.7.18로 통일
- RestTemplate 사용
- 소스 코드는 하나만 유지
- 컴파일만 Java 버전별로 다르게

## 결론

**✅ 통일 가능합니다!**

1. **Spring Boot 2.7.18**로 통일 → Java 8, 11, 17 모두 지원
2. **RestTemplate** 사용 → 모든 Java 버전에서 동작
3. **소스 코드 통일** → 유지보수 용이

이렇게 하면 코드 중복 없이 모든 Java 버전을 지원할 수 있습니다.

