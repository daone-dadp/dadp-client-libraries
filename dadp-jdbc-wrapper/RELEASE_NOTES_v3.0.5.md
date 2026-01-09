# DADP JDBC Wrapper v3.0.5 Release Notes

## 🎉 릴리즈 정보

**버전**: 3.0.5  
**릴리즈 일자**: 2025-11-26  
**주요 개선사항**: Hibernate/MyBatis 등 다중 ORM 지원, 첫 번째 쿼리부터 암호화 정책 적용 보장, Java 버전별 HTTP 클라이언트 추상화

---

## 📋 주요 변경사항

### ✅ Hibernate SQL Alias 자동 변환 (다중 ORM 지원)

Hibernate가 생성하는 SQL의 alias(`email3_0_`)를 원본 컬럼명(`email`)으로 자동 변환하여 복호화가 정상 동작하도록 개선했습니다.

#### 문제 상황 (이전)

```sql
-- Hibernate 생성 SQL
SELECT user0_.email as email3_0_ FROM users user0_
```

- `getString("email3_0_")` 호출 시 정책 조회 실패 → 복호화 안 됨

#### 해결 내용 (v3.0.5)

```
getString("email3_0_") 호출
        ↓
SQL 파싱에서 alias 매핑 확인: email3_0_ → email
        ↓
원본 컬럼명으로 정책 조회: users.email → dadp 정책
        ↓
복호화 성공! ✅
```

#### 지원 프레임워크

| 프레임워크 | 암호화 | 복호화 | 비고 |
|-----------|--------|--------|------|
| **JdbcTemplate** | ✅ | ✅ | 직접 컬럼명 사용 |
| **Hibernate/JPA** | ✅ | ✅ | alias 자동 변환 |
| **MyBatis** | ✅ | ✅ | AS alias 파싱 지원 |
| **jOOQ** | ✅ | ✅ | AS alias 파싱 지원 |
| **QueryDSL** | ✅ | ✅ | AS alias 파싱 지원 |

#### 지원 SQL 패턴

| SQL 패턴 | 예시 | 대응 |
|----------|------|------|
| 직접 컬럼 | `SELECT email FROM users` | ✅ |
| AS alias | `SELECT email AS user_email FROM users` | ✅ |
| 테이블.컬럼 + AS | `SELECT u.email AS e FROM users u` | ✅ |
| Hibernate alias | `SELECT user0_.email as email3_0_` | ✅ |

### ✅ 첫 번째 쿼리부터 암호화 정책 적용 보장

이번 릴리즈에서는 애플리케이션 시작 후 첫 번째 쿼리 실행 시에도 암호화 정책이 적용되도록 개선했습니다.

### ✅ Statement 프록시 지원 (DadpProxyStatement)

JdbcTemplate 등에서 Statement를 사용하는 경우에도 복호화가 적용되도록 `DadpProxyStatement` 클래스를 추가했습니다.

| 구분 | 이전 | 현재 |
|------|------|------|
| `createStatement()` | 실제 Statement 반환 (복호화 안 됨) | `DadpProxyStatement` 반환 (복호화 적용) |
| `Statement.executeQuery()` | 실제 ResultSet 반환 | `DadpProxyResultSet` 반환 |

### ✅ ResultSet.getObject() 복호화 지원

JdbcTemplate은 내부적으로 `getObject()` 메서드를 사용하는 경우가 많아, 해당 메서드에도 복호화 로직을 추가했습니다.

```java
// String 타입인 경우 자동 복호화
Object value = rs.getObject(columnIndex);  // → 복호화된 값 반환
```

### ✅ Java 버전별 HTTP 클라이언트 추상화

Java 버전에 따라 최적의 HTTP 클라이언트를 자동으로 선택하는 추상화 레이어를 제공합니다.

| Java 버전 | HTTP 클라이언트 | 특징 |
|-----------|----------------|------|
| Java 8    | `HttpURLConnection` | JDK 표준 API, 추가 의존성 없음 |
| Java 11+  | `java.net.http.HttpClient` | 모던 API, 더 나은 성능 |

```java
// 팩토리에서 Java 버전에 맞는 구현체 자동 선택
HttpClientAdapter client = HttpClientAdapter.Factory.create(5000, 10000);
```

#### 문제 상황 (이전 버전)

