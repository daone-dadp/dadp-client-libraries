# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [5.1.0] - 2026-01-07

### 🔄 Changed

- **버전 체계 전환**: 4.8.1 → 5.1.0
  - A=5: Root POM 버전과 동기화
  - B=1: Java 8 최소 요구사항 (매핑 ID)
  - C=0: 새 체계 시작
- **저장소 구조 통일**: `dadp-jdbc-wrapper`를 `dadp-client-libraries` 내부로 이동
  - 모든 클라이언트 라이브러리를 하나의 저장소에서 관리
  - SCM 정보를 `dadp-client-libraries.git`로 통일
- **기능 및 호환성**: 변경 없음 (버전 번호 및 저장소 구조만 변경)

### Compatibility

- Product Version: `5.1.0`
- Hub 최소 버전: `3.8.0` (변경 없음)
- Engine 최소 버전: `5` (Root POM 버전과 동기화)
- Java 최소 버전: `Java 8` (변경 없음)
- Breaking Changes: **No**

---

## [4.8.1] - 2025-12-15 (배포 전)

### 🎉 릴리즈 정보

**버전**: 4.8.1  
**릴리즈 일자**: 2025-12-15  
**배포 상태**: ⚠️ **개발 완료, Maven Central 미배포** (배포 전)  
**주요 개선사항**: 엔진 통계 수집 개선, 통계 설정 영구 저장 기능 추가

### ✅ Fixed

- **엔진 통계 수집 개선**: Wrapper 테스트 앱에서 자동 암호화 테스트 시 통계가 수집되지 않던 문제 해결
  - `DirectCryptoAdapter`와 `HubCryptoAdapter`에서 `includeStats` 파라미터 전달 제거
  - 엔진이 `includeStats`와 무관하게 항상 자동으로 통계를 수집하도록 변경
  - Wrapper를 통한 모든 암복호화 요청이 엔진 통계에 자동으로 기록됨

### 📝 기술적 배경

- `includeStats`는 AOP 로깅용 파라미터이며, 엔진 통계 수집과는 무관
- 엔진은 모든 암복호화 요청에 대해 자동으로 통계 수집 (시도수, 지연시간)
- 성공/실패 구분 없이 시도수만 카운트하도록 엔진 측 개선됨

### 🔄 영향

- ✅ Wrapper를 통한 암복호화 요청도 엔진 통계에 정상적으로 기록됨
- ✅ Hub 대시보드에서 Wrapper 사용 통계 확인 가능
- ✅ 기존 코드 변경 불필요 (투명한 개선)

### ✅ Added

- **통계 설정 영구 저장**: 스키마 동기화 시 통계 설정도 함께 영구 저장
  - `EndpointStorage`에 통계 설정 필드 추가 (`statsAggregatorEnabled`, `statsAggregatorUrl`, `statsAggregatorMode`)
  - Hub에서 받은 통계 설정을 `~/.dadp-wrapper/crypto-endpoints.json`에 저장
  - Hub 연결 없이도 저장된 통계 설정 사용 가능
- **Wrapper 통계 옵션 저장/동기화**: Hub에서 Wrapper별 통계 전송/버퍼/샘플링/네트워크 옵션 설정 및 동기화 지원
  - Hub DB에 `ProxyInstanceStatsConfig.options` JSON 컬럼 추가 (Flyway V29)
  - Hub 백엔드 API에서 통계 옵션 조회/저장 지원
  - Hub 프론트엔드 통계 설정 모달에 고급 옵션 섹션 추가
  - Wrapper 동기화 시 통계 옵션을 받아 JSON으로 저장 후 적용
  - 지원 옵션:
    - 전송/버퍼: `buffer.maxEvents`, `flush.maxEvents`, `flush.intervalMillis`, `maxBatchSize`, `maxPayloadBytes`
    - 품질/샘플링: `samplingRate`, `includeSqlNormalized`, `includeParams`, `normalizeSqlEnabled`
    - 네트워크/재시도: `http.connectTimeoutMillis`, `http.readTimeoutMillis`, `retry.onFailure`

### 🔧 Changed

- **`EndpointStorage.saveEndpoints()` 메서드**: 통계 설정 파라미터를 받는 오버로드 메서드 추가
- **`SchemaSyncService.saveEndpointInfo()` 메서드**: 통계 설정도 함께 저장하도록 수정
- **`SchemaSyncService.EndpointInfo` DTO**: 통계 설정 필드 및 `cryptoUrl` 필드 추가

