# DADP AOP Library v5.0.7 Release Notes (Java 8)

## 🎉 릴리즈 정보

**버전**: 5.0.7  
**릴리즈 일자**: 2026-01-06  
**배포 일자**: 2026-01-06  
**배포 상태**: ✅ **Maven Central 배포 완료**  
**Deployment ID**: `797c2b6a-896a-45d4-b679-ba1734a42695`  
**Java 버전**: **Java 8 전용**  
**주요 개선사항**: Spring Boot 3.x AutoConfiguration 지원

---

## 📋 주요 변경사항

### ✨ New Features

- **Spring Boot 3.x AutoConfiguration 지원**: `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 파일 추가
  - Spring Boot 3.x에서 `DadpAopAutoConfiguration` 자동 로드 가능
  - 기존 `spring.factories` 방식과 함께 지원 (하위 호환성 유지)
  - Spring Boot 3.x 애플리케이션에서 별도 설정 없이 AOP 자동 활성화

### 🔧 Technical Details

**AutoConfiguration 파일 구조**:
```
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

**파일 내용**:
```
com.dadp.aop.config.DadpAopAutoConfiguration
```

**호환성**:
- ✅ Spring Boot 2.x: 기존 `spring.factories` 방식 사용
- ✅ Spring Boot 3.x: `AutoConfiguration.imports` 파일 사용 (새로운 방식)

---

## 🔄 마이그레이션 가이드

### 5.0.5 → 5.0.7 (Java 8)

**변경사항**: 없음 (자동 호환)

**의존성 업데이트**:
```xml
<!-- 이전 -->
<dependency>
    <groupId>io.github.daone-dadp</groupId>
    <artifactId>dadp-aop-j8</artifactId>
    <version>5.0.5</version>
</dependency>

<!-- 현재 (Java 8) -->
<dependency>
    <groupId>io.github.daone-dadp</groupId>
    <artifactId>dadp-aop-j8</artifactId>
    <version>5.0.7</version>
</dependency>
```

**설정 파일**: 변경 불필요

**코드 변경**: 불필요

**호환성**: 
- ✅ **완전 호환**: 기존 코드 및 설정 변경 불필요
- ✅ **자동 개선**: Spring Boot 3.x에서 AutoConfiguration 자동 로드

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
| Java 17   | ❌ 권장 안 함 | `dadp-aop-j17:5.0.7` 사용 권장 |
| Java 21   | ❌ 권장 안 함 | `dadp-aop-j17:5.0.7` 사용 권장 |

### Spring Boot 버전

| Spring Boot 버전 | 지원 여부 | 비고 |
|-----------------|----------|------|
| Spring Boot 2.x | ✅ 지원   | **권장 버전** (2.7.18), 기존 `spring.factories` 방식 사용 |
| Spring Boot 3.x | ✅ 지원   | `AutoConfiguration.imports` 파일 사용 (새로운 방식) |

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
- [이전 버전 릴리즈 노트](./RELEASE_NOTES_v5.0.5.md)

---

## 📝 참고사항

### 배포 상태

이 버전은 **Maven Central에 배포 완료**되었습니다.

### 배포 정보

- **배포 일자**: 2026-01-06
- **Deployment ID**: `797c2b6a-896a-45d4-b679-ba1734a42695`
- **배포 상태**: Validated (수동 Publish 필요)
- **Maven Central URL**: https://central.sonatype.com/publishing/deployments

### 버전 선택 가이드

| Java 버전 | 권장 버전 | 사용 가능 버전 |
|-----------|----------|---------------|
| Java 8    | `5.0.7` (이 버전) | `5.0.7`만 가능 |
| Java 11   | `5.0.7` (하위 호환) | `5.0.7` 사용 가능 |
| Java 17   | `dadp-aop-j17:5.0.7` | `dadp-aop-j17:5.0.7` 사용 권장 |
| Java 21   | `dadp-aop-j17:5.0.7` | `dadp-aop-j17:5.0.7` 사용 권장 |

**설명**:
- **Java 8**: `dadp-aop-j8:5.0.7` 사용 (이 버전)
- **Java 17+**: `dadp-aop-j17:5.0.7` 사용 권장

---

## 👥 기여자

- DADP Development Team

---

**릴리즈 날짜**: 2026-01-06  
**배포 날짜**: 2026-01-06  
**이전 버전**: 5.0.5  
**Java 버전**: Java 8 전용  
**배포 상태**: ✅ Maven Central 배포 완료