```
첫 번째 쿼리 실행 (09:25:37.020)
        ↓
정책 로드 완료 (09:25:38.664)  ← 약 1.6초 후
        ↓
두 번째 쿼리 실행 (09:25:45.378)  ← 암호화 적용됨
```

- 정책 로드가 비동기로 수행되어 첫 번째 쿼리 시점에 정책이 아직 없는 상태
- **첫 번째 쿼리에 암호화가 적용되지 않는 문제 발생**

#### 해결 내용 (v3.0.5)

```
정책 로드 시작
        ↓
첫 번째 쿼리 요청 → 정책 로드 완료 대기 (최대 10초)
        ↓
정책 로드 완료
        ↓
쿼리 실행 (암호화 적용됨) ✅
```

- `CountDownLatch`를 사용하여 정책 로드 완료 대기 로직 추가
- 모든 `prepareStatement()` 호출 전에 정책 로드 완료 확인
- **첫 번째 쿼리부터 암호화가 적용됨**

---

## 🔧 기술적 세부사항

### 변경된 파일

- `DadpProxyConnection.java` - 정책 로드 완료 대기 로직 추가, createStatement() 프록시 적용
- `DadpProxyStatement.java` - **신규 추가** - Statement 래핑하여 복호화 처리
- `DadpProxyResultSet.java` - getObject() 메서드에 복호화 로직 추가, getString(String) alias 변환 로직 추가
- `SqlParser.java` - SELECT문 alias 매핑 기능 추가 (aliasToColumnMap)
- `com.dadp.jdbc.http` 패키지 - HTTP 클라이언트 추상화 (이전 버전에서 추가)
  - `HttpClientAdapter.java` - 인터페이스 및 팩토리
  - `Java8HttpClientAdapter.java` - Java 8용 구현 (HttpURLConnection)
  - `Java11HttpClientAdapter.java` - Java 11+용 구현 (HttpClient)

### HTTP 클라이언트 추상화 구조

```
HttpClientAdapter (인터페이스)
├── Factory.create() - Java 버전 감지 후 적절한 구현체 반환
├── Java8HttpClientAdapter - HttpURLConnection 기반
└── Java11HttpClientAdapter - java.net.http.HttpClient 기반
```

#### Java 버전 자동 감지

```java
private static int getJavaVersion() {
    String version = System.getProperty("java.version");
    if (version.startsWith("1.")) {
        // Java 8 이하: "1.8.0_xxx"
        version = version.substring(2, 3);
    } else {
        // Java 9 이상: "11.0.1", "17.0.1" 등
        int dot = version.indexOf(".");
        if (dot != -1) {
            version = version.substring(0, dot);
        }
    }
    return Integer.parseInt(version);
}
```

#### 팩토리 패턴

```java
public static HttpClientAdapter create(int connectTimeout, int readTimeout) {
    if (JAVA_VERSION >= 11) {
        // Java 11+ : java.net.http.HttpClient 사용 (리플렉션으로 로드)
        try {
            Class<?> clazz = Class.forName("com.dadp.jdbc.http.Java11HttpClientAdapter");
            return (HttpClientAdapter) constructor.newInstance(connectTimeout, readTimeout);
        } catch (Exception e) {
            // 폴백: Java 8 구현 사용
            return new Java8HttpClientAdapter(connectTimeout, readTimeout);
        }
    } else {
        // Java 8 : HttpURLConnection 사용
        return new Java8HttpClientAdapter(connectTimeout, readTimeout);
    }
}
```

### 주요 변경 내용 (정책 로드 대기)

#### 1. CountDownLatch 추가

```java
// Proxy Instance별 매핑 로드 완료 대기용 Latch (static으로 공유)
private static final ConcurrentHashMap<String, CountDownLatch> mappingsLoadedLatchMap = new ConcurrentHashMap<>();

// 정책 로드 대기 타임아웃 (초)
private static final int POLICY_LOAD_TIMEOUT_SECONDS = 10;
```

#### 2. 정책 로드 완료 대기 메서드 추가

```java
/**
 * 정책 매핑 로드가 완료되었는지 확인하고, 필요시 대기
 * 첫 번째 쿼리 실행 전 정책이 적용되도록 보장합니다.
 */
private void ensureMappingsLoaded() {
    String instanceId = config.getInstanceId();
    CountDownLatch latch = mappingsLoadedLatchMap.get(instanceId);
    
    // Latch가 있고 아직 해제되지 않았으면 대기
    if (latch != null && latch.getCount() > 0) {
        log.debug("⏳ 정책 매핑 로드 완료 대기 중... instanceId={}", instanceId);
        waitForMappingsLoaded();
    }
}
```

