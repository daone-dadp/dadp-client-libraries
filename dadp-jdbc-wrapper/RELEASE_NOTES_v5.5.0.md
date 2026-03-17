# DADP JDBC Wrapper v5.5.0 Release Notes

**배포 버전 → 5.5.0 — 배포 완료 (2026-02-25)**

> 이 문서는 **마지막 배포 버전(5.1.0)** 릴리즈와 **5.5.0** 작업 내용을 통합한 릴리즈 문서입니다.

---

## 🎉 릴리즈 정보

**버전**: 5.5.0
**릴리즈 일자**: 2026-02-25
**배포 상태**: ✅ **배포 완료** (Hub 5.5.0에 포함 배포)
**마지막 배포 버전**: 5.1.0 (본 문서에 5.1.0 → 5.5.0 변경 통합)
**이전 문서 기준**: 5.1.0
**주요 개선사항**: 동적 SELECT 복호화 스킵 이슈 해결, instanceId당 부팅 1세트 공유, policyName 기반 복호화 경로 추가, isEncryptedData 최소 길이 완화

---

## 📌 이전 배포 버전 요약 (5.1.0)

- **v5.1.0** (마지막 배포): 버전 체계 전환(4.8.1 → 5.1.0), 저장소 구조 통일(dadp-client-libraries 내부), Maven Central 배포 완료. 기능 변경 없음.
- 상세: `RELEASE_NOTES_v5.1.0.md` 참조.

---

## 📋 5.5.0 변경사항

### ✅ 구현 완료

#### 동적 SELECT 복호화 스킵 이슈 해결

- **문제**: 백틱(`` `users` ``) 또는 따옴표(`'users'`)로 감싼 테이블명을 사용한 동적 SELECT 시 `SqlParser` 파싱 실패 → `sqlParseResult == null`로 복호화 스킵 → 암호문 그대로 반환되던 현상.
- **메타데이터 폴백·식별자 정규화**: 파싱 실패 시에도 `DadpProxyResultSet`에서 메타데이터(`getTableName(columnIndex)`, `getColumnName(columnIndex)` 또는 레이블)로 테이블/컬럼명 조회. 식별자에 `'` 또는 백틱이 있으면 `stripQuotesFromIdentifier()`로 제거 후 정책 조회·복호화 수행. (`getString(int)`, `getString(String)`, `decryptIfNeeded`, `decryptStringByLabel` 적용.)
- **폴백 경로 메모리 캐시**: 동일 ResultSet 내 반복 접근 시 `fallbackCacheByIndex`, `fallbackLabelToIndex`로 메타데이터·정책 조회 제거, 캐시 히트 시 `decryptValueWithResolvedPolicy()`만 호출.
- **정책 갱신 시 캐시 무효화**: `FallbackDecryptCacheEntry`에 `policyVersion` 저장. 캐시 사용 시 현재 버전과 비교해 다르면 해당 엔트리만 제거 후 재계산 — 수동 캐시 비우기 없이 갱신된 정책 자동 반영.
- 참고: `docs/working/dynamic-select-decryption-skip-analysis.md`

#### instanceId당 1세트 공유·중복 초기화 제거

- **오케스트레이터**: Connection 필드 제거, `runBootstrapFlow(Connection)` 인자로만 전달. 첫 부팅 시 메타데이터(dbVendor, host, port, database, schema) 추출 후 저장·재사용.
- **JdbcSchemaCollector**: Connection을 필드로 두지 않고 `collectSchemas(Connection)` 호출 시점에만 전달. 스키마 수집은 전체 부팅 1회만.
- **DadpProxyConnection**: instanceId당 오케스트레이터 1세트 캐시·재사용, `runBootstrapFlow(actualConnection)` 호출.
- **JdbcPolicyMappingSyncService**: 재등록(404) 시 connection/originalUrl 제거, 오케스트레이터에 저장한 메타데이터만 사용. 스케줄러에서 Connection 없이 재등록 가능.
- **Hub 알림 서비스**: instanceId당 1회만 생성·공유. "이미 실행됨" 분기에서 `initializeServicesWithHubId` 호출 제거 → 커넥션 풀에서 HubNotificationClient/Service 초기화 로그 반복 제거.
- 참고: `docs/design/jdbc-wrapper-bootstrap-logical-order.md`, `docs/working/wrapper-v5.5.0-todolist.md`

