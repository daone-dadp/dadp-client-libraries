# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [5.8.6] - 2026-04-16

### Fixed

- **AOP and JDBC policy snapshot endpoint sync now preserve stats-aggregator settings**
  - AOP snapshot consumers now pass `endpoint.statsAggregator.enabled/url/mode/slowThresholdMs` through to endpoint storage
  - Spring 5 AOP now consumes snapshot endpoint data directly when the Hub includes it, instead of always forcing a follow-up endpoint sync

## [5.8.5] - 2026-04-16

### Fixed

- **Wrapper stats-aggregator config now honors the Hub 5.8.6 policy snapshot contract**
  - `PolicySnapshot.endpoint.statsAggregator` is now deserialized by the wrapper sync DTO
  - Wrapper endpoint storage no longer overwrites snapshot stats values with hard-coded defaults during policy-sync saves

- **Exported config stats parsing now prefers the new contract with legacy fallback**
  - `statsConfig.enabled/url/mode/slowThresholdMs` is read first
  - Legacy `statsAggregator*` keys are used only when the new fields are absent

## [5.8.4] - 2026-04-16

### Added

- **SQL insight telemetry now covers previously missed JDBC execution paths**
  - `Statement.executeQuery/executeUpdate/execute` and their overloads now emit wrapper SQL telemetry
  - `PreparedStatement.execute()` now emits wrapper SQL telemetry with the existing best-effort async sender
  - `PreparedStatement` Statement-style overloads now emit telemetry and preserve the SQL text used by `getResultSet()`

### Notes

- Telemetry remains best-effort and asynchronous to avoid adding a blocking network round trip to SQL execution
- Result-set wrapping now keeps the last executed SQL text for `Statement.execute(...)` and equivalent overloads

## [5.8.3] - 2026-04-16

### Changed

- **Wrapper direct crypto HTTP logs now follow the shared logging-level policy**
  - External API request/response success logs were lowered to `DEBUG`
  - Recoverable HTTP failures, timeouts, and non-2xx responses are emitted at `WARN`
  - Request logging now uses length and count metadata instead of plaintext/ciphertext previews

### Added

- **Wrapper HTTP timing logs for direct crypto calls**
  - `HubCryptoService` now logs `writeMs`, `responseCodeMs`, `readMs`, and `totalMs` per HTTP call
  - Timing logs are emitted at `DEBUG` for encrypt, decrypt, and batch direct-crypto requests

## [5.8.2] - 2026-04-16

### Changed

- **Wrapper direct crypto transport now keeps successful HTTP connections reusable**
  - `HubCryptoService` no longer force-disconnects successful requests after the response body has been fully consumed
  - This keeps the Java 8 `HttpURLConnection` path eligible for JDK keep-alive reuse in repeated encrypt/decrypt calls

### Verified

- `mvn -pl dadp-jdbc-wrapper -am test -DskipITs=true`
- Java 8 wrapper reactor build stayed green after the transport lifecycle change
- Existing wrapper unit tests passed without additional runtime contract changes

## [5.8.1] - 2026-04-13

### Added

- **SQream JDBC URL and vendor recognition support**
  - Wrapper now accepts `jdbc:dadp:Sqream://...` URLs
  - SQream semicolon JDBC parameters are preserved while wrapper-only options are stripped before vendor-driver handoff
  - `SqreamDB` product metadata is normalized to the internal `sqream` vendor key

### Fixed

- **SQream metadata collection no longer fails on missing optional JDBC columns**
  - `SchemaRecognizer` now checks the available `DatabaseMetaData.getColumns()` labels before reading optional fields such as `IS_AUTOINCREMENT`
  - Missing SQream optional metadata is treated as absent data instead of a wrapper-side failure

- **Java 8 dependency modules now explicitly override inherited compiler release**
  - `dadp-common-logging-lib`, `dadp-common-sync-lib-core`, and `dadp-common-sync-lib-j8` now force `release 8`
  - This prevents Java 17 bytecode leakage into the wrapper Java 8 runtime path

### Verified

- Existing JDBC URL extraction behavior kept for MySQL, MariaDB, PostgreSQL, Oracle, and SQL Server
- SQream wrapper URL path now preserves vendor parameters while extracting wrapper-only options
- Wrapper dependency modules remain on Java 8 bytecode during reactor builds

## [5.5.15] - 2026-03-13

### Added

- **Oracle/Tibero: USER_TAB_COLUMNS + USER_OBJECTS 데이터 딕셔너리 뷰 기반 스키마 수집**
  - `getTables()` VIEW 누락 방지: 메타데이터 기반 스키마 수집으로 안정성 향상
  - USER_TAB_COLUMNS (컬럼 정보) + USER_OBJECTS (객체 정보) 조인으로 완벽한 스키마 정보 구성