#### 3. 모든 prepareStatement 오버로드에 대기 로직 추가

```java
@Override
public PreparedStatement prepareStatement(String sql) throws SQLException {
    log.debug("🔍 PreparedStatement 생성: {}", sql);
    // 정책 매핑 로드 완료 대기 (첫 번째 쿼리 실행 전 정책 적용 보장)
    ensureMappingsLoaded();
    PreparedStatement actualPs = actualConnection.prepareStatement(sql);
    return new DadpProxyPreparedStatement(actualPs, sql, this);
}
```

### 동작 방식

| 구분 | 이전 (v3.0.4) | 현재 (v3.0.5) |
|------|---------------|---------------|
| 첫 쿼리 | 암호화 적용 안 됨 | ✅ 암호화 적용됨 |
| 정책 로드 | 비동기 (기다리지 않음) | 비동기 + 대기 (최대 10초) |
| 타임아웃 시 | N/A | 경고 로그 후 쿼리 실행 |

### 타임아웃 동작

- 기본 타임아웃: **10초**
- 타임아웃 발생 시: 경고 로그 출력 후 쿼리 실행 (Fail-open 모드 유지)

```
⚠️ 정책 매핑 로드 대기 타임아웃 (10초): instanceId=board-app-1
```

---

## 📦 빌드 및 배포

### 빌드 명령어

```bash
# Fat JAR 빌드 (모든 의존성 포함)
mvn clean package -DskipTests

# Java 8용 빌드
mvn clean package -Pjava8 -DskipTests
```

### 생성되는 아티팩트

- `dadp-jdbc-wrapper-3.0.5.jar` (기본)
- `dadp-jdbc-wrapper-3.0.5-java8.jar` (Java 8용)
- `dadp-jdbc-wrapper-3.0.5-all.jar` (Fat JAR, 모든 의존성 포함)
- `dadp-jdbc-wrapper-3.0.5-sources.jar` (소스 코드)
- `dadp-jdbc-wrapper-3.0.5-javadoc.jar` (JavaDoc)

---

## ✅ 테스트 완료

- [x] Java 8 환경에서 정상 동작 확인
- [x] 첫 번째 쿼리부터 암호화 적용 확인
- [x] 정책 로드 완료 후 쿼리 실행 확인
- [x] 타임아웃 동작 확인
- [x] **Hibernate/JPA 암복호화 정상 동작 확인** (sample-app)
- [x] **JdbcTemplate 암복호화 정상 동작 확인** (board-app)
- [x] **Hibernate alias 변환 확인** (`email3_0_` → `email`)

---

## 🔄 마이그레이션 가이드

### 기존 사용자

**변경 사항 없음**: 기존 코드 그대로 사용 가능합니다.

### 업그레이드 방법

JAR 파일만 교체하면 됩니다:

```bash
# 기존 JAR 백업
mv dadp-jdbc-wrapper-3.0.4-all.jar dadp-jdbc-wrapper-3.0.4-all.jar.bak

# 새 JAR 복사
cp dadp-jdbc-wrapper-3.0.5-all.jar ./
```

---

## 📚 호환성 매트릭스

| Java 버전 | 지원 여부 | 비고 |
|-----------|----------|------|
| Java 8    | ✅ 지원   | 기존 지원 유지 |
| Java 11   | ✅ 지원   | 기존 지원 유지 |
| Java 17   | ✅ 지원   | 기본 빌드 버전 |
| Java 21   | ✅ 지원   | 하위 호환성으로 지원 |

---

## 🐛 알려진 이슈

없음

---

## 🙏 기여자

DADP Development Team

---

## 📄 라이선스

Apache License, Version 2.0

---

## 🔗 관련 링크

- GitHub: https://github.com/daone-dadp/dadp-jdbc-wrapper
- Maven Central: https://central.sonatype.com/artifact/io.github.daone-dadp/dadp-jdbc-wrapper
- 문서: [README.md](README.md)
- CHANGELOG: [CHANGELOG.md](CHANGELOG.md)