#### decrypt 경로에 policyName 파라미터 추가

- **흐름**: `DadpProxyResultSet` → `DirectCryptoAdapter` → `HubCryptoService` 복호화 요청 시 `policyName` 파라미터 전달 추가.
- Engine이 policyName 기반으로 알고리즘을 조회하여 복호화할 수 있도록 지원.
- 암호화 데이터 포맷 통일(3-part) 변경에 대응하여 복호화 정확도 향상.

#### HubCryptoService.isEncryptedData() 최소 길이 완화

- **변경 전**: 최소 길이 28 (A256GCM 기준).
- **변경 후**: 최소 길이 16으로 완화 — A256ECB, ARIA256, SEED128, FPE_FF1 등 비-GCM 알고리즘 암호문에 대응.
- 짧은 암호문이 평문으로 오인되어 복호화 시도 자체가 스킵되던 문제 해결.

#### Shade Plugin Tomcat 호환성 수정 (ServicesResourceTransformer 오염 제거)

- **문제**: maven-shade-plugin의 `ServicesResourceTransformer`가 모든 의존성의 `META-INF/services` 파일을 병합하면서, `javax.servlet.ServletContainerInitializer`에 Logback·Spring·Tomcat WebSocket의 `ServletContainerInitializer` 구현체가 포함됨. `minimizeJar`가 실제 클래스는 제거하나 서비스 파일 등록은 잔존 → Tomcat 환경에서 `ClassNotFoundException` 발생, JDBC 드라이버 초기화 실패.
- **수정**: pom.xml shade plugin filter에 `javax.servlet.ServletContainerInitializer`, `jakarta.servlet.ServletContainerInitializer`, `META-INF/maven/ch.qos.logback/**` exclude 추가.
- **검증**: dadp-test-app Docker 환경에서 JPA/MyBatis 암복호화 테스트 완료.

---

## v5.5.8 — #배포 전 (2026-03-10)

### ✨ New

#### 검색 암호화 (WHERE clause) 지원

- **기능**: 암호화 대상 컬럼의 WHERE 조건에서 알고리즘 특성(useIv)에 따라 암호화 검색/평문 검색 자동 분기
- **분기 로직**:
  - `useIv=false` (A256ECB, FPE_FF1): 결정적 암호화 → 검색값 암호화 후 DB 검색
  - `useIv=true` (A256GCM, ARIA256, SEED128): 비결정적 암호화 → 평문 검색 (매칭 불가)
  - `usePlain=true`: 평문 저장 컬럼 → 평문 검색
- **LIKE 와일드카드 감지**: `%`, `_` 포함 시 평문 검색, 미포함 시 정책 기반 분기

#### Exported Config 버전 비교 업데이트

- **기능**: `ExportedConfigLoader`가 `policyVersion` 비교를 통해 초기 부트스트랩뿐 아니라 중간 정책 업데이트도 지원
- **동작**: 파일 버전 > 현재 버전 → 적용, 동일/이전 버전 → 스킵
- **파일명**: `wrapper-config*.json` 패턴 자동 인식 (Hub 다운로드 파일명 그대로 사용 가능)

#### PolicyAttributes (useIv, usePlain) 동기화

- **기능**: Hub `/proxy/policies` API의 `policyAttributes` 맵을 파싱하여 PolicyResolver에 전달
- **검색 분기**: `PolicyResolver.isSearchEncryptionNeeded()` 메서드로 판단

---

## v5.5.9 — #배포 전 (2026-03-11)

### ⚡ Performance

#### Oracle/Tibero 스키마 이름 조회 캐싱 (sysauth$ 반복 쿼리 제거)