- **Oracle: MATERIALIZED VIEW 수집 지원**
  - ROW_NUMBER 중복 제거: MATERIALIZED VIEW도 일반 VIEW와 동일하게 취급
  - MVIEW 타입 객체도 정상적으로 스키마 수집

- **Oracle: USER_COL_COMMENTS 한글 코멘트 수집**
  - columnComment 필드 추가: 한글 주석도 정상 수집

- **로그 설정 우선순위 정리**
  - Hub PolicySnapshot logConfig 1순위 (hubManaged 플래그)
  - 로컬 설정은 무시됨: Hub에서 중앙화된 로깅 정책 관리

---

## [5.5.14] - 2026-03-13

### Added

- **Schema force reload support**
  - Hub UI에서 "스키마 리로드" 버튼 클릭 시 Wrapper가 DB 메타데이터를 재수집
  - PolicySnapshot에 `forceSchemaReload` 플래그 추가
  - `JdbcBootstrapOrchestrator.forceReloadSchemas()`: 네이티브 JDBC Connection으로 스키마 재수집 후 Hub에 전송
  - VIEW 추가 등 DB 구조 변경 시 Wrapper 재시작 없이 스키마 갱신 가능

---

## [5.5.13] - 2026-03-13

### Fixed

- **Empty string encryption skip**
  - `processStringEncryption()` now skips encryption for empty strings (`""`)
  - Prevents Engine `@NotBlank` validation failure (HTTP 400) when WHERE clause parameters are empty
  - Applies to all SQL types: SELECT WHERE, INSERT, UPDATE

---

## [5.5.12] - 2026-03-12

### Added

- **VIEW support in schema collection**
  - `SchemaRecognizer.getTables()` now collects both TABLE and VIEW types
  - VIEWs can be mapped with encryption policies in Hub, enabling encrypt/decrypt on VIEW columns

---

## [5.5.11] - 2026-03-12

### Fixed

- **UPDATE WHERE clause parameter mapping for ECB search encryption**
  - UPDATE statements now parse WHERE clause parameters (previously only SELECT did)
  - WHERE clause parameters in UPDATE are routed through search encryption path (`encryptForSearch`)
  - SET clause parameters continue to use full encryption path (`encrypt`)
  - Added `whereClauseParamIndices` tracking to distinguish SET vs WHERE parameters
  - Fixes: `UPDATE ... SET col=? WHERE ecb_col=?` now correctly encrypts the WHERE value for ECB column search

---

## [5.5.10] - 2026-03-11

### Changed

- **All log messages and exception messages converted to English (removed Korean text and emoji)**
  - All bundled libraries: dadp-jdbc-wrapper, dadp-common-sync-lib, dadp-hub-crypto-lib, dadp-aop-spring5/6
  - Consistent English-only logging across all components

### Fixed

- **`setSchema()` now updates cached schema name**
  - When `connection.setSchema()` is called (e.g. `ALTER SESSION SET CURRENT_SCHEMA`), the cached schema name is updated accordingly
  - `cachedSchemaName` changed from `final` to `volatile` for thread-safe visibility

---

## [5.5.9] - 2026-03-11

### ⚡ Performance

- **Oracle/Tibero 스키마 이름 조회 캐싱 (sysauth$ 반복 쿼리 제거)**
  - `getCurrentSchemaName()`이 매 SQL 파라미터 바인딩마다 `connection.getSchema()` / `getMetaData().getUserName()` 호출
  - Oracle JDBC 드라이버가 이 호출 시 내부적으로 `sysauth$` 계층 쿼리를 반복 실행하여 성능 저하 유발
  - **수정**: Connection 생성 시 스키마 이름을 1회만 조회하여 `cachedSchemaName` 필드에 캐싱
  - `getCurrentSchemaName()`은 캐싱된 값을 즉시 반환 (DB 쿼리 없음)

---

## [5.5.8] - 2026-03-10

### ✨ New

- **검색 암호화 (WHERE clause) 지원**
  - 암호화 대상 컬럼의 WHERE 조건에서 알고리즘 특성(useIv)에 따라 암호화 검색/평문 검색 자동 분기
  - `useIv=false` (A256ECB, FPE_FF1): 결정적 암호화 → 암호화해서 검색
  - `useIv=true` (A256GCM, ARIA256, SEED128): 비결정적 암호화 → 평문 검색
  - `usePlain=true`: 평문 저장 컬럼 → 평문 검색
  - LIKE 와일드카드(%, _) 감지: 와일드카드 있으면 평문 검색, 없으면 일반 검색 로직 적용