### 📝 기술적 배경 (통계 설정 저장)

- 기존에는 엔드포인트 정보만 영구 저장했으나, 통계 설정도 함께 저장하여 Hub 연결 없이 사용 가능
- 스키마 동기화 시 한 번에 모든 설정(엔드포인트, 통계)을 받아서 저장
- Proxy 인스턴스별 통계 설정이 있으면 해당 설정 사용, 없으면 전역 설정 사용

### 🔄 영향 (통계 설정 저장)

- ✅ Hub 연결 없이도 통계 설정 사용 가능
- ✅ 스키마 동기화 시 모든 설정이 한 번에 저장되어 효율적
- ✅ 기존 코드와 하위 호환성 유지 (기존 메서드 유지)

---

## [4.8.0] - 2025-12-12

### 🎉 릴리즈 정보

**버전**: 4.8.0  
**릴리즈 일자**: 2025-12-12  
**주요 개선사항**: Java 8 전용 버전, Hub 스키마 동기화 시 암복호화 URL 자동 전달, 단일 cryptoUrl 사용

### ⚠️ 중요: 버전 번호 체계

**버전 번호는 Java 버전을 나타냅니다:**
- **`4.17.0`** = Java 17 이상용
- **`4.8.0`** = Java 8 전용 (이 버전)

Java 8 환경에서는 반드시 `4.8.0` 버전을 사용해야 합니다.

### ✅ Added

- **Java 8 전용 버전**: Java 8 환경에서 사용하기 위한 별도 버전
  - Java 8 타겟 빌드 (`maven.compiler.source/target` 1.8)
  - Java 8 호환 코드로 수정 (`.toList()` → `.collect(Collectors.toList())`)
  - `dadp-hub-crypto-lib:java8` 의존성 사용
- **Hub 스키마 동기화 시 암복호화 URL 자동 전달**
  - 스키마 동기화 응답에 `EndpointInfo` 포함
  - 단일 `cryptoUrl` 사용으로 통합
  - `EndpointStorage`에 엔드포인트 정보 자동 저장
- **Datasource 및 Schema 추상화**
  - `datasourceId : schemaName.tableName.columnName` 형식 지원
  - 정책 스냅샷 API 및 버전 관리

### 🔧 Changed

- **Java 8 호환성 개선**
  - `.toList()` → `.collect(Collectors.toList())` 변경
  - `Java11HttpClientAdapter` 제외 (Java 8에서는 `HttpURLConnection` 사용)
- **DirectCryptoAdapter**
  - Java 8 호환 코드로 수정
  - Stream API 사용 시 `Collectors.toList()` 사용
- **버전 번호 체계**
  - `4.8.0` = Java 8 전용 버전
  - `4.17.0` = Java 17 이상용 버전 
  - 캐시된 정책 사용 로직 추가
  - 오프라인 모드에서의 복호화 지원
- **DadpProxyStatement**: 
  - Statement 래핑 지원
  - 캐시된 정책을 사용한 복호화
- **SqlParser**: 
  - SQL 파싱 성능 개선
  - Alias 매핑 로직 최적화
- **HubCryptoService API 변경**: 
  - `createInstance(String hubUrl, int timeout, boolean failOpen)` 
  - → `createInstance(String hubUrl, String apiBasePath, int timeout, boolean failOpen)`
- **DirectCryptoAdapter 생성자 변경**: 
  - `DirectCryptoAdapter(HubCryptoService hubCryptoService)` 
  - → `DirectCryptoAdapter(HubCryptoService hubCryptoService, EndpointStorage endpointStorage)`

### 🐛 Known Issues

- **컴파일 에러**: 현재 버전은 일부 컴파일 에러가 있어 빌드가 완료되지 않음
  - EndpointStorage.java: 변수 초기화 문제
  - DadpProxyResultSet.java: 변수 참조 오류
  - DadpProxyConnection.java: HubCryptoService API 변경
  - DirectCryptoAdapter.java: API 시그니처 불일치

### 📚 Compatibility

| Java 버전 | 지원 여부 | 비고 |
|-----------|----------|------|
| Java 8    | ✅ 지원   | 기존 지원 유지 |
| Java 11   | ✅ 지원   | 기존 지원 유지 |
| Java 17   | ✅ 지원   | 기본 빌드 버전 |
| Java 21   | ✅ 지원   | 하위 호환성으로 지원 |

