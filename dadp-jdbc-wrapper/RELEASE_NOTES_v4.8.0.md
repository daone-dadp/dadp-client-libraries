# DADP JDBC Wrapper v4.8.0 Release Notes

## 🎉 릴리즈 정보

**버전**: 4.8.0  
**릴리즈 일자**: 2025-12-12  
**배포 상태**: ✅ **Maven Central 배포 완료** (2025-12-09)  
**Java 버전**: **Java 8 전용** (별도 버전)  
**주요 개선사항**: Java 8 환경 지원, Hub 스키마 동기화 시 암복호화 URL 자동 전달, 단일 cryptoUrl 사용, datasourceId와 schemaName 기반 정책 키 형식

### 📦 배포 정보

- **Maven Central**: ✅ 배포 완료
  - 배포 일자: 2025-12-09
  - Deployment ID: `7cdc7c95-5d44-4a06-981e-1a5307b18e8f`
  - 검색: https://search.maven.org/search?q=io.github.daone-dadp:dadp-jdbc-wrapper:4.8.0
  - 의존성 추가:
    ```xml
    <dependency>
        <groupId>io.github.daone-dadp</groupId>
        <artifactId>dadp-jdbc-wrapper</artifactId>
        <version>4.8.0</version>
    </dependency>
    ```

---

## ⚠️ 중요: 버전 번호 체계

**버전 번호는 Java 버전을 나타냅니다:**

- **`4.17.0`** = Java 17 이상용 (기본 버전)
- **`4.8.0`** = Java 8 전용 (이 버전)

Java 8 환경에서는 반드시 `4.8.0` 버전을 사용해야 합니다.

---

## 📋 주요 변경사항

### ✅ Java 8 전용 버전

이 버전은 Java 8 환경에서 사용하기 위해 별도로 빌드된 버전입니다.

#### 주요 특징

- **Java 8 타겟**: `maven.compiler.source/target` 1.8
- **Java 8 호환 코드**: Java 8에서 사용 불가능한 문법 제거
  - `.toList()` → `.collect(Collectors.toList())` 변경
  - `Java11HttpClientAdapter` 제외 (Java 8에서는 `HttpURLConnection` 사용)
- **의존성**: `dadp-hub-crypto-lib:java8` 사용

#### 코드 변경사항

```java
// Java 16+ 문법 (4.17.0)
List<EngineEndpoint> activeEngines = engines.stream()
    .filter(e -> "ACTIVE".equals(e.getStatus()))
    .toList();

// Java 8 호환 코드 (4.8.0)
List<EngineEndpoint> activeEngines = engines.stream()
    .filter(e -> "ACTIVE".equals(e.getStatus()))
    .collect(Collectors.toList());
```

### ✅ Hub 스키마 동기화 시 암복호화 URL 자동 전달

Wrapper가 Hub에 스키마를 동기화할 때, Hub가 자동으로 암복호화 URL(Engine 또는 Gateway)을 반환하도록 개선했습니다.

#### 주요 기능

- **스키마 동기화 응답에 EndpointInfo 포함**: Hub가 `routingMode`, `cryptoUrl`, `apiBasePath` 정보를 자동으로 제공
- **단일 cryptoUrl 사용**: GATEWAY 모드와 DIRECT 모드 모두 단일 `cryptoUrl`로 통합
- **자동 엔드포인트 저장**: Wrapper가 받은 엔드포인트 정보를 `EndpointStorage`에 자동 저장
- **Hub LB 로직 제거**: Hub에서 로드 밸런싱 로직을 제거하고 단일 URL만 반환

#### 동작 방식

```
Wrapper가 Hub에 스키마 동기화 요청
    ↓
Hub가 스키마 저장 및 EndpointInfo 생성
    ↓
Hub가 routingMode에 따라 cryptoUrl 결정:
  - GATEWAY 모드: Gateway URL
  - DIRECT 모드: 첫 번째 활성 Engine URL
    ↓
Hub가 EndpointInfo를 응답으로 반환
    ↓
Wrapper가 EndpointInfo를 EndpointStorage에 저장
    ↓
Wrapper가 저장된 cryptoUrl로 암복호화 요청
```