- **문제**: `getCurrentSchemaName()`이 매 SQL 파라미터 바인딩마다 `connection.getSchema()` / `getMetaData().getUserName()` 호출 → Oracle JDBC 드라이버가 내부적으로 `sysauth$` 계층 쿼리를 반복 실행하여 성능 저하 유발
- **수정**: Connection 생성 시 스키마 이름을 1회만 조회하여 `cachedSchemaName` 필드에 캐싱, `getCurrentSchemaName()`은 캐싱된 값을 즉시 반환 (DB 쿼리 없음)
- **스키마 해석 로직**: `resolveSchemaName()` static 메서드로 분리 (PostgreSQL/Oracle/Tibero/MSSQL/MySQL 벤더별 처리)

---

## v5.5.10 — #배포 전 (2026-03-11~12)

### 🐛 Fixed

#### setSchema() 호출 시 캐싱된 스키마 이름 갱신

- **문제**: `connection.setSchema()` 또는 `ALTER SESSION SET CURRENT_SCHEMA` 실행 시 캐시된 값과 실제 스키마가 불일치
- **수정**: `setSchema()` 오버라이드하여 `cachedSchemaName`도 함께 갱신, `final` → `volatile`로 변경 (스레드 안전 가시성 보장)

#### AOP CryptoService.java decrypt() 호출 시그니처 수정

- **문제**: `directCryptoAdapter.decrypt(encryptedData, maskPolicyName, maskPolicyUid, includeStats)` — 4개 파라미터로 호출하나 실제 메서드는 5개 파라미터 `(String, String, String, String, boolean)`
- **수정**: `decrypt(encryptedData, null, maskPolicyName, maskPolicyUid, includeStats)` — policyName에 null 추가 (spring5, spring6 모두)

### Changed

#### 전체 라이브러리 로그 영문화

- 대상: dadp-jdbc-wrapper, dadp-common-sync-lib (core/j17/j8), dadp-hub-crypto-lib, dadp-aop-spring5/6, dadp-common-logging-lib
- 모든 log 메시지 및 exception 메시지에서 한국어 텍스트와 이모지 제거, 영문으로 통일

#### 로그 레벨 재정의 (27건)

- **기준 정립**:
  - TRACE: 매 SQL/파라미터 바인딩 상세
  - DEBUG: 내부 흐름/상태 (정책 조회, 엔드포인트 로드, HTTP 연결, 동기화)
  - INFO: 초기화 완료, 부트스트랩, 설정 변경 (1회성 이벤트만)
  - WARN: 복구 가능한 오류, 폴백 동작
  - ERROR: 실제 실패 (복구 불가)
- **주요 변경**:
  - INFO → DEBUG (14건): 스토리지 로드, 엔드포인트 설정, Oracle 스키마 상세 등 반복 로그
  - INFO → WARN (2건): Data truncation 발생 (실제 경고 사항)
  - WARN → DEBUG (7건): Hub 연결 실패 후 폴백 동작 (이미 상위에서 WARN 출력됨)
  - DEBUG → TRACE (2건): 어댑터 생성, 엔드포인트 스냅샷 수신
  - ERROR → WARN (2건): SSL 초기화 실패, 크립토 서비스 초기화 실패 (복구 가능)

---

## v5.5.12 — #배포 전 (2026-03-12)

### ✨ New

#### VIEW 스키마 수집 지원 (SchemaRecognizer)

- **기능**: DB VIEW도 스키마 수집 대상에 포함하여 VIEW 컬럼에 암복호화 정책 매핑 가능
- **변경**: `SchemaRecognizer.getTables()` 호출 시 `new String[]{"TABLE"}` → `new String[]{"TABLE", "VIEW"}` 로 변경
- **효과**: Hub에서 VIEW를 대상으로 정책을 설정하면 VIEW 컬럼의 암복호화가 정상 동작
- **검증**: `user_view` VIEW에서 SELECT 복호화 정상 동작 확인

---

## v5.5.13 — #배포 전 (2026-03-13)

### 🐛 Fixed

#### Empty String 암호화 스킵

- **문제**: WHERE절 파라미터가 빈 문자열(`""`)인 경우 Engine에 암호화 요청 시 `@NotBlank` 검증 실패(HTTP 400) 발생
- **수정**: `processStringEncryption()`에서 빈 문자열은 암호화를 건너뛰고 그대로 전달
- **영향**: SELECT WHERE, INSERT, UPDATE 모든 SQL 타입에 적용

