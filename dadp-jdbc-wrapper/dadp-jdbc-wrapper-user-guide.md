# DADP JDBC Wrapper 사용 가이드

> **고객사를 위한 DADP JDBC Wrapper 사용 가이드**

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

---

## 개요

DADP JDBC Wrapper는 JDBC 드라이버 레벨에서 동작하는 암복호화 프록시 드라이버입니다.  
**코드 수정 없이** JDBC URL만 변경하여 자동 암복호화를 제공합니다.

### 📦 제공 라이브러리

1. **dadp-jdbc-wrapper** (4.17.0)
   - JDBC Wrapper Driver (Fat JAR)
   - 모든 JDBC 호환 드라이버에서 동작
   - 코드 수정 없이 JDBC URL만 변경

### 특징

- ✅ **코드 수정 불필요**: JDBC URL만 변경
- ✅ **자동 암복호화**: PreparedStatement 파라미터 자동 암호화
- ✅ **자동 복호화**: ResultSet 조회 시 자동 복호화
- ✅ **모든 JDBC 드라이버 지원**: MySQL, PostgreSQL, Oracle, MariaDB 등
- ✅ **SQL 파싱**: 테이블/컬럼 자동 인식

---

## 빠른 시작

### 1단계: Maven 리포지토리 설정

DADP 라이브러리는 **Maven Central**을 통해 배포됩니다 (배포 완료 ✅).

