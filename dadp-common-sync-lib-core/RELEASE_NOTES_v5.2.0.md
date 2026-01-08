# DADP Common Sync Library Core v5.2.0 Release Notes

## 🎉 릴리즈 정보

**버전**: 5.2.0  
**릴리즈 일자**: 2026-01-07  
**배포 일자**: 2026-01-07  
**배포 상태**: ✅ **Validated** (수동 Publish 필요)  
**주요 개선사항**: Hub 엔드포인트 통신 구조 개선 지원, 헤더 기반 인증 지원

---

## 📋 주요 변경사항

### 🔄 Changed

- **Hub 엔드포인트 통신 구조 개선 지원**
  - 스키마 등록 엔드포인트 개선 지원
    - Body에서 `instanceId`와 `hubId` 제거 지원
    - 헤더 기반 hubId 전달 지원
  - 매핑 버전 체크 엔드포인트 개선 지원
    - Query 파라미터 제거 지원
    - 헤더 기반 hubId 및 version 전달 지원

- **공통 라이브러리 구조 유지**
  - 모든 모듈(AOP Java 8/17, Wrapper Java 8/17)이 동일한 인터페이스 사용
  - 버전 독립적인 공통 코드(DTOs, interfaces, storage) 제공

---

## 🔄 마이그레이션 가이드

### 5.1.0 → 5.2.0

**의존성 업데이트**:
```xml
<!-- 이전 -->
<dependency>
    <groupId>io.github.daone-dadp</groupId>
    <artifactId>dadp-common-sync-lib-core</artifactId>
    <version>5.1.0</version>
</dependency>

<!-- 현재 -->
<dependency>
    <groupId>io.github.daone-dadp</groupId>
    <artifactId>dadp-common-sync-lib-core</artifactId>
    <version>5.2.0</version>
</dependency>
```

**설정 파일**: 변경 불필요

**코드 변경**: 불필요

**주요 변경사항**:
- Hub 엔드포인트 통신 구조 개선 지원으로 더 안전하고 일관된 통신 보장
- 헤더 기반 인증 지원으로 보안 향상

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

- **dadp-common-logging-lib**: 1.0.0
- **Jackson**: 2.13.5 (JSON 처리)
- **SLF4J**: 1.7.36 (로깅)

---

## 🔗 관련 문서

- [CHANGELOG.md](../CHANGELOG.md)
- [이전 버전 릴리즈 노트](./RELEASE_NOTES_v5.1.0.md)

---

## 📝 참고사항

### 배포 정보

- **배포 일자**: 2026-01-07
- **Deployment ID**: `ce33728e-b403-4f06-871a-716ed00d1715`
- **배포 상태**: ✅ Validated (수동 Publish 필요)
- **Maven Central URL**: https://search.maven.org/artifact/io.github.daone-dadp/dadp-common-sync-lib-core/5.2.0/jar

---

## 👥 기여자

- DADP Development Team

---

**릴리즈 날짜**: 2026-01-07  
**배포 날짜**: 2026-01-07  
**이전 버전**: 5.1.0  
**배포 상태**: ✅ Validated (수동 Publish 필요)