---

## v5.5.14 — #배포 전 (2026-03-13)

### ✨ New

#### Hub 스키마 강제 리로드 (Schema Force Reload)

- **기능**: Hub UI에서 "스키마 리로드" 버튼 클릭 시 Wrapper가 DB 메타데이터를 재수집하여 Hub에 재전송
- **Hub 변경**:
  - `InstanceSyncStatus` 엔티티에 `schemaReloadRequested` 필드 추가
  - `EngineRoutingController`: 스키마 리로드 요청/상태 조회 API 추가 (`POST/GET /api/engine-routing/schema-reload/proxy/{hubId}`)
  - `ProxyController`: PolicySnapshot에 `forceSchemaReload` 플래그 포함, 스키마 수신 시 플래그 자동 해제
  - Hub 프론트엔드: 프록시 인스턴스 설정 모달에 "스키마 리로드" 버튼 추가 (상태 폴링 포함)
- **Wrapper 변경**:
  - `JdbcBootstrapOrchestrator`: `forceReloadSchemas()` 메서드 추가 — 네이티브 JDBC Connection을 구해 SchemaRecognizer로 스키마 재수집 후 Hub에 전송
  - `JdbcPolicyMappingSyncService`: PolicySnapshot에서 `forceSchemaReload` 플래그 감지 시 `forceReloadSchemas()` 호출
  - `MappingSyncService` (j8): PolicySnapshot 파싱에 `forceSchemaReload` 필드 추가
- **효과**: VIEW 추가, 테이블 변경 등 DB 구조 변경 시 Wrapper 재시작 없이 스키마 갱신 가능

---

## v5.5.15 — #배포 전 (2026-03-13)

### ✨ New

#### Oracle/Tibero 데이터 딕셔너리 뷰 기반 스키마 수집

- **기능**: Oracle/Tibero DB에서 표준 JDBC `getTables()`/`getColumns()` 대신 데이터 딕셔너리 뷰(`USER_TAB_COLUMNS`, `USER_OBJECTS`, `USER_COL_COMMENTS`)를 사용하여 스키마 수집
- **배경**: 고객 Oracle 앱에서 표준 JDBC 메타데이터 API로 VIEW가 수집되지 않는 문제 발생
- **변경**: `SchemaRecognizer` 분기 추가:
  - Oracle/Tibero → `collectOracleSchemas()`: 데이터 딕셔너리 뷰 기반 (TABLE + VIEW + MATERIALIZED VIEW)
  - 기타 DB → `collectStandardSchemas()`: 기존 JDBC `getTables()`/`getColumns()` 유지
- **Oracle SQL**: `USER_TAB_COLUMNS` + `USER_OBJECTS` JOIN + `USER_COL_COMMENTS` LEFT JOIN
  - `ROW_NUMBER()` OVER 중복 제거: MATERIALIZED VIEW가 TABLE + MV 두 가지 오브젝트를 생성하는 문제 대응
  - `columnComment` 필드 추가: 한글 주석도 정상 수집
- **검증**: Oracle XE 18c Docker 환경에서 TABLE(6) + VIEW(2) + MATERIALIZED VIEW(1) 정상 수집 확인

### 🔧 Changed

#### Hub 로그 설정 우선순위 정리 (Hub > Local)

- **변경 전**: Hub PolicySnapshot과 로컬 설정(앱 구동시 옵션, 환경변수)이 서로 덮어쓰는 "last-write-wins" 구조
- **변경 후**: Hub PolicySnapshot의 logConfig가 1순위, 로컬 설정은 Hub 설정 이후 무시
- **구현**: `DadpLoggerFactory`에 `hubManaged` 플래그 추가
  - `setFromHub(boolean enabled, String level)`: Hub에서 호출 → `hubManaged=true` 설정 후 적용
  - `setLoggingEnabled()`, `setLogLevel()`: `hubManaged=true`이면 무시 (early return)
- **Wrapper**: `JdbcPolicyMappingSyncService.applyLogConfigFromSnapshot()`에서 `setFromHub()` 사용으로 변경

