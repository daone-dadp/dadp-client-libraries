# 릴리즈 관리 가이드

## 📋 일반적인 릴리즈 노트 관리 방법

### 1. 단일 모듈 프로젝트

**일반적인 관행:**
- 프로젝트 루트에 `CHANGELOG.md` (모든 버전 통합 관리)
- 또는 `RELEASE_NOTES.md` (버전별로 섹션 구분)

**예시:**
```
project-root/
├── CHANGELOG.md          # 모든 버전의 변경사항
├── README.md
└── pom.xml
```

### 2. 멀티 모듈 프로젝트

**옵션 A: 각 모듈별로 관리 (권장)**
```
project-root/
├── module-a/
│   ├── CHANGELOG.md      # module-a의 변경사항
│   └── README.md
└── module-b/
    ├── CHANGELOG.md      # module-b의 변경사항
    └── README.md
```

**옵션 B: 루트에 통합 관리**
```
project-root/
├── CHANGELOG.md          # 모든 모듈의 변경사항 (모듈별 섹션)
└── modules/
    ├── module-a/
    └── module-b/
```

### 3. GitHub Releases

**일반적인 관행:**
- GitHub 웹 UI에서 릴리즈 노트 작성 (별도 파일 없이)
- 또는 `CHANGELOG.md`를 자동으로 읽어옴
- 태그와 함께 릴리즈 생성

**예시:**
```
GitHub Releases 페이지:
- v3.0.4 (2025-11-12)
  - Java 8 호환성 개선
  - ...
- v3.0.3 (2025-10-15)
  - ...
```

---

## 🎯 dadp-jdbc-wrapper 릴리즈 관리 전략

### 현재 구조

```
dadp-jdbc-wrapper/
├── CHANGELOG.md              # 모든 버전의 변경사항 (Keep a Changelog 형식)
├── RELEASE_NOTES_v3.0.4.md   # v3.0.4 상세 릴리즈 노트
├── README.md                  # 프로젝트 개요 및 사용법
└── pom.xml
```

### 파일 역할

1. **CHANGELOG.md**
   - 모든 버전의 변경사항을 한 파일에 통합 관리
   - [Keep a Changelog](https://keepachangelog.com/) 형식 준수
   - 빠른 변경사항 확인용

2. **RELEASE_NOTES_v{version}.md**
   - 각 주요 릴리즈의 상세한 릴리즈 노트
   - 기술적 세부사항, 마이그레이션 가이드 등 포함
   - 상세 정보 확인용

3. **README.md**
   - 프로젝트 개요 및 사용법
   - CHANGELOG 및 Release Notes 링크 포함

---

## 📝 릴리즈 노트 작성 가이드

### CHANGELOG.md 형식

```markdown
## [버전] - YYYY-MM-DD

### Added
- 새로운 기능

### Changed
- 변경된 기능

### Deprecated
- 곧 제거될 기능

### Removed
- 제거된 기능

### Fixed
- 버그 수정

### Security
- 보안 관련 변경
```

### RELEASE_NOTES_v{version}.md 형식

```markdown
# DADP JDBC Wrapper v{version} Release Notes

## 🎉 릴리즈 정보
- 버전, 릴리즈 일자, 주요 개선사항

## 📋 주요 변경사항
- 상세한 변경 내용

## 🔧 기술적 세부사항
- 기술적 구현 내용

## 🔄 마이그레이션 가이드
- 업그레이드 가이드

## 📚 호환성 매트릭스
- 지원 환경 정보
```

---

## 🚀 릴리즈 프로세스

1. **코드 변경 및 테스트**
2. **버전 업데이트** (`pom.xml`)
3. **CHANGELOG.md 업데이트**
4. **RELEASE_NOTES_v{version}.md 작성** (주요 릴리즈의 경우)
5. **Git 태그 생성**
   ```bash
   git tag v3.0.4
   git push origin v3.0.4
   ```
6. **GitHub Release 생성**
   - GitHub 웹 UI에서 태그 기반 릴리즈 생성
   - RELEASE_NOTES_v{version}.md 내용 복사/붙여넣기

---

## 📚 참고 자료

- [Keep a Changelog](https://keepachangelog.com/)
- [Semantic Versioning](https://semver.org/)
- [GitHub Releases Guide](https://docs.github.com/en/repositories/releasing-projects-on-github)

