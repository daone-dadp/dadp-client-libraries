package com.dadp.jdbc.config;

// TODO: Hub API 구현 후 주석 해제
// import com.dadp.common.sync.config.SchemaCollectionConfigResolver;
// import com.dadp.common.sync.config.SchemaCollectionConfigStorage;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;

import java.util.Map;

/**
 * Proxy 설정 관리
 * 
 * 설정 우선순위:
 * 1. 시스템 프로퍼티 (dadp.proxy.hub-url, dadp.proxy.instance-id, dadp.proxy.fail-open)
 * 2. 환경 변수 (DADP_HUB_BASE_URL > DADP_PROXY_HUB_URL, DADP_PROXY_INSTANCE_ID, DADP_PROXY_FAIL_OPEN)
 * 3. JDBC URL 쿼리 파라미터 (hubUrl, instanceId, failOpen)
 * 4. 기본값
 * 
 * @author DADP Development Team
 * @version 3.0.0
 * @since 2025-11-07
 */
public class ProxyConfig {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(ProxyConfig.class);
    
    private static final String DEFAULT_HUB_URL = "http://localhost:9004";
    private static final String DEFAULT_INSTANCE_ID = "proxy-1";
    private static final long DEFAULT_SCHEMA_COLLECTION_TIMEOUT_MS = 30000; // 30초
    private static final int DEFAULT_MAX_SCHEMAS = 100;
    private static final String DEFAULT_SCHEMA_COLLECTION_FAIL_MODE = "fail-open"; // fail-open 또는 fail-close
    
    private static volatile ProxyConfig instance;
    private final String hubUrl;  // Hub URL (스키마 동기화 + 암복호화 라우팅, Hub가 Engine/Gateway로 자동 라우팅)
    private final String instanceId;  // 사용자가 설정한 별칭 (검색/표시용)
    private volatile String hubId;  // Hub가 발급한 고유 ID (X-DADP-TENANT 헤더에 사용, HubIdManager에서 관리)
    private final boolean failOpen;
    private final boolean enableLogging;  // DADP 통합 로그 활성화
    private final Map<String, String> urlParams;  // JDBC URL 파라미터 (InstanceIdProvider용)
    
    // 스키마 수집 안정성 설정
    private final long schemaCollectionTimeoutMs;  // 스키마 수집 타임아웃 (밀리초)
    private final int maxSchemas;  // 최대 스키마 개수
    private final String schemaAllowlist;  // 허용 스키마 목록 (쉼표로 구분, 예: "public,auth,payment")
    private final String schemaCollectionFailMode;  // 실패 모드 ("fail-open" 또는 "fail-close")
    private volatile boolean enabled;  // Wrapper 활성화 여부 (false면 암복호화 없이 순수 패스스루, Hub 설정으로 변경 가능)

    // InstanceConfigStorage는 HubIdManager에서 관리하므로 제거
    
