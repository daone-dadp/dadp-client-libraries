package com.dadp.jdbc.config;

import com.dadp.common.sync.config.StoragePathResolver;
// TODO: Hub API 구현 후 주석 해제
// import com.dadp.common.sync.config.SchemaCollectionConfigResolver;
// import com.dadp.common.sync.config.SchemaCollectionConfigStorage;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;

import java.util.Map;

/**
 * Proxy 설정 관리
 *
 * 6.0 configuration boundary:
 * - JDBC URL: hubUrl and alias only
 * - ENV/system property: storage bootstrap only
 * - Hub runtime snapshot: failOpen, enabled, cryptoMode, debug/stats, policy bindings
 * 
 * @author DADP Development Team
 * @version 3.0.0
 * @since 2025-11-07
 */
public class ProxyConfig {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(ProxyConfig.class);
    
    private static final long DEFAULT_SCHEMA_COLLECTION_TIMEOUT_MS = 30000; // 30초
    private static final int DEFAULT_MAX_SCHEMAS = 100;
    private static final String DEFAULT_SCHEMA_COLLECTION_FAIL_MODE = "fail-open"; // fail-open 또는 fail-close
    
    private static volatile ProxyConfig instance;
    private final String hubUrl;  // Hub URL (스키마 동기화 + 암복호화 라우팅, Hub가 Engine/Gateway로 자동 라우팅)
    private final boolean hubUrlConfigured;
    private final String alias;  // 공유 DB 그룹 별칭
    private final boolean aliasConfigured;
    private volatile String hubId;  // Hub가 발급한 tenantId (X-DADP-Tenant-Id 헤더에 사용, HubIdManager에서 관리)
    private final boolean failOpen;
    private final boolean enableLogging;  // DADP 통합 로그 활성화
    private final String singleTransportMode;  // 단건 암복호화 transport mode (json | binary-framed)
    private final String engineTransport;  // Engine transport mode (http | binary-tcp)
    private final int engineBinaryPort;  // Engine binary TCP port
    private final String cryptoMode;  // Wrapper crypto execution mode (remote | local)
    private final boolean cryptoLocalFallbackRemote;  // Local crypto failure fallback to remote Engine
    private final int cryptoLocalTimeoutMs;  // Hub policy/key material fetch timeout for local crypto
    private final boolean wrapperCryptoStatsEnabled;  // Wrapper local crypto aggregated stats enabled
    private final String wrapperCryptoStatsAggregationLevel;  // Wrapper local crypto aggregated stats level
    private final boolean sqlMappingDebugEnabled;  // SQL -> column mapping debug enabled
    private final boolean autoPolicyMappingSyncEnabled;  // 6.0 default: manual mapping refresh
    private final boolean cryptoProfileEnabled;  // Wrapper 암복호화 stage profiling 활성화 (기본값: false)
    private final String cryptoProfilePath;  // Wrapper 암복호화 stage profiling 출력 경로
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
        String hubUrlProp = trimToNull(urlParams != null ? urlParams.get("hubUrl") : null);
        if (hubUrlProp == null || hubUrlProp.trim().isEmpty()) {
            this.hubUrl = null;
            this.hubUrlConfigured = false;
            emitMissingRequiredHubUrl();
        } else {
            this.hubUrl = hubUrlProp.trim();
            this.hubUrlConfigured = true;
        }

        String aliasProp = trimToNull(urlParams != null ? urlParams.get("alias") : null);
        if (aliasProp == null || aliasProp.trim().isEmpty()) {
            this.alias = null;
            this.aliasConfigured = false;
            emitMissingRequiredAlias();
        } else {
            this.alias = aliasProp.trim();
            this.aliasConfigured = true;
        }

        this.failOpen = false;
        this.enableLogging = false;
        DadpLoggerFactory.setLoggingEnabled(this.enableLogging);

        this.singleTransportMode = normalizeSingleTransportMode(null);
        this.engineTransport = normalizeEngineTransport(null);
        this.engineBinaryPort = parsePort(null, 9104);
        this.cryptoMode = normalizeCryptoMode(null);