---

## v5.5.11 — #배포 전 (2026-03-12)

### ✨ New

#### UPDATE WHERE절 파라미터 매핑 추가 (ECB 검색 암호화 지원)

- **문제**: UPDATE 문의 WHERE절 파라미터가 컬럼명 매핑 없이 암호화 정책이 적용되지 않아, ECB/FPE 기반의 검색 암호화가 동작하지 않는 문제.
- **수정**: `DadpProxyPreparedStatement`에 `whereClauseParamIndices` 추적 Set 추가. UPDATE 파싱 시 SET절과 WHERE절 파라미터 인덱스를 구분하여 추적.
  - SET절 파라미터: 기존대로 전체 암호화(`encrypt`) 경로
  - WHERE절 파라미터: 검색용 암호화(`encryptForSearch`) 경로 — 결정론적 알고리즘(A256ECB, FPE_FF1)에서 동등 검색 정상 동작
- **파일**: `DadpProxyPreparedStatement.java`

#### Hub 연동 로그 설정 (logConfig) 동적 제어

- **기능**: Hub에서 Wrapper 로그 활성화 여부 및 레벨을 런타임에 동적으로 제어.
- **Wrapper 변경**:
  - `DadpLoggerFactory`: `setLogLevel(String level)`, `isLevelEnabled(String level)` 메서드 추가 — 런타임 로그 레벨 동적 변경
  - `Slf4jAdapter`: 각 로그 메서드(`debug`, `info`, `warn`, `error`, `trace`) 진입 시 `isLevelEnabled()` 가드 추가
  - `MappingSyncService` (j17, j8): PolicySnapshot 파싱 시 `logConfig` 섹션 추출 (enabled, level)
  - `JdbcPolicyMappingSyncService`: Hub에서 수신한 `logConfig`를 `DadpLoggerFactory`에 즉시 반영

---

### 📋 설계·계획 (구현 대기)

#### Root CA TrustStore 자동 설치 (hubId 발급 시점)

- **목표**: Wrapper가 hubId 발급 시 DADP Root CA를 JVM TrustStore에 자동 설치.
- **흐름**: `registerDatasource()` 완료 후 Hub/Manager에서 Root CA 다운로드(`GET /hub/api/v1/certificates/root` 등) → `keytool -importcert`로 TrustStore 설치.
- **에러 처리**: Root CA 다운로드/설치 실패 시 경고 로그만, hubId 발급은 성공 처리.

#### HTTPS 통신 지원

- Hub가 내려주는 Engine URL을 외부 게이트웨이 기준(HTTPS)으로 사용. Hub 5.5.0과 연동.
- DADP Root CA 설치 후 HTTPS 통신 시 PKIX 오류 방지.

- 참고: `docs/working/wrapper-v5.5.0-improvements.md`

*(실제 구현 완료 시 위 항목을 "구현 완료"로 픽스)*

---

## 🔗 관련 문서

- `docs/working/wrapper-v5.5.0-improvements.md` — Wrapper 5.5.0 개선 설계
- `docs/working/wrapper-v5.5.0-todolist.md` — 5.5.0 작업 리스트
- `docs/reports/weekly/2026/02/2026-2-06.md` — 주간 업무 보고서

---

## 📌 릴리즈 문서 관리 방식

- **배포되지 않은 중간 버전**이 있거나, 연관 모듈 버전 상승으로 특정 버전을 건너뛴 경우, **배포된 버전 ~ 현재 작업 버전**을 하나의 릴리즈 문서로 통합 관리합니다.
- 문서 상단에 **#배포 전**을 명시하고, **배포 버전 → X.X.X (현재 작업 중인 버전)** 으로 표기합니다.
- **배포를 실행한 뒤** 이 문서를 픽스합니다: 배포 일자·배포 완료 상태 반영, `#배포 전` 문구 제거 → **배포 릴리즈 문서**로 확정합니다.

---

**릴리즈 일자**: 2026-02-25
**마지막 배포 버전**: 5.1.0
**배포 버전**: 5.5.0 — 배포 완료 (2026-02-25)
**작성자**: DADP Development Team
