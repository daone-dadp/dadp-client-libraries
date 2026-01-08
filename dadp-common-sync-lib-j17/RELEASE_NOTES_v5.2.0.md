# DADP Common Sync Library v5.2.0 Release Notes (Java 17)

## 🎉 릴리즈 정보

**버전**: 5.2.0  
**릴리즈 일자**: 2026-01-07  
**배포 일자**: 2026-01-07  
**배포 상태**: ✅ **Validated** (수동 Publish 필요)  
**Java 버전**: **Java 17 전용**  
**주요 개선사항**: Hub 엔드포인트 통신 구조 개선, 헤더 기반 인증으로 전환, 의존성 버전 업데이트

---

## 📋 주요 변경사항

### 🔄 Changed

- **Hub 엔드포인트 통신 구조 개선**
  - 스키마 등록 엔드포인트(`POST /api/v1/aop/schemas/sync`) 개선
    - Body에서 `instanceId`와 `hubId` 제거 → 스키마 DTO만 포함
    - 헤더에 hubId 필수 (`X-DADP-TENANT`, `required = true`)
    - 헤더에 hubId가 없으면 404 반환
  - 매핑 버전 체크 엔드포인트(`GET /api/v1/aop/mappings/check`) 개선
    - Query 파라미터 제거 (`instanceId`, `alias`)
    - 헤더에 hubId 필수 (`X-DADP-TENANT`, `required = true`)
    - 헤더에 version 필수 (`X-Current-Version`, `required = true`)
    - 헤더에 hubId가 없으면 400 Bad Request 반환
    - 재등록 로직 제거 (404 반환만)

- **의존성 버전 업데이트**
  - `dadp-common-sync-lib-core`: 5.1.0 → 5.2.0

- **MappingSyncService 개선**
  - 200 OK 응답 시 `hasChange` 필드 확인 제거 → 무조건 `true` 반환
  - 404 Not Found 시 예외를 다시 던져서 상위에서 등록 처리
  - 헤더 기반 인증으로 전환하여 보안 및 일관성 향상

---

## 🔄 마이그레이션 가이드

### 5.1.0 → 5.2.0 (Java 17)

**의존성 업데이트**:
```xml
<!-- 이전 -->
<dependency>
    <groupId>io.github.daone-dadp</groupId>
    <artifactId>dadp-common-sync-lib-j17</artifactId>
    <version>5.1.0</version>
</dependency>

<!-- 현재 (Java 17) -->
<dependency>
    <groupId>io.github.daone-dadp</groupId>
    <artifactId>dadp-common-sync-lib-j17</artifactId>
    <version>5.2.0</version>
</dependency>
```

**설정 파일**: 변경 불필요

**코드 변경**: 불필요

**주요 변경사항**:
- Hub 엔드포인트 통신 구조가 헤더 기반으로 변경되어 더 안전하고 일관된 통신 보장
- Query 파라미터 제거로 URL 단순화 및 보안 향상
- 의존성 버전 업데이트로 최신 기능 및 버그 수정 반영

---

## 📚 호환성 매트릭스

### Java 버전

| Java 버전 | 지원 여부 | 비고 |
|-----------|----------|------|
| Java 8    | ❌ 지원 안 함 | `dadp-common-sync-lib-j8:5.2.0` 사용 |
| Java 11   | ❌ 지원 안 함 | `dadp-common-sync-lib-j8:5.2.0` 사용 |
| Java 17   | ✅ 지원   | **이 버전의 타겟** (컴파일 타겟) |
| Java 21   | ✅ 지원   | **하위 호환성** (Java 17 바이트코드는 Java 21에서 실행 가능) |

### 의존성

- **dadp-common-sync-lib-core**: 5.2.0
- **Spring Web**: 6.1.5 (Java 17+ 호환, RestTemplate 사용)
- **Jackson**: 2.16.1 (JSON 처리)
- **dadp-hub-crypto-lib**: 1.2.0
- **dadp-common-logging-lib**: 1.0.0

### Hub/Engine 버전

| 컴포넌트 | 최소 버전 | 권장 버전 |
|----------|----------|----------|
| **Hub** | 5.2.0 | 5.2.0+ (헤더 기반 엔드포인트 지원) |
| **Engine** | 5.0.5 | 5.0.5+ |

**⚠️ 중요**: Hub 5.2.0 이상이 필요합니다. 이전 버전의 Hub와는 호환되지 않습니다.

---

## 🔗 관련 문서

- [CHANGELOG.md](../CHANGELOG.md)
- [이전 버전 릴리즈 노트](./RELEASE_NOTES_v5.1.0.md)

---

## 📝 참고사항

### 배포 정보

- **배포 일자**: 2026-01-07
- **Deployment ID**: `b918a87d-f8a6-49c8-a385-028fbe3f527c`
- **배포 상태**: ✅ Validated (수동 Publish 필요)
- **Maven Central URL**: https://search.maven.org/artifact/io.github.daone-dadp/dadp-common-sync-lib-j17/5.2.0/jar

---

## 👥 기여자

- DADP Development Team

---

**릴리즈 날짜**: 2026-01-07  
**배포 날짜**: 2026-01-07  
**이전 버전**: 5.1.0  
**Java 버전**: Java 17 전용  
**배포 상태**: ✅ Validated (수동 Publish 필요)

