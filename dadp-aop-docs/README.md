# DADP AOP 공통 문서

> **이 폴더는 `dadp-aop-j8`과 `dadp-aop-j17`의 공통 문서를 관리하는 폴더입니다.**

## 📋 목적

Java 버전별로 분리된 `dadp-aop-j8`과 `dadp-aop-j17` 모듈은 **동일한 기능**을 제공하므로, 공통 문서를 이 폴더에서 관리합니다.

## 📁 문서 구조

- `CHANGELOG.md`: 공통 변경 이력
- `dadp-aop-user-guide.md`: 사용자 가이드 (Java 버전별 차이점 포함)
- `RELEASE_NOTES_*.md`: 릴리즈 노트 (Java 버전별 차이점 포함)

## 🔄 릴리즈 전략

### 통합 관리 원칙

- **동일 기능/릴리즈는 Java 버전과 무관하게 같은 버전 번호 사용**
- **Java 버전은 ArtifactId로 구분** (`-j8`, `-j17`)
- 예시: `dadp-aop-j8:5.2.1`, `dadp-aop-j17:5.2.1` (동일한 기능, 다른 Java 버전 타겟)

### 버전 체계

- **A.C 버전 체계**: `5.2.1` (A=5, C=1)
  - **A (Compatibility Line)**: Hub/Engine과의 호환성 라인
  - **C (Release Increment)**: 실제 배포 단위

## 📝 문서 관리 규칙

1. **공통 변경사항**: 이 폴더의 문서에 작성
2. **Java 버전별 차이점**: 문서 내에 명시
3. **각 모듈 폴더**: 필요시 이 폴더의 문서를 참조하는 README만 유지

## 🔗 관련 모듈

- [`dadp-aop-j8`](../dadp-aop-j8/): Java 8 전용 모듈
- [`dadp-aop-j17`](../dadp-aop-j17/): Java 17+ 전용 모듈

