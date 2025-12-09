package com.dadp.aop.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Import;

/**
 * DADP AOP Spring Boot Starter Auto-Configuration (Spring Boot 3.x 스타일)
 * 
 * <p>Spring Boot 3.x의 새로운 자동 설정 방식을 사용합니다.</p>
 * <p>META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports 파일에 등록되어 있습니다.</p>
 * <p>이 클래스는 핵심 DADP AOP 설정을 자동으로 임포트합니다.</p>
 * 
 * @author DADP Development Team
 * @version 3.0.0
 * @since 3.0.0
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.boot.autoconfigure.SpringBootApplication")
@Import(com.dadp.aop.config.DadpAopAutoConfiguration.class)
public class DadpAopSpringBootStarterAutoConfiguration {
    /**
     * Private constructor to prevent instantiation.
     */
    private DadpAopSpringBootStarterAutoConfiguration() {
        // Utility class - no instantiation
    }
}

