package com.dadp.common.sync.config;

import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;

/**
 * 스키마 수집 설정 리졸버
 * 
 * Hub에서 받은 스키마 수집 설정을 관리하고,
 * Hub가 다운되어도 저장된 설정을 사용할 수 있도록 합니다.
 * 
 * @author DADP Development Team
 * @version 5.4.0
 * @since 2026-01-09
 */
public class SchemaCollectionConfigResolver {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(SchemaCollectionConfigResolver.class);
    
    // 싱글톤 인스턴스 (기본 경로 사용)
    private static volatile SchemaCollectionConfigResolver defaultInstance = null;
    private static final Object singletonLock = new Object();
    
    // 현재 설정 버전
    private volatile Long currentVersion = null;
    
    // 현재 설정 (캐시)
    private volatile SchemaCollectionConfigStorage.SchemaCollectionConfig currentConfig = null;
    
    // 영구 저장소 (Hub 다운 시에도 사용)
    private final SchemaCollectionConfigStorage storage;
    
    /**
     * 기본 저장 디렉토리 조회
     * 시스템 프로퍼티 또는 환경 변수에서 읽고, 없으면 기본값 사용
     * 
     * @return 저장 디렉토리 경로
     */
    private static String getDefaultStorageDir() {
        return StoragePathResolver.resolveStorageDir();
    }
    
    /**
     * 기본 저장 디렉토리 조회 (instanceId 사용)
     * 시스템 프로퍼티 또는 환경 변수에서 읽고, 없으면 ./dadp/wrapper/instanceId 형태로 생성
     * 
     * @param instanceId 인스턴스 ID (별칭, 앱 구동 시점에 알 수 있음)
     * @return 저장 디렉토리 경로
     */
    private static String getDefaultStorageDir(String instanceId) {
        return StoragePathResolver.resolveStorageDir(instanceId);
    }
    
    /**
     * 싱글톤 인스턴스 조회 (기본 경로 사용)
     * 기본 경로는 시스템 프로퍼티(dadp.storage.dir) 또는 환경 변수(DADP_STORAGE_DIR)로 설정 가능
     * 
     * @return 싱글톤 SchemaCollectionConfigResolver 인스턴스
     */
    public static SchemaCollectionConfigResolver getInstance() {
        if (defaultInstance == null) {
            synchronized (singletonLock) {
                if (defaultInstance == null) {
                    defaultInstance = new SchemaCollectionConfigResolver();
                }
            }
        }
        return defaultInstance;
    }
    
    /**
     * 기본 생성자 (영구 저장소 자동 초기화)
     * 기본 경로는 시스템 프로퍼티(dadp.storage.dir) 또는 환경 변수(DADP_STORAGE_DIR)로 설정 가능
     */
    public SchemaCollectionConfigResolver() {
        this(new SchemaCollectionConfigStorage());
    }
    
    /**
     * instanceId를 사용한 생성자
     * 
     * @param instanceId 인스턴스 ID (별칭, 앱 구동 시점에 알 수 있음)
     */
    public SchemaCollectionConfigResolver(String instanceId) {
        this(new SchemaCollectionConfigStorage(instanceId));
    }
    
    /**
     * 커스텀 저장소 경로 지정
     * 
     * @param storageDir 저장 디렉토리
     * @param fileName 파일명
     */
    public SchemaCollectionConfigResolver(String storageDir, String fileName) {
        this(new SchemaCollectionConfigStorage(storageDir, fileName));
    }
    
    /**
     * SchemaCollectionConfigStorage를 직접 받는 생성자
     */
    public SchemaCollectionConfigResolver(SchemaCollectionConfigStorage storage) {
        this.storage = storage;
        // 저장된 설정 정보 로드 (Hub 다운 시에도 사용)
        loadConfigFromStorage();
    }
    
