# DADP AOP 및 라이브러리 사용 가이드 (고객사용)

> **고객사를 위한 DADP AOP와 Hub 암복호화 라이브러리 사용 가이드**

## 📋 목차

1. [개요](#개요)
2. [빠른 시작](#빠른-시작)
3. [프로젝트 설정](#프로젝트-설정)
4. [애플리케이션 설정](#애플리케이션-설정)
5. [Hub 연동 설정](#hub-연동-설정)
6. [사용 예시](#사용-예시)
7. [문제 해결](#문제-해결)
8. [체크리스트](#체크리스트)

---

## 개요

DADP는 외부 고객사가 쉽게 암복호화 기능을 통합할 수 있도록 라이브러리를 제공합니다.

### 📦 제공 라이브러리

1. **dadp-hub-crypto-lib** (1.0.0)
   - Hub와의 암복호화 통신을 담당하는 핵심 라이브러리
   - `HubCryptoService`를 통해 암복호화 수행

2. **dadp-aop** (2.0.0)
   - AOP 기반 암복호화 자동화 라이브러리
   - `@Encrypt`, `@Decrypt` 어노테이션 지원

3. **dadp-aop-spring-boot-starter** (2.0.0) ⭐ 권장
   - Spring Boot Starter 패키지
   - 자동 설정 및 의존성 관리
   - 가장 편리한 통합 방법

---

## 빠른 시작

### 1단계: Maven 리포지토리 설정

DADP 라이브러리는 **JitPack**을 통해 배포됩니다 (배포 완료 ✅).

> **배포 상태:** ✅ JitPack 배포 완료 (2025-11-03)  
> **레포지토리:** [https://github.com/daone-dadp/dadp-client-libraries](https://github.com/daone-dadp/dadp-client-libraries)  
> **JitPack 페이지:** [https://jitpack.io/#daone-dadp/dadp-client-libraries](https://jitpack.io/#daone-dadp/dadp-client-libraries)

#### JitPack 설정 (현재 배포 방법)

> **참고:** JitPack(지트팩/짓팩)은 GitHub 저장소를 자동으로 Maven 리포지토리로 변환해주는 서비스입니다.

**특징:**
- ✅ 실제 프로덕션 환경에서 널리 사용됨
- ✅ 많은 오픈소스 프로젝트와 기업에서 사용
- ✅ 설정이 간단
- ✅ 배포 완료 상태 (즉시 사용 가능)

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

### 2단계: 의존성 추가

#### 방법 1: Spring Boot Starter 사용 (권장) ⭐

```xml
<dependencies>
    <!-- DADP AOP Spring Boot Starter (권장) -->
    <dependency>
        <groupId>com.github.daone-dadp</groupId>
        <artifactId>dadp-aop-spring-boot-starter</artifactId>
        <version>v2.0.0</version>
    </dependency>
</dependencies>
```

**주의사항:**
- 그룹 ID: `com.github.daone-dadp` (실제 배포된 레포지토리)
- 버전 형식: `v2.0.0` (v 접두사 필수)
- 첫 다운로드 시 JitPack에서 빌드하므로 시간 소요 가능 (5-10분)
- 이후 빌드는 캐시되어 빠르게 다운로드됨

#### 방법 2: AOP 라이브러리만 사용

```xml
<dependencies>
    <dependency>
        <groupId>com.github.daone-dadp</groupId>
        <artifactId>dadp-aop</artifactId>
        <version>v2.0.0</version>
    </dependency>
</dependencies>
```

#### 방법 3: Hub 암복호화 라이브러리만 사용

```xml
<dependencies>
    <dependency>
        <groupId>com.github.daone-dadp</groupId>
        <artifactId>dadp-hub-crypto-lib</artifactId>
        <version>v1.0.0</version>
    </dependency>
</dependencies>
```

**💡 참고:** 
- 현재 JitPack을 통해 배포 완료되었습니다.
- Maven Central 배포는 추후 계획 중입니다.
- 배포 상태는 [JitPack 페이지](https://jitpack.io/#daone-dadp/dadp-client-libraries)에서 확인 가능합니다.

### 3단계: 설정 파일 추가

`application.properties`:
```properties
# Hub 서버 설정
hub.crypto.base-url=http://your-hub-server:9004
```

### 4단계: 사용하기

```java
@Service
public class UserService {
    
    @Encrypt(policy = "dadp")
    public String getSensitiveData() {
        return "민감한 데이터";
    }
}
```

---

## 프로젝트 설정

### Maven 프로젝트

#### 방법 1: Spring Boot Starter 사용 (권장)

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.github.daone-dadp</groupId>
        <artifactId>dadp-aop-spring-boot-starter</artifactId>
        <version>v2.0.0</version>
    </dependency>
</dependencies>
```

#### 방법 2: AOP 라이브러리만 사용

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.github.daone-dadp</groupId>
        <artifactId>dadp-aop</artifactId>
        <version>v2.0.0</version>
    </dependency>
</dependencies>
```

#### 방법 3: Hub 암복호화 라이브러리만 사용

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.github.daone-dadp</groupId>
        <artifactId>dadp-hub-crypto-lib</artifactId>
        <version>v1.0.0</version>
    </dependency>
</dependencies>
```

---

## 애플리케이션 설정

### application.properties 설정

```properties
# DADP AOP 설정
dadp.aop.enabled=true
dadp.aop.hub-base-url=http://your-hub-server:9004
dadp.aop.default-policy=dadp
dadp.aop.fallback-to-original=true
dadp.aop.enable-logging=true

# Hub 암복호화 라이브러리 설정
hub.crypto.base-url=http://your-hub-server:9004
hub.crypto.timeout=5000
hub.crypto.retry-count=3
hub.crypto.enable-logging=true
hub.crypto.default-policy=dadp
```

### application.yml 설정

```yaml
dadp:
  aop:
    enabled: true
    hub-base-url: http://your-hub-server:9004
    default-policy: dadp
    fallback-to-original: true
    enable-logging: true

hub:
  crypto:
    base-url: http://your-hub-server:9004
    timeout: 5000
    retry-count: 3
    enable-logging: true
    default-policy: dadp
```

### 환경 변수 설정 (권장)

프로덕션 환경에서는 환경 변수를 사용합니다:

```bash
export DADP_AOP_HUB_BASE_URL=http://your-hub-server:9004
export HUB_CRYPTO_BASE_URL=http://your-hub-server:9004
```

```properties
# application.properties에서 환경 변수 참조
dadp.aop.hub-base-url=${DADP_AOP_HUB_BASE_URL:http://localhost:9004}
hub.crypto.base-url=${HUB_CRYPTO_BASE_URL:http://localhost:9004}
```

---

## Hub 연동 설정

### 1. Hub 서버 정보

다음 정보를 DADP 운영팀으로부터 제공받아야 합니다:

- **Hub 서버 URL**: 예) `http://your-hub-server:9004`
- **Hub API 경로**: `/hub/api/v1/encrypt`, `/hub/api/v1/decrypt`
- **인증 토큰** (필요시)
- **암호화 정책명**: 예) `dadp`

### 2. 네트워크 연결 확인

```bash
# Hub 서버 연결 확인
curl http://your-hub-server:9004/hub/actuator/health

# 예상 응답
{"status":"UP"}
```

### 3. 암호화 정책 확인

Hub에서 사용할 암호화 정책을 확인합니다:

```bash
# Hub에서 정책 목록 조회 (예시)
curl http://your-hub-server:9004/hub/api/v1/policies
```

---

## 사용 예시

### 1. AOP 어노테이션 사용 (가장 간단한 방법)

#### 암호화 예시

```java
@Service
public class UserService {
    
    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    
    /**
     * @Encrypt 어노테이션으로 반환값이 자동으로 암호화됩니다.
     */
    @Encrypt(policy = "dadp", enableLogging = true)
    public String getSensitiveData() {
        log.info("민감한 데이터 조회");
        return "민감한 데이터";
    }
    
    /**
     * 암호화된 사용자 정보 반환
     */
    @Encrypt(policy = "dadp")
    public UserDto getUserInfo(Long userId) {
        User user = userRepository.findById(userId);
        return UserDto.builder()
            .id(user.getId())
            .name(user.getName())
            .email(user.getEmail())  // 자동으로 암호화됨
            .phone(user.getPhone())  // 자동으로 암호화됨
            .build();
    }
}
```

#### 복호화 예시

```java
@Service
public class UserService {
    
    /**
     * @Decrypt 어노테이션으로 반환값이 자동으로 복호화됩니다.
     */
    @Decrypt(enableLogging = true)
    public String processEncryptedData(String encryptedData) {
        log.info("암호화된 데이터 처리");
        // 자동으로 복호화되어 전달됨
        return encryptedData;
    }
    
    /**
     * 복호화된 사용자 정보 반환
     */
    @Decrypt
    public UserDto getDecryptedUserInfo(UserDto encryptedUser) {
        // encryptedUser의 암호화된 필드들이 자동으로 복호화됨
        return encryptedUser;
    }
}
```

### 2. HubCryptoService 직접 사용

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

### 3. REST Controller 예시

```java
@RestController
@RequestMapping("/api/users")
public class UserController {
    
    @Autowired
    private UserService userService;
    
    /**
     * 사용자 정보 조회 (자동 암호화)
     */
    @GetMapping("/{userId}")
    public ResponseEntity<UserDto> getUser(@PathVariable Long userId) {
        // UserService의 메서드가 @Encrypt로 되어 있으면 자동 암호화
        UserDto user = userService.getUserInfo(userId);
        return ResponseEntity.ok(user);
    }
    
    /**
     * 암호화된 데이터 저장
     */
    @PostMapping
    public ResponseEntity<String> createUser(@RequestBody UserDto userDto) {
        // 저장 전 복호화가 필요한 경우
        UserDto decryptedUser = userService.getDecryptedUserInfo(userDto);
        userService.saveUser(decryptedUser);
        return ResponseEntity.ok("사용자 생성 완료");
    }
}
```

### 4. 엔티티 필드 암복호화

엔티티 필드에 직접 어노테이션 사용:

```java
@Entity
public class User {
    
    @Id
    private Long id;
    
    private String name;
    
    @EncryptField(policy = "dadp")
    private String email;  // 저장 시 자동 암호화
    
    @EncryptField(policy = "dadp")
    private String phone;  // 저장 시 자동 암호화
    
    @DecryptField
    private String encryptedData;  // 조회 시 자동 복호화
    
    // getters, setters...
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

3. **리포지토리 URL 확인**
   - JitPack: `https://jitpack.io` (현재 사용 중)
   - JitPack 레포지토리 설정이 `pom.xml`에 포함되어 있는지 확인
   - JitPack 빌드 상태는 [빌드 페이지](https://jitpack.io/#daone-dadp/dadp-client-libraries)에서 확인

### 2. AOP가 동작하지 않는 경우

#### 증상
- `@Encrypt`, `@Decrypt` 어노테이션이 작동하지 않음

#### 해결 방법

1. **Spring Boot Starter 사용 확인**
   ```xml
   <repositories>
       <repository>
           <id>jitpack.io</id>
           <url>https://jitpack.io</url>
       </repository>
   </repositories>
   <dependency>
       <groupId>com.github.daone-dadp</groupId>
       <artifactId>dadp-aop-spring-boot-starter</artifactId>
       <version>v2.0.0</version>
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
   hub.crypto.base-url=http://your-hub-server:9004
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

1. **정책명 확인**
   ```java
   @Encrypt(policy = "dadp")  // 정확한 정책명 사용
   ```

2. **Hub에서 정책 목록 확인**
   ```bash
   curl http://your-hub-server:9004/hub/api/v1/policies
   ```

3. **기본 정책 사용**
   ```java
   @Encrypt  // policy 기본값 "dadp" 사용
   ```

### 5. 버전 호환성 문제

#### 증상
```
ClassNotFoundException 또는 MethodNotFoundException
```

#### 해결 방법

1. **버전 일치 확인**
   ```xml
   <!-- 모든 DADP 라이브러리 버전을 일치시킴 -->
   <dependency>
       <groupId>com.github.daone-dadp</groupId>
       <artifactId>dadp-aop-spring-boot-starter</artifactId>
       <version>v2.0.0</version>
   </dependency>
   ```

2. **의존성 트리 확인**
   ```bash
   mvn dependency:tree | grep dadp
   ```

3. **JitPack 빌드 상태 확인**
   - [JitPack 페이지](https://jitpack.io/#daone-dadp/dadp-client-libraries)에서 빌드 상태 확인
   - `v2.0.0` 태그에 초록색 체크마크가 표시되어야 함
   - 빌드 실패 시 페이지에서 오류 로그 확인 가능

---

## 체크리스트

### 통합 전 확인사항

- [ ] Maven 의존성 추가 완료 (`com.github.daone-dadp:dadp-aop-spring-boot-starter:v2.0.0`)
- [ ] JitPack 리포지토리 설정 완료 (`https://jitpack.io`)
- [ ] JitPack 빌드 상태 확인 ([빌드 페이지](https://jitpack.io/#daone-dadp/dadp-client-libraries))
- [ ] `application.properties` 또는 `application.yml` 설정 완료
- [ ] Hub 서버 URL 확인
- [ ] Hub 서버 연결 확인
- [ ] 암호화 정책명 확인
- [ ] 테스트 코드 작성 및 검증

### 통합 후 확인사항

- [ ] 애플리케이션 정상 시작 확인
- [ ] 암호화 기능 동작 확인
- [ ] 복호화 기능 동작 확인
- [ ] 로그 확인 (에러 없음)
- [ ] 성능 테스트 (필요시)

---

## 📦 배포 정보

### 현재 배포 상태

✅ **JitPack 배포 완료** (2025-11-03)

- **레포지토리**: [daone-dadp/dadp-client-libraries](https://github.com/daone-dadp/dadp-client-libraries)
- **JitPack 페이지**: [https://jitpack.io/#daone-dadp/dadp-client-libraries](https://jitpack.io/#daone-dadp/dadp-client-libraries)
- **배포 버전**: `v2.0.0`
- **라이선스**: Apache 2.0

### 사용 가능한 라이브러리

| 라이브러리 | 그룹 ID | 아티팩트 ID | 버전 |
|----------|---------|------------|------|
| Spring Boot Starter | `com.github.daone-dadp` | `dadp-aop-spring-boot-starter` | `v2.0.0` |
| AOP 라이브러리 | `com.github.daone-dadp` | `dadp-aop` | `v2.0.0` |
| Hub 암복호화 라이브러리 | `com.github.daone-dadp` | `dadp-hub-crypto-lib` | `v1.0.0` |

### 빠른 시작 예제

```xml
<!-- pom.xml -->
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.github.daone-dadp</groupId>
        <artifactId>dadp-aop-spring-boot-starter</artifactId>
        <version>v2.0.0</version>
    </dependency>
</dependencies>
```

```properties
# application.properties
hub.crypto.base-url=http://your-hub-server:9004
```

---

**작성일**: 2025-11-03  
**버전**: 2.0.0  
**최종 업데이트**: 2025-11-03 (JitPack 배포 완료 반영)  
**작성자**: DADP Development Team

