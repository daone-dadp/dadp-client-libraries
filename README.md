# DADP Client Libraries

> **Spring Boot libraries for DADP Hub integration**

DADP Hub와 통합하기 위한 클라이언트 라이브러리입니다. AOP 기반 암복호화를 `@Encrypt`/`@Decrypt` 어노테이션으로 간편하게 사용할 수 있습니다.

## 📦 제공 라이브러리

| 라이브러리 | 버전 | 설명 |
|----------|------|------|
| `dadp-aop-spring-boot-starter` | 2.0.0 | Spring Boot Starter (권장) ⭐ |
| `dadp-aop` | 2.0.0 | AOP 라이브러리 |
| `dadp-hub-crypto-lib` | 1.0.0 | Hub 암복호화 라이브러리 |

## 🚀 빠른 시작

### Maven 설정

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

### application.properties 설정

```properties
hub.crypto.base-url=http://your-hub-server:9004
```

### 사용 예제

```java
@Service
public class UserService {
    
    @Encrypt(policy = "dadp")
    public String getSensitiveData() {
        return "민감한 데이터";
    }
}
```

## 📚 문서

- **[사용 가이드](docs/USER_GUIDE.md)** - 고객사용 통합 가이드

> **온라인 문서**: GitHub Pages 활성화 후 `https://daone-dadp.github.io/dadp-client-libraries/`에서 확인 가능

## 🔗 링크

- **GitHub**: https://github.com/daone-dadp/dadp-client-libraries
- **JitPack**: https://jitpack.io/#daone-dadp/dadp-client-libraries
- **배포 상태**: ✅ JitPack 배포 완료 (v2.0.0)

## 📄 라이선스

Apache License 2.0

---

**작성일**: 2025-11-03  
**최종 업데이트**: 2025-11-03

