# DADP JDBC Wrapper v5.0.5 Release Notes (Java 8)

## 🎉 릴리즈 정보

**버전**: 5.0.5  
**릴리즈 일자**: 2026-01-07  
**배포 일자**: 2026-01-07  
**배포 상태**: ✅ **Maven Central 배포 완료**  
**Deployment ID**: `d6efbc56-7b8e-40cd-9f50-255e785b741f`  
**Java 버전**: **Java 8 전용**  
**주요 개선사항**: Java 버전별 모듈 분리

---

## ⚠️ 중요: Java 버전별 모듈 분리

**이번 릴리즈는 Java 버전별 모듈 분리 릴리즈입니다.**

### 모듈 구조 변경

- **이전**: `dadp-jdbc-wrapper:5.1.0` (Java 8로 빌드되었지만 Java 버전 구분이 명확하지 않음)
- **현재**: `dadp-jdbc-wrapper-j8:5.0.5` (Java 8 전용), `dadp-jdbc-wrapper-j17:5.0.5` (Java 17 전용)

### 분리 이유

- **빌드 최적화**: Java 버전별로 최소화된 코드만 포함
- **의존성 관리**: Java 버전별로 적절한 의존성 사용
- **명확한 버전 관리**: ArtifactId로 Java 버전 구분

### 버전 체계

- **버전 번호**: `5.0.5` (Java 버전과 무관, 동일한 기능)
- **Java 버전 구분**: ArtifactId로 구분 (`-j8`, `-j17`)
- **예시**: `dadp-jdbc-wrapper-j8:5.0.5`, `dadp-jdbc-wrapper-j17:5.0.5` (동일한 기능, 다른 Java 버전 타겟)

---

## 📋 주요 변경사항

### 🔄 Changed

- **Java 8 호환성 개선**: Java 8 전용 모듈로 분리
  - `javax.persistence.*` 사용 (Java 8 호환)
  - Spring Boot 2.7.18 사용 (Java 8 호환)
  - `dadp-common-sync-lib-j8` 의존성 사용
  - `dadp-hub-crypto-lib:java8` classifier 사용

### ✨ New Features

- **Java 8 전용 모듈**: `dadp-jdbc-wrapper-j8:5.0.5` 신규 배포
  - Java 8 환경에서 사용 가능
  - Java 8에 최적화된 의존성 사용

---

## 🔧 변경된 API

(변경 없음)

---

## 🔄 마이그레이션 가이드

### 5.1.0 → 5.0.5 (Java 8)

**필수 변경사항**:

1. **의존성 변경**: `dadp-jdbc-wrapper` → `dadp-jdbc-wrapper-j8`

**의존성 업데이트**:
```xml
<!-- 이전 -->
<dependency>
    <groupId>io.github.daone-dadp</groupId>
    <artifactId>dadp-jdbc-wrapper</artifactId>
    <version>5.1.0</version>
    <classifier>all</classifier>
</dependency>

<!-- 현재 (Java 8) -->
<dependency>
    <groupId>io.github.daone-dadp</groupId>
    <artifactId>dadp-jdbc-wrapper-j8</artifactId>
    <version>5.0.5</version>
    <classifier>all</classifier>
</dependency>
```

**코드 변경**: 불필요 (의존성만 변경)

**호환성**: 
- ✅ **하위 호환**: 기존 코드는 변경 불필요
- ⚠️ **의존성 변경**: ArtifactId 변경 필요

---

## 🐛 알려진 이슈

(없음)

---

## 📚 호환성 매트릭스

### Java 버전

| Java 버전 | 지원 여부 | 비고 |
|-----------|----------|------|
| Java 8    | ✅ 지원   | **이 버전의 타겟** (컴파일 타겟) |
| Java 11   | ✅ 지원   | **하위 호환성** (Java 8 바이트코드는 Java 11에서 실행 가능) |
| Java 17   | ❌ 권장 안 함 | `dadp-jdbc-wrapper-j17:5.0.5` 사용 권장 |
| Java 21   | ❌ 권장 안 함 | `dadp-jdbc-wrapper-j17:5.0.5` 사용 권장 |

### ORM/Framework 호환성

| 프레임워크 | 암호화 | 복호화 | 비고 |
|-----------|--------|--------|------|
| **JdbcTemplate** | ✅ | ✅ | 직접 컬럼명 사용 |
| **Hibernate/JPA** | ✅ | ✅ | alias 자동 변환 |
| **MyBatis** | ✅ | ✅ | AS alias 파싱 지원 |
| **jOOQ** | ✅ | ✅ | AS alias 파싱 지원 |
| **QueryDSL** | ✅ | ✅ | AS alias 파싱 지원 |

### Hub/Engine 버전

| 컴포넌트 | 최소 버전 | 권장 버전 |
|----------|----------|----------|
| **Hub** | 5.0.0 | 5.0.0+ |
| **Engine** | 5.0.0 | 5.0.0+ |

### 의존성

- **dadp-hub-crypto-lib**: `1.2.0` (classifier: `java8`)
- **dadp-common-sync-lib-j8**: `5.0.5`
- **Spring Boot**: `2.7.18` (Java 8 지원)
- **Jackson**: `2.13.5` (Java 8 호환)

---

## 🔗 관련 문서

- [CHANGELOG.md](./CHANGELOG.md)
- [README.md](./README.md)
- [이전 버전 릴리즈 노트](./RELEASE_NOTES_v5.1.0.md)

---

## 📝 참고사항

### 배포 상태

이 버전은 **Maven Central에 배포 완료**되었습니다.

### 배포 정보

- **배포 일자**: 2026-01-07
- **Deployment ID**: `d6efbc56-7b8e-40cd-9f50-255e785b741f`
- **배포 상태**: Validated (수동 Publish 필요)
- **Maven Central URL**: https://central.sonatype.com/publishing/deployments

### 버전 선택 가이드

| Java 버전 | 권장 버전 | 사용 가능 버전 |
|-----------|----------|---------------|
| Java 8    | `5.0.5` (이 버전) | `5.0.5`만 가능 |
| Java 11   | `5.0.5` (하위 호환) | `5.0.5` 사용 가능 |
| Java 17   | `dadp-jdbc-wrapper-j17:5.0.5` | `dadp-jdbc-wrapper-j17:5.0.5` 사용 권장 |
| Java 21   | `dadp-jdbc-wrapper-j17:5.0.5` | `dadp-jdbc-wrapper-j17:5.0.5` 사용 권장 |

**설명**:
- **Java 8**: `dadp-jdbc-wrapper-j8:5.0.5` 사용 (이 버전)
- **Java 17+**: `dadp-jdbc-wrapper-j17:5.0.5` 사용 권장

---

## 🙏 기여자

- DADP Development Team

---

**릴리즈 날짜**: 2026-01-07  
**배포 날짜**: 2026-01-07  
**이전 버전**: 5.1.0  
**Java 버전**: Java 8 전용  
**배포 상태**: ✅ Maven Central 배포 완료

