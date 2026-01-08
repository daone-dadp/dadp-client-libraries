# DADP Common Sync Library Core v5.1.0 Release Notes

## 🎉 릴리즈 정보

**버전**: 5.1.0  
**릴리즈 일자**: 2026-01-07  
**배포 일자**: 2026-01-07  
**배포 상태**: ✅ **PUBLISHED** (Maven Central 반영 완료)  
**주요 개선사항**: 정책 매핑 저장/로드 로직 개선, 공통 라이브러리 구조 유지

---

## 📋 주요 변경사항

### 🔄 Changed

- **정책 매핑 저장/로드 로직 개선**
  - `PolicyMappingStorage`와 `PolicyResolver`의 공통 라이브러리 구조 유지
  - 스키마 정보는 정책 매핑 키(`table.column`)에 포함되어 있음을 명확히 함
  - 정책 매핑 값이 `null`이면 스키마는 있지만 정책이 없는 상태로 처리

### 🐛 Fixed

- **버전 동기화 완료 로그 메시지 개선**
  - `MappingSyncService.syncPolicyMappingsAndUpdateVersion()`에서 버전 업데이트 로그 개선
  - 304 응답 시 "업데이트 실패"가 아닌 "이미 동기화됨"으로 명확하게 표시
  - DEBUG 레벨 로그로 변경하여 불필요한 경고 로그 제거

---

## 🔄 마이그레이션 가이드

### 5.0.5 → 5.1.0

**의존성 업데이트**:
```xml
<!-- 이전 -->
<dependency>
    <groupId>io.github.daone-dadp</groupId>
    <artifactId>dadp-common-sync-lib-core</artifactId>
    <version>5.0.5</version>
</dependency>

<!-- 현재 -->
<dependency>
    <groupId>io.github.daone-dadp</groupId>
    <artifactId>dadp-common-sync-lib-core</artifactId>
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
| Java 8    | ✅ 지원   | **하위 호환성** |
| Java 11   | ✅ 지원   | **하위 호환성** |
| Java 17   | ✅ 지원   | **하위 호환성** |
| Java 21   | ✅ 지원   | **하위 호환성** |

### 의존성

- **Jackson**: 2.15.2 (JSON 처리)
- **SLF4J**: 1.7.36 (로깅)

---

## 🔗 관련 문서

- [CHANGELOG.md](../CHANGELOG.md)
- [이전 버전 릴리즈 노트](./RELEASE_NOTES_v5.0.5.md)

---

## 📝 참고사항

### 배포 정보

- **배포 일자**: 2026-01-07
- **Deployment ID**: `faa11176-c119-4296-81bf-b3b83b3499cc`
- **배포 상태**: ✅ Validated (수동 Publish 필요)
- **Maven Central URL**: https://search.maven.org/artifact/io.github.daone-dadp/dadp-common-sync-lib-core/5.1.0/jar

---

## 👥 기여자

- DADP Development Team

---

**릴리즈 날짜**: 2026-01-07  
**배포 날짜**: 2026-01-07  
**이전 버전**: 5.0.5  
**배포 상태**: ✅ Validated (수동 Publish 필요)