- **Exported Config 버전 비교 업데이트 지원**
  - `ExportedConfigLoader`가 초기 부트스트랩뿐 아니라 중간 정책 업데이트도 지원
  - `policyVersion` 비교: 파일 버전 > 현재 버전일 때만 적용, 동일/이전 버전은 스킵
  - `wrapper-config*.json` 파일명 자동 인식 (Hub 다운로드 파일명 그대로 사용 가능)

- **PolicyAttributes (useIv, usePlain) 동기화**
  - Hub `/proxy/policies` API에서 `policyAttributes` 맵 수신
  - `MappingSyncService`가 policyAttributes 파싱 후 `PolicyResolver`에 전달
  - `PolicyResolver.isSearchEncryptionNeeded()` 메서드로 검색 암호화 필요 여부 판단

---

## [5.5.7] - 2026-02-27

### 🐛 Fixed

- **SLF4J NOP 바인딩으로 DADP 로그 미출력 문제 해결**
  - `slf4j-nop` 의존성이 `logback-classic`과 공존하여 shade JAR의 `StaticLoggerBinder`가 `NOPLoggerFactory`를 사용
  - `-Ddadp.enable-logging=true` 설정해도 모든 로그가 NOP으로 버려짐
  - **수정**: `slf4j-nop` 의존성 제거 → `logback-classic`의 바인딩이 정상 사용되어 로그 출력
  - **주의**: Tomcat lib에 이전 버전 JAR(5.5.5, 5.5.6)이 남아있으면 NOP 바인딩이 우선 로드될 수 있음. 반드시 이전 JAR 삭제 필요

### 📝 Known Issues (고객 환경)

- **Oracle 한국어 캐릭터셋(KO16MSWIN949) 스키마 수집 실패**
  - Oracle DB가 `KO16MSWIN949` 캐릭터셋 사용 시 `SchemaRecognizer`에서 메타데이터 수집 실패
  - 에러: `java.sql.SQLException: Non supported character set (add orai18n.jar in your classpath): KO16MSWIN949`
  - **해결**: Tomcat lib 또는 클래스패스에 `orai18n.jar` 추가 필요 (Oracle JDBC 확장 캐릭터셋 지원 라이브러리)
  - 다운로드: `https://repo1.maven.org/maven2/com/oracle/database/nls/orai18n/21.7.0.0/orai18n-21.7.0.0.jar`

---

## [5.5.6] - 2026-02-27

### 🐛 Fixed

- **Oracle 비 DBA 유저 스키마 스캔 실패 해결**
  - `SchemaRecognizer`가 `metaData.getTables(null, null, "%", ...)` 호출 시 Oracle `ALL_TABLES` 전체 조회
  - 비 DBA 유저(예: soe)는 권한 부족으로 스키마 수집 실패 또는 빈 결과 반환
  - **수정**: Oracle인 경우 `connection.getSchema()` (폴백: `metaData.getUserName()`)를 `schemaPattern`으로 사용하여 자기 스키마만 조회

---

## [5.5.5] - 2026-02-27

### 🔧 Changed

- **hub-crypto-lib 의존성 완전 분리 (Spring-free Wrapper)**
  - Wrapper JAR에서 Spring Framework 의존성 완전 제거
  - `HubCryptoService`: `RestTemplate` → `HttpURLConnection` 기반으로 재작성
  - DTO 클래스(EncryptRequest/Response, DecryptRequest/Response): Lombok 제거, Plain Java로 재작성
  - Exception 클래스(HubCryptoException, HubConnectionException): Wrapper 모듈 내부에 복사
  - Standalone Tomcat(순수 Java Servlet) 환경에서 정상 동작 확인

---

## [5.5.4] - 2026-02-26

### 🐛 Fixed

- **ConsoleLogger 추가**: SLF4J가 없는 환경(standalone Tomcat)에서 `System.out` 기반 폴백 로거 제공
- **Hub bootstrap handshake 수정**: Hub 연결 시 초기 핸드셰이크 실패 문제 해결

---

## [5.5.1] ~ [5.5.3] - 2026-02-26

### 🐛 Fixed

- **Oracle JDBC URL 파싱 오류 해결**: `jdbc:dadp:oracle:thin:@//host:port/service` 형식 URL 파싱 실패 수정
- **MSSQL JDBC URL 파싱 오류 해결**: `jdbc:dadp:sqlserver://host:port;databaseName=db` 형식 URL 파싱 실패 수정
- **WrapperDownloadController 버전 선택 개선**: `findLatestVersionForJavaVersion()` 메서드가 최신 버전을 올바르게 반환하도록 수정

---

## [5.5.0] - 2026-02-25

### 🎉 릴리즈 정보

- 상세: [RELEASE_NOTES_v5.5.0.md](RELEASE_NOTES_v5.5.0.md)

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
