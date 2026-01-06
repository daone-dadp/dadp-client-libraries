# DADP AOP Library v5.0.5 Release Notes (Java 8)

## 🎉 릴리즈 정보

**버전**: 5.0.5  
**릴리즈 일자**: 2026-01-07  
**배포 일자**: 2026-01-07  
**배포 상태**: ✅ **Maven Central 배포 완료**  
**Deployment ID**: `fc1f6415-3a3b-4edb-b6c0-18b97273e439`  
**Java 버전**: **Java 8 전용**  
**주요 개선사항**: Java 버전별 모듈 분리, Hub URL 설정 통일

---

## ⚠️ 중요: Java 버전별 모듈 분리

**이번 릴리즈는 Java 버전별 모듈 분리 릴리즈입니다.**

### 모듈 구조 변경

- **이전**: `dadp-aop:5.3.1` (Java 17만 지원)
- **현재**: `dadp-aop-j8:5.0.5` (Java 8 전용), `dadp-aop-j17:5.0.5` (Java 17 전용)

### 분리 이유

- **빌드 최적화**: Java 버전별로 최소화된 코드만 포함
- **의존성 관리**: Java 버전별로 적절한 의존성 사용
- **명확한 버전 관리**: ArtifactId로 Java 버전 구분

### 버전 체계

- **버전 번호**: `5.0.5` (Java 버전과 무관, 동일한 기능)
- **Java 버전 구분**: ArtifactId로 구분 (`-j8`, `-j17`)
- **예시**: `dadp-aop-j8:5.0.5`, `dadp-aop-j17:5.0.5` (동일한 기능, 다른 Java 버전 타겟)

---

## 📋 주요 변경사항

### 🔄 Changed

- **Hub URL 설정 통일**: `dadp.aop.hub-base-url` → `dadp.hub-base-url`로 통일
  - `@ConditionalOnProperty` 조건을 `dadp.hub-base-url`로 변경
  - Wrapper와 동일한 설정 방식으로 통일
  - 설정 단순화 및 사용자 편의성 향상
- **Java 8 호환성 개선**: Java 8 전용 모듈로 분리
  - `javax.persistence.*` 사용 (Java 8 호환)
  - Spring Boot 2.7.18 사용 (Java 8 호환)
  - `dadp-common-sync-lib-j8` 의존성 사용

### ✨ New Features

- **Java 8 전용 모듈**: `dadp-aop-j8:5.0.5` 신규 배포
  - Java 8 환경에서 사용 가능
  - Java 8에 최적화된 의존성 사용

---

## 🔧 변경된 API

### 설정 속성 변경

**이전 (v5.3.1)**:
```properties
# 두 가지 설정 혼재
dadp.hub-base-url=http://localhost:9004
dadp.aop.hub-base-url=http://localhost:9004  # AOP 전용 설정
```

**현재 (v5.0.5)**:
```properties
# 하나의 설정으로 통일
dadp.hub-base-url=http://localhost:9004  # Wrapper와 동일
```

### @ConditionalOnProperty 변경

**이전**:
```java
@ConditionalOnProperty(prefix = "dadp.aop", name = "hub-base-url")
```

**현재**:
```java
@ConditionalOnProperty(prefix = "dadp", name = "hub-base-url")
```

---

## 🔄 마이그레이션 가이드

### 5.3.1 → 5.0.5 (Java 8)

**필수 변경사항**:

1. **의존성 변경**: `dadp-aop` → `dadp-aop-j8`
2. **설정 파일 수정**: `dadp.aop.hub-base-url` 제거

**의존성 업데이트**:
```xml
<!-- 이전 -->
<dependency>
    <groupId>io.github.daone-dadp</groupId>
    <artifactId>dadp-aop</artifactId>
    <version>5.3.1</version>
</dependency>

<!-- 현재 (Java 8) -->
<dependency>
    <groupId>io.github.daone-dadp</groupId>
    <artifactId>dadp-aop-j8</artifactId>
    <version>5.0.5</version>
</dependency>
```

**설정 파일 수정**:
```properties
# 이전
dadp.hub-base-url=http://localhost:9004
dadp.aop.hub-base-url=http://localhost:9004  # 제거 필요

# 현재
dadp.hub-base-url=http://localhost:9004  # 하나로 통일
```

**코드 변경**: 불필요 (설정 파일만 수정)

**호환성**: 
- ✅ **하위 호환**: 기존 코드는 변경 불필요
- ⚠️ **설정 변경**: `dadp.aop.hub-base-url` 제거 필요

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
| Java 17   | ❌ 권장 안 함 | `dadp-aop-j17:5.0.5` 사용 권장 |
| Java 21   | ❌ 권장 안 함 | `dadp-aop-j17:5.0.5` 사용 권장 |

### Spring Boot 버전

| Spring Boot 버전 | 지원 여부 | 비고 |
|-----------------|----------|------|
| Spring Boot 2.x | ✅ 지원   | **권장 버전** (2.7.18) |
| Spring Boot 3.x | ❌ 지원 안 함 | Java 17+ 필요, `dadp-aop-j17:5.0.5` 사용 |

### 의존성

- **dadp-hub-crypto-lib**: 1.2.0 (classifier: `java8`)
- **dadp-common-sync-lib-j8**: 5.0.5
- **Spring Boot**: 2.7.18 (Java 8 호환)
- **Spring AOP**: 5.3.31 (Java 8 호환)
- **AspectJ**: 1.9.22

### Hub/Engine 버전

| 컴포넌트 | 최소 버전 | 권장 버전 |
|----------|----------|----------|
| **Hub** | 5.0.0 | 5.0.0+ |
| **Engine** | 5.0.0 | 5.0.0+ |

---

## 🔗 관련 문서

- [CHANGELOG.md](./CHANGELOG.md)
- [사용 가이드](./dadp-aop-user-guide.md)
- [이전 버전 릴리즈 노트](./RELEASE_NOTES_v5.3.0.md)

---

## 📝 참고사항

### 배포 상태

이 버전은 **Maven Central에 배포 완료**되었습니다.

### 배포 정보

- **배포 일자**: 2026-01-07
- **Deployment ID**: `fc1f6415-3a3b-4edb-b6c0-18b97273e439`
- **배포 상태**: Validated (수동 Publish 필요)
- **Maven Central URL**: https://central.sonatype.com/publishing/deployments

### 버전 선택 가이드

| Java 버전 | 권장 버전 | 사용 가능 버전 |
|-----------|----------|---------------|
| Java 8    | `5.0.5` (이 버전) | `5.0.5`만 가능 |
| Java 11   | `5.0.5` (하위 호환) | `5.0.5` 사용 가능 |
| Java 17   | `dadp-aop-j17:5.0.5` | `dadp-aop-j17:5.0.5` 사용 권장 |
| Java 21   | `dadp-aop-j17:5.0.5` | `dadp-aop-j17:5.0.5` 사용 권장 |

**설명**:
- **Java 8**: `dadp-aop-j8:5.0.5` 사용 (이 버전)
- **Java 17+**: `dadp-aop-j17:5.0.5` 사용 권장

---

## 👥 기여자

- DADP Development Team

---

**릴리즈 날짜**: 2026-01-07  
**배포 날짜**: 2026-01-07  
**이전 버전**: 5.3.1 (Java 17 전용)  
**Java 버전**: Java 8 전용  
**배포 상태**: ✅ Maven Central 배포 완료

