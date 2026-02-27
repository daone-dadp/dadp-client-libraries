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
