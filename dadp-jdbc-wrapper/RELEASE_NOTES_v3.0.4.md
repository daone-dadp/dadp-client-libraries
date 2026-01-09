# DADP JDBC Wrapper v3.0.4 Release Notes

## 🎉 릴리즈 정보

**버전**: 3.0.4  
**릴리즈 일자**: 2025-11-12  
**주요 개선사항**: Java 8 호환성 개선

---

## 📋 주요 변경사항

### ✅ Java 8 호환성 개선

이번 릴리즈에서는 Java 8 환경에서 발생하던 `NoClassDefFoundError: java/net/http/HttpClient` 오류를 해결하기 위해 내부 HTTP 클라이언트 구현을 변경했습니다.

#### 변경 내용
- **SchemaSyncService**: `java.net.http.HttpClient` (Java 11+) → `java.net.HttpURLConnection` (Java 8+)
- **MappingSyncService**: `java.net.http.HttpClient` (Java 11+) → `java.net.HttpURLConnection` (Java 8+)

#### 해결된 문제
- ✅ Java 8 환경에서 정상 동작
- ✅ Java 11+ 환경에서도 정상 동작 (하위 호환성 유지)
- ✅ 추가 의존성 없이 JDK 표준 API만 사용

---

## 🔧 기술적 세부사항

### HTTP 클라이언트 변경

**이전 (Java 11+ 전용)**:
```java
HttpClient httpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .build();
```

**현재 (Java 8+ 호환)**:
```java
HttpURLConnection conn = (HttpURLConnection) url.openConnection();
conn.setConnectTimeout(5000);  // 밀리초
conn.setReadTimeout(10000);    // 밀리초
```

### 성능 영향

- **단일 요청**: 성능 차이 미미 (< 10ms)
- **동시 요청**: 현재 사용 패턴(드문 동기화 요청)에서는 영향 없음
- **메모리**: HttpURLConnection이 더 경량

---

## 📦 빌드 및 배포

### Java 버전별 빌드

```bash
# Java 8용 빌드
mvn clean package -Pjava8

# Java 11용 빌드
mvn clean package -Pjava11

# Java 17용 빌드 (기본)
mvn clean package -Pjava17
```

### 생성되는 아티팩트

- `dadp-jdbc-wrapper-3.0.4.jar` (기본)
- `dadp-jdbc-wrapper-3.0.4-java8.jar` (Java 8용)
- `dadp-jdbc-wrapper-3.0.4-java11.jar` (Java 11용)
- `dadp-jdbc-wrapper-3.0.4-java17.jar` (Java 17용)
- `dadp-jdbc-wrapper-3.0.4-all.jar` (Fat JAR, 모든 의존성 포함)

---

## ✅ 테스트 완료

- [x] Java 8 환경에서 정상 동작 확인
- [x] Java 11 환경에서 정상 동작 확인
- [x] Java 17 환경에서 정상 동작 확인
- [x] 스키마 동기화 기능 테스트 완료
- [x] 매핑 동기화 기능 테스트 완료
- [x] board-app-java8 통합 테스트 완료

---

## 🔄 마이그레이션 가이드

### 기존 사용자 (Java 11+)

**변경 사항 없음**: 기존 코드 그대로 사용 가능합니다.

### Java 8 사용자

**이전**: Java 8에서 `NoClassDefFoundError` 발생  
**현재**: Java 8에서 정상 동작

```java
// 변경 전 (Java 11+만 지원)
// NoClassDefFoundError 발생

// 변경 후 (Java 8+ 지원)
// 정상 동작
```

---

## 📚 호환성 매트릭스

| Java 버전 | 지원 여부 | 비고 |
|-----------|----------|------|
| Java 8    | ✅ 지원   | 이번 릴리즈에서 추가 |
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