> **배포 상태:** ✅ Maven Central 배포 완료  
> **Group ID:** `io.github.daone-dadp`  
> **레포지토리:** [https://github.com/daone-dadp/dadp-jdbc-wrapper](https://github.com/daone-dadp/dadp-jdbc-wrapper)  
> **Maven Central 검색:** [https://search.maven.org/search?q=io.github.daone-dadp](https://search.maven.org/search?q=io.github.daone-dadp)

#### Maven Central 설정 (권장) ⭐

**Maven Central은 별도의 리포지토리 설정이 필요 없습니다!**

```xml
<!-- 리포지토리 설정 불필요 - Maven Central은 기본 리포지토리 -->
```

### 2단계: 의존성 추가

```xml
<dependencies>
    <dependency>
        <groupId>io.github.daone-dadp</groupId>
        <artifactId>dadp-jdbc-wrapper</artifactId>
        <version>4.17.0</version>
        <classifier>all</classifier>
    </dependency>
</dependencies>
```

**주의사항:**
- `classifier`를 `all`로 지정해야 합니다 (Fat JAR)
- 실제 DB 드라이버는 별도로 추가해야 합니다

### 3단계: DB 드라이버 추가

Wrapper JAR에는 DB 드라이버가 포함되지 않습니다. 필요한 DB 드라이버를 별도로 추가하세요.

#### MySQL 사용 시

```xml
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <version>8.0.33</version>
</dependency>
```

#### PostgreSQL 사용 시

```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.6.0</version>
</dependency>
```

#### Oracle 사용 시

```xml
<dependency>
    <groupId>com.oracle.database.jdbc</groupId>
    <artifactId>ojdbc8</artifactId>
    <version>21.7.0.0</version>
</dependency>
```

### 4단계: JDBC URL 변경

**기존 JDBC URL:**
```properties
spring.datasource.url=jdbc:mysql://localhost:3306/mydb
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
```

**변경 후 (코드 수정 없음):**
```properties
spring.datasource.url=jdbc:dadp:mysql://localhost:3306/mydb
spring.datasource.driver-class-name=com.dadp.jdbc.DadpJdbcDriver
```

### 5단계: Hub 설정 추가

```properties
# Hub 서버 설정
dadp.proxy.hub-url=http://localhost:9004
dadp.proxy.instance-id=proxy-1
```

**설정 우선순위:**
1. 시스템 프로퍼티 (`-Ddadp.proxy.hub-url=...`) - 최우선
2. 환경 변수 (`DADP_HUB_BASE_URL`)
3. JDBC URL 파라미터 (`hubUrl=...`)
4. 기본값 (`http://localhost:9004`)

---

## 프로젝트 설정

### Maven 프로젝트

```xml
<dependencies>
    <!-- DADP JDBC Wrapper -->
    <dependency>
        <groupId>io.github.daone-dadp</groupId>
        <artifactId>dadp-jdbc-wrapper</artifactId>
        <version>4.17.0</version>
        <classifier>all</classifier>
    </dependency>
    
    <!-- 실제 DB 드라이버 (예: MySQL) -->
    <dependency>
        <groupId>com.mysql</groupId>
        <artifactId>mysql-connector-j</artifactId>
        <version>8.0.33</version>
    </dependency>
</dependencies>
```

### Gradle 프로젝트

```gradle
dependencies {
    // DADP JDBC Wrapper
    implementation 'io.github.daone-dadp:dadp-jdbc-wrapper:4.17.0:all'
    
    // 실제 DB 드라이버 (예: MySQL)
    implementation 'com.mysql:mysql-connector-j:8.0.33'
}
```

---

## 애플리케이션 설정

### application.properties 설정

```properties
# JDBC URL 변경 (코드 수정 없음)
spring.datasource.url=jdbc:dadp:mysql://localhost:3306/mydb
spring.datasource.driver-class-name=com.dadp.jdbc.DadpJdbcDriver
spring.datasource.username=root
spring.datasource.password=1234

# Hub 서버 설정
dadp.proxy.hub-url=http://localhost:9004
dadp.proxy.instance-id=proxy-1

# Wrapper 설정 (선택)
dadp.proxy.enable-logging=true
dadp.proxy.schema-sync-enabled=true
```

**참고**: Hub URL 설정 우선순위는 위의 "설정 우선순위" 섹션을 참조하세요.

### application.yml 설정

```yaml
spring:
  datasource:
    url: jdbc:dadp:mysql://localhost:3306/mydb
    driver-class-name: com.dadp.jdbc.DadpJdbcDriver
    username: root
    password: 1234

dadp:
  proxy:
    hub-url: http://localhost:9004
    instance-id: proxy-1
    enable-logging: true
    schema-sync-enabled: true
```

### 환경 변수 설정 (권장)

프로덕션 환경에서는 환경 변수를 사용합니다:

```bash
export DADP_HUB_BASE_URL=http://your-hub-server:9004
```

```properties
# application.properties에서 환경 변수 참조
dadp.proxy.hub-url=${DADP_HUB_BASE_URL:http://localhost:9004}
```

### 설정 우선순위

Hub URL 설정은 다음 우선순위로 적용됩니다:

1. **시스템 프로퍼티** (`-D` 옵션) - 최우선
   ```bash
   java -Ddadp.proxy.hub-url=http://your-hub:9004 -jar app.jar
   ```

2. **환경 변수** (`DADP_HUB_BASE_URL` > `DADP_PROXY_HUB_URL`)
   ```bash
   export DADP_HUB_BASE_URL=http://your-hub:9004
   ```

3. **JDBC URL 파라미터** (`hubUrl=...`)
   ```properties
   spring.datasource.url=jdbc:dadp:mysql://localhost:3306/mydb?hubUrl=http://your-hub:9004
   ```

4. **기본값** (`http://localhost:9004`)

**참고**: Docker 컨테이너 내부에서는 서비스 이름을 사용해야 합니다:
- `http://dadp-hub:9004` (Docker 네트워크 내)
- `http://localhost:9004` (호스트에서 실행 시)

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

### 1. Spring Boot JPA 사용 (권장) ⭐

**코드 수정 없이 JDBC URL만 변경하면 자동으로 암복호화가 적용됩니다.**

#### application.properties

```properties
# JDBC URL 변경
spring.datasource.url=jdbc:dadp:mysql://localhost:3306/mydb
spring.datasource.driver-class-name=com.dadp.jdbc.DadpJdbcDriver

# Hub 설정
dadp.proxy.hub-url=http://localhost:9004
```

#### 서비스 사용

```java
@Service
public class UserService {
    @Autowired
    private UserRepository userRepository;
    
    public User createUser(String name, String email, String phone) {
        User user = new User(name, email, phone);
        return userRepository.save(user);  // 자동 암호화
    }
    
    public Optional<User> getUser(Long id) {
        return userRepository.findById(id);  // 자동 복호화
    }
}
```

### 2. 순수 JDBC 사용

```java
// JDBC URL만 변경
String url = "jdbc:dadp:mysql://localhost:3306/mydb";
Connection conn = DriverManager.getConnection(url, "root", "1234");

// INSERT - 자동 암호화
PreparedStatement ps = conn.prepareStatement(
    "INSERT INTO users (name, email, phone) VALUES (?, ?, ?)");
ps.setString(1, "홍길동");
ps.setString(2, "email@example.com");  // ← 자동 암호화
ps.setString(3, "010-1234-5678");      // ← 자동 암호화
ps.executeUpdate();

// SELECT - 자동 복호화
PreparedStatement ps2 = conn.prepareStatement(
    "SELECT email, phone FROM users WHERE id = ?");
ps2.setLong(1, 1L);
ResultSet rs = ps2.executeQuery();
if (rs.next()) {
    String email = rs.getString("email");  // ← 자동 복호화
    String phone = rs.getString("phone");  // ← 자동 복호화
}
```

### 3. MyBatis 사용

**MyBatis 설정 파일 수정:**

```xml
<!-- mybatis-config.xml -->
<configuration>
    <environments default="development">
        <environment id="development">
            <transactionManager type="JDBC"/>
            <dataSource type="POOLED">
                <property name="driver" value="com.dadp.jdbc.DadpJdbcDriver"/>
                <property name="url" value="jdbc:dadp:mysql://localhost:3306/mydb"/>
                <property name="username" value="root"/>
                <property name="password" value="1234"/>
            </dataSource>
        </environment>
    </environments>
</configuration>
```

**Mapper XML (코드 수정 불필요):**

```xml
<!-- UserMapper.xml -->
<insert id="insertUser">
    INSERT INTO users (name, email, phone) VALUES (#{name}, #{email}, #{phone})
    <!-- email, phone은 자동 암호화됨 -->
</insert>

<select id="selectUser" resultType="User">
    SELECT * FROM users WHERE id = #{id}
    <!-- email, phone은 자동 복호화됨 -->
</select>
```

---

## 지원 명령어

### ✅ 지원

1. **PreparedStatement.setString(int, String)**
   - INSERT/UPDATE 시 String 파라미터 암호화 처리
   - 정책 확인 후 암호화 수행

2. **PreparedStatement.setObject(int, Object)** (String 타입인 경우)
   - String 타입인 경우 `setString()`과 동일한 암호화 로직 적용
   - `instanceof String` 체크 후 처리
   - JPA/Hibernate에서 자동으로 처리됨

3. **PreparedStatement.setObject(int, Object, int)** (String 타입인 경우)
   - String 타입인 경우 `setString()`과 동일한 암호화 로직 적용
   - 타입 지정 버전

4. **PreparedStatement.setNString(int, String)**
   - `setString()`과 동일한 암호화 로직 적용
   - 공통 메서드(`processStringEncryption()`)로 재사용

5. **PreparedStatement.executeUpdate()**
   - 암호화된 파라미터로 UPDATE/INSERT 실행
   - Data truncation 재시도 지원

6. **PreparedStatement.executeQuery()**
   - SELECT 실행 후 ResultSet 래핑하여 복호화 처리

7. **ResultSet.getString(int) / getString(String)**
   - 컬럼 조회 시 정책 확인 후 복호화 처리

9. **ResultSet.getObject(int) / getObject(String)** (String 타입인 경우)
   - String 타입인 경우 복호화 처리

10. **ResultSet.getObject(int, Class<T>) / getObject(String, Class<T>)** (String 타입인 경우)
    - String 타입인 경우 복호화 처리

11. **Statement.executeQuery(String sql)**
    - SELECT 실행 후 ResultSet 래핑하여 복호화 처리

12. **Statement.getResultSet()**
    - ResultSet 래핑하여 복호화 처리

13. **Connection.prepareStatement(String sql)**
    - PreparedStatement 래핑하여 암호화/복호화 지원

14. **Connection.createStatement()**
    - Statement 래핑하여 복호화 지원

### ❌ 미지원

1. **Statement.executeUpdate(String sql)**
   - **사유**: SQL 문자열이 이미 완성되어 있어 Wrapper가 파라미터를 추출할 수 없음. 플레이스홀더(`?`)가 없어 파라미터 위치 추적 불가. `executeUpdate(String sql)` 호출 시점에는 이미 SQL 문이 완성되어 `setString()` 같은 바인딩 메서드가 없어 가로채기 불가. **PreparedStatement 사용 필수**

2. **Statement.execute(String sql)**
   - **사유**: SQL 문자열이 이미 완성되어 있어 Wrapper가 파라미터를 추출할 수 없음. 플레이스홀더(`?`)가 없어 파라미터 위치 추적 불가. `execute(String sql)` 호출 시점에는 이미 SQL 문이 완성되어 `setString()` 같은 바인딩 메서드가 없어 가로채기 불가. **PreparedStatement 사용 필수**

3. **Connection.prepareCall(String sql)** (CallableStatement)
   - **사유**: CallableStatement 래핑 미구현 (구현 가능하나 아직 구현되지 않음). `prepareCall()`은 현재 실제 `CallableStatement`를 그대로 반환하여 `DadpProxyCallableStatement` 같은 래퍼 클래스가 존재하지 않음. 기술적으로는 `DadpProxyPreparedStatement`와 유사한 방식으로 래핑 가능하나, 저장 프로시저 호출의 SQL 파싱 복잡도로 인해 아직 구현되지 않음. 현재 상태에서는 IN 파라미터 암호화 불가능, OUT 파라미터 복호화 불가능, ResultSet 복호화 불가능

4. **배치 암호화** (PreparedStatement.addBatch() + executeBatch())
   - **사유**: JDBC PreparedStatement 구조적 제약으로 인해 여러 파라미터를 한번에 배치 암호화 불가. `setString()` 호출 시점에 즉시 암호화 처리되어 배치 암호화 불가. 
   **개별 암호화는 지원** (각 파라미터별 암호화 처리됨)

5. **배치 복호화** (ResultSet 여러 행 조회)
   - **사유**: ResultSet이 스트리밍 방식으로 동작하여 여러 행을 한번에 배치 복호화 불가 (JDBC ResultSet 구조적 제약). **개별 복호화는 지원** (각 행/컬럼별 복호화 처리됨)

### 🧪 테스트 중

(현재 테스트 중인 항목 없음)

---

### 상세 설명

### PreparedStatement란?

#### 정의

**PreparedStatement**는 JDBC에서 제공하는 인터페이스로, **미리 컴파일된 SQL 문**을 실행하는 데 사용됩니다.

#### 특징

1. **파라미터 바인딩**: `?` 플레이스홀더를 사용하여 값을 동적으로 바인딩
2. **SQL 파싱 최적화**: SQL 문이 미리 파싱되어 성능 향상
3. **SQL Injection 방지**: 파라미터가 자동으로 이스케이프 처리되어 보안 강화
4. **재사용 가능**: 동일한 SQL 문을 여러 번 실행할 때 효율적

#### 플레이스홀더(Placeholder)란?

**플레이스홀더**는 SQL 문에서 **나중에 실제 값으로 대체될 위치**를 표시하는 기호입니다.

- **기호**: `?` (물음표)
- **역할**: SQL 문을 미리 작성하고, 실행 시점에 실제 값을 바인딩
- **장점**: 
  - SQL Injection 방지 (값이 자동으로 이스케이프 처리됨)
  - 성능 향상 (SQL 문이 미리 파싱됨)
  - 재사용 가능 (같은 SQL 문을 여러 번 실행 가능)

#### 플레이스홀더 사용 예시

```java
// 플레이스홀더 사용 (권장)
String sql = "INSERT INTO users (name, email, phone) VALUES (?, ?, ?)";
//                                              ↑      ↑      ↑
//                                          플레이스홀더 1, 2, 3

PreparedStatement ps = conn.prepareStatement(sql);
ps.setString(1, "홍길동");              // 첫 번째 ?에 "홍길동" 바인딩
ps.setString(2, "email@example.com");  // 두 번째 ?에 "email@example.com" 바인딩
ps.setString(3, "010-1234-5678");      // 세 번째 ?에 "010-1234-5678" 바인딩
ps.executeUpdate();

// 실행되는 실제 SQL:
// INSERT INTO users (name, email, phone) VALUES ('홍길동', 'email@example.com', '010-1234-5678')
```

#### PreparedStatement가 왜 필수인가?

Wrapper는 **JDBC 드라이버 레벨에서 동작**하며, 다음과 같은 방식으로 암호화를 처리합니다:

1. **Connection 래핑**: `Connection.prepareStatement()` 호출 시 `DadpProxyPreparedStatement` 반환
2. **SQL 파싱**: SQL 문을 분석하여 테이블명, 컬럼명, 파라미터 위치 추출
3. **메서드 가로채기**: `setString()` 호출 시 암호화 처리 후 실제 DB 드라이버에 전달
4. **정책 확인**: 테이블.컬럼 → 정책명 매핑 확인 후 암호화 수행

**PreparedStatement가 필수인 이유:**
- `setString(1, value)` → 첫 번째 `?` 플레이스홀더에 해당하는 컬럼 확인 가능
- SQL 파싱으로 `parameterIndex → columnName` 매핑 생성
- `PreparedStatement` 인터페이스를 구현하여 `setString()` 메서드 오버라이드 가능
- `Statement.executeUpdate(String sql)`은 SQL 문자열을 직접 실행하므로 가로채기 불가

### Statement는 언제 사용할 수 있고, 언제 사용할 수 없는가?

Wrapper는 **Statement도 래핑**하지만, **기능별로 지원 범위가 다릅니다**:

| 기능 | Statement 사용 가능? | 이유 |
|------|---------------------|------|
| **SELECT (조회)** | ✅ **사용 가능** | `Statement.executeQuery()` → `DadpProxyResultSet` 반환하여 **복호화 지원** |
| **INSERT/UPDATE (저장)** | ❌ **사용 불가** | `Statement.executeUpdate()` → SQL 문자열 직접 실행하여 **암호화 불가** |

#### Statement 사용 현황

**일반적으로 금융권이나 고객사에서는:**

1. **PreparedStatement 권장 (대부분의 경우)**
   - 보안: SQL Injection 방지
   - 성능: 쿼리 파싱 최적화
   - 표준: 업계 모범 사례
   - **대부분의 현대적인 애플리케이션은 PreparedStatement 사용**

2. **Statement 사용 (드물지만 존재)**
   - 레거시 시스템: 오래된 코드베이스
   - 동적 쿼리: 복잡한 동적 SQL 생성이 필요한 경우
   - 특수 요구사항: 특정 프레임워크나 라이브러리 제약
   - **하지만 보안 취약점과 성능 이슈로 인해 권장되지 않음**

**금융권/보안이 중요한 환경:**
- **PreparedStatement 사용이 더욱 강력히 권장됨**
- 보안 감사에서 Statement 사용 시 경고 또는 거부될 수 있음
- SQL Injection 공격 방지를 위해 PreparedStatement 필수

**Wrapper 사용 시:**
- **INSERT/UPDATE는 PreparedStatement 필수** (Statement 사용 시 암호화 미지원)
- SELECT는 Statement 또는 PreparedStatement 모두 사용 가능 (복호화 지원)

**SELECT는 Statement 사용 가능 (복호화 지원):**
```java
// ✅ Statement로 SELECT 사용 가능 (복호화 지원)
Statement stmt = conn.createStatement();
ResultSet rs = stmt.executeQuery("SELECT email, phone FROM users WHERE id = 1");
if (rs.next()) {
    String email = rs.getString("email");  // ← 자동 복호화 처리됨
    String phone = rs.getString("phone");  // ← 자동 복호화 처리됨
}
```

**INSERT/UPDATE는 Statement 사용 불가 (암호화 미지원):**
```java
// ❌ Statement로 INSERT/UPDATE 사용 불가 (암호화 미지원)
Statement stmt = conn.createStatement();
stmt.executeUpdate("INSERT INTO users (name, email, phone) VALUES ('홍길동', 'email@example.com', '010-1234-5678')");
// → SQL 문자열이 이미 완성되어 있어 Wrapper가 암호화할 수 없음
// → email, phone이 평문으로 저장됨 (암호화되지 않음)
```

**요약:**
- **조회(SELECT)**: Statement 또는 PreparedStatement 모두 사용 가능
- **저장(INSERT/UPDATE)**: **PreparedStatement 필수** (Statement는 암호화 미지원)

#### setObject()는 왜 안 되는가?

**setObject()**는 `PreparedStatement`에서 **모든 타입의 값을 바인딩**할 수 있는 범용 메서드입니다:

```java
// setObject 사용 예시
ps.setObject(1, "email@example.com");           // String
ps.setObject(2, 12345);                          // Integer
ps.setObject(3, new Date());                     // Date
ps.setObject(4, new BigDecimal("123.45"));     // BigDecimal
```

#### setObject()는 어디서 사용되나?

**setObject()는 주로 ORM 프레임워크에서 사용됩니다:**

1. **JPA/Hibernate**
   - JPA의 `EntityManager`나 Hibernate의 `Session`이 내부적으로 `PreparedStatement`를 사용할 때
   - `entityManager.persist()`, `repository.save()` 등이 내부적으로 `setObject()` 호출
   - 예: `User user = new User(); user.setEmail("email@example.com"); repository.save(user);`
   - → Hibernate가 내부적으로 `PreparedStatement.setObject(1, "email@example.com")` 호출

2. **MyBatis**
   - MyBatis가 SQL 매핑을 처리할 때 다양한 타입의 파라미터를 바인딩하기 위해 사용
   - 예: `#{email}` 파라미터가 String이든 Integer든 상관없이 `setObject()` 사용

3. **고객사 애플리케이션 직접 사용**
   - 고객사가 직접 `PreparedStatement`를 사용할 때 타입을 모르는 경우
   - 예: 동적으로 타입이 결정되는 경우

**실제 사용 예시:**

```java
// JPA/Hibernate 사용 시 (내부적으로 setObject() 호출)
@Entity
public class User {
    private String email;
    private String phone;
}

// 고객사 코드
User user = new User();
user.setEmail("email@example.com");  // ← 내부적으로 setObject() 호출될 수 있음
user.setPhone("010-1234-5678");      // ← 내부적으로 setObject() 호출될 수 있음
userRepository.save(user);            // ← Hibernate가 setObject() 사용
```

**setObject() 동작 방식:**

Wrapper는 `setObject()` 호출 시 **암호화 대상인지 확인 후 암호화 처리**를 시도합니다:

1. **String 타입 확인**
   - `instanceof String` 체크로 String 타입인지 확인
   - String이 아니면 원본 그대로 전달 (Integer, Date 등은 암호화하지 않음)

2. **암호화 대상 확인**
   - 테이블명, 컬럼명 확인
   - SELECT 문의 WHERE 절 파라미터는 암호화하지 않음 (부분 암호화 검색 지원)
   - **정책 확인**: `PolicyResolver.resolvePolicy()`로 정책 매핑 확인
   - **정책이 있는 경우에만 암호화 수행** (정책이 없으면 평문 그대로 전달)

3. **암호화 처리**
   - 정책이 확인된 경우에만 `setString()`과 동일한 암호화 로직 적용
   - 암호화된 값으로 `setObject()` 호출

4. **실패 처리**
   - 암호화 실패 시 Fail-open 모드면 평문으로 저장
   - Fail-closed 모드면 예외 발생

**구현 코드:**

```java
@Override
public void setObject(int parameterIndex, Object x) throws SQLException {
    // 1. String 타입 확인
    if (x instanceof String) {
        String stringValue = (String) x;
        
        // 2. 테이블명, 컬럼명 확인
        String columnName = parameterToColumnMap.get(parameterIndex);
        String tableName = sqlParseResult.getTableName();
        
        // 3. 정책 확인 (암호화 대상인지 확인)
        String policyName = policyResolver.resolvePolicy(datasourceId, schemaName, tableName, columnName);
        
        // 4. 정책이 있는 경우에만 암호화 수행
        if (policyName != null) {
            String encrypted = cryptoAdapter.encrypt(stringValue, policyName);
            actualPreparedStatement.setObject(parameterIndex, encrypted);
            return;
        }
        // 정책이 없으면 평문 그대로 전달
    }
    
    // String이 아니거나 정책이 없는 경우 원본 그대로 전달
    actualPreparedStatement.setObject(parameterIndex, x);
}
```

**암호화 대상 확인 조건:**

1. ✅ **String 타입** (`instanceof String`)
2. ✅ **INSERT/UPDATE 쿼리** (SELECT WHERE 절은 암호화하지 않음)
3. ✅ **정책 매핑 존재** (`policyName != null`)
   - Hub에서 테이블.컬럼 → 정책명 매핑이 설정되어 있어야 함
   - 정책 매핑이 없으면 평문 그대로 저장

**사용 예시:**

```java
// ✅ setString() 사용 (권장)
PreparedStatement ps = conn.prepareStatement(
    "INSERT INTO users (email, phone) VALUES (?, ?)");
ps.setString(1, "email@example.com");  // 암호화 처리됨
ps.setString(2, "010-1234-5678");      // 암호화 처리됨
ps.executeUpdate();

// ✅ setObject() 사용 (String 타입인 경우 암호화 처리됨)
PreparedStatement ps = conn.prepareStatement(
    "INSERT INTO users (email, phone) VALUES (?, ?)");
ps.setObject(1, "email@example.com");  // String 타입 → 암호화 처리됨
ps.setObject(2, "010-1234-5678");      // String 타입 → 암호화 처리됨
ps.executeUpdate();

// ⚠️ setObject() 사용 (String이 아닌 경우 암호화 처리 안 됨)
PreparedStatement ps = conn.prepareStatement(
    "INSERT INTO users (id, email) VALUES (?, ?)");
ps.setObject(1, 12345);                 // Integer 타입 → 암호화 처리 안 됨 (정상)
ps.setObject(2, "email@example.com");  // String 타입 → 암호화 처리됨
ps.executeUpdate();
```

**JPA/Hibernate 사용 시:**

JPA/Hibernate가 내부적으로 `setObject()`를 사용하더라도, String 타입인 경우 자동으로 암호화 처리됩니다:

```java
// JPA/Hibernate 사용 시 (내부적으로 setObject() 호출)
User user = new User();
user.setEmail("email@example.com");  // ← Hibernate가 setObject(1, "email@example.com") 호출
user.setPhone("010-1234-5678");      // ← Hibernate가 setObject(2, "010-1234-5678") 호출
userRepository.save(user);            // ← String 타입이므로 자동 암호화 처리됨
```

---

## 미지원 명령어 상세 설명

### Statement.executeUpdate(String sql) / execute(String sql) 암호화 불가능한 이유

**암호화가 불가능한 이유:**

1. **SQL 문자열 직접 실행**
   - `executeUpdate("INSERT INTO users VALUES ('홍길동', 'email@example.com')")` 
   - SQL 문이 이미 완성된 문자열 형태로 전달됨
   - Wrapper가 파라미터를 추출할 수 없음

2. **플레이스홀더 없음**
   - `?` 플레이스홀더가 없어 파라미터 위치 추적 불가
   - 값과 컬럼의 매핑 관계를 파악할 수 없음

3. **메서드 가로채기 불가**
   - `executeUpdate(String sql)` 호출 시점에는 이미 SQL 문이 완성됨
   - `setString()` 같은 바인딩 메서드가 없어 가로채기 불가
   - Wrapper가 암호화를 처리할 수 있는 시점이 없음

**해결 방법:**

**PreparedStatement 사용 필수:**

```java
// ❌ Statement 사용 (암호화 불가능)
Statement stmt = conn.createStatement();
stmt.executeUpdate("INSERT INTO users (email, phone) VALUES ('email@example.com', '010-1234-5678')");
// → email, phone이 평문으로 저장됨 (암호화되지 않음)

// ✅ PreparedStatement 사용 (암호화 가능)
PreparedStatement ps = conn.prepareStatement(
    "INSERT INTO users (email, phone) VALUES (?, ?)");
ps.setString(1, "email@example.com");  // ← 암호화 처리됨
ps.setString(2, "010-1234-5678");      // ← 암호화 처리됨
ps.executeUpdate();
```

### CallableStatement 암호화/복호화 미지원 이유

**CallableStatement 암호화/복호화 미지원 이유:**

1. **래핑 미구현**
   - `prepareCall()`은 실제 `CallableStatement`를 그대로 반환
   - `DadpProxyCallableStatement` 같은 래퍼 클래스가 존재하지 않음
   - Wrapper가 `CallableStatement`의 메서드를 가로챌 수 없음

2. **IN 파라미터 암호화 불가능**
   ```java
   CallableStatement cs = conn.prepareCall("{call insert_user(?, ?)}");
   cs.setString(1, "email@example.com");  // ← 암호화되지 않음 (래핑되지 않음)
   cs.setString(2, "010-1234-5678");      // ← 암호화되지 않음 (래핑되지 않음)
   cs.execute();
   ```

3. **OUT 파라미터 복호화 불가능**
   ```java
   CallableStatement cs = conn.prepareCall("{call get_user(?, ?)}");
   cs.setInt(1, 123);
   cs.registerOutParameter(2, Types.VARCHAR);
   cs.execute();
   String email = cs.getString(2);  // ← 복호화되지 않음 (래핑되지 않음)
   ```

4. **ResultSet 복호화 불가능**
   ```java
   CallableStatement cs = conn.prepareCall("{call get_users()}");
   cs.execute();
   ResultSet rs = cs.getResultSet();  // ← 래핑되지 않아 복호화 불가능
   ```

**해결 방법:**

현재는 `CallableStatement` 대신 `PreparedStatement`를 사용하거나, 저장 프로시저를 직접 호출하는 대신 일반 SQL 쿼리를 사용해야 합니다.

```java
// ❌ CallableStatement 사용 (암호화/복호화 불가능)
CallableStatement cs = conn.prepareCall("{call insert_user(?, ?)}");
cs.setString(1, "email@example.com");
cs.execute();

// ✅ PreparedStatement 사용 (암호화 가능)
PreparedStatement ps = conn.prepareStatement("INSERT INTO users (email) VALUES (?)");
ps.setString(1, "email@example.com");  // ← 암호화 처리됨
ps.executeUpdate();
```

---

## 미지원 명령어 (확인됨)

> **참고**: 지원되는 명령어는 위의 ["✅ 지원"](#-지원) 섹션을 참조하세요.

#### ❌ **배치 암호화 미지원 (구조적 제약)**

| 기능 | 설명 | 상태 |
|------|------|------|
| `PreparedStatement.addBatch()` + `executeBatch()` | 배치 INSERT | ✅ **개별 암호화 지원** - 각 파라미터별 암호화 처리됨<br>❌ **배치 암호화 미지원** - JDBC PreparedStatement 구조적 제약으로 인해 여러 파라미터를 한번에 배치 암호화 불가 |
| 하나의 row 내 여러 필드 배치 암호화 | 단일 INSERT 내 여러 컬럼 | ✅ **개별 암호화 지원** - 각 필드별 암호화 처리됨<br>❌ **배치 암호화 미지원** - `setString()` 호출 시점에 즉시 암호화 처리되어 배치 암호화 불가 |

#### ❌ **배치 복호화 미지원 (구조적 제약)**

| 기능 | 설명 | 상태 |
|------|------|------|
| `findAll()` 등 여러 행 조회 | 배치 복호화 | ✅ **개별 복호화 지원** - 각 행/컬럼별 복호화 처리됨<br>❌ **배치 복호화 미지원** - ResultSet이 스트리밍 방식으로 동작하여 여러 행을 한번에 배치 복호화 불가 (JDBC ResultSet 구조적 제약) |

### 사용 권장사항

#### ✅ **권장 사용법**

```java
// ✅ 권장: PreparedStatement 사용
PreparedStatement ps = conn.prepareStatement(
    "INSERT INTO users (name, email, phone) VALUES (?, ?, ?)");
ps.setString(1, "홍길동");
ps.setString(2, "email@example.com");  // ← 자동 암호화
ps.setString(3, "010-1234-5678");      // ← 자동 암호화
ps.executeUpdate();

// ✅ 권장: PreparedStatement로 조회
PreparedStatement ps = conn.prepareStatement(
    "SELECT email, phone FROM users WHERE id = ?");
ps.setLong(1, 1L);
ResultSet rs = ps.executeQuery();
if (rs.next()) {
    String email = rs.getString("email");  // ← 자동 복호화
    String phone = rs.getString("phone");  // ← 자동 복호화
}
```

#### ❌ **비권장 사용법**

```java
// ❌ 비권장: Statement로 INSERT/UPDATE 사용 (암호화 미지원)
Statement stmt = conn.createStatement();
stmt.executeUpdate("INSERT INTO users VALUES ('홍길동', 'email@example.com', '010-1234-5678')");
// 암호화되지 않음!

// ✅ setObject 사용 (String 타입인 경우 암호화 처리됨)
ps.setObject(1, "email@example.com");  // String 타입 → 암호화 처리됨
// → JPA/Hibernate 사용 시 자동으로 처리됨
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
   - Maven Central은 별도 리포지토리 설정이 필요 없습니다

2. **의존성 다운로드 강제 실행**
   ```bash
   mvn clean install -U
   ```

3. **Maven Central 확인**
   - Group ID: `io.github.daone-dadp`
   - Maven Central 검색: https://search.maven.org/search?q=io.github.daone-dadp

### 2. Driver를 찾을 수 없는 경우

#### 증상
```
java.sql.SQLException: No suitable driver found for jdbc:dadp:mysql://...
```

#### 해결 방법

1. **Driver 클래스 확인**
   ```properties
   spring.datasource.driver-class-name=com.dadp.jdbc.DadpJdbcDriver
   ```

2. **의존성 확인**
   ```xml
   <dependency>
       <groupId>io.github.daone-dadp</groupId>
       <artifactId>dadp-jdbc-wrapper</artifactId>
       <version>4.17.0</version>
       <classifier>all</classifier>
   </dependency>
   ```

3. **클래스패스 확인**
   - Wrapper JAR가 클래스패스에 포함되어 있는지 확인

### 3. Hub 연결 실패

#### 증상
```
HubConnectionException: Hub 연결 실패
```

#### 해결 방법

1. **Hub 서버 URL 확인**
   ```properties
   dadp.proxy.hub-url=http://localhost:9004
   ```

2. **네트워크 연결 확인**
   ```bash
   curl http://your-hub-server:9004/hub/actuator/health
   ```

3. **로깅 활성화**
   ```properties
   dadp.proxy.enable-logging=true
   ```

### 4. 암호화 정책 오류

#### 증상
```
HubCryptoException: 암호화 실패: 정책을 찾을 수 없습니다
```

#### 해결 방법

1. **Hub에서 정책 목록 확인**
   ```bash
   curl http://your-hub-server:9004/hub/api/v1/policies
   ```

2. **스키마 동기화 확인**
   - Hub에서 테이블/컬럼 정보가 등록되어 있는지 확인
   - `dadp.proxy.schema-sync-enabled=true` 설정 확인

### 5. 암호화가 동작하지 않는 경우

#### 증상
- 데이터가 암호화되지 않음

#### 해결 방법

1. **PreparedStatement 사용 확인**
   - `Statement.executeUpdate(String sql)` 사용 시 암호화 미지원
   - `PreparedStatement` 사용 필수
   - 자세한 내용은 위의 ["❌ 미지원"](#-미지원) 섹션 참조

2. **setString 사용 확인**
   - `setObject()`는 String 타입인 경우 암호화 지원 (✅ 지원됨)
   - `setString()` 사용 권장 (더 명확함)
   - 자세한 내용은 위의 ["✅ 지원"](#-지원) 섹션 참조

3. **정책 설정 확인**
   - Hub에서 테이블/컬럼에 정책이 설정되어 있는지 확인

---

## 제한사항 및 주의사항

### Wrapper 제한사항

1. **배치 처리 미지원 (구조적 제약)**
   - ✅ **개별 암복호화는 지원**: 각 파라미터/행/컬럼별로 암복호화 처리됨
   - ❌ **배치 암호화 미지원**: JDBC PreparedStatement 구조적 제약으로 인해 여러 파라미터를 한번에 배치 암호화 불가
   - ❌ **배치 복호화 미지원**: ResultSet이 스트리밍 방식으로 동작하여 여러 행을 한번에 배치 복호화 불가 (구조적 제약)
   - 자세한 내용은 위의 ["❌ 미지원"](#-미지원) 섹션 참조

2. **SQL 문자열 직접 실행 시 암호화 미지원**
   - `Statement.executeUpdate(String sql)` 사용 시 암호화 불가
   - `PreparedStatement` 사용 필수
   - 자세한 내용은 위의 ["❌ 미지원"](#-미지원) 섹션 참조

3. **SELECT WHERE 절 파라미터 암호화 안 함**
   - 부분 암호화 검색을 위해 평문으로 유지
   - 예: `WHERE phone LIKE ?` - 파라미터는 암호화하지 않음

6. **DB 드라이버 별도 필요**
   - Wrapper JAR에는 DB 드라이버가 포함되지 않음
   - 필요한 DB 드라이버를 별도로 추가해야 함

---

## 체크리스트



## 참고사항

- Wrapper는 JDBC 드라이버 레벨에서 동작하므로 모든 JDBC 호환 드라이버에서 동작
- 코드 수정 없이 JDBC URL만 변경하면 자동으로 암복호화 적용
- PreparedStatement 사용을 권장 (Statement 직접 실행 시 암호화 미지원)
- 배치 암호화는 미지원 (각 파라미터별 개별 암호화)

---

## 📦 배포 정보

### 현재 배포 상태

✅ **Maven Central 배포 완료**

- **레포지토리**: [daone-dadp/dadp-jdbc-wrapper](https://github.com/daone-dadp/dadp-jdbc-wrapper)
- **Maven Central 검색**: [https://search.maven.org/search?q=io.github.daone-dadp](https://search.maven.org/search?q=io.github.daone-dadp)
- **배포 버전**: `4.17.0`
- **라이선스**: Apache 2.0

### 사용 가능한 라이브러리

| 라이브러리 | 그룹 ID | 아티팩트 ID | 버전 | Classifier |
|----------|--------|------------|------|------------|
| JDBC Wrapper | `io.github.daone-dadp` | `dadp-jdbc-wrapper` | `4.17.0` | `all` |

---

**작성일**: 2025-01-26  
**버전**: 4.17.0  
**최종 업데이트**: 2025-01-26  
**작성자**: DADP Development Team