### 🔗 Links

- Release Notes: [RELEASE_NOTES_v4.8.0.md](RELEASE_NOTES_v4.8.0.md)

---

## [3.0.5] - 2025-11-26

### 🎉 릴리즈 정보

**버전**: 3.0.5  
**릴리즈 일자**: 2025-11-26  
**주요 개선사항**: Hibernate/MyBatis 등 다중 ORM 지원, 첫 번째 쿼리부터 암호화 정책 적용 보장, Java 버전별 HTTP 클라이언트 추상화

### ✅ Added

- **Hibernate SQL Alias 자동 변환**: Hibernate가 생성하는 alias(`email3_0_`)를 원본 컬럼명(`email`)으로 자동 변환
- **다중 ORM/프레임워크 지원**: Hibernate, MyBatis, JdbcTemplate, jOOQ, QueryDSL 등 모든 JDBC 기반 프레임워크 호환
- **SqlParser alias 매핑**: SELECT문 파싱 시 `AS` 키워드 기반 alias 매핑 자동 생성
- **정책 로드 완료 대기 로직**: `CountDownLatch`를 사용하여 정책 로드 완료를 대기하는 기능 추가
- **`ensureMappingsLoaded()` 메서드**: 모든 `prepareStatement` 호출 전에 정책 로드 완료 확인
- **타임아웃 설정**: 정책 로드 대기 최대 10초 (무한 대기 방지)
- **DadpProxyStatement 클래스**: Statement 래핑하여 `executeQuery()`에서 복호화 처리
- **ResultSet.getObject() 복호화**: JdbcTemplate 호환을 위해 `getObject()` 메서드에 복호화 로직 추가
- **HTTP 클라이언트 추상화**: Java 버전에 따라 최적의 HTTP 클라이언트 자동 선택
  - Java 8: `HttpURLConnection` 사용
  - Java 11+: `java.net.http.HttpClient` 사용
  - `HttpClientAdapter.Factory.create()` 팩토리 패턴으로 구현체 생성
- **Hub 알림 시스템 통합**: 암복호화 실패 시 Hub로 자동 알림 전송
- **Data truncation 자동 복구**: 암호화된 데이터가 컬럼 크기를 초과할 경우 평문으로 자동 재시도 (Fail-open 모드)
- **원본 데이터 저장**: Data truncation 발생 시 평문으로 재시도하기 위한 원본 데이터 보관 기능

### 🔧 Changed

- **DadpProxyConnection**: 정책 로드가 완료될 때까지 쿼리 실행 대기 (첫 번째 쿼리부터 암호화 적용 보장)
- **DadpProxyConnection.createStatement()**: `DadpProxyStatement`를 반환하도록 변경
- **`loadMappingsFromHub()`**: `CountDownLatch`를 사용하여 완료 시점 알림
- **DadpProxyResultSet.getString(String)**: alias를 원본 컬럼명으로 변환 후 정책 조회
- **DadpProxyResultSet.getObject()**: String 타입인 경우 복호화 처리 추가
- **DadpProxyResultSet.decryptStringByLabel()**: alias 변환 로직 추가
- **SqlParser.SqlParseResult**: aliasToColumnMap 필드 추가, getOriginalColumnName() 메서드 추가
- **DadpProxyPreparedStatement**: `executeUpdate()` 메서드에서 Data truncation 에러 감지 및 자동 복구 로직 추가
- **HubCryptoAdapter**: 암복호화 실패 시 Hub 알림 서비스와 통합
- **DadpProxyConnection**: HubNotificationService 초기화 및 통합

### 🐛 Fixed

- ✅ **Hibernate 복호화 실패 문제 해결**: alias(`email3_0_`) → 원본 컬럼명(`email`) 변환으로 정책 조회 성공
- ✅ 첫 번째 쿼리에 암호화 정책이 적용되지 않던 문제 해결
- ✅ 정책 로드가 비동기로 수행되어 발생하던 타이밍 이슈 해결
- ✅ JdbcTemplate이 Statement를 사용할 때 복호화가 안 되던 문제 해결 (DadpProxyStatement 추가)
- ✅ ResultSet.getObject() 호출 시 복호화가 안 되던 문제 해결
- ✅ DadpProxyResultSet.getString() 중괄호 오류 수정
- Data truncation 에러 발생 시 애플리케이션 중단 문제 해결 (평문으로 자동 재시도)
- 암호화된 데이터가 컬럼 크기를 초과할 경우 알림 전송 및 자동 복구 기능 추가

