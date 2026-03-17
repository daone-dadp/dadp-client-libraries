# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [5.5.1] - 2026-03-17

### 🔄 Changed

- **ApiResponse v2 code 기반 전환 (Phase 4)**
  - 모든 응답 클래스에 `code` 필드 추가, `isSuccess()`를 code 우선 + success fallback으로 변경
  - **dadp-common-sync-lib-j8/j17**: MappingSyncService 내부 클래스 (CheckMappingChangeResponse, PolicySnapshotResponse, MappingListResponse)
  - **dadp-jdbc-wrapper**: DatasourceRegistrationService.ApiResponse, MappingSyncService 내부 클래스, HubCryptoService 외부 응답 체크
  - **dadp-hub-crypto-lib**: HubCryptoService 외부 응답 체크, HubEndpointSyncService
  - **dadp-aop-spring5**: AopSchemaSyncService, AopSchemaSyncServiceV2
  - **dadp-aop-spring6**: AopPolicyMappingSyncService, AopBootstrapOrchestrator
  - **RestTemplateSchemaSyncExecutor** (j8/j17): Map 기반 + 내부 DTO 모두 v2
  - **HttpClientSchemaSyncExecutor** (j8): Map 기반 code 우선 체크
  - **EndpointSyncService** (j8/j17/jdbc): JsonNode code 우선 체크

### Compatibility

- Product Version: `5.5.1`
- Hub 최소 버전: `5.6.3`
- Engine 최소 버전: `5.6.3`
- Breaking Changes: No (v1 boolean 하위 호환 유지)
