# DADP AOP Library v3.17.0 Release Notes

## 🎉 릴리즈 정보

**버전**: 3.17.0  
**릴리즈 일자**: 2025-12-05  
**배포 상태**: ✅ **Maven Central 배포 완료** (2025-12-09)  
**Java 버전**: **Java 17 이상** (권장)  
**주요 개선사항**: Engine 직접 연결, 리포지토리 레벨 암복호화 지원, `findAll()` 배치 복호화 최적화 (약 3배 성능 향상)

### 📦 배포 정보

- **Maven Central**: ✅ 배포 완료
  - 배포 일자: 2025-12-09
  - Deployment ID: `7981b39a-2b9e-4766-871f-cbcdd488fc6b`
  - 검색: https://search.maven.org/search?q=io.github.daone-dadp:dadp-aop:3.17.0
  - 의존성 추가:
    ```xml
    <!-- Spring Boot Starter (권장) -->
    <dependency>
        <groupId>io.github.daone-dadp</groupId>
        <artifactId>dadp-aop-spring-boot-starter</artifactId>
        <version>3.17.0</version>
    </dependency>
    
    <!-- 또는 AOP 라이브러리만 -->
    <dependency>
        <groupId>io.github.daone-dadp</groupId>
        <artifactId>dadp-aop</artifactId>
        <version>3.17.0</version>
    </dependency>
    ```

---

## ⚠️ 중요: 버전 호환성

**이전 버전과 호환되지 않습니다:**

- **v2.1.0** → **v3.17.0**: 호환성 깨짐 (major 버전 증가)
- **설정 변경 필요**: `hub.crypto.base-url`을 Engine/Gateway URL로 변경
- **코드 변경 권장**: 리포지토리 레벨 암복호화로 전환

---

## 📋 주요 변경사항

### ✅ Engine 직접 연결

Hub를 거치지 않고 Engine에 직접 암복호화 요청하도록 변경했습니다.

#### 변경 사항

**이전 방식 (v2.1.0)**:
```properties
hub.crypto.base-url=http://localhost:9004  # Hub 경유
hub.crypto.api-base-path=/hub/api/v1
```

**새로운 방식 (v3.17.0)**:
```properties
hub.crypto.base-url=http://localhost:9003  # Engine 직접 연결
hub.crypto.api-base-path=/api
```

#### 주요 기능

- **성능 향상**: Hub 경유 지연 제거
- **직접 통신**: Engine과 직접 통신하여 응답 속도 개선
- **Gateway 지원**: Gateway URL 사용 가능

### ✅ 리포지토리 레벨 암복호화 지원 (권장)

리포지토리 메서드에 `@Encrypt`/`@Decrypt` 어노테이션을 적용하여 암복호화를 자동화합니다.

#### 사용 방법

**이전 방식 (서비스 레벨)**:
```java
@Service
public class UserService {
    @Encrypt
    public User createUser(User user) { ... }
    
    @Decrypt
    public User getUser(Long id) { ... }
}
```

**새로운 방식 (리포지토리 레벨, 권장)**:
```java
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    @Encrypt(enableLogging = true)
    @Override
    <S extends User> S save(S entity);
    
    @Decrypt(enableLogging = true)
    @Override
    Optional<User> findById(Long id);
    
    @Decrypt(enableLogging = true)
    @Override
    List<User> findAll();
}
```

#### 장점

- **서비스 레이어 분리**: 서비스는 비즈니스 로직만 담당
- **일관성**: 모든 저장/조회 시점에 자동 암복호화
- **유지보수성**: 암복호화 로직이 리포지토리 레벨에 집중
- **성능 최적화**: `findAll()` 시 배치 복호화 자동 사용

### ✅ `findAll()` 배치 복호화 최적화

`findAll()` 메서드 호출 시 배치 복호화를 자동으로 사용하여 성능을 크게 향상시켰습니다.

#### 성능 개선

- **개별 복호화**: 1000건 조회 시 약 7.15초
- **배치 복호화**: 1000건 조회 시 약 2.31초
- **성능 향상**: **약 3배 이상 빠른 성능**

#### 동작 방식

```java
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    @Decrypt(enableLogging = true)
    @Override
    List<User> findAll();  // 자동으로 배치 복호화 사용
}
```

**처리 과정**:
1. `findAll()` 호출 시 여러 엔티티 반환
2. AOP가 Collection 타입 감지
3. 모든 필드 데이터를 수집하여 배치 복호화 API 호출
4. 결과를 각 엔티티에 매칭하여 설정

#### 성능 측정 결과

| 항목 | 개별 복호화 | 배치 복호화 | 개선율 |
|------|------------|------------|--------|
| 1000건 조회 | 약 7.15초 | 약 2.31초 | **약 3.1배** |
| API 호출 횟수 | 1000회 | 1회 | **1000배 감소** |
| 네트워크 오버헤드 | 높음 | 낮음 | **대폭 감소** |

### ⚠️ `saveAll()` 개별 암호화 제약사항

`saveAll()`은 Spring Data JPA의 내부 구조상 배치 암호화를 지원하지 않으며, 개별 암호화만 가능합니다.

#### 제약사항

- **Spring Data JPA 구조**: `saveAll()`은 내부적으로 각 엔티티에 대해 `save()`를 호출
- **Self-invocation**: 내부 `save()` 호출은 AOP 프록시를 거치지 않음
- **결과**: 각 엔티티가 개별적으로 암호화됨 (배치 처리의 성능 이점 없음)

#### 동작 방식

```java
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    @Encrypt(enableLogging = true)
    @Override
    <S extends User> List<S> saveAll(Iterable<S> entities);
    // 각 엔티티별로 개별 암호화 API 호출
}
```