### ✅ 단일 암복호화 URL 사용

기존의 `gatewayUrl`과 `engines` 리스트를 단일 `cryptoUrl`로 통합하여 사용을 단순화했습니다.

- **EndpointInfo DTO 단순화**: `cryptoUrl` 단일 필드 사용
- **DirectCryptoAdapter 개선**: 단일 URL로 `HubCryptoService` 초기화
- **EndpointStorage 개선**: 단일 `cryptoUrl` 저장 및 로드

### ✅ Datasource 및 Schema 추상화 (datasourceId : schemaName.tableName.columnName 형식)

엔터프라이즈 환경에서 하나의 애플리케이션이 여러 데이터소스와 스키마를 사용하는 경우를 지원하기 위해 통일된 정책 키 형식을 도입했습니다.

#### 주요 기능

- **통일된 정책 키 형식**: `datasourceId : schemaName.tableName.columnName`
  - Hub가 생성한 논리 데이터소스 ID(`datasourceId`) 기반
  - DB 벤더별 차이를 DADP 추상 레이어로 흡수
  - 하나의 Proxy 인스턴스에서 여러 데이터소스 지원
- **Datasource 자동 등록**: Proxy가 Hub에 물리 DB 정보를 등록하고 `datasourceId`를 받음
- **SQL 파싱 개선**: `INSERT INTO schema.table`, `UPDATE schema.table`, `SELECT ... FROM schema.table` 형식 지원
- **정책 스냅샷 API**: Hub에서 정책 매핑 전체를 버전과 함께 제공 (`GET /api/v1/proxy/policies`)
- **정책 버전 관리**: Proxy Instance 단위 전역 정책 버전으로 효율적인 동기화
- **하위 호환성 유지**: `datasourceId`가 없는 경우 `schemaName.tableName.columnName` 또는 `tableName.columnName` 형식으로 fallback

---

## 🔧 변경된 API

### HubCryptoService

```java
// 이전 (3.0.5)
HubCryptoService.createInstance(String hubUrl, int timeout, boolean failOpen)

// 현재 (4.8.0)
HubCryptoService.createInstance(String cryptoUrl, String apiBasePath, int timeout, boolean enableLogging)
```

### DirectCryptoAdapter

```java
// 이전 (3.0.5)
DirectCryptoAdapter(boolean failOpen, HubNotificationService notificationService)

// 현재 (4.8.0)
DirectCryptoAdapter(boolean failOpen, HubNotificationService notificationService)
// setEndpointData(EndpointStorage.EndpointData)로 cryptoUrl 설정
```

---

## 📊 성능 개선

### 스키마 동기화 최적화

- **엔드포인트 정보 자동 전달**: 스키마 동기화와 함께 암복호화 URL 자동 수신
- **단일 URL 사용**: 복잡한 로드 밸런싱 로직 제거로 성능 향상
- **엔드포인트 캐싱**: `EndpointStorage`에 저장하여 Hub 연결 불가 시에도 사용 가능

---

## 🔄 마이그레이션 가이드

### 3.0.5 → 4.8.0 (Java 8 환경)

#### 1. 의존성 업데이트

```xml
<dependency>
    <groupId>io.github.daone-dadp</groupId>
    <artifactId>dadp-jdbc-wrapper</artifactId>
    <version>4.8.0</version>
    <classifier>all</classifier>
</dependency>
```

#### 2. JDBC URL 설정

기존 설정 그대로 사용 가능합니다. Hub가 자동으로 암복호화 URL을 전달합니다:

```properties
spring.datasource.url=jdbc:dadp:mysql://localhost:3306/db?hubUrl=http://localhost:9004&instanceId=my-app-1
```

#### 3. Java 버전

**이 버전은 Java 8로 컴파일되었지만, Java 11/17/21에서도 실행 가능합니다.**

- **Java 8**: ✅ 지원 (컴파일 타겟)
- **Java 11**: ✅ 지원 (하위 호환성)
- **Java 17**: ✅ 지원 (하위 호환성)
- **Java 21**: ✅ 지원 (하위 호환성)

