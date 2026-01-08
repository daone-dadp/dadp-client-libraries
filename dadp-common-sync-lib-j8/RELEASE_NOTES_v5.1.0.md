# DADP Common Sync Library v5.1.0 Release Notes (Java 8)

## 🎉 릴리즈 정보

**버전**: 5.1.0  
**릴리즈 일자**: 2026-01-07  
**배포 일자**: 2026-01-07  
**배포 상태**: ✅ **PUBLISHED** (Maven Central 반영 완료)  
**Java 버전**: **Java 8 전용**  
**주요 개선사항**: 정책 매핑 동기화 로직 개선, Hub 304 응답 처리 개선

---

## 📋 주요 변경사항

### 🔄 Changed

- **정책 매핑 동기화 로직 개선**
  - `MappingSyncService.syncPolicyMappingsAndUpdateVersion()`에서 버전 업데이트 로직 개선
  - `checkMappingChange()` 호출 시 304 응답 처리 개선
  - 버전 동기화 완료 로그 메시지 개선

- **Hub 304 Not Modified 응답 처리 개선**
  - `checkMappingChange()`에서 304 응답을 올바르게 처리
  - 버전이 같을 때 불필요한 동기화 작업 제거

### 🐛 Fixed

- **버전 동기화 완료 로그 메시지 개선**
  - `syncPolicyMappingsAndUpdateVersion()`에서 버전 업데이트 로그 개선
  - 304 응답 시 "업데이트 실패"가 아닌 "이미 동기화됨"으로 명확하게 표시
  - DEBUG 레벨 로그로 변경하여 불필요한 경고 로그 제거

---

## 🔄 마이그레이션 가이드

### 5.0.5 → 5.1.0 (Java 8)

**의존성 업데이트**:
```xml
<!-- 이전 -->
<dependency>
    <groupId>io.github.daone-dadp</groupId>
    <artifactId>dadp-common-sync-lib-j8</artifactId>
    <version>5.0.5</version>
</dependency>

<!-- 현재 (Java 8) -->
<dependency>
    <groupId>io.github.daone-dadp</groupId>
    <artifactId>dadp-common-sync-lib-j8</artifactId>
    <version>5.1.0</version>
</dependency>
```

**설정 파일**: 변경 불필요

**코드 변경**: 불필요

---

## 📚 호환성 매트릭스

### Java 버전

| Java 버전 | 지원 여부 | 비고 |
|-----------|----------|------|
| Java 8    | ✅ 지원   | **이 버전의 타겟** (컴파일 타겟) |
| Java 11   | ✅ 지원   | **하위 호환성** (Java 8 바이트코드는 Java 11에서 실행 가능) |
| Java 17   | ❌ 권장 안 함 | `dadp-common-sync-lib-j17:5.1.0` 사용 권장 |
| Java 21   | ❌ 권장 안 함 | `dadp-common-sync-lib-j17:5.1.0` 사용 권장 |

### 의존성

- **dadp-common-sync-lib-core**: 5.1.0
- **Spring Web**: 5.3.31 (Java 8 호환)
- **Jackson**: 2.15.2 (JSON 처리)
- **SLF4J**: 1.7.36 (로깅)

### Hub/Engine 버전

| 컴포넌트 | 최소 버전 | 권장 버전 |
|----------|----------|----------|
| **Hub** | 5.0.5 | 5.0.5+ (304 응답 지원) |
| **Engine** | 5.0.5 | 5.0.5+ |

---

## 🔗 관련 문서

- [CHANGELOG.md](../CHANGELOG.md)
- [이전 버전 릴리즈 노트](./RELEASE_NOTES_v5.0.5.md)

---

## 📝 참고사항

### 배포 정보

- **배포 일자**: 2026-01-07
- **Deployment ID**: `c838f963-2116-455d-8818-14cfed07d852`
- **배포 상태**: ✅ Validated (수동 Publish 필요)
- **Maven Central URL**: https://search.maven.org/artifact/io.github.daone-dadp/dadp-common-sync-lib-j8/5.1.0/jar

---

## 👥 기여자

- DADP Development Team

---

**릴리즈 날짜**: 2026-01-07  
**배포 날짜**: 2026-01-07  
**이전 버전**: 5.0.5  
**Java 버전**: Java 8 전용  
**배포 상태**: ✅ Validated (수동 Publish 필요)