    /**
     * JDBC URL 쿼리 파라미터에서 Proxy 설정을 읽어서 생성
     */
    public ProxyConfig(Map<String, String> urlParams) {
        this.urlParams = urlParams;  // InstanceIdProvider용으로 저장
        // Hub URL 읽기 (우선순위: 시스템 프로퍼티 > 환경 변수 > URL 파라미터 > 기본값)
        // 환경변수 우선순위: DADP_HUB_BASE_URL > DADP_PROXY_HUB_URL (하위 호환성)
        String hubUrlProp = null;
        // 1. 시스템 프로퍼티 우선 확인
        if (hubUrlProp == null || hubUrlProp.trim().isEmpty()) {
            hubUrlProp = System.getProperty("dadp.proxy.hub-url");
        }
        // 2. 환경 변수 확인
        if (hubUrlProp == null || hubUrlProp.trim().isEmpty()) {
            // 새로운 표준 환경변수 우선 사용
            hubUrlProp = System.getenv("DADP_HUB_BASE_URL");
        }
        if (hubUrlProp == null || hubUrlProp.trim().isEmpty()) {
            // 하위 호환성: 기존 환경변수 지원
            hubUrlProp = System.getenv("DADP_PROXY_HUB_URL");
        }
        // 3. JDBC URL 파라미터 확인
        if (hubUrlProp == null || hubUrlProp.trim().isEmpty()) {
            hubUrlProp = urlParams != null ? urlParams.get("hubUrl") : null;
        }
        // 4. 기본값 사용
        if (hubUrlProp == null || hubUrlProp.trim().isEmpty()) {
            hubUrlProp = DEFAULT_HUB_URL;
        }
        this.hubUrl = hubUrlProp.trim();
        
        // Instance ID 읽기 (우선순위: 시스템 프로퍼티 > 환경 변수 > URL 파라미터 > 기본값)
        String instanceIdProp = null;
        // 1. 시스템 프로퍼티 우선 확인
        if (instanceIdProp == null || instanceIdProp.trim().isEmpty()) {
            instanceIdProp = System.getProperty("dadp.proxy.instance-id");
        }
        // 2. 환경 변수 확인
        if (instanceIdProp == null || instanceIdProp.trim().isEmpty()) {
            instanceIdProp = System.getenv("DADP_PROXY_INSTANCE_ID");
        }
        // 3. JDBC URL 파라미터 확인
        if (instanceIdProp == null || instanceIdProp.trim().isEmpty()) {
            instanceIdProp = urlParams != null ? urlParams.get("instanceId") : null;
        }
        // 4. 기본값 사용
        if (instanceIdProp == null || instanceIdProp.trim().isEmpty()) {
            instanceIdProp = DEFAULT_INSTANCE_ID;
        }
        this.instanceId = instanceIdProp.trim();
        
        // Fail-open 모드 읽기 (우선순위: 시스템 프로퍼티 > 환경 변수 > URL 파라미터 > 기본값)
        String failOpenProp = null;
        // 1. 시스템 프로퍼티 우선 확인
        if (failOpenProp == null || failOpenProp.trim().isEmpty()) {
            failOpenProp = System.getProperty("dadp.proxy.fail-open");
        }
        // 2. 환경 변수 확인
        if (failOpenProp == null || failOpenProp.trim().isEmpty()) {
            failOpenProp = System.getenv("DADP_PROXY_FAIL_OPEN");
        }
        // 3. JDBC URL 파라미터 확인
        if (failOpenProp == null || failOpenProp.trim().isEmpty()) {
            failOpenProp = urlParams != null ? urlParams.get("failOpen") : null;
        }
        // 4. 기본값 사용 (기본값: true)
        this.failOpen = failOpenProp == null || failOpenProp.trim().isEmpty() || 
                       Boolean.parseBoolean(failOpenProp);
        
        // DADP 통합 로그 활성화 설정 읽기 (우선순위: 시스템 프로퍼티 > 환경 변수 > URL 파라미터 > 기본값)
        String enableLoggingProp = null;
        // 1. 시스템 프로퍼티 우선 확인
        if (enableLoggingProp == null || enableLoggingProp.trim().isEmpty()) {
            enableLoggingProp = System.getProperty("dadp.enable-logging");
        }
        // 2. 환경 변수 확인
        if (enableLoggingProp == null || enableLoggingProp.trim().isEmpty()) {
            enableLoggingProp = System.getenv("DADP_ENABLE_LOGGING");
        }
        // 3. JDBC URL 파라미터 확인
        if (enableLoggingProp == null || enableLoggingProp.trim().isEmpty()) {
            enableLoggingProp = urlParams != null ? urlParams.get("enableLogging") : null;
        }
        // 4. 기본값 사용 (기본값: false)
        this.enableLogging = enableLoggingProp != null && !enableLoggingProp.trim().isEmpty() && 
                            ("true".equalsIgnoreCase(enableLoggingProp) || "1".equals(enableLoggingProp));
        
        // DadpLoggerFactory에 로그 활성화 설정 전달 (JDBC URL 파라미터를 통해 설정된 경우 반영)
        DadpLoggerFactory.setLoggingEnabled(this.enableLogging);
        
        // 스키마 수집 설정 읽기 (우선순위: 시스템 프로퍼티 > 환경 변수 > URL 파라미터 > 기본값)
        // TODO: Hub API 구현 후 Hub 저장소 우선순위 추가
        // SchemaCollectionConfigResolver configResolver = SchemaCollectionConfigResolver.getInstance();
        // SchemaCollectionConfigStorage.SchemaCollectionConfig storedConfig = configResolver.getConfig();
        
        // 타임아웃 읽기
        String timeoutProp = null;
        // TODO: Hub API 구현 후 주석 해제
        // if (storedConfig != null && storedConfig.getTimeoutMs() != null) {
        //     // Hub에서 받은 설정 우선 사용
        //     this.schemaCollectionTimeoutMs = storedConfig.getTimeoutMs();
        //     log.trace("📋 Hub 저장소에서 타임아웃 로드: {}ms", this.schemaCollectionTimeoutMs);
        // } else {
            // 로컬 설정 확인
            if (timeoutProp == null || timeoutProp.trim().isEmpty()) {
                timeoutProp = System.getProperty("dadp.wrapper.schema-collection.timeout");
            }
            if (timeoutProp == null || timeoutProp.trim().isEmpty()) {
                timeoutProp = System.getenv("DADP_WRAPPER_SCHEMA_COLLECTION_TIMEOUT");
            }
            if (timeoutProp == null || timeoutProp.trim().isEmpty()) {
                timeoutProp = urlParams != null ? urlParams.get("schemaCollectionTimeout") : null;
            }
            // 시간 문자열 파싱 (예: "30s", "1m", "30000" 등)
            this.schemaCollectionTimeoutMs = parseTimeout(timeoutProp, DEFAULT_SCHEMA_COLLECTION_TIMEOUT_MS);
        // }
        
        // 최대 스키마 개수 읽기
        String maxSchemasProp = null;
        // TODO: Hub API 구현 후 주석 해제
        // if (storedConfig != null && storedConfig.getMaxSchemas() != null) {
        //     // Hub에서 받은 설정 우선 사용
        //     this.maxSchemas = storedConfig.getMaxSchemas();
        //     log.trace("📋 Hub 저장소에서 최대 스키마 개수 로드: {}", this.maxSchemas);
        // } else {
        // 로컬 설정 확인
        if (maxSchemasProp == null || maxSchemasProp.trim().isEmpty()) {
            maxSchemasProp = System.getProperty("dadp.wrapper.schema-collection.max-schemas");
        }
        if (maxSchemasProp == null || maxSchemasProp.trim().isEmpty()) {
            maxSchemasProp = System.getenv("DADP_WRAPPER_SCHEMA_COLLECTION_MAX_SCHEMAS");
        }
        if (maxSchemasProp == null || maxSchemasProp.trim().isEmpty()) {
            maxSchemasProp = urlParams != null ? urlParams.get("maxSchemas") : null;
        }
        int parsedMaxSchemas;
        try {
            parsedMaxSchemas = maxSchemasProp != null && !maxSchemasProp.trim().isEmpty() 
                ? Integer.parseInt(maxSchemasProp.trim()) 
                : DEFAULT_MAX_SCHEMAS;
        } catch (NumberFormatException e) {
            log.warn("Failed to parse max schemas: {} (using default: {})", maxSchemasProp, DEFAULT_MAX_SCHEMAS);
            parsedMaxSchemas = DEFAULT_MAX_SCHEMAS;
        }
        this.maxSchemas = parsedMaxSchemas;
        // }
        
        // 스키마 Allowlist 읽기
        String allowlistProp = null;
        // TODO: Hub API 구현 후 주석 해제
        // if (storedConfig != null && storedConfig.getAllowlist() != null) {
        //     // Hub에서 받은 설정 우선 사용
        //     this.schemaAllowlist = storedConfig.getAllowlist();
        //     log.trace("📋 Hub 저장소에서 Allowlist 로드: {}", this.schemaAllowlist);
        // } else {
            // 로컬 설정 확인
            if (allowlistProp == null || allowlistProp.trim().isEmpty()) {
                allowlistProp = System.getProperty("dadp.wrapper.schema-collection.allowlist");
            }
            if (allowlistProp == null || allowlistProp.trim().isEmpty()) {
                allowlistProp = System.getenv("DADP_WRAPPER_SCHEMA_COLLECTION_ALLOWLIST");
            }
            if (allowlistProp == null || allowlistProp.trim().isEmpty()) {
                allowlistProp = urlParams != null ? urlParams.get("schemaAllowlist") : null;
            }
            this.schemaAllowlist = (allowlistProp != null && !allowlistProp.trim().isEmpty()) 
                ? allowlistProp.trim() 
                : null;
        // }
        
        // 스키마 수집 실패 모드 읽기
        String failModeProp = null;
        // TODO: Hub API 구현 후 주석 해제
        // if (storedConfig != null && storedConfig.getFailMode() != null) {
        //     // Hub에서 받은 설정 우선 사용
        //     this.schemaCollectionFailMode = storedConfig.getFailMode().toLowerCase();
        //     log.trace("📋 Hub 저장소에서 실패 모드 로드: {}", this.schemaCollectionFailMode);
        // } else {
            // 로컬 설정 확인
            if (failModeProp == null || failModeProp.trim().isEmpty()) {
                failModeProp = System.getProperty("dadp.wrapper.schema-collection.fail-mode");
            }
            if (failModeProp == null || failModeProp.trim().isEmpty()) {
                failModeProp = System.getenv("DADP_WRAPPER_SCHEMA_COLLECTION_FAIL_MODE");
            }
            if (failModeProp == null || failModeProp.trim().isEmpty()) {
                failModeProp = urlParams != null ? urlParams.get("schemaCollectionFailMode") : null;
            }
            this.schemaCollectionFailMode = (failModeProp != null && !failModeProp.trim().isEmpty()) 
                ? failModeProp.trim().toLowerCase() 
                : DEFAULT_SCHEMA_COLLECTION_FAIL_MODE;
        // }
        
        // Wrapper 활성화 여부 (JDBC URL 파라미터 또는 exported config에서만 설정)
        // 시스템 프로퍼티/환경변수는 모든 인스턴스에 적용되어 Hub 인스턴스별 설정을 덮어쓰므로 제거
        String enabledProp = urlParams != null ? urlParams.get("enabled") : null;
        this.enabled = enabledProp == null || enabledProp.trim().isEmpty() ||
                       !"false".equalsIgnoreCase(enabledProp.trim());

        // hubId는 HubIdManager에서 전역으로 관리 (지연 로드, 오케스트레이터의 runBootstrapFlow()에서만 로드)
        // 생성자에서 파일을 읽지 않음 (AOP 플로우와 일치)
        this.hubId = null;
        
        // Connection Pool에서 반복적으로 생성되므로 TRACE 레벨로 처리 (로그 정책 참조)
        log.trace("Proxy config loaded:");
        log.trace("   - Hub URL (schema sync + crypto routing): {}", this.hubUrl);
        log.trace("   - Instance ID: {}", this.instanceId);
        log.trace("   - Fail-open: {}", this.failOpen);
        log.trace("   - Wrapper enabled: {}", this.enabled);
        log.trace("   - DADP logging enabled: {}", this.enableLogging);
        log.trace("   - Schema collection timeout: {}ms", this.schemaCollectionTimeoutMs);
        log.trace("   - Max schemas: {}", this.maxSchemas);
        log.trace("   - Schema Allowlist: {}", this.schemaAllowlist != null ? this.schemaAllowlist : "(none)");
        log.trace("   - Schema collection fail mode: {}", this.schemaCollectionFailMode);
        log.trace("   - Hub ID: (lazy loaded)");
    }
    
