package com.dadp.common.sync.config;

import java.util.Map;

/**
 * InstanceId 제공자
 * 
 * 각 모듈(AOP, Wrapper)에서 instanceId를 가져오는 공통 로직을 제공합니다.
 * 모듈별 차이는 생성자 파라미터로 주입받아 처리합니다.
 * 
 * @author DADP Development Team
 * @version 5.2.0
 * @since 2026-01-07
 */
public class InstanceIdProvider {
    
    // AOP용 설정
    private static final String AOP_ENV_VAR = "DADP_AOP_INSTANCE_ID";
    private static final String AOP_DEFAULT = "aop";
    
    // Wrapper용 설정
    private static final String WRAPPER_SYSTEM_PROP = "dadp.proxy.instance-id";
    private static final String WRAPPER_ENV_VAR = "DADP_PROXY_INSTANCE_ID";
    private static final String WRAPPER_DEFAULT = "dadp-proxy";
    
    // 모듈 타입
    public enum ModuleType {
        AOP,
        WRAPPER
    }
    
    private final ModuleType moduleType;
    private final String springPropertyValue;  // AOP용: Spring property 값 (null 가능)
    private final Map<String, String> urlParams;  // Wrapper용: JDBC URL 파라미터 (null 가능)
    
    /**
     * AOP용 생성자
     * 
     * @param springPropertyValue Spring property 값 (spring.application.name, null 가능)
     */
    public InstanceIdProvider(String springPropertyValue) {
        this.moduleType = ModuleType.AOP;
        this.springPropertyValue = springPropertyValue;
        this.urlParams = null;
    }
    
    /**
     * Wrapper용 생성자
     * 
     * @param urlParams JDBC URL 파라미터 (null 가능)
     */
    public InstanceIdProvider(Map<String, String> urlParams) {
        this.moduleType = ModuleType.WRAPPER;
        this.springPropertyValue = null;
        this.urlParams = urlParams;
    }
    
    /**
     * instanceId 조회
     * 
     * @return instanceId (null이면 안 됨, 기본값이라도 반환해야 함)
     */
    public String getInstanceId() {
        if (moduleType == ModuleType.AOP) {
            return getAopInstanceId();
        } else {
            return getWrapperInstanceId();
        }
    }
    
    /**
     * AOP용 instanceId 조회
     */
    private String getAopInstanceId() {
        // 1. 환경 변수 확인
        String instanceId = System.getenv(AOP_ENV_VAR);
        if (instanceId != null && !instanceId.trim().isEmpty()) {
            return instanceId.trim();
        }
        
        // 2. Spring property 확인
        if (springPropertyValue != null && !springPropertyValue.trim().isEmpty()) {
            return springPropertyValue.trim();
        }
        
        // 3. 기본값 사용
        return AOP_DEFAULT;
    }
    
    /**
     * Wrapper용 instanceId 조회
     */
    private String getWrapperInstanceId() {
        String instanceId = null;
        
        // 1. 시스템 프로퍼티 확인
        instanceId = System.getProperty(WRAPPER_SYSTEM_PROP);
        if (instanceId != null && !instanceId.trim().isEmpty()) {
            return instanceId.trim();
        }
        
        // 2. 환경 변수 확인
        instanceId = System.getenv(WRAPPER_ENV_VAR);
        if (instanceId != null && !instanceId.trim().isEmpty()) {
            return instanceId.trim();
        }
        
        // 3. JDBC URL 파라미터 확인
        if (urlParams != null) {
            instanceId = urlParams.get("instanceId");
            if (instanceId != null && !instanceId.trim().isEmpty()) {
                return instanceId.trim();
            }
        }
        
        // 4. 기본값 사용
        return WRAPPER_DEFAULT;
    }
}