        this.cryptoLocalFallbackRemote = true;
        this.cryptoLocalTimeoutMs = parsePositiveInt(null, 30000, "crypto local timeout");
        this.wrapperCryptoStatsEnabled = false;
        this.wrapperCryptoStatsAggregationLevel =
                normalizeWrapperCryptoStatsAggregationLevel(null);
        this.sqlMappingDebugEnabled = false;
        this.autoPolicyMappingSyncEnabled = false;
        this.cryptoProfileEnabled = false;
        String defaultProfileDir = StoragePathResolver.resolveStorageDir(this.alias);
        this.cryptoProfilePath = defaultProfileDir + java.io.File.separator + "crypto-stage-profile.ndjson";

        this.schemaCollectionTimeoutMs = DEFAULT_SCHEMA_COLLECTION_TIMEOUT_MS;
        this.maxSchemas = DEFAULT_MAX_SCHEMAS;
        this.schemaAllowlist = null;
        this.schemaCollectionFailMode = DEFAULT_SCHEMA_COLLECTION_FAIL_MODE;

        this.enabled = true;

        // hubId는 HubIdManager에서 전역으로 관리 (지연 로드, 오케스트레이터의 runBootstrapFlow()에서만 로드)
        // 생성자에서 파일을 읽지 않음
        this.hubId = null;
        
        // Connection Pool에서 반복적으로 생성되므로 TRACE 레벨로 처리 (로그 정책 참조)
        log.trace("Proxy config loaded:");
        log.trace("   - Hub URL (schema sync + crypto routing): {}", this.hubUrl);
        log.trace("   - Hub URL configured from JDBC URL: {}", this.hubUrlConfigured);
        log.trace("   - Alias: {}", this.alias);
        log.trace("   - Fail-open: {}", this.failOpen);
        log.trace("   - Wrapper enabled: {}", this.enabled);
        log.trace("   - DADP logging enabled: {}", this.enableLogging);
        log.trace("   - Single transport mode: {}", this.singleTransportMode);
        log.trace("   - Engine transport: {}", this.engineTransport);
        log.trace("   - Engine binary port: {}", this.engineBinaryPort);
        log.trace("   - Wrapper crypto mode: {}", this.cryptoMode);
        log.trace("   - Wrapper local crypto fallback remote: {}", this.cryptoLocalFallbackRemote);
        log.trace("   - Wrapper local crypto timeout: {}ms", this.cryptoLocalTimeoutMs);
        log.trace("   - Wrapper local crypto stats enabled: {}", this.wrapperCryptoStatsEnabled);
        log.trace("   - Wrapper local crypto stats aggregation level: {}", this.wrapperCryptoStatsAggregationLevel);
        log.trace("   - SQL mapping debug enabled: {}", this.sqlMappingDebugEnabled);
        log.trace("   - Auto policy mapping sync enabled: {}", this.autoPolicyMappingSyncEnabled);
        log.trace("   - Wrapper crypto profile enabled: {}", this.cryptoProfileEnabled);
        log.trace("   - Wrapper crypto profile path: {}", this.cryptoProfilePath);
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

    public boolean isHubUrlConfigured() {
        return hubUrlConfigured;
    }
    
    public String getAlias() {
        return alias;
    }

    /**
     * Legacy compatibility accessor.
     *
     * <p>In the 5.9 line, wrapper runtime should treat this value as alias.</p>
     */
    public String getInstanceId() {
        return alias;
    }

    private static void emitMissingRequiredAlias() {
        String message = "DADP wrapper startup failed: missing required alias. Configure JDBC URL alias.";
        System.err.println(message);
        try {
            log.error(message);
        } catch (Exception ignored) {
            // System.err emission is the mandatory fallback for startup failure.
        }
    }