**주의사항**: 대량 데이터 저장 시에도 각 엔티티별로 개별 암호화 API 호출이 발생합니다.

### ✅ 테스트 앱 개선

테스트 앱의 코드를 단순화하고 유지보수성을 향상시켰습니다.

#### 변경사항

- **개별 테스트 옵션 제거**: `findById` 기반 개별 조회 테스트 옵션 제거
- **findAll/saveAll 통일**: 모든 테스트가 `findAll`과 `saveAll`만 사용하도록 변경
- **불필요한 코드 제거**: `getAllUserIds()` 메서드 및 관련 Repository 메서드 제거
- **프론트엔드 정리**: 개별 테스트 스위치 UI 제거 및 관련 JavaScript 코드 정리

### ✅ 알림 기능 통일

AOP와 Wrapper에서 `dadp-hub-crypto-lib`의 `HubNotificationClient`를 사용하도록 통일했습니다.

#### 알림 정책

- **공통 원칙**: 의도치 않은 예외(Exception) 발생 시 `ERROR` 레벨 알림
- **엔티티 식별**: `entityType="AOP"`, `entityId=DADP_AOP_INSTANCE_ID` 또는 `spring.application.name`
- **알림 전송**: Hub의 알림 API를 통해 전송

### ✅ 환경변수 통일

표준 환경변수를 사용하도록 개선했습니다.

#### 환경변수

- **`DADP_CRYPTO_BASE_URL`**: Crypto Base URL (필수, 예: `http://engine:9003`)
- **`DADP_HUB_BASE_URL`**: Hub Base URL (선택, 알림용, 예: `http://hub:9004`)

경로는 라이브러리에서 자동 추가 (`/api` 또는 `/hub/api/v1`).

---

## 🔄 마이그레이션 가이드

### 필수 설정 변경

**이전 (v2.1.0)**:
```properties
hub.crypto.base-url=http://localhost:9004
hub.crypto.api-base-path=/hub/api/v1
```

**새로운 (v3.17.0)**:
```properties
# Engine 직접 연결
hub.crypto.base-url=http://localhost:9003
hub.crypto.api-base-path=/api

# 또는 Gateway 사용
hub.crypto.base-url=http://gateway:port
hub.crypto.api-base-path=/api
```

### 코드 변경 (권장)

**리포지토리 레벨 암복호화로 전환**:

```java
// 1. 엔티티 정의
@Entity
@Table(name = "users")
public class User {
    @EncryptField(policy = "dadp")
    private String email;
    
    @EncryptField(policy = "dadp")
    private String phone;
}

// 2. 리포지토리에 어노테이션 추가
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    @Encrypt(enableLogging = true)
    @Override
    <S extends User> S save(S entity);
    
    @Decrypt(enableLogging = true)
    @Override
    Optional<User> findById(Long id);
    
    @Decrypt(enableLogging = true)
    @Override
    List<User> findAll();
}

// 3. 서비스는 비즈니스 로직만 담당
@Service
public class UserService {
    @Autowired
    private UserRepository userRepository;
    
    public User createUser(String email, String phone) {
        User user = new User(email, phone);
        return userRepository.save(user);  // 자동 암호화
    }
    
    public Optional<User> getUser(Long id) {
        return userRepository.findById(id);  // 자동 복호화
    }
    
    public List<User> getAllUsers() {
        return userRepository.findAll();  // 자동 배치 복호화
    }
}
```

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

- **dadp-hub-crypto-lib**: 1.1.0 (자동 포함)
- **Spring Boot**: 3.2.12
- **Spring AOP**: Spring Boot에 포함
- **AspectJ**: Spring Boot에 포함

---

## 🐛 알려진 이슈

1. **`saveAll()` 배치 암호화 미지원**
   - Spring Data JPA의 구조적 제약으로 인해 개별 암호화만 지원
   - 각 엔티티별로 개별 암호화 API 호출 발생

2. **`@Query` 네이티브 쿼리 미지원**
   - 향후 지원 예정

3. **`Page<T>`, `Slice<T>` 반환 타입 미지원**
   - 향후 지원 예정

---

## 📝 변경 내역

### Added
- ✅ Engine 직접 연결 지원
- ✅ 리포지토리 레벨 암복호화 지원
- ✅ `findAll()` 배치 복호화 최적화
- ✅ `CryptoService.batchEncrypt()` / `batchDecrypt()` 메서드 추가
- ✅ `HubCryptoService` 배치 메서드 추가
- ✅ 알림 기능 통일 (`HubNotificationClient` 사용)
- ✅ 환경변수 통일 (`DADP_CRYPTO_BASE_URL`, `DADP_HUB_BASE_URL`)

### Changed
- ✅ `hub.crypto.base-url` 설정 변경 (Hub → Engine/Gateway)
- ✅ `hub.crypto.api-base-path` 설정 추가
- ✅ 서비스 레이어와 리포지토리 레이어 분리
- ✅ 테스트 앱 개선 (개별 테스트 옵션 제거, findAll/saveAll 통일)

### Fixed
- ✅ AOP 복호화 문제 해결 (리포지토리 메서드에 어노테이션 적용)
- ✅ 성능 최적화 검증 완료

### Deprecated
- ⚠️ 서비스 레벨 암복호화 (리포지토리 레벨 사용 권장)

---

## 📚 관련 문서

- [AOP 라이브러리 사용 가이드](./dadp-aop-user-guide.md)
- [통합 릴리즈 노트](../../docs/releases/RELEASE_v4.8.0.md)
- [Maven Central 검색](https://search.maven.org/search?q=io.github.daone-dadp:dadp-aop:3.17.0)

---

## 👥 기여자

- DADP Development Team

---

**작성일**: 2025-12-09  
**최종 업데이트**: 2025-12-09

