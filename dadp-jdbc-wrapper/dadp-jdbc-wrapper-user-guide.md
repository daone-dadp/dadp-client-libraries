# DADP JDBC Wrapper 사용 가이드

> **DADP JDBC Wrapper v5.5.8 -- 고객사 배포용 가이드**

## 목차

1. [개요](#1-개요)
2. [설치](#2-설치)
3. [설정](#3-설정)
4. [Hub 관리 화면 사용법](#4-hub-관리-화면-사용법)
5. [오프라인 설정 (Hub 미연결 환경)](#5-오프라인-설정-hub-미연결-환경)
6. [사용 예시](#6-사용-예시)
   - [6.5 검색(WHERE절) 동작](#65-검색where절-동작)
7. [지원 범위](#7-지원-범위)
8. [Fail-open / Fail-close 모드](#8-fail-open--fail-close-모드)
9. [문제 해결](#9-문제-해결)
10. [도입 체크리스트](#10-도입-체크리스트)
11. [릴리즈 정보](#11-릴리즈-정보)

---

## 1. 개요

DADP JDBC Wrapper는 JDBC 드라이버 레벨에서 동작하는 암복호화 프록시 드라이버입니다.
**코드 수정 없이** JDBC URL만 변경하여 자동 암복호화를 제공합니다.

### 특징

- **코드 수정 불필요**: JDBC URL만 변경
- **자동 암호화**: PreparedStatement 파라미터 자동 암호화
- **자동 복호화**: ResultSet 조회 시 자동 복호화
- **모든 JDBC 드라이버 지원**: MySQL, PostgreSQL, Oracle, MariaDB 등
- **SQL 파싱**: 테이블/컬럼 자동 인식

### 동작 원리

1. 애플리케이션이 `jdbc:dadp:` 접두사가 붙은 URL로 커넥션을 생성합니다.
2. Wrapper가 실제 DB 드라이버를 감싸서 PreparedStatement / ResultSet을 래핑합니다.
3. Hub에서 동기화된 암호화 정책(테이블.컬럼 -> 정책명)에 따라 파라미터를 암호화하고, 조회 결과를 복호화합니다.

### 지원 DB

| DB | JDBC URL 접두사 | 비고 |
|----|-----------------|------|
| MySQL | `jdbc:dadp:mysql://` | |
| PostgreSQL | `jdbc:dadp:postgresql://` | |
| Oracle | `jdbc:dadp:oracle:thin:@` | orai18n 필요 시 별도 추가 |
| MariaDB | `jdbc:dadp:mariadb://` | |

---

## 2. 설치

### 2.1 Maven Central (권장)

Maven Central은 별도의 리포지토리 설정이 필요 없습니다.

**Maven:**

```xml
<dependency>
    <groupId>io.github.daone-dadp</groupId>
    <artifactId>dadp-jdbc-wrapper</artifactId>
    <version>5.5.8</version>
    <classifier>all</classifier>
</dependency>
```

**Gradle:**

```gradle
dependencies {
    implementation 'io.github.daone-dadp:dadp-jdbc-wrapper:5.5.8:all'
}
```

> `classifier`를 `all`로 지정해야 합니다 (Fat JAR). 실제 DB 드라이버는 별도로 추가해야 합니다.

### 2.2 Hub에서 다운로드

Hub 관리 화면에서 Wrapper JAR를 직접 다운로드할 수 있습니다.

1. Hub 관리 화면 접속 (예: `http://your-hub-server:9004`)
2. Wrapper 다운로드 메뉴에서 `dadp-jdbc-wrapper-5.5.8-all.jar` 다운로드
3. 프로젝트 classpath에 추가

### 2.3 수동 배치 (Tomcat 등 Non-Spring 환경)

Standalone Tomcat 등 Maven/Gradle을 사용하지 않는 환경에서는 JAR를 직접 배치합니다.

```bash
# Wrapper Fat JAR를 Tomcat lib에 배치
cp dadp-jdbc-wrapper-5.5.8-all.jar /opt/tomcat/lib/

# DB 드라이버도 lib에 배치 (없는 경우)
cp ojdbc8.jar /opt/tomcat/lib/
```

> **주의**: lib 폴더에 Wrapper JAR는 반드시 1개만 배치하세요.
> 여러 버전이 존재하면 SLF4J 바인딩 충돌이 발생합니다.

### 2.4 DB 드라이버 추가

Wrapper JAR에는 DB 드라이버가 포함되지 않습니다. 필요한 DB 드라이버를 별도로 추가하세요.

**MySQL:**

```xml
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <version>8.0.33</version>
</dependency>
```

**PostgreSQL:**

```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.6.0</version>
</dependency>
```

**Oracle:**

```xml
<dependency>
    <groupId>com.oracle.database.jdbc</groupId>
    <artifactId>ojdbc8</artifactId>
    <version>21.7.0.0</version>
</dependency>
```

**Oracle + 한글 캐릭터셋(KO16MSWIN949 등) 사용 시:**

한글 캐릭터셋을 사용하는 Oracle 환경에서는 `orai18n.jar`가 필수입니다. 없으면 스키마 수집 시 `Non supported character set` 오류가 발생합니다.

```xml
<!-- Maven -->
<dependency>
    <groupId>com.oracle.database.nls</groupId>
    <artifactId>orai18n</artifactId>
    <version>21.7.0.0</version>
</dependency>
```

수동 배치의 경우 `orai18n-21.7.0.0.jar`를 classpath에 추가하세요.

---

## 3. 설정

### 3.1 JDBC URL 변경

기존 JDBC URL에 `dadp:` 접두사를 추가하고, 드라이버 클래스를 변경합니다.

**변경 전:**

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/mydb
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
```

**변경 후:**

```properties
spring.datasource.url=jdbc:dadp:mysql://localhost:3306/mydb
spring.datasource.driver-class-name=com.dadp.jdbc.DadpJdbcDriver
```

### 3.2 Hub 연동 설정

**application.properties:**

```properties
spring.datasource.url=jdbc:dadp:mysql://localhost:3306/mydb
spring.datasource.driver-class-name=com.dadp.jdbc.DadpJdbcDriver
spring.datasource.username=root
spring.datasource.password=1234

# Hub 서버 설정
dadp.proxy.hub-url=http://localhost:9004
dadp.proxy.instance-id=proxy-1
```

**application.yml:**

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
```

Hub 서버 정보(URL, 인스턴스 ID, 암호화 정책명 등)는 DADP 운영팀으로부터 제공받습니다.

### 3.3 설정 우선순위

Hub URL 설정은 다음 우선순위로 적용됩니다.

| 우선순위 | 설정 방식 | 예시 |
|---------|----------|------|
| 1 (최우선) | 시스템 프로퍼티 (`-D`) | `java -Ddadp.proxy.hub-url=http://hub:9004 -jar app.jar` |
| 2 | 환경 변수 | `DADP_HUB_BASE_URL=http://hub:9004` |
| 3 | JDBC URL 파라미터 | `jdbc:dadp:mysql://host/db?hubUrl=http://hub:9004` |
| 4 | 기본값 | `http://localhost:9004` |

> Docker 컨테이너 내부에서는 서비스 이름을 사용해야 합니다:
> `http://dadp-hub:9004` (Docker 네트워크 내), `http://localhost:9004` (호스트에서 실행 시)

### 3.4 환경 변수 설정

프로덕션 환경에서는 환경 변수를 권장합니다.

```bash
export DADP_HUB_BASE_URL=http://your-hub-server:9004
```

```properties
# application.properties에서 환경 변수 참조
dadp.proxy.hub-url=${DADP_HUB_BASE_URL:http://localhost:9004}
```

---

## 4. Hub 관리 화면 사용법

### 4.1 전체 작업 흐름

Wrapper 도입 시 다음 순서로 설정합니다.

1. **정책 생성** -- 암호화 알고리즘 및 키를 정의
2. **Wrapper 설치** -- 애플리케이션에 Wrapper JAR 추가 및 JDBC URL 변경
3. **프록시 스키마 관리** -- 인스턴스에 연결된 DB의 테이블/컬럼을 확인하고, 암호화 대상 컬럼에 정책을 매핑
4. **자동 동기화** -- Wrapper가 Hub에서 정책 정보를 주기적으로 동기화
5. **암복호화 동작** -- INSERT/UPDATE 시 자동 암호화, SELECT 시 자동 복호화

### 4.2 정책 생성

Hub 관리 화면에서 암호화 정책을 생성합니다.

| 항목 | 설명 | 예시 |
|------|------|------|
| 정책명 | 고유한 정책 식별자 | `policy-personal-info` |
| 암호화 키 | 정책에 사용할 암호화 키 | Hub에서 자동 생성 또는 직접 입력 |
| 알고리즘 | 암호화 알고리즘 선택 | 아래 참조 |

**지원 알고리즘:**

| 알고리즘 | 설명 |
|---------|------|
| AES-256-GCM | 인증 암호화 (권장) |
| AES-256-ECB | 블록 암호화 |
| ARIA-256-CBC | 국산 표준 암호화 |
| SEED-128-CBC | 국산 표준 암호화 |
| FPE/FF1 | 형식 보존 암호화 (Format Preserving Encryption) |

### 4.3 프록시 스키마 관리

인스턴스에 연결된 DB 스키마 정보를 관리합니다.

1. **인스턴스 선택** -- 등록된 Wrapper 인스턴스 목록에서 대상 선택
2. **스키마 목록 확인** -- 연결된 DB의 테이블/컬럼 목록이 자동으로 수집됨
3. **정책 매핑** -- 암호화 대상 컬럼에 생성한 정책을 매핑
4. **동기화 상태 확인** -- Wrapper가 변경된 정책 정보를 정상적으로 동기화했는지 확인

### 4.4 인스턴스 설정

| 항목 | 설명 |
|------|------|
| Engine URL | 암복호화를 수행하는 Engine 서비스의 URL |
| 통계 앱 | 통계 수집 대상 애플리케이션 설정 |

---

## 5. 오프라인 설정 (Hub 미연결 환경)

Wrapper는 일반적으로 Hub와 통신하여 암호화 정책을 자동 동기화합니다.
Hub에 연결할 수 없는 환경(에어갭, 폐쇄망 등)에서는 **설정 내보내기** 기능을 사용하여 Wrapper를 독립 운영할 수 있습니다.

### 5.1 설정 내보내기 (Hub에서 다운로드)

Hub 관리 화면에서 설정 파일을 내보냅니다.

1. Hub 관리 화면 접속 후 **프록시 스키마 관리** 페이지로 이동
2. 대상 인스턴스의 **별칭**과 **hubId**를 선택
3. 인스턴스 정보 바에서 **설정 내보내기** 버튼 클릭
4. `wrapper-config-{별칭}.json` 파일이 다운로드됨

또는 API를 직접 호출할 수도 있습니다:

```
GET /hub/api/v1/proxy/export-config?hubId={hubId}
```

### 5.2 설정 가져오기 (Wrapper에 적용)

다운로드한 설정 파일을 Wrapper 서버에 배치합니다.

**파일명**: 다음 중 하나를 사용

- `exported-config.json` (우선 적용)
- `wrapper-config-{instanceId}.json` (Hub에서 다운로드한 파일명 그대로 사용 가능)

**배치 경로**: Wrapper 영구 저장소 디렉토리

```
{storageDir}/exported-config.json
또는
{storageDir}/wrapper-config-{instanceId}.json
```

저장소 경로 결정 우선순위:

- 1순위: `-Ddadp.storage.dir` 시스템 프로퍼티 (예: `-Ddadp.storage.dir=/opt/dadp`)
- 2순위: `DADP_STORAGE_DIR` 환경변수 (예: `export DADP_STORAGE_DIR=/opt/dadp`)
- 3순위: 기본값 `{user.dir}/dadp/wrapper/{instanceId}/` (예: `/app/dadp/wrapper/soe-app/`)

**예시 (instanceId가 `soe-app`인 경우):**

```bash
# 방법 1: 다운로드한 파일명 그대로 복사
scp wrapper-config-soe-app.json user@wrapper-server:/app/dadp/wrapper/soe-app/

# 방법 2: exported-config.json으로 이름 변경하여 복사
scp wrapper-config-soe-app.json user@wrapper-server:/app/dadp/wrapper/soe-app/exported-config.json
```

### 5.3 Wrapper 기동

설정 파일이 배치된 상태에서 Wrapper를 포함한 애플리케이션을 시작합니다.

**기동 흐름:**

1. 기존 영구 저장소 데이터 확인 → 없음
2. Hub 연결 시도 → 실패 (또는 미설정)
3. `exported-config.json` 확인 → 파일 발견
4. 설정 로드 (정책 매핑, Engine URL, hubId 등)
5. 정상 기동 → Engine에 직접 암복호화 요청

**확인 로그:**

```
ExportedConfigLoader - Exported config loaded successfully: hubId=pi_xxx, cryptoUrl=http://...
JdbcBootstrapOrchestrator - Step 2.5: Loaded configuration from exported config file
```

### 5.4 주의 사항

- 설정 파일의 `cryptoUrl`은 Wrapper에서 접근 가능한 Engine URL이어야 합니다 (Docker 내부 주소 사용 불가)
- Hub 관리 화면의 **엔진 라우팅** 페이지에서 Engine URL을 IP 기반으로 설정한 후 내보내기하세요
- 정책 변경 시 설정 파일을 다시 내보내기하여 교체해야 합니다
- 기존 영구 저장소 파일이 있으면 `exported-config.json`보다 우선 사용됩니다 (초기화 필요 시 기존 파일 삭제)

---

## 6. 사용 예시

### 6.1 Spring Boot + JPA (권장)

코드 수정 없이 JDBC URL만 변경하면 자동으로 암복호화가 적용됩니다.

**application.properties:**

```properties
spring.datasource.url=jdbc:dadp:mysql://localhost:3306/mydb
spring.datasource.driver-class-name=com.dadp.jdbc.DadpJdbcDriver

dadp.proxy.hub-url=http://localhost:9004
```

**서비스 코드 (변경 불필요):**

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

### 6.2 순수 JDBC

```java
String url = "jdbc:dadp:mysql://localhost:3306/mydb";
Connection conn = DriverManager.getConnection(url, "root", "1234");

// INSERT - 자동 암호화
PreparedStatement ps = conn.prepareStatement(
    "INSERT INTO users (name, email, phone) VALUES (?, ?, ?)");
ps.setString(1, "홍길동");
ps.setString(2, "email@example.com");  // 자동 암호화
ps.setString(3, "010-1234-5678");      // 자동 암호화
ps.executeUpdate();

// SELECT - 자동 복호화
PreparedStatement ps2 = conn.prepareStatement(
    "SELECT email, phone FROM users WHERE id = ?");
ps2.setLong(1, 1L);
ResultSet rs = ps2.executeQuery();
if (rs.next()) {
    String email = rs.getString("email");  // 자동 복호화
    String phone = rs.getString("phone");  // 자동 복호화
}
```

### 6.3 MyBatis

**mybatis-config.xml:**

```xml
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

**Mapper XML (변경 불필요):**

```xml
<insert id="insertUser">
    INSERT INTO users (name, email, phone) VALUES (#{name}, #{email}, #{phone})
    <!-- email, phone은 자동 암호화됨 -->
</insert>

<select id="selectUser" resultType="User">
    SELECT * FROM users WHERE id = #{id}
    <!-- email, phone은 자동 복호화됨 -->
</select>
```

### 6.4 Standalone Tomcat (Servlet)

Maven/Gradle 없이 Tomcat에 직접 배치하는 환경에서의 사용 예시입니다.

```java
@WebServlet("/api/data")
public class DataServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        String url = "jdbc:dadp:oracle:thin:@db-host:1521:ORCL";

        try (Connection conn = DriverManager.getConnection(url, "user", "pass");
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT name, email FROM users WHERE id = ?")) {

            ps.setLong(1, Long.parseLong(req.getParameter("id")));
            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                resp.getWriter().println(
                    rs.getString("name") + " / " + rs.getString("email"));
                // email은 자동 복호화
            }
        } catch (SQLException e) {
            throw new ServletException(e);
        }
    }
}
```

### 6.5 검색(WHERE절) 동작

암호화 대상 컬럼의 검색 시 알고리즘 특성에 따라 자동으로 분기됩니다.

**검색 암호화 가능 알고리즘** (결정론적 암호화):

| 알고리즘 | 설명 |
|---------|------|
| `A256ECB` | AES-256 ECB 모드 (IV 없음, 동일 입력 = 동일 출력) |
| `FPE_FF1` | Format Preserving Encryption (형식 보존, IV 없음) |

**검색 암호화 불가 알고리즘** (비결정론적 암호화):

| 알고리즘 | 설명 |
|---------|------|
| `A256GCM` | AES-256 GCM 모드 (랜덤 IV, 기본 알고리즘) |
| `ARIA256` | ARIA-256 CBC 모드 (IV 사용) |
| `SEED128` | SEED-128 CBC 모드 (IV 사용) |

**WHERE절 처리 규칙:**

| 조건 | 처리 방식 |
|------|----------|
| 등치 검색 (`=`, `!=`) — 검색 가능 알고리즘 | 암호화 후 검색 (Engine 호출) |
| LIKE 검색 (와일드카드 없음) — 검색 가능 알고리즘 | 등치 검색과 동일 (암호화 후 검색) |
| LIKE 검색 (와일드카드 `%`, `_` 포함) — 모든 알고리즘 | 평문으로 검색 |
| 검색 불가 알고리즘 (`A256GCM`, `ARIA256`, `SEED128`) | 항상 평문으로 검색 |
| 부분암호화(`usePlain=true`) 컬럼 | 항상 평문으로 검색 |

> **참고**: 검색 암호화 가능 여부는 Wrapper가 Hub에서 동기화한 정책 속성(`useIv`)을 기반으로 Engine 호출 없이 로컬에서 판단합니다.
> 와일드카드 검색(`LIKE %값%` 등)은 암호화 여부와 무관하게 평문으로 처리되므로 암호화 컬럼에 대한 와일드카드 검색은 지원되지 않습니다.

**동작 예시 (phone 컬럼이 A256ECB 정책인 경우):**

```java
// 등치 검색 → 암호화 후 검색 (정상 동작)
PreparedStatement ps = conn.prepareStatement(
    "SELECT * FROM users WHERE phone = ?");
ps.setString(1, "010-1234-5678");  // 자동 암호화 후 검색

// LIKE 검색 (와일드카드 없음) → 암호화 후 검색 (정상 동작)
PreparedStatement ps2 = conn.prepareStatement(
    "SELECT * FROM users WHERE phone LIKE ?");
ps2.setString(1, "010-1234-5678");  // 자동 암호화 후 검색

// LIKE 검색 (와일드카드 포함) → 평문으로 검색 (매칭 불가)
PreparedStatement ps3 = conn.prepareStatement(
    "SELECT * FROM users WHERE phone LIKE ?");
ps3.setString(1, "010-1234%");  // 평문 그대로 전달 (암호화된 DB값과 매칭 불가)
```

---

## 7. 지원 범위

### 7.1 지원 명령어

| 메서드 | 설명 |
|--------|------|
| `PreparedStatement.setString(int, String)` | INSERT/UPDATE 시 파라미터 암호화 |
| `PreparedStatement.setObject(int, Object)` | String 타입인 경우 암호화 (JPA/Hibernate 호환) |
| `PreparedStatement.setObject(int, Object, int)` | String 타입인 경우 암호화 |
| `PreparedStatement.setNString(int, String)` | setString과 동일한 암호화 |
| `PreparedStatement.executeUpdate()` | 암호화된 파라미터로 실행 |
| `PreparedStatement.executeQuery()` | 실행 후 ResultSet 래핑하여 복호화 |
| `ResultSet.getString(int/String)` | 컬럼 조회 시 복호화 |
| `ResultSet.getObject(int/String)` | String 타입인 경우 복호화 |
| `ResultSet.getObject(int/String, Class)` | String 타입인 경우 복호화 |
| `Statement.executeQuery(String)` | SELECT 실행 후 ResultSet 래핑하여 복호화 |
| `Statement.getResultSet()` | ResultSet 래핑하여 복호화 |
| `Connection.prepareStatement(String)` | PreparedStatement 래핑 |
| `Connection.createStatement()` | Statement 래핑 |

### 7.2 미지원 명령어

| 메서드 | 사유 |
|--------|------|
| `Statement.executeUpdate(String)` | SQL이 이미 완성되어 파라미터 가로채기 불가. PreparedStatement 사용 필수. |
| `Statement.execute(String)` | SQL이 이미 완성되어 파라미터 가로채기 불가. PreparedStatement 사용 필수. |
| `Connection.prepareCall(String)` | CallableStatement 래핑 미구현. PreparedStatement 또는 일반 SQL 사용 권장. |

> **요약**: INSERT/UPDATE 시에는 반드시 PreparedStatement를 사용하세요.
> SELECT(조회)는 Statement와 PreparedStatement 모두 지원합니다.

---

## 8. Fail-open / Fail-close 모드

Wrapper는 암복호화 처리 실패 시 두 가지 동작 모드를 제공합니다.

| 모드 | 동작 | 설정값 |
|------|------|--------|
| **Fail-open** (기본) | 암복호화 실패 시 평문 그대로 처리 (서비스 중단 방지) | `dadp.proxy.fail-mode=open` |
| **Fail-close** | 암복호화 실패 시 예외 발생 (보안 우선) | `dadp.proxy.fail-mode=close` |

**Fail-open 모드 (기본):**
- Hub 연결 실패, 정책 미설정 등의 상황에서도 애플리케이션이 정상 동작
- 암호화 실패 시 평문으로 저장, 복호화 실패 시 암호문 그대로 반환
- 서비스 가용성이 중요한 환경에 적합

**Fail-close 모드:**
- 암복호화 실패 시 `SQLException` 예외 발생
- 평문 데이터가 저장되는 것을 원천 차단
- 보안이 최우선인 환경에 적합

---

## 9. 문제 해결

### 9.1 드라이버를 찾을 수 없는 경우

**증상:**

```
java.sql.SQLException: No suitable driver found for jdbc:dadp:mysql://...
```

**해결:**

1. 드라이버 클래스 설정 확인
   ```properties
   spring.datasource.driver-class-name=com.dadp.jdbc.DadpJdbcDriver
   ```
2. 의존성 확인 (`classifier`가 `all`인지 확인)
   ```xml
   <dependency>
       <groupId>io.github.daone-dadp</groupId>
       <artifactId>dadp-jdbc-wrapper</artifactId>
       <version>5.5.8</version>
       <classifier>all</classifier>
   </dependency>
   ```
3. Wrapper JAR가 classpath에 포함되어 있는지 확인

### 9.2 Hub 연결 실패

**증상:**

```
HubConnectionException: Hub 연결 실패
```

**해결:**

1. Hub 서버 URL 확인
   ```properties
   dadp.proxy.hub-url=http://your-hub-server:9004
   ```
2. 네트워크 연결 확인
   ```bash
   curl http://your-hub-server:9004/hub/actuator/health
   # 정상 응답: {"status":"UP"}
   ```
3. 방화벽 및 보안그룹에서 9004 포트가 허용되어 있는지 확인

### 9.3 암호화가 동작하지 않는 경우

**증상:** 데이터가 평문으로 저장됨

**해결:**

1. **PreparedStatement 사용 확인** -- `Statement.executeUpdate(String)` 사용 시 암호화 미지원
2. **Hub 정책 매핑 확인** -- Hub 관리 화면에서 대상 테이블/컬럼에 정책이 매핑되어 있는지 확인
3. **스키마 동기화 확인** -- Wrapper가 Hub에서 정책 정보를 정상적으로 동기화했는지 확인
4. **로깅 활성화** 후 Wrapper 내부 로그를 확인 (9.6 참조)

### 9.4 Oracle 한글 캐릭터셋 오류

**증상:**

```
java.sql.SQLException: Non supported character set (add orai18n.jar in your classpath): KO16MSWIN949
```

**원인:** Oracle DB가 한글 캐릭터셋을 사용하는데 orai18n.jar가 classpath에 없음

**해결:** orai18n.jar를 classpath에 추가

- Maven:
  ```xml
  <dependency>
      <groupId>com.oracle.database.nls</groupId>
      <artifactId>orai18n</artifactId>
      <version>21.7.0.0</version>
  </dependency>
  ```
- Tomcat: `/opt/tomcat/lib/orai18n-21.7.0.0.jar` 배치

### 9.5 Tomcat lib 복수 JAR 충돌

**증상:**

```
SLF4J: Class path contains multiple SLF4J bindings.
SLF4J: Actual binding is of type [org.slf4j.helpers.NOPLoggerFactory]
```

**원인:** `/opt/tomcat/lib/`에 여러 버전의 Wrapper JAR이 존재

**해결:** 이전 버전 JAR 삭제 후 최신 버전만 유지

```bash
ls /opt/tomcat/lib/dadp-jdbc-wrapper-*
# 이전 버전 삭제
rm /opt/tomcat/lib/dadp-jdbc-wrapper-5.5.5-all.jar
rm /opt/tomcat/lib/dadp-jdbc-wrapper-5.5.6-all.jar
# 최신 버전만 유지
ls /opt/tomcat/lib/dadp-jdbc-wrapper-5.5.8-all.jar
```

### 9.6 로깅 활성화

Wrapper 내부 로그를 확인하려면 로깅을 활성화합니다.

**시스템 프로퍼티:**

```bash
java -Ddadp.enable-logging=true -jar app.jar
```

**환경 변수:**

```bash
export DADP_ENABLE_LOGGING=true
```

**Tomcat의 경우** `catalina.sh` 또는 `setenv.sh`에 추가:

```bash
JAVA_OPTS="$JAVA_OPTS -Ddadp.enable-logging=true"
```

---

## 10. 도입 체크리스트

| 항목 | 확인 |
|------|------|
| Wrapper JAR 의존성 추가 (classifier: all) | |
| DB 드라이버 별도 추가 (MySQL, PostgreSQL, Oracle 등) | |
| Oracle 한글 캐릭터셋 사용 시 orai18n.jar 추가 | |
| JDBC URL에 `dadp:` 접두사 추가 | |
| driver-class-name을 `com.dadp.jdbc.DadpJdbcDriver`로 변경 | |
| Hub URL 설정 (`dadp.proxy.hub-url`) | |
| 인스턴스 ID 설정 (`dadp.proxy.instance-id`) | |
| Hub 관리 화면에서 암호화 정책 생성 | |
| Hub 관리 화면에서 대상 테이블/컬럼에 정책 매핑 | |
| Hub 서버 네트워크 연결 확인 (포트 9004) | |
| INSERT/UPDATE에 PreparedStatement 사용 확인 | |
| Fail-open / Fail-close 모드 결정 | |
| Tomcat 환경: lib 폴더에 Wrapper JAR 1개만 배치 확인 | |

---

## 11. 릴리즈 정보

| 항목 | 값 |
|------|-----|
| 레포지토리 | [daone-dadp/dadp-client-libraries](https://github.com/daone-dadp/dadp-client-libraries) |
| Maven Central | [io.github.daone-dadp](https://search.maven.org/search?q=io.github.daone-dadp) |
| 배포 버전 | `5.5.8` |
| Group ID | `io.github.daone-dadp` |
| Artifact ID | `dadp-jdbc-wrapper` |
| Classifier | `all` |
| 라이선스 | Apache 2.0 |

---

**작성일**: 2026-02-27
**버전**: 5.5.8
**최종 업데이트**: 2026-03-10
**작성자**: DADP Development Team