    private static void emitMissingRequiredHubUrl() {
        String message = "DADP wrapper startup failed: missing required hubUrl. Configure JDBC URL hubUrl.";
        System.err.println(message);
        try {
            log.error(message);
        } catch (Exception ignored) {
            // System.err emission is the mandatory fallback for startup failure.
        }
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

    public boolean isStartupReady() {
        return hubUrlConfigured && aliasConfigured;
    }

    public boolean isRuntimeActive() {
        return enabled && hubUrlConfigured && aliasConfigured;
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

    public String getSingleTransportMode() {
        return singleTransportMode;
    }

    public String getEngineTransport() {
        return engineTransport;
    }

    public int getEngineBinaryPort() {
        return engineBinaryPort;
    }

    public String getCryptoMode() {
        return cryptoMode;
    }

    public boolean isCryptoLocalFallbackRemote() {
        return cryptoLocalFallbackRemote;
    }

    public int getCryptoLocalTimeoutMs() {
        return cryptoLocalTimeoutMs;
    }

    public boolean isWrapperCryptoStatsEnabled() {
        return wrapperCryptoStatsEnabled;
    }

    public String getWrapperCryptoStatsAggregationLevel() {
        return wrapperCryptoStatsAggregationLevel;
    }

    public boolean isSqlMappingDebugEnabled() {
        return sqlMappingDebugEnabled;
    }

    public boolean isAutoPolicyMappingSyncEnabled() {
        return autoPolicyMappingSyncEnabled;
    }

    /**
     * Wrapper 암복호화 stage profiling 활성화 여부 조회
     *
     * @return profiling 활성화 여부
     */
    public boolean isCryptoProfileEnabled() {
        return cryptoProfileEnabled;
    }

    /**
     * Wrapper 암복호화 stage profiling 출력 경로 조회
     *
     * @return profiling NDJSON 파일 경로
     */
    public String getCryptoProfilePath() {
        return cryptoProfilePath;
    }

    private static String normalizeSingleTransportMode(String mode) {
        if (mode == null || mode.trim().isEmpty()) {
            return "json";
        }

        String normalized = mode.trim().toLowerCase();
        if ("binary-framed".equals(normalized) || "json".equals(normalized)) {
            return normalized;
        }

        log.warn("Unsupported single transport mode: {} (falling back to json)", mode);
        return "json";
    }

    private static String normalizeEngineTransport(String mode) {
        if (mode == null || mode.trim().isEmpty()) {
            return "http";
        }

        String normalized = mode.trim().toLowerCase();
        if ("netty-binary".equals(normalized)) {
            normalized = "binary-tcp";
        }
        if ("http".equals(normalized) || "binary-tcp".equals(normalized)) {
            return normalized;
        }

        log.warn("Unsupported engine transport: {} (falling back to http)", mode);
        return "http";
    }

    private static String normalizeCryptoMode(String mode) {
        if (mode == null || mode.trim().isEmpty()) {
            return "remote";
        }

        String normalized = mode.trim().toLowerCase();
        if ("remote".equals(normalized) || "local".equals(normalized)) {
            return normalized;
        }

        log.warn("Unsupported wrapper crypto mode: {} (falling back to remote)", mode);
        return "remote";
    }

    private static String normalizeWrapperCryptoStatsAggregationLevel(String level) {
        if (level == null || level.trim().isEmpty()) {
            return "1hour";
        }

        String normalized = level.trim().toLowerCase();
        if ("1hour".equals(normalized) || "1day".equals(normalized)) {
            return normalized;
        }

        log.warn("Unsupported wrapper crypto stats aggregation level: {} (falling back to 1hour)", level);
        return "1hour";
    }

    private static int parsePort(String value, int defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 && parsed <= 65535 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            log.warn("Invalid engine binary port: {} (falling back to {})", value, defaultValue);
            return defaultValue;
        }
    }

    private static int parsePositiveInt(String value, int defaultValue, String label) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            log.warn("Invalid {}: {} (falling back to {})", label, value, defaultValue);
            return defaultValue;
        }
    }

    private static String trimToNull(String value) {
        return value != null && !value.trim().isEmpty() ? value.trim() : null;
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