    /**
     * 기본 생성자 (레거시 호환성)
     */
    private ProxyConfig() {
        this(null);
    }
    
    /**
     * 싱글톤 인스턴스 (레거시 호환성, 권장하지 않음)
     */
    public static ProxyConfig getInstance() {
        if (instance == null) {
            synchronized (ProxyConfig.class) {
                if (instance == null) {
                    instance = new ProxyConfig();
                }
            }
        }
        return instance;
    }
    
    public String getHubUrl() {
        return hubUrl;
    }
    
    public String getInstanceId() {
        return instanceId;
    }
    
    /**
     * hubId 조회 (캐시된 값만 반환, 파일 읽기 없음)
     * 
     * 오케스트레이터에서만 공통 라이브러리(InstanceConfigStorage)를 직접 사용하고,
     * 여기서는 오케스트레이터가 설정한 캐시된 값만 반환합니다.
     * 
     * @return hubId, 없으면 null
     */
    public String getHubId() {
        // 캐시된 값만 반환 (파일 읽기 없음)
        // 오케스트레이터에서 setHubId()로 설정한 값만 사용
        return hubId;
    }
    
    /**
     * hubId 저장 (캐시만 업데이트, 영구저장소는 HubIdManager에서 관리)
     * 
     * @param hubId Hub가 발급한 고유 ID
     * @deprecated hubId는 HubIdManager에서 전역으로 관리되므로 이 메서드는 캐시만 업데이트합니다.
     *             실제 저장은 HubIdManager.setHubId()를 사용하세요.
     */
    @Deprecated
    public void setHubId(String hubId) {
        // 캐시만 업데이트 (하위 호환성을 위해 유지)
        // 실제 저장은 HubIdManager에서 관리
        this.hubId = hubId;
    }
    
