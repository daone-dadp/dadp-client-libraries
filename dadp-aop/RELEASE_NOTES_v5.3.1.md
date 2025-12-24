# DADP AOP Library v5.3.1 Release Notes

## 🎉 릴리즈 정보

**버전**: 5.3.1  
**릴리즈 일자**: 2025-12-24  
**배포 상태**: ⚠️ **개발 완료, Maven Central 미배포** (배포 전)  
**Java 버전**: **Java 17 이상** (권장)  
**주요 개선사항**: Engine URL 환경변수 직접 관리 지원

---

## 📋 주요 변경사항

### 🔄 Changed

- **Engine URL 관리 방식 변경**: Hub에서 자동 조회 방식 제거, 환경변수 직접 지정 방식으로 변경
  - **이전**: `DADP_CRYPTO_BASE_URL`이 없으면 `DADP_HUB_BASE_URL`을 통해 Hub에서 엔진 URL 자동 조회
  - **현재**: `DADP_CRYPTO_BASE_URL` 환경변수로 직접 지정 (필수)
  - **이유**: Wrapper와 동일하게 내부망/외부망/도커 환경에서 유연하게 관리 가능하도록 개선

### ✨ New Features

- **환경변수 직접 관리 지원**: `DADP_CRYPTO_BASE_URL` 환경변수로 Engine URL 직접 지정
  - 내부망, 외부망, 도커 네트워크 등 다양한 환경에서 IP/호스트명 직접 지정 가능
  - Hub 의존성 없이 독립적으로 동작 가능

### 🗑️ Removed

- **Hub 엔드포인트 자동 조회 기능 제거**: `HubEndpointSyncService`를 통한 Hub에서 엔진 URL 조회 기능 제거
  - `DadpAopProperties.getEngineBaseUrl()`: Hub 조회 로직 제거
  - `HubCryptoConfig.hubCryptoService()`: Hub 조회 로직 제거
  - `DADP_AOP_INSTANCE_ID` 환경변수: 더 이상 사용되지 않음 (알림 기능에는 여전히 사용 가능)

---

## 🔧 변경된 API

### 환경변수 우선순위 변경

**이전 (v5.3.0)**:
1. `DADP_CRYPTO_BASE_URL` 환경변수 확인
2. 없으면 `DADP_HUB_BASE_URL` + `DADP_AOP_INSTANCE_ID`로 Hub에서 조회
3. 조회 실패 시 설정 파일 값 사용
4. 기본값: `http://localhost:9003`

**현재 (v5.3.1)**:
1. `DADP_CRYPTO_BASE_URL` 환경변수 확인 (필수)
2. 없으면 설정 파일 값 사용
3. 기본값: `http://localhost:9003`

### 제거된 환경변수 의존성

- `DADP_HUB_BASE_URL`: Engine URL 조회용으로는 더 이상 사용되지 않음 (알림 기능에는 여전히 사용)
- `DADP_AOP_INSTANCE_ID`: Engine URL 조회용으로는 더 이상 사용되지 않음 (알림 기능에는 여전히 사용)

---

## 📊 성능 개선

- **초기화 시간 단축**: Hub 엔드포인트 조회 API 호출 제거로 애플리케이션 시작 시간 단축
- **의존성 감소**: Hub 서비스와의 네트워크 의존성 제거로 더 안정적인 동작

---

## 🔄 마이그레이션 가이드

### 5.3.0 → 5.3.1

**필수 변경사항**:

1. **환경변수 추가**: `DADP_CRYPTO_BASE_URL` 환경변수를 반드시 설정해야 합니다.

**이전 설정 (v5.3.0)**:
```yaml
# docker-compose.yml 또는 환경변수
DADP_HUB_BASE_URL: http://dadp-hub:9004
DADP_AOP_INSTANCE_ID: test-app-aop-1
# DADP_CRYPTO_BASE_URL은 선택사항 (Hub에서 자동 조회)
```

**현재 설정 (v5.3.1)**:
```yaml
# docker-compose.yml 또는 환경변수
DADP_HUB_BASE_URL: http://dadp-hub:9004  # 알림 기능용 (선택)
DADP_CRYPTO_BASE_URL: http://dadp-engine:9003  # 필수: Engine URL 직접 지정
DADP_AOP_INSTANCE_ID: test-app-aop-1  # 알림 기능용 (선택)
```

**설정 예시**:

```yaml
# 로컬 개발 환경 (Docker 네트워크)
DADP_CRYPTO_BASE_URL: http://dadp-engine:9003

# 내부망 환경 (IP 직접 지정)
DADP_CRYPTO_BASE_URL: http://192.168.1.100:9003

# 외부망 환경 (외부 IP 또는 도메인)
DADP_CRYPTO_BASE_URL: http://engine.example.com:9003

# HTTPS 환경
DADP_CRYPTO_BASE_URL: https://engine.example.com:9003
```

**코드 변경**: 불필요 (환경변수만 추가)

**의존성 업데이트**:
```xml
<dependency>
    <groupId>io.github.daone-dadp</groupId>
    <artifactId>dadp-aop</artifactId>
    <version>5.3.1</version>
</dependency>
```

**호환성**: 
- ✅ **하위 호환**: 기존 코드는 변경 불필요
- ⚠️ **환경변수 필수**: `DADP_CRYPTO_BASE_URL` 환경변수 설정 필수

---

## 🐛 알려진 이슈

(없음)

---

## 📚 호환성 매트릭스

### Java 버전

| Java 버전 | 지원 여부 | 비고 |
|-----------|----------|------|
| Java 8    | ❌ 지원 안 함 | Java 8 바이트코드는 호환되지만, Spring Boot 3.x 의존성으로 인해 Java 17 이상 권장 |
| Java 11   | ✅ 지원   | **하위 호환성** (Java 17 바이트코드는 Java 11에서 실행 가능) |
| Java 17   | ✅ 지원   | **권장 버전** (컴파일 타겟) |
| Java 21   | ✅ 지원   | **하위 호환성** (Java 17 바이트코드는 Java 21에서 실행 가능) |

### Spring Boot 버전

| Spring Boot 버전 | 지원 여부 | 비고 |
|-----------------|----------|------|
| Spring Boot 2.x | ❌ 지원 안 함 | Spring Boot 3.x 의존성 사용 |
| Spring Boot 3.x | ✅ 지원   | **권장 버전** (3.2.12) |

### 의존성

- **dadp-hub-crypto-lib**: 1.2.0 (자동 포함)
- **Spring Boot**: 3.2.12
- **Spring AOP**: Spring Boot에 포함
- **AspectJ**: Spring Boot에 포함

### Hub/Engine 버전

| 컴포넌트 | 최소 버전 | 권장 버전 |
|----------|----------|----------|
| **Hub** | 3.17.1 | 3.17.1+ (알림 기능용, 선택) |
| **Engine** | 5 | 5+ (Root POM 버전과 동기화, 필수) |

---

## 🔗 관련 문서

- [CHANGELOG.md](./CHANGELOG.md)
- [사용 가이드](./dadp-aop-user-guide.md)
- [이전 버전 릴리즈 노트](./RELEASE_NOTES_v5.3.0.md)

---

## 📝 참고사항

### 개발 상태

이 버전은 **개발 완료**되었으며, 아직 Maven Central에 배포되지 않았습니다.

### 배포 예정

- 배포 일정: (미정)
- 배포 전 체크리스트:
  - [x] 빌드 테스트 완료
  - [x] 로컬 테스트 완료
  - [x] Java 17 환경 테스트 완료
  - [x] 문서 업데이트 완료
  - [ ] 통합 테스트 완료

### 주요 변경 이유

이번 변경은 **Wrapper와의 일관성** 및 **유연한 환경 관리**를 위해 수행되었습니다:

1. **Wrapper와의 일관성**: Wrapper는 이미 `DADP_HUB_BASE_URL`로 Hub URL을 직접 지정하므로, AOP도 동일하게 Engine URL을 직접 지정하도록 변경
2. **환경 유연성**: 내부망, 외부망, 도커 네트워크 등 다양한 환경에서 IP/호스트명을 직접 지정하여 유연하게 관리 가능
3. **의존성 감소**: Hub 서비스와의 네트워크 의존성 제거로 더 안정적인 동작

### 환경변수 설정 가이드

**필수 환경변수**:
- `DADP_CRYPTO_BASE_URL`: Engine URL (예: `http://dadp-engine:9003`)

**선택 환경변수**:
- `DADP_HUB_BASE_URL`: Hub URL (알림 기능 사용 시)
- `DADP_AOP_INSTANCE_ID`: AOP 인스턴스 ID (알림 기능 사용 시)

---

## 👥 기여자

- DADP Development Team

---

**릴리즈 날짜**: 2025-12-24  
**이전 버전**: 5.3.0  
**Java 버전**: Java 17 이상 (권장)  
**배포 상태**: ⚠️ 배포 전

