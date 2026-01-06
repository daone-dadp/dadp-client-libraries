# DADP AOP 라이브러리 사용 가이드

> **고객사를 위한 DADP AOP 라이브러리 사용 가이드**

## 📋 목차

1. [개요](#개요)
2. [빠른 시작](#빠른-시작)
3. [프로젝트 설정](#프로젝트-설정)
4. [애플리케이션 설정](#애플리케이션-설정)
5. [Hub 연동 설정](#hub-연동-설정)
6. [사용 예시](#사용-예시)
7. [지원 명령어](#지원-명령어)
8. [문제 해결](#문제-해결)
9. [체크리스트](#체크리스트)
10. [릴리즈 정보](#릴리즈-정보)

---

## 개요

DADP AOP는 Spring AOP 기반으로 암복호화 기능을 자동화하는 라이브러리입니다.

### ✨ 주요 특징

- ✅ **자동 암복호화**: `@Encrypt`, `@Decrypt` 어노테이션으로 간편한 설정
- ✅ **성능 최적화**: `findAll()` 시 배치 복호화 자동 사용으로 **개별 복호화 대비 약 3배 이상 빠른 성능**
- ✅ **비침투적**: 기존 코드 수정 없이 어노테이션만으로 적용 가능
- ✅ **유지보수성**: 암복호화 로직이 리포지토리 레벨에 집중되어 관리 용이

### 📦 제공 라이브러리

1. **dadp-hub-crypto-lib** (1.2.0) ✅ Maven Central 배포 완료
   - Hub와의 암복호화 통신을 담당하는 핵심 라이브러리
   - `HubCryptoService`를 통해 암복호화 수행

2. **dadp-aop** (5.3.0) ✅ Maven Central 배포 완료
   - AOP 기반 암복호화 자동화 라이브러리
   - `@Encrypt`, `@Decrypt` 어노테이션 지원
   - 리포지토리 레벨 암복호화 지원
   - **성능 최적화**: `findAll()` 시 배치 복호화 자동 사용 (개별 복호화 대비 약 3배 이상 빠름)

3. **dadp-aop-spring-boot-starter** (5.3.0) ⭐ 권장
   - Spring Boot Starter 패키지
   - 자동 설정 및 의존성 관리
   - 가장 편리한 통합 방법
   - ⚠️ **참고**: 현재 버전은 Maven Central에 배포되지 않았습니다. `dadp-aop`를 직접 사용하세요.

---

## 빠른 시작

### 1단계: Maven 리포지토리 설정

DADP 라이브러리는 **Maven Central**을 통해 배포됩니다 (배포 완료 ✅).

> **배포 상태:** ✅ Maven Central 배포 완료 (2025-12-29)  
> **Group ID:** `io.github.daone-dadp`  
> **레포지토리:** [https://github.com/daone-dadp/dadp-client-libraries](https://github.com/daone-dadp/dadp-client-libraries)  
> **Maven Central 검색:** [https://search.maven.org/search?q=io.github.daone-dadp](https://search.maven.org/search?q=io.github.daone-dadp)  
> **배포된 버전:**
> - `dadp-aop:5.3.0` ✅
> - `dadp-hub-crypto-lib:1.2.0` ✅

#### Maven Central 설정 (권장) ⭐

**Maven Central은 별도의 리포지토리 설정이 필요 없습니다!**  
Maven/Gradle이 기본적으로 Maven Central을 사용하므로 추가 설정 없이 바로 사용할 수 있습니다.

```xml
<!-- 리포지토리 설정 불필요 - Maven Central은 기본 리포지토리 -->
```

**특징:**
- ✅ 별도 리포지토리 설정 불필요
- ✅ 빠른 다운로드 속도
- ✅ 프로덕션 환경 표준
- ✅ 배포 완료 상태 (즉시 사용 가능)

### 2단계: 의존성 추가

#### 방법 1: AOP 라이브러리 사용 (권장) ⭐

**Maven `pom.xml`에 추가:**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    
    <!-- 프로젝트 정보 -->
    <groupId>com.example</groupId>
    <artifactId>my-application</artifactId>
    <version>1.0.0</version>
    
    <dependencies>
        <!-- DADP AOP 라이브러리 (권장) -->
        <dependency>
            <groupId>io.github.daone-dadp</groupId>
            <artifactId>dadp-aop</artifactId>
            <version>5.3.0</version>
        </dependency>
        
        <!-- Spring Boot 의존성 (필요한 경우) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
            <version>3.2.12</version>
        </dependency>
    </dependencies>
</project>
```

**의존성 정보:**
- **Group ID**: `io.github.daone-dadp`
- **Artifact ID**: `dadp-aop`
- **Version**: `5.3.0`
- **자동 포함**: `dadp-hub-crypto-lib:1.2.0`이 자동으로 포함됩니다
- **리포지토리 설정**: 불필요 (Maven Central 기본 사용)

**Maven Central 검색:**
- https://search.maven.org/search?q=io.github.daone-dadp:dadp-aop:5.3.0

#### 방법 2: Hub 암복호화 라이브러리만 사용

**Maven `pom.xml`에 추가:**

```xml
<dependencies>
    <dependency>
        <groupId>io.github.daone-dadp</groupId>
        <artifactId>dadp-hub-crypto-lib</artifactId>
        <version>1.2.0</version>
    </dependency>
</dependencies>
```

**의존성 정보:**
- **Group ID**: `io.github.daone-dadp`
- **Artifact ID**: `dadp-hub-crypto-lib`
- **Version**: `1.2.0`
- **리포지토리 설정**: 불필요 (Maven Central 기본 사용)

**💡 참고:** 
- ✅ Maven Central 배포 완료 (2025-12-29)
- ✅ Group ID: `io.github.daone-dadp`
- ✅ Maven Central만 제공 (JitPack은 더 이상 지원하지 않음)
- ✅ 배포된 버전: `dadp-aop:5.3.0`, `dadp-hub-crypto-lib:1.2.0`
- ✅ `dadp-aop:5.3.0`을 사용하면 `dadp-hub-crypto-lib:1.2.0`이 자동으로 포함됩니다
- ⚠️ `dadp-aop-spring-boot-starter:5.3.0`은 현재 Maven Central에 배포되지 않았습니다
- ✅ 별도 리포지토리 설정이 필요 없습니다 (Maven Central 기본 사용)
- Maven Central 검색: https://search.maven.org/search?q=io.github.daone-dadp

### 3단계: 설정 파일 추가

`application.properties`:
```properties
# Engine URL 설정 (필수)
# DADP_CRYPTO_BASE_URL 환경변수로도 설정 가능
dadp.crypto.base-url=${DADP_CRYPTO_BASE_URL:http://localhost:9003}

# Hub 서버 설정 (선택 - 알림 기능 사용 시)
dadp.hub-base-url=${DADP_HUB_BASE_URL:http://localhost:9004}
```

### 4단계: 사용하기

**리포지토리 레벨에서 암복호화 처리 (권장):**

```java
// 1. 엔티티 정의
@Entity
@Table(name = "users")
public class User {
    @Id
    private Long id;
    
    @EncryptField(policy = "dadp")
    private String email;  // 암호화 대상 필드
}

// 2. 리포지토리 정의
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    @Encrypt(enableLogging = true)
    @Override
    <S extends User> S save(S entity);
    
    @Decrypt(enableLogging = true)
    @Override
    Optional<User> findById(Long id);
}

// 3. 서비스 사용
@Service
public class UserService {
    @Autowired
    private UserRepository userRepository;
    
    public User createUser(String email) {
        User user = new User(email);
        return userRepository.save(user);  // 자동 암호화
    }
    
    public Optional<User> getUser(Long id) {
        return userRepository.findById(id);  // 자동 복호화
    }
}
```

---

## 프로젝트 설정

### Maven 프로젝트

#### 방법 1: AOP 라이브러리 사용 (권장) ⭐

**Maven Central 사용 (별도 리포지토리 설정 불필요):**

```xml
<dependencies>
    <!-- DADP AOP 라이브러리 (권장) -->
    <dependency>
        <groupId>io.github.daone-dadp</groupId>
        <artifactId>dadp-aop</artifactId>
        <version>5.3.0</version>
    </dependency>
</dependencies>
```

**의존성 정보:**
- `dadp-aop:5.3.0`은 자동으로 `dadp-hub-crypto-lib:1.2.0`을 포함합니다
- 별도로 `dadp-hub-crypto-lib`를 추가할 필요가 없습니다

#### 방법 2: Hub 암복호화 라이브러리만 사용

```xml
<dependencies>
    <dependency>
        <groupId>io.github.daone-dadp</groupId>
        <artifactId>dadp-hub-crypto-lib</artifactId>
        <version>1.2.0</version>
    </dependency>
</dependencies>
```

### Maven 리포지토리 설정

#### Maven Central (유일한 배포 방법) ⭐

**Maven Central은 별도의 리포지토리 설정이 필요 없습니다!**

```xml
<dependencies>
    <!-- DADP AOP 라이브러리 -->
    <dependency>
        <groupId>io.github.daone-dadp</groupId>
        <artifactId>dadp-aop</artifactId>
        <version>5.3.0</version>
    </dependency>
</dependencies>
```

> **✅ 배포 완료:** Maven Central 배포 완료 (2025-12-29)  
> **Group ID:** `io.github.daone-dadp`  
> **검색:** https://search.maven.org/search?q=io.github.daone-dadp

**사용 가능한 라이브러리:**
- `io.github.daone-dadp:dadp-aop:5.3.0` ⭐ 권장 (Maven Central 배포 완료)
- `io.github.daone-dadp:dadp-hub-crypto-lib:1.2.0` (Maven Central 배포 완료)

**⚠️ 참고:**
- `dadp-aop-spring-boot-starter:5.3.0`은 현재 Maven Central에 배포되지 않았습니다
- `dadp-aop:5.3.0`을 사용하면 동일한 기능을 사용할 수 있습니다

---

## 애플리케이션 설정

### application.properties 설정

```properties
# DADP Hub 설정 (통합)
dadp.hub-base-url=${DADP_HUB_BASE_URL:http://localhost:9004}

# DADP AOP 설정
dadp.aop.enabled=true
dadp.aop.default-policy=dadp
dadp.aop.fallback-to-original=true
dadp.aop.enable-logging=true

# AOP 배치 처리 설정 (선택사항)
dadp.aop.batch-min-size=100
dadp.aop.batch-max-size=10000

# Hub 암복호화 라이브러리 설정
hub.crypto.timeout=5000
hub.crypto.retry-count=3
hub.crypto.enable-logging=true
hub.crypto.default-policy=dadp
```

### application.yml 설정

```yaml
dadp:
  hub-base-url: ${DADP_HUB_BASE_URL:http://localhost:9004}
  aop:
    enabled: true
    default-policy: dadp
    fallback-to-original: true
    enable-logging: true
    batch-min-size: 100
    batch-max-size: 10000

hub:
  crypto:
    timeout: 5000
    retry-count: 3
    enable-logging: true
    default-policy: dadp
```

### 환경 변수 설정 (권장)

프로덕션 환경에서는 환경 변수를 사용합니다:

#### 필수 환경 변수

```bash
# Hub URL (필수, 알림용 + 암복호화 URL 자동 조회용)
export DADP_HUB_BASE_URL=http://your-hub-server:9004
```

#### 선택적 환경 변수

```bash
# 암복호화 URL 직접 지정 (선택, 없으면 Hub에서 자동 조회)
export DADP_CRYPTO_BASE_URL=http://your-gateway:9003

# AOP 인스턴스 ID (선택, Hub 엔드포인트 조회 시 사용)
export DADP_AOP_INSTANCE_ID=my-app-aop-1
```

**동작 방식:**
1. `DADP_CRYPTO_BASE_URL`이 있으면 직접 사용
2. 없으면 `DADP_HUB_BASE_URL`을 사용하여 Hub에서 엔드포인트 정보 자동 조회
3. 조회 실패 시 기본값 사용 (`http://localhost:9003`)

```properties
# application.properties에서 환경 변수 참조
dadp.hub-base-url=${DADP_HUB_BASE_URL:http://localhost:9004}
```

### 배치 처리 환경변수 설정 (선택사항)

배치 처리 성능 최적화를 위한 환경변수 설정:

```bash
# 배치 처리 최소 크기 (기본값: 100)
# 이 값보다 작은 데이터셋은 자동으로 개별 처리로 폴백
export DADP_AOP_BATCH_MIN_SIZE=100

# 배치 처리 최대 크기 (기본값: 10,000)
# 이 값보다 큰 데이터셋은 청크 단위로 분할 처리
export DADP_AOP_BATCH_MAX_SIZE=10000

# 배치 처리 완전 비활성화 (기본값: false)
# true로 설정하면 무조건 개별 처리 사용 (테스트용)
export DADP_AOP_DISABLE_BATCH=false
```

**설정 우선순위:**
1. 환경변수 (`DADP_AOP_BATCH_MIN_SIZE`, `DADP_AOP_BATCH_MAX_SIZE`, `DADP_AOP_DISABLE_BATCH`)
2. 설정 파일 (`dadp.aop.batch-min-size`, `dadp.aop.batch-max-size`)
3. 기본값 (100, 10,000, false)

**권장 설정:**
- 작은 데이터셋(100개 필드 미만)이 많은 경우: `DADP_AOP_BATCH_MIN_SIZE=100` (기본값)
- 대량 데이터셋(10,000개 필드 이상)이 많은 경우: `DADP_AOP_BATCH_MAX_SIZE=10000` (기본값)
- 성능 테스트 시: `DADP_AOP_DISABLE_BATCH=true` (개별 처리 강제)

---

## Hub 연동 설정

### 1. Hub 서버 정보

다음 정보를 DADP 운영팀으로부터 제공받아야 합니다:

- **Hub 서버 URL**: 예) `http://your-hub-server:9004` (필수)
- **암복호화 URL**: 예) `http://your-gateway:9003` (선택, 없으면 Hub에서 자동 조회)
- **AOP 인스턴스 ID**: 예) `my-app-aop-1` (선택, Hub 엔드포인트 조회 시 사용)
- **암호화 정책명**: 예) `dadp`

### 2. 환경 변수 설정

**필수:**
```bash
export DADP_HUB_BASE_URL=http://your-hub-server:9004
```

**선택:**
```bash
# 암복호화 URL 직접 지정 (없으면 Hub에서 자동 조회)
export DADP_CRYPTO_BASE_URL=http://your-gateway:9003

# AOP 인스턴스 ID (Hub 엔드포인트 조회 시 사용)
export DADP_AOP_INSTANCE_ID=my-app-aop-1
```

### 3. 네트워크 연결 확인

```bash
# Hub 서버 연결 확인
curl http://your-hub-server:9004/hub/actuator/health

# 예상 응답
{"status":"UP"}
```

### 4. 암호화 정책 확인

Hub에서 사용할 암호화 정책을 확인합니다:

```bash
# Hub에서 정책 목록 조회 (예시)
curl http://your-hub-server:9004/hub/api/v1/policies
```

---

## 사용 예시

### 1. 리포지토리 레벨 암복호화 (권장 방법) ⭐

**가장 일관성 있고 권장되는 방법입니다.**  
리포지토리 메서드에 `@Encrypt`/`@Decrypt` 어노테이션을 적용하여 저장/조회 시점에 자동으로 암복호화를 처리합니다.

#### 엔티티 정의

```java
@Entity
@Table(name = "users")
public class User {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String name;
    
    @EncryptField(policy = "dadp")
    private String email;  // 암호화 대상 필드
    
    @EncryptField(policy = "dadp")
    private String phone;  // 암호화 대상 필드
    
    // getters, setters...
}
```

#### 리포지토리 정의

```java
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    
    /**
     * 저장 시 자동 암호화
     * 파라미터의 @EncryptField 필드가 저장 전에 자동으로 암호화됩니다.
     */
    @Encrypt(enableLogging = true)
    @Override
    <S extends User> S save(S entity);
    
    /**
     * 조회 시 자동 복호화
     * 반환값의 @EncryptField 필드가 자동으로 복호화됩니다.
     */
    @Decrypt(enableLogging = true)
    @Override
    List<User> findAll();
    
    @Decrypt(enableLogging = true)
    @Override
    Optional<User> findById(Long id);
    
    @Decrypt(enableLogging = true)
    Optional<User> findByEmail(String email);
}
```

#### 서비스 구현

```java
@Service
public class UserService {
    
    @Autowired
    private UserRepository userRepository;
    
    /**
     * 사용자 생성
     * 리포지토리에서 자동으로 암호화되어 저장됩니다.
     */
    public User createUser(String name, String email, String phone) {
        User user = new User(name, email, phone);
        return userRepository.save(user);  // 자동 암호화
    }
    
    /**
     * 모든 사용자 조회
     * 리포지토리에서 자동으로 복호화되어 반환됩니다.
     */
    public List<User> getAllUsers() {
        return userRepository.findAll();  // 자동 복호화 (배치 복호화 사용)
    }
    
    /**
     * ID로 사용자 조회
     */
    public Optional<User> getUserById(Long id) {
        return userRepository.findById(id);  // 자동 복호화
    }
}
```

**장점:**
- ✅ 일관성: 암호화/복호화가 모두 리포지토리 레벨에서 처리
- ✅ 관심사 분리: 서비스는 비즈니스 로직만 담당
- ✅ 유지보수성: 암복호화 로직 변경 시 리포지토리만 수정
- ✅ 명확성: 코드만 봐도 어디서 암복호화가 일어나는지 명확
- ✅ 성능: `findAll()` 시 배치 복호화 자동 사용

### 2. 서비스 레벨 암복호화 (선택적 방법)

서비스 메서드에 직접 어노테이션을 적용할 수도 있습니다:

```java
@Service
public class UserService {
    
    @Autowired
    private UserRepository userRepository;
    
    /**
     * 암호화된 사용자 정보 반환
     */
    @Encrypt(enableLogging = true)
    public UserDto getUserInfo(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        return convertToDto(user);  // 반환값의 @EncryptField 필드가 암호화됨
    }
    
    /**
     * 복호화된 사용자 정보 반환
     */
    @Decrypt(enableLogging = true)
    public UserDto getDecryptedUserInfo(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        return convertToDto(user);  // 반환값의 @EncryptField 필드가 복호화됨
    }
}
```

### 3. HubCryptoService 직접 사용

AOP를 사용하지 않고 직접 제어하고 싶은 경우:

```java
@Service
public class UserService {
    
    @Autowired
    private HubCryptoService hubCryptoService;
    
    /**
     * 직접 암호화 호출
     */
    public String encryptUserData(String userData) {
        try {
            String encrypted = hubCryptoService.encrypt(userData, "dadp");
            log.info("데이터 암호화 완료");
            return encrypted;
        } catch (HubCryptoException e) {
            log.error("암호화 실패: {}", e.getMessage());
            throw new ServiceException("암호화 처리 실패", e);
        }
    }
    
    /**
     * 직접 복호화 호출
     */
    public String decryptUserData(String encryptedData) {
        try {
            String decrypted = hubCryptoService.decrypt(encryptedData);
            log.info("데이터 복호화 완료");
            return decrypted;
        } catch (HubCryptoException e) {
            log.error("복호화 실패: {}", e.getMessage());
            throw new ServiceException("복호화 처리 실패", e);
        }
    }
}
```

### 4. REST Controller 예시

```java
@RestController
@RequestMapping("/api/users")
public class UserController {
    
    @Autowired
    private UserService userService;
    
    /**
     * 사용자 정보 조회 (자동 복호화)
     * 리포지토리에서 이미 복호화된 데이터가 반환됩니다.
     */
    @GetMapping("/{userId}")
    public ResponseEntity<UserDto> getUser(@PathVariable Long userId) {
        Optional<User> userOpt = userService.getUserById(userId);
        if (userOpt.isPresent()) {
            UserDto userDto = convertToDto(userOpt.get());
            return ResponseEntity.ok(userDto);
        }
        return ResponseEntity.notFound().build();
    }
    
    /**
     * 사용자 생성 (자동 암호화)
     * 리포지토리에서 자동으로 암호화되어 저장됩니다.
     */
    @PostMapping
    public ResponseEntity<UserDto> createUser(@RequestBody CreateUserRequest request) {
        User user = userService.createUser(
            request.getName(),
            request.getEmail(),
            request.getPhone()
        );
        UserDto userDto = convertToDto(user);
        return ResponseEntity.ok(userDto);
    }
}
```

### 5. 엔티티 필드 암호화 정책 지정

**암호화 정책(`policy`)은 엔티티 필드에서만 지정할 수 있습니다.**

```java
@Entity
@Table(name = "users")
public class User {
    
    @Id
    private Long id;
    
    private String name;
    
    /**
     * 엔티티 필드에서 암호화 정책 지정
     * 이 필드는 저장 시 자동으로 암호화됩니다.
     */
    @EncryptField(policy = "dadp")
    private String email;
    
    @EncryptField(policy = "dadp")
    private String phone;
    
    // getters, setters...
}
```

**주의사항:**
- `@EncryptField`는 엔티티 필드에만 사용
- `policy` 속성으로 암호화 정책 지정
- 리포지토리나 서비스 메서드의 `@Encrypt`/`@Decrypt`는 이 정책을 자동으로 사용

---

## 지원 명령어

### 어노테이션 (확정)

#### ✅ **메서드 레벨 어노테이션**

| 어노테이션 | 설명 | 지원 기능 |
|-----------|------|-----------|
| `@Encrypt` | 메서드 반환값 암호화 | ✅ 완전 지원 - 단일 객체, Collection, Optional, 배치 암호화 |
| `@Decrypt` | 메서드 반환값 복호화 | ✅ 완전 지원 - 단일 객체, Collection, Optional, 배치 복호화 |

#### ✅ **필드 레벨 어노테이션**

| 어노테이션 | 설명 | 지원 기능 |
|-----------|------|-----------|
| `@EncryptField` | 필드 암호화 지정 | ✅ 완전 지원 - 정책 지정 가능 |
| `@DecryptField` | 필드 복호화/마스킹 지정 | ✅ 완전 지원 - 마스킹 정책 지정 가능 |
| `@DefaultEncryptionPolicy` | 클래스 기본 정책 지정 | ✅ 완전 지원 |

### JPA Repository 메서드 (확정)

#### ✅ **완전 지원 (암호화/복호화 처리됨)**

| 메서드 | 설명 | 비고 |
|--------|------|------|
| `save(S entity)` | 단일 엔티티 저장 | **@Encrypt 적용 시 암호화 처리됨** - 개별 암호화 처리 |
| `saveAll(Iterable<S> entities)` | 여러 엔티티 저장 | **@Encrypt 적용 시 암호화 처리됨** - **개별 암호화 처리** (배치 처리 불가)<br>**⚠️ 주의**: Spring Data JPA의 `saveAll()`은 내부적으로 각 엔티티에 대해 `save()`를 호출하므로, AOP는 `saveAll()` 자체를 감지하지 못하고 개별 `save()` 호출만 감지됩니다. 따라서 배치 암호화는 불가능하지만, 각 엔티티는 정상적으로 암호화됩니다.<br>**Iterable 타입 지원** - Collection(List, Set 등) 및 Iterable 모두 지원 |
| `findById(ID id)` | ID로 조회 | **@Decrypt 적용 시 복호화 처리됨** - Optional 반환 지원 |
| `findAll()` | 전체 조회 | **@Decrypt 적용 시 복호화 처리됨** - **✅ 배치 복호화 사용**<br>**성능 최적화**: `findAll()`은 AOP가 정상적으로 감지되며, 여러 엔티티의 복호화를 배치로 처리합니다. 개별 복호화 대비 **약 3배 이상 빠른 성능**을 제공합니다. |
| `findByEmail(String email)` | 이메일로 조회 | **@Decrypt 적용 시 복호화 처리됨** |
| `findByNameContaining(String name)` | 이름 포함 검색 | **@Decrypt 적용 시 복호화 처리됨** |
| `findByPhoneContaining(String phone)` | 전화번호 포함 검색 | **@Decrypt 적용 시 복호화 처리됨** |

#### ✅ **지원 (암호화/복호화 불필요)**

| 메서드 | 설명 | 비고 |
|--------|------|------|
| `deleteById(ID id)` | ID로 삭제 | 지원 (암호화 불필요) |
| `delete(S entity)` | 엔티티 삭제 | 지원 (암호화 불필요) |
| `deleteAll()` | 전체 삭제 | 지원 (암호화 불필요) |
| `existsById(ID id)` | 존재 여부 확인 | 지원 (암호화 불필요) |
| `count()` | 개수 조회 | 지원 (암호화 불필요) |

### 반환 타입 지원 (확정)

#### ✅ **완전 지원**

| 반환 타입 | 설명 | 비고 |
|----------|------|------|
| `단일 객체` | User, UserAop 등 | ✅ 완전 지원 - 필드별 암호화/복호화 |
| `List<T>` | 컬렉션 | ✅ 완전 지원 - **배치 복호화 사용** (`findAll()` 시)<br>**주의**: `saveAll()` 반환값은 배치 암호화 미지원 (개별 암호화만 가능) |
| `Set<T>` | 컬렉션 | ✅ 완전 지원 - 각 항목별 처리 |
| `Collection<T>` | 컬렉션 | ✅ 완전 지원 - List, Set 등 모든 Collection 구현체 지원 |
| `Iterable<T>` | 반복 가능한 타입 | ✅ 완전 지원 - Collection이 아닌 Iterable도 지원 (드물지만 안전을 위해) |
| `Optional<T>` | Optional | ✅ 완전 지원 - 내부 값 추출 후 처리 |
| `String` | 문자열 | ✅ 완전 지원 - 직접 암호화/복호화 |

### JPA Entity 지원 (확정)

#### ✅ **완전 지원**

| 기능 | 설명 | 비고 |
|------|------|------|
| `@Entity` | JPA 엔티티 | ✅ 완전 지원 - Jakarta/Javax 모두 지원 |
| `@Table` | 테이블 매핑 | ✅ 완전 지원 |
| `@Column` | 컬럼 매핑 | ✅ 완전 지원 |
| `@Id` | 기본키 | ✅ 완전 지원 |
| `@GeneratedValue` | 자동 생성 | ✅ 완전 지원 |
| `@EncryptField` | 암호화 필드 | ✅ 완전 지원 - 필드 레벨 정책 지정 가능 |
| `@DecryptField` | 복호화/마스킹 필드 | ✅ 완전 지원 - 마스킹 정책 지정 가능 |

### 미지원 명령어 (확인됨)

#### ✅ **지원 JPA 기능**

| 기능 | 설명 | 상태 |
|------|------|------|
| `@Query` (네이티브 쿼리) | 네이티브 SQL 쿼리 | ✅ **지원** - `@Decrypt` 어노테이션 정상 적용, 반환 타입에 따라 자동 복호화 |
| `@Query` (JPQL) | JPQL 쿼리 | ✅ **지원** - 네이티브 쿼리와 동일하게 처리 |
| `@Modifying` | 수정 쿼리 | ⚠️ **테스트 중** - `@Encrypt`는 파라미터 암호화 가능하나 테스트 필요, `@Decrypt`는 반환값 없어 의미 없음 |

#### ❌ **미지원 JPA 기능**

| 기능 | 설명 | 상태 |
|------|------|------|
| `EntityManager` 직접 사용 | EntityManager 직접 호출 | ❌ **미지원** - 어노테이션 적용 불가 |
| `Criteria API` | Criteria 쿼리 | ❌ **미지원** - 어노테이션 적용 불가 |
| `JPQL` 직접 작성 | JPQL 문자열 | ❌ **미지원** - 어노테이션 적용 불가 |

#### ✅ **지원 반환 타입**

| 반환 타입 | 설명 | 상태 |
|----------|------|------|
| `Stream<T>` | 스트림 | ✅ **제한적 지원** - Stream 전체를 수집 후 복호화, in-memory Stream으로 재생성. 대량 데이터 시 메모리 사용량 증가 가능 |
| `Page<T>` | 페이징 | ✅ **완전 지원** - content 자동 복호화 후 Page 재생성 |
| `Slice<T>` | 슬라이스 | ✅ **완전 지원** - content 자동 복호화 후 Slice 재생성 |

### 사용 권장사항

#### ✅ **권장 사용법**

```java
// ✅ 권장: Repository 메서드에 어노테이션 적용
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    @Encrypt
    @Override
    <S extends User> S save(S entity);
    
    @Decrypt
    @Override
    List<User> findAll();
    
    @Decrypt
    @Override
    Optional<User> findById(Long id);
}

// ✅ 권장: Entity에 필드 레벨 어노테이션
@Entity
public class User {
    private String name;
    
    @EncryptField(policy = "dadp")
    private String email;
    
    @EncryptField(policy = "dadp_plain")
    private String phone;
}
```

#### ✅ **지원 사용법 (네이티브 쿼리)**

```java
// ✅ 지원: @Query 네이티브 쿼리
@Query(value = "SELECT * FROM users WHERE email = ?1", nativeQuery = true)
@Decrypt  // ← 정상 적용됨
List<User> findByEmailNative(String email);

// ✅ 지원: Stream 반환 타입
@Decrypt
@Query("SELECT u FROM User u")
Stream<User> findAllAsStream();  // ← 내부적으로 Stream → List → 복호화 → Stream 변환

// ⚠️ 주의: Stream 타입은 대량 데이터 시 메모리 사용량 증가 가능
```

#### ❌ **비권장 사용법**

```java
// ❌ 비권장: EntityManager 직접 사용
@Autowired
private EntityManager em;

public User findUser(Long id) {
    return em.find(User.class, id);  // ← @Decrypt 적용 불가
}
```

---

## 문제 해결

### 1. 라이브러리를 찾을 수 없는 경우

#### 증상
```
Could not resolve dependencies for project ...
```

#### 해결 방법

1. **Maven 리포지토리 설정 확인**
   - `pom.xml` 또는 `~/.m2/settings.xml`에 리포지토리 추가 확인

2. **의존성 다운로드 강제 실행**
   ```bash
   mvn clean install -U
   ```

3. **Maven Central 확인**
   - Maven Central은 별도 리포지토리 설정이 필요 없습니다
   - Group ID: `io.github.daone-dadp`
   - Maven Central 검색: https://search.maven.org/search?q=io.github.daone-dadp

### 2. AOP가 동작하지 않는 경우

#### 증상
- `@Encrypt`, `@Decrypt` 어노테이션이 작동하지 않음

#### 해결 방법

1. **AOP 라이브러리 사용 확인**
   ```xml
   <dependency>
       <groupId>io.github.daone-dadp</groupId>
       <artifactId>dadp-aop</artifactId>
       <version>5.3.0</version>
   </dependency>
   ```

2. **자동 설정 확인**
   ```properties
   # application.properties
   dadp.aop.enabled=true
   ```

3. **AspectJ 의존성 확인**
   ```xml
   <!-- Spring Boot Starter에 포함되어 있음 -->
   <dependency>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-starter-aop</artifactId>
   </dependency>
   ```

### 3. Hub 연결 실패

#### 증상
```
HubConnectionException: Hub 연결 실패
```

#### 해결 방법

1. **Hub 서버 URL 확인**
   ```properties
   dadp.hub-base-url=${DADP_HUB_BASE_URL:http://localhost:9004}
   ```

2. **네트워크 연결 확인**
   ```bash
   curl http://your-hub-server:9004/hub/actuator/health
   ```

3. **타임아웃 설정 증가**
   ```properties
   hub.crypto.timeout=10000  # 10초로 증가
   ```

4. **로깅 활성화**
   ```properties
   hub.crypto.enable-logging=true
   dadp.aop.enable-logging=true
   ```

### 4. 암호화 정책 오류

#### 증상
```
HubCryptoException: 암호화 실패: 정책을 찾을 수 없습니다
```

#### 해결 방법

1. **엔티티 필드에서 정책명 확인**
   ```java
   @Entity
   public class User {
       @EncryptField(policy = "dadp")  // 엔티티 필드에서 정책 지정
       private String email;
   }
   ```

2. **Hub에서 정책 목록 확인**
   ```bash
   curl http://your-hub-server:9004/hub/api/v1/policies
   ```

3. **마스킹 정책 확인**
   ```bash
   curl http://your-hub-server:9004/hub/api/v1/mask-policies
   ```

### 5. 버전 호환성 문제

#### 증상
```
ClassNotFoundException 또는 MethodNotFoundException
```

#### 해결 방법

1. **버전 일치 확인**
   ```xml
   <!-- DADP AOP 라이브러리 사용 -->
   <dependency>
       <groupId>io.github.daone-dadp</groupId>
       <artifactId>dadp-aop</artifactId>
       <version>5.3.0</version>
   </dependency>
   ```

2. **의존성 트리 확인**
   ```bash
   mvn dependency:tree | grep dadp
   ```

3. **Maven Central 확인**
   - Maven Central 검색: https://search.maven.org/search?q=io.github.daone-dadp
   - 최신 버전 확인 및 다운로드

---

## 기존 데이터 마이그레이션 가이드

### ⚠️ 중요: 컬럼 크기 확장 필수

기존 데이터를 암호화할 때는 **반드시 컬럼 크기를 확장**해야 합니다. 암호화된 데이터는 원본 데이터보다 훨씬 길어질 수 있습니다.

#### 암호화된 데이터 크기 계산

DADP 암호화는 Base64 인코딩된 데이터를 반환하며, 다음과 같은 오버헤드가 있습니다:

- **원본 데이터 크기**: N 바이트
- **암호화 오버헤드**: 약 33% 증가 (AES-GCM + Base64 인코딩)
- **정책 UUID 포함**: 약 50-100 바이트 추가
- **최종 크기**: 약 `N * 1.5 + 100` 바이트

**권장 컬럼 크기:**
- 원본이 `VARCHAR(100)` 이하 → `TEXT` 또는 `VARCHAR(500)` 이상
- 원본이 `VARCHAR(255)` 이하 → `TEXT` 또는 `VARCHAR(1000)` 이상
- 원본이 `VARCHAR(500)` 이상 → `TEXT` 권장

#### 마이그레이션 절차

**1단계: 컬럼 크기 확인 및 확장**

```sql
-- 예시: users 테이블의 email, phone 컬럼 확장
-- MySQL/MariaDB
ALTER TABLE users 
  MODIFY COLUMN email TEXT NOT NULL,
  MODIFY COLUMN phone TEXT;

-- PostgreSQL
ALTER TABLE users 
  ALTER COLUMN email TYPE TEXT,
  ALTER COLUMN phone TYPE TEXT;
```

**2단계: 인덱스 재생성 (필요시)**

```sql
-- TEXT 컬럼에는 인덱스 생성 시 길이 제한 필요
-- MySQL/MariaDB
CREATE INDEX idx_users_email ON users(email(255));
DROP INDEX idx_users_email_old ON users; -- 기존 인덱스 삭제
```

**3단계: 암호화 적용 전 검증**

```java
// 테스트 코드로 암호화된 데이터 크기 확인
@SpringBootTest
class ColumnSizeValidationTest {
    
    @Autowired
    private HubCryptoService hubCryptoService;
    
    @Test
    void testEncryptedDataSize() {
        String originalData = "test@example.com"; // 원본 데이터
        String encrypted = hubCryptoService.encrypt(originalData, "dadp");
        
        System.out.println("원본 크기: " + originalData.length());
        System.out.println("암호화 크기: " + encrypted.length());
        System.out.println("증가율: " + (encrypted.length() * 100.0 / originalData.length()) + "%");
        
        // 암호화된 데이터가 컬럼 크기를 초과하지 않는지 확인
        assert encrypted.length() < 65535; // TEXT 최대 크기
    }
}
```

**4단계: 점진적 마이그레이션 (권장)**

1. **컬럼 확장**: 기존 컬럼 크기 확장
2. **새 데이터 암호화**: 새로운 데이터부터 암호화 적용
3. **기존 데이터 암호화**: 배치 작업으로 기존 데이터 암호화
4. **검증**: 암호화된 데이터 정상 저장 확인

#### 주의사항

- ⚠️ **프로덕션 환경에서는 반드시 백업 후 진행**
- ⚠️ **점진적 마이그레이션 권장** (전체 데이터 한 번에 암호화 시 부하 발생)
- ⚠️ **인덱스 재생성 시 서비스 중단 시간 고려**
- ⚠️ **TEXT 컬럼은 인덱스 생성 시 길이 제한 필요** (MySQL/MariaDB)

#### 문제 해결

**오류: "Data too long for column"**
- 원인: 컬럼 크기가 암호화된 데이터를 담기에 부족
- 해결: 컬럼을 `TEXT`로 변경하거나 크기 확장

**오류: "Index key too long"**
- 원인: TEXT 컬럼에 인덱스 생성 시 길이 제한 초과
- 해결: 인덱스 생성 시 길이 제한 지정 (`email(255)`)

---

## 체크리스트

### 통합 전 확인사항

- [ ] Maven 의존성 추가 완료 (`io.github.daone-dadp:dadp-aop:5.3.0`)
- [ ] Maven Central에서 라이브러리 다운로드 확인
- [ ] `application.properties` 또는 `application.yml` 설정 완료
- [ ] Hub 서버 URL 확인
- [ ] Hub 서버 연결 확인
- [ ] 암호화 정책명 확인 (엔티티 필드에 지정)
- [ ] 마스킹 정책명 확인 (서비스 메서드 또는 DTO 필드에 지정)
- [ ] **컬럼 크기 확장 완료** (기존 데이터 마이그레이션 시 필수)
- [ ] **암호화된 데이터 크기 검증** (테스트 코드로 확인)
- [ ] 테스트 코드 작성 및 검증

### 통합 후 확인사항

- [ ] 애플리케이션 정상 시작 확인
- [ ] 암호화 기능 동작 확인
- [ ] 복호화 기능 동작 확인
- [ ] 로그 확인 (에러 없음)
- [ ] 성능 테스트 (필요시)

---

## 제한사항 및 주의사항

### AOP 제한사항

1. **네이티브 쿼리 지원** ✅
   - `@Query(nativeQuery = true)` 사용 시 `@Decrypt` 어노테이션 정상 적용
   - 반환 타입이 Entity / List<Entity> / Optional<Entity> / Page / Slice / Collection 일 경우,
     일반 메서드와 동일하게 복호화 처리
   - 네이티브 쿼리든 JPQL이든 반환값 처리 방식 동일

2. **@Modifying 쿼리 테스트 중**
   - `@Modifying` + `@Encrypt`: 파라미터 암호화 가능하나 테스트 필요
   - `@Modifying` + `@Decrypt`: 반환값이 없어 의미 없음 (void 또는 int 반환)
   - UPDATE/DELETE 쿼리 파라미터 암호화 검증 필요

3. **EntityManager 직접 사용 미지원**
   - `EntityManager` 직접 호출 시 어노테이션 적용 불가
   - Repository 인터페이스 사용 권장

4. **Stream 반환 타입 제한적 지원** ✅
   - `Stream<T>` 반환 시 `@Decrypt` 어노테이션 적용 가능
   - 내부적으로 Stream 전체를 수집 후 복호화하고, in-memory Stream으로 재생성
   - **주의**: 대량 데이터 조회 시 메모리 사용량 증가 가능
   - JPA의 lazy-stream이 아닌 in-memory Stream이 반환됨
   - 소규모 데이터(1,000개 이하): 문제 없음
   - 중규모 데이터(1,000 ~ 10,000개): 주의 필요 (1-2초 소요)
   - 대규모 데이터(10,000개 이상): **비권장** (메모리 및 시간 부하)
   - 대안: 대량 데이터 조회 시 `Page<T>` 또는 `Slice<T>` 사용 권장
   - **read-only 트랜잭션 지원**: Stream 복호화 시 read-only 트랜잭션에서도 정상 동작 (v3.17.1)
     - Stream을 List로 수집한 직후, 복호화 전에 모든 엔티티를 detach하여 Hibernate의 변경 추적 차단
     - read-only 트랜잭션에서 UPDATE 쿼리 시도 없음
     - `@Transactional(readOnly = true)`와 함께 사용 가능

5. **Page/Slice 반환 타입 지원** ✅
   - `Page<T>`, `Slice<T>` 반환 타입 완전 지원
   - content 자동 복호화 후 Page/Slice 재생성
   - Pageable, totalElements, hasNext 등 메타데이터 보존

---

## 참고사항

- AOP는 Spring AOP 기반이므로 Spring Framework 환경에서만 동작
- 배치 암호화는 하나의 객체 내 여러 필드 암호화 시 자동 사용
- **배치 복호화**: `findAll()` 시 자동 사용 - 여러 엔티티의 복호화를 배치로 처리하여 **성능이 크게 향상됩니다** (개별 복호화 대비 약 3배 이상 빠름)
- `@EncryptField`는 엔티티 필드에만 사용 가능
- `@DecryptField`는 마스킹 정책 지정용으로 사용
- **Collection 및 Iterable 지원**: `saveAll(Iterable<S>)` 메서드의 파라미터는 `Iterable` 타입이며, `Collection`(List, Set 등)과 `Iterable`(Collection이 아닌 경우) 모두 지원됩니다. 실제로는 대부분 `List`를 전달하지만, 안전을 위해 `Iterable`도 처리합니다.

### 배치 처리 지원 현황

#### ✅ 배치 복호화 지원 (성능 최적화)
- **`findAll()`**: AOP가 정상적으로 감지되며, 여러 엔티티의 복호화를 배치로 처리합니다.
  - **동작 방식**: 1000개 엔티티 조회 시 1000번의 개별 복호화 API 호출 대신, 1번의 배치 복호화 API 호출로 처리됩니다.
  - **성능 향상 효과**:
    - 네트워크 오버헤드 대폭 감소 (1000번 → 1번 API 호출)
    - 엔진의 병렬 처리 활용으로 처리 속도 향상
    - **실제 측정 결과**: 개별 복호화 방식 대비 약 **3배 이상 빠른 성능** (예: 1000건 조회 시 개별 복호화 7.15초 → 배치 복호화 2.31초)
  - **권장사항**: 대량 데이터 조회 시 `findAll()`을 사용하면 배치 복호화의 성능 이점을 자동으로 활용할 수 있습니다.

#### ⚠️ 배치 암호화 미지원 (개별 암호화만 가능)
- **`saveAll()`**: Spring Data JPA의 구조적 제약으로 인해 배치 암호화는 불가능합니다.
  - **원인**: Spring Data JPA의 `saveAll()`은 내부적으로 각 엔티티에 대해 `save()`를 호출합니다. 이때 `saveAll()` 자체는 AOP 프록시를 거치지만, 내부의 `save()` 호출은 self-invocation으로 프록시를 거치지 않아 AOP가 `saveAll()`을 감지하지 못합니다.
  - **결과**: `saveAll()` 호출 시 AOP는 개별 `save()` 호출만 감지하여 각 엔티티를 개별적으로 암호화합니다.
  - **동작**: 각 엔티티는 정상적으로 암호화되지만, 배치 처리의 성능 이점은 얻을 수 없습니다.
  - **권장사항**: 대량 데이터 저장 시에도 `saveAll()`을 사용해도 되지만, 각 엔티티별로 개별 암호화 API 호출이 발생합니다.

---

## 📦 배포 정보

### 현재 배포 상태

✅ **Maven Central 배포 완료** (2025-12-29)

- **레포지토리**: [daone-dadp/dadp-client-libraries](https://github.com/daone-dadp/dadp-client-libraries)
- **Maven Central 검색**: [https://search.maven.org/search?q=io.github.daone-dadp](https://search.maven.org/search?q=io.github.daone-dadp)
- **배포 버전**: 
  - `dadp-aop:5.3.0` (Deployment ID: `2f9f91f6-3ecc-4b33-82bf-c6d971500abb`)
  - `dadp-hub-crypto-lib:1.2.0` (Deployment ID: `c38192c9-cc35-42a6-9e76-9da31cfc447b`)
- **라이선스**: Apache 2.0

### 사용 가능한 라이브러리

| 라이브러리 | 그룹 ID | 아티팩트 ID | 버전 | 배포 상태 |
|----------|--------|------------|------|----------|
| AOP 라이브러리 | `io.github.daone-dadp` | `dadp-aop` | `5.3.0` | ✅ 배포 완료 |
| Hub 암복호화 라이브러리 | `io.github.daone-dadp` | `dadp-hub-crypto-lib` | `1.2.0` | ✅ 배포 완료 |
| Spring Boot Starter | `io.github.daone-dadp` | `dadp-aop-spring-boot-starter` | `5.3.0` | ⚠️ 배포 전 |

**💡 사용 권장:**
- `dadp-aop:5.3.0` 사용 권장 (Maven Central 배포 완료)
- `dadp-aop:5.3.0`은 자동으로 `dadp-hub-crypto-lib:1.2.0`을 포함합니다

---

## 📝 릴리즈 정보

### 현재 버전

**v5.3.0** (2025-12-29 배포 완료) ✅

- [릴리즈 노트](./RELEASE_NOTES_v5.3.0.md)
- [변경 내역](./CHANGELOG.md)
- Maven Central 배포 완료

### 주요 변경사항 (v5.3.0)

- ✅ 버전 체계 전환 (3.17.1 → 5.3.0)
- ✅ Engine URL 환경변수 직접 관리 지원
- ✅ Collection 복호화 시 평문 저장 문제 해결
- ✅ 로그 출력 정책 개선

### 이전 버전

- **v3.17.0** (2025-12-09): Engine 직접 연결, 리포지토리 레벨 암복호화 지원
- **v2.1.0** (2025-11-06): Maven Central 배포 완료
- **v2.0.0** (2025-10-17): 초기 릴리즈

---

**작성일**: 2025-12-29  
**버전**: 5.3.0  
**최종 업데이트**: 2025-12-29  
**작성자**: DADP Development Team