    public boolean isFailOpen() {
        return failOpen;
    }

    /**
     * Wrapper 활성화 여부 조회
     * false이면 암복호화 없이 순수 패스스루 모드로 동작합니다.
     *
     * @return Wrapper 활성화 여부 (기본값: true)
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Wrapper 활성화 여부 설정 (Hub 설정 또는 exported config에서 변경 시 사용)
     *
     * @param enabled Wrapper 활성화 여부
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    /**
     * DADP 통합 로그 활성화 여부 조회
     * 
     * @return 로그 활성화 여부
     */
    public boolean isEnableLogging() {
        return enableLogging;
    }
    
    /**
     * JDBC URL 파라미터 조회 (InstanceIdProvider용)
     * 
     * @return JDBC URL 파라미터 맵
     */
    public Map<String, String> getUrlParams() {
        return urlParams;
    }
    
    /**
     * 스키마 수집 타임아웃 조회 (밀리초)
     * 
     * @return 타임아웃 (밀리초)
     */
    public long getSchemaCollectionTimeoutMs() {
        return schemaCollectionTimeoutMs;
    }
    
    /**
     * 최대 스키마 개수 조회
     * 
     * @return 최대 스키마 개수
     */
    public int getMaxSchemas() {
        return maxSchemas;
    }
    
