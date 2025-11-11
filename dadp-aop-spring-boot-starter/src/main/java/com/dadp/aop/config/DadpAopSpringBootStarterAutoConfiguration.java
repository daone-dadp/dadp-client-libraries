package com.dadp.aop.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Import;

/**
 * DADP AOP Spring Boot Starter Auto-Configuration
 * 
 * <p>This class provides auto-configuration for DADP AOP functionality in Spring Boot applications.</p>
 * 
 * <p>It automatically imports the core DADP AOP configuration when Spring Boot is detected.</p>
 * 
 * @author DADP Development Team
 * @version 2.1.0
 * @since 2.1.0
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