**권장 사항**:
- **Java 8 환경**: `4.8.0` 사용 (필수)
- **Java 11/17 환경**: `4.17.0` 사용 권장 (최신 기능 및 성능), 또는 `4.8.0` 사용 가능 (하위 호환성)

---

## 🐛 알려진 이슈

현재 버전은 빌드가 완료되었으며, 모든 컴파일 에러가 수정되었습니다.

### URL 선택 전략

현재 구현에서는 Hub가 `engineUrl` (Docker 내부 또는 Private IP)을 우선적으로 사용하고, 없을 경우 `enginePublicUrl` (Public IP)을 사용합니다. Wrapper는 자동으로 자신의 위치를 판단하지 않으므로, Hub에서 적절한 URL을 제공해야 합니다.

자세한 내용은 [CRYPTO_URL_SELECTION_STRATEGY.md](../../docs/design/CRYPTO_URL_SELECTION_STRATEGY.md)를 참조하세요.

---

## 📚 호환성

### Java 버전

| Java 버전 | 지원 여부 | 비고 |
|-----------|----------|------|
| Java 8    | ✅ 지원   | **이 버전의 타겟** (컴파일 타겟) |
| Java 11   | ✅ 지원   | **하위 호환성** (Java 8 바이트코드는 Java 11에서 실행 가능) |
| Java 17   | ✅ 지원   | **하위 호환성** (Java 8 바이트코드는 Java 17에서 실행 가능) |
| Java 21   | ✅ 지원   | **하위 호환성** (Java 8 바이트코드는 Java 21에서 실행 가능) |

**중요**: 
- **4.8.0**은 Java 8로 컴파일되었지만, Java 11/17/21에서도 실행 가능합니다 (하위 호환성)
- **4.17.0**은 Java 17로 컴파일되어 Java 8에서는 실행 불가능합니다
- **권장**: Java 11/17 환경에서는 `4.17.0` 버전 사용 권장 (최신 기능 및 성능)

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
| **Hub** | 3.8.0 | 3.8.0+ |
| **Engine** | 3.8.0 | 3.8.0+ |

### 의존성

- **dadp-hub-crypto-lib**: `1.1.0` (classifier: `java8`)
- **Spring Boot**: `2.7.18` (Java 8 지원)
- **Jackson**: `2.13.5` (Java 8 호환)

---

## 🔗 관련 문서

- [CHANGELOG.md](CHANGELOG.md)
- [README.md](README.md)
- [Architecture Overview](../../docs/architecture-overview.md)
- [Engine Persistent Cache Design](../../docs/engine-persistent-cache-design.md)

---

## 📝 참고사항

### 개발 상태

이 버전은 **빌드 완료 및 Maven Central 배포 완료** 상태입니다.

- ✅ 빌드 완료 (2025-12-09)
- ✅ Maven Central 배포 완료 (2025-12-09)
- ✅ Deployment ID: `7cdc7c95-5d44-4a06-981e-1a5307b18e8f`
- ✅ 검증 완료 및 Publish 완료

### 버전 선택 가이드

| Java 버전 | 권장 버전 | 사용 가능 버전 |
|-----------|----------|---------------|
| Java 8    | `4.8.0` (이 버전) | `4.8.0`만 가능 |
| Java 11   | `4.17.0` (권장) | `4.8.0` 또는 `4.17.0` |
| Java 17   | `4.17.0` (권장) | `4.8.0` 또는 `4.17.0` |
| Java 21   | `4.17.0` (권장) | `4.8.0` 또는 `4.17.0` |

**설명**:
- **Java 8**: `4.8.0`만 사용 가능 (Java 17 바이트코드는 Java 8에서 실행 불가)
- **Java 11/17/21**: `4.17.0` 사용 권장 (최신 기능), 또는 `4.8.0` 사용 가능 (하위 호환성)

---

## 🙏 기여자

- DADP Development Team

---

**릴리즈 날짜**: 2025-12-12  
**이전 버전**: 3.0.5  
**Java 버전**: Java 8 전용