    /**
     * 스키마 Allowlist 조회
     * 
     * @return 허용 스키마 목록 (쉼표로 구분), 없으면 null
     */
    public String getSchemaAllowlist() {
        return schemaAllowlist;
    }
    
    /**
     * 스키마 수집 실패 모드 조회
     * 
     * @return 실패 모드 ("fail-open" 또는 "fail-close")
     */
    public String getSchemaCollectionFailMode() {
        return schemaCollectionFailMode;
    }
    
    /**
     * 시간 문자열을 밀리초로 파싱
     * 
     * @param timeoutStr 시간 문자열 (예: "30s", "1m", "30000")
     * @param defaultValue 기본값 (밀리초)
     * @return 밀리초
     */
    private static long parseTimeout(String timeoutStr, long defaultValue) {
        if (timeoutStr == null || timeoutStr.trim().isEmpty()) {
            return defaultValue;
        }
        
        String trimmed = timeoutStr.trim().toLowerCase();
        try {
            if (trimmed.endsWith("s")) {
                // 초 단위
                long seconds = Long.parseLong(trimmed.substring(0, trimmed.length() - 1));
                return seconds * 1000;
            } else if (trimmed.endsWith("m")) {
                // 분 단위
                long minutes = Long.parseLong(trimmed.substring(0, trimmed.length() - 1));
                return minutes * 60 * 1000;
            } else {
                // 밀리초 단위 (숫자만)
                return Long.parseLong(trimmed);
            }
        } catch (NumberFormatException e) {
            log.warn("Failed to parse timeout: {} (using default: {}ms)", timeoutStr, defaultValue);
            return defaultValue;
        }
    }
    
    // InstanceConfigStorage는 HubIdManager에서 관리하므로 제거됨
}