    /**
     * 영구 저장소에서 설정 정보 로드
     */
    private void loadConfigFromStorage() {
        SchemaCollectionConfigStorage.SchemaCollectionConfig storedConfig = storage.loadConfig();
        if (storedConfig != null) {
            this.currentConfig = storedConfig;
            // 저장된 버전 정보도 로드
            Long storedVersion = storage.loadVersion();
            if (storedVersion != null) {
                this.currentVersion = storedVersion;
            } else {
                // 버전이 없으면 0으로 초기화 (첫 실행 시)
                this.currentVersion = 0L;
                log.debug("No version info in persistent storage, initializing to 0");
            }
            log.debug("Schema collection config loaded from persistent storage: timeout={}ms, maxSchemas={}, allowlist={}, failMode={}, version={}",
                    storedConfig.getTimeoutMs(), storedConfig.getMaxSchemas(), storedConfig.getAllowlist(),
                    storedConfig.getFailMode(), this.currentVersion);
        } else {
            // 설정이 없어도 버전은 0으로 초기화 (첫 실행 시)
            this.currentVersion = 0L;
            log.debug("No schema collection config in persistent storage (will load from Hub), initializing version=0");
        }
    }
    
    /**
     * 스키마 수집 설정 조회
     * 
     * @return 설정 정보 (없으면 null)
     */
    public SchemaCollectionConfigStorage.SchemaCollectionConfig getConfig() {
        return currentConfig;
    }
    
    /**
     * 스키마 수집 설정 캐시 갱신
     * Hub API로부터 최신 설정 정보를 받아 캐시를 갱신하고 영구 저장소에 저장합니다.
     * 
     * @param config 설정 정보 (null 가능)
     * @param version 설정 버전 (null 가능)
     */
    public void refreshConfig(SchemaCollectionConfigStorage.SchemaCollectionConfig config, Long version) {
        log.trace("Schema collection config cache refresh started: timeout={}ms, maxSchemas={}, allowlist={}, failMode={}, version={}",
                config != null ? config.getTimeoutMs() : null,
                config != null ? config.getMaxSchemas() : null,
                config != null ? config.getAllowlist() : null,
                config != null ? config.getFailMode() : null,
                version);
        
        this.currentConfig = config;
        
        // 버전 정보 저장 (version이 null이면 0으로 초기화)
        if (version != null) {
            this.currentVersion = version;
            log.debug("Schema collection config version updated: version={}", version);
        } else {
            // Hub에서 버전을 받지 못한 경우 0으로 초기화 (첫 실행 시나 재등록 시)
            this.currentVersion = 0L;
            log.debug("No version info received from Hub (version=null), initializing to 0");
        }
        
        // 영구 저장소에 저장 (Hub 다운 시에도 사용 가능하도록)
        boolean saved = storage.saveConfig(config, version);
        if (saved) {
            log.debug("Schema collection config persisted: timeout={}ms, maxSchemas={}, allowlist={}, failMode={}, version={}",
                    config != null ? config.getTimeoutMs() : null,
                    config != null ? config.getMaxSchemas() : null,
                    config != null ? config.getAllowlist() : null,
                    config != null ? config.getFailMode() : null,
                    version);
        } else {
            log.warn("Schema collection config persistence failed (using memory cache only)");
        }
        
        log.trace("Schema collection config cache refresh completed");
    }
    
    /**
     * 현재 설정 버전 조회
     * 
     * @return 설정 버전 (없으면 null)
     */
    public Long getCurrentVersion() {
        return currentVersion;
    }
    
    /**
     * 설정 버전 설정 (메모리만 업데이트, 영구저장소 저장은 refreshConfig에서 수행)
     * 
     * @param version 설정 버전
     */
    public void setCurrentVersion(Long version) {
        this.currentVersion = version;
    }
    
    /**
     * 영구 저장소에서 설정 정보 다시 로드
     * Hub 연결 실패 시 호출하여 저장된 정보 사용
     */
    public void reloadFromStorage() {
        SchemaCollectionConfigStorage.SchemaCollectionConfig storedConfig = storage.loadConfig();
        if (storedConfig != null) {
            this.currentConfig = storedConfig;
            Long storedVersion = storage.loadVersion();
            if (storedVersion != null) {
                this.currentVersion = storedVersion;
            }
            log.debug("Schema collection config reloaded from persistent storage: timeout={}ms, maxSchemas={}, allowlist={}, failMode={}, version={}",
                    storedConfig.getTimeoutMs(), storedConfig.getMaxSchemas(), storedConfig.getAllowlist(),
                    storedConfig.getFailMode(), storedVersion);
        } else {
            log.warn("No schema collection config in persistent storage");
        }
    }
    
    /**
     * 영구 저장소 경로 조회
     * 
     * @return 저장 경로
     */
    public String getStoragePath() {
        return storage.getStoragePath();
    }
}