### 🔌 ORM/Framework Compatibility

| 프레임워크 | 암호화 | 복호화 | 비고 |
|-----------|--------|--------|------|
| **JdbcTemplate** | ✅ | ✅ | 직접 컬럼명 사용 |
| **Hibernate/JPA** | ✅ | ✅ | alias 자동 변환 |
| **MyBatis** | ✅ | ✅ | AS alias 파싱 지원 |
| **jOOQ** | ✅ | ✅ | AS alias 파싱 지원 |
| **QueryDSL** | ✅ | ✅ | AS alias 파싱 지원 |

### 📚 Compatibility

| Java 버전 | 지원 여부 | 비고 |
|-----------|----------|------|
| Java 8    | ✅ 지원   | 기존 지원 유지 |
| Java 11   | ✅ 지원   | 기존 지원 유지 |
| Java 17   | ✅ 지원   | 기본 빌드 버전 |
| Java 21   | ✅ 지원   | 하위 호환성으로 지원 |

### 🔗 Links

- Release Notes: [RELEASE_NOTES_v3.0.5.md](RELEASE_NOTES_v3.0.5.md)

---

## [3.0.4] - 2025-11-12

### 🎉 릴리즈 정보

**버전**: 3.0.4  
**릴리즈 일자**: 2025-11-12  
**주요 개선사항**: Java 8 호환성 개선

### ✅ Added

- Java 8 호환성 지원 추가
- Java 8, 11, 17 프로파일별 빌드 지원

### 🔧 Changed

- **SchemaSyncService**: `java.net.http.HttpClient` (Java 11+) → `java.net.HttpURLConnection` (Java 8+)
- **MappingSyncService**: `java.net.http.HttpClient` (Java 11+) → `java.net.HttpURLConnection` (Java 8+)

### 🐛 Fixed

- Java 8 환경에서 발생하던 `NoClassDefFoundError: java/net/http/HttpClient` 오류 해결
- Java 8 환경에서 정상 동작 확인

### 📦 Build & Deployment

- **Java 8 전용 빌드**: 프로파일 없이 기본 빌드가 Java 8 타겟
- **의존성**: `dadp-hub-crypto-lib:java8:1.1.0` 사용
- **Maven Central 배포**: 아직 진행되지 않음 (로컬 테스트 필요)

### 📚 Compatibility

| Java 버전 | 지원 여부 | 비고 |
|-----------|----------|------|
| Java 8    | ✅ 지원   | **이 버전의 타겟** (컴파일 타겟) |
| Java 11   | ✅ 지원   | **하위 호환성** (Java 8 바이트코드는 Java 11에서 실행 가능) |
| Java 17   | ✅ 지원   | **하위 호환성** (Java 8 바이트코드는 Java 17에서 실행 가능) |
| Java 21   | ✅ 지원   | **하위 호환성** (Java 8 바이트코드는 Java 21에서 실행 가능) |

**중요**: 
- **4.8.0**은 Java 8로 컴파일되었지만, Java 11/17/21에서도 실행 가능합니다 (하위 호환성)
- **4.17.0**은 Java 17로 컴파일되어 Java 8에서는 실행 불가능합니다
- **권장**: Java 11/17 환경에서는 `4.17.0` 버전 사용 권장 (최신 기능 및 성능)

### 🔗 Links

- Release Notes: [RELEASE_NOTES_v4.8.0.md](RELEASE_NOTES_v4.8.0.md)

### 🔗 Links

- GitHub: https://github.com/daone-dadp/dadp-jdbc-wrapper
- Maven Central: https://central.sonatype.com/artifact/io.github.daone-dadp/dadp-jdbc-wrapper
- Release Notes: [RELEASE_NOTES_v3.0.4.md](RELEASE_NOTES_v3.0.4.md)

---

## [3.0.3] - 이전 버전

이전 버전의 변경사항은 [GitHub Releases](https://github.com/daone-dadp/dadp-jdbc-wrapper/releases)에서 확인하세요.

---

## 릴리즈 노트 형식

각 주요 릴리즈에 대한 상세한 릴리즈 노트는 별도 파일로 관리됩니다:

- [v3.0.5 Release Notes](RELEASE_NOTES_v3.0.5.md)
- [v3.0.4 Release Notes](RELEASE_NOTES_v3.0.4.md)

