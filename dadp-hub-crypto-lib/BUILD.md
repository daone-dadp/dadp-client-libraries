# dadp-hub-crypto-lib 빌드 가이드

## 통일된 모듈 구조

`dadp-hub-crypto-lib`는 하나의 모듈로 통일되었으며, Maven 프로파일을 사용하여 Java 8, 11, 17 버전별로 빌드합니다.

## 빌드 방법

### Java 17 버전 (기본)
```bash
mvn clean package
```
생성 파일: `dadp-hub-crypto-lib-1.0.0-java17.jar`

### Java 11 버전
```bash
mvn clean package -Pjava11
```
생성 파일: `dadp-hub-crypto-lib-1.0.0-java11.jar`

### Java 8 버전
```bash
mvn clean package -Pjava8
```
생성 파일: `dadp-hub-crypto-lib-1.0.0-java8.jar`

### 모든 버전 빌드
```bash
mvn clean package -Pjava8,java11,java17
```

## 사용 방법

### Maven 의존성

#### Java 17
```xml
<dependency>
    <groupId>com.dadp</groupId>
    <artifactId>dadp-hub-crypto-lib</artifactId>
    <version>1.0.0</version>
    <classifier>java17</classifier>
</dependency>
```

#### Java 11
```xml
<dependency>
    <groupId>com.dadp</groupId>
    <artifactId>dadp-hub-crypto-lib</artifactId>
    <version>1.0.0</version>
    <classifier>java11</classifier>
</dependency>
```

#### Java 8
```xml
<dependency>
    <groupId>com.dadp</groupId>
    <artifactId>dadp-hub-crypto-lib</artifactId>
    <version>1.0.0</version>
    <classifier>java8</classifier>
</dependency>
```

## 통일된 구조의 장점

1. **코드 중복 제거**: 하나의 소스 코드만 유지
2. **유지보수 용이**: 한 곳만 수정하면 모든 버전에 반영
3. **버그 수정 간소화**: 한 번만 수정
4. **테스트 간소화**: 하나의 코드만 테스트
5. **호환성 보장**: Java 8, 11, 17 모두 지원

## 제거된 모듈

다음 모듈들은 통일되어 제거되었습니다:
- `dadp-hub-crypto-lib-java8` (삭제됨)
- `dadp-hub-crypto-lib-java11` (삭제됨)

이제 `dadp-hub-crypto-lib` 하나만 사용합니다.

