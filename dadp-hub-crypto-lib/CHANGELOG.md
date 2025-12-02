# DADP Hub Crypto Library - Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - 2025-11-26

### Added
- **Engine 직접 연결 지원**: `apiBasePath` 설정 추가
  - Hub 연결: `/hub/api/v1` (기본값)
  - Engine 직접 연결: `/api`
  - 환경변수: `HUB_CRYPTO_API_BASE_PATH`
  - 설정: `hub.crypto.api-base-path`

- **Engine 응답 형식 지원**: Engine이 반환하는 응답 형식 처리
  - Hub 응답: `{ "data": { "encryptedData": "..." } }`
  - Engine 응답: `{ "data": "암호화된문자열" }`
  - 두 형식 모두 자동 감지 및 처리

### Fixed
- **Spring Boot 2.x/3.x 호환성 개선**
  - `HttpStatusCodeException.getStatusCode()` 리플렉션 처리
  - `ResponseEntity.getStatusCode()` 호환성 유지

### Configuration Examples

#### Hub 연결 (기존 방식)
```yaml
hub:
  crypto:
    base-url: http://dadp-hub:9004
    api-base-path: /hub/api/v1  # 기본값, 생략 가능
```

#### Engine 직접 연결 (새로운 방식)
```yaml
hub:
  crypto:
    base-url: http://dadp-engine:9003
    api-base-path: /api
```

#### Docker 환경변수
```yaml
environment:
  HUB_CRYPTO_BASE_URL: http://dadp-engine:9003
  HUB_CRYPTO_API_BASE_PATH: /api
```

---

## [1.0.1] - 2025-11-01

### Added
- 초기 릴리즈
- Hub 암복호화 API 연동
- 마스킹 정책 지원 (`maskPolicyName`, `maskPolicyUid`)
- Spring Boot 자동 설정 (`@ConfigurationProperties`)
- RestTemplate 기반 HTTP 통신
- 에러 핸들링 및 로깅

### Features
- `encrypt(data, policy)`: 데이터 암호화
- `decrypt(encryptedData)`: 데이터 복호화
- `decrypt(encryptedData, maskPolicyName, maskPolicyUid)`: 마스킹 적용 복호화
- `isEncryptedData(data)`: 암호화 데이터 여부 확인

---

## Migration Guide

### 1.0.1 → 1.1.0

#### Engine 직접 연결을 사용하려면

1. 환경변수 추가:
```yaml
HUB_CRYPTO_BASE_URL: http://dadp-engine:9003
HUB_CRYPTO_API_BASE_PATH: /api
```

2. 또는 application.yml 설정:
```yaml
hub:
  crypto:
    base-url: http://dadp-engine:9003
    api-base-path: /api
```

#### 기존 Hub 연결 유지

변경 사항 없음. 기본값이 Hub 연결 방식입니다.

