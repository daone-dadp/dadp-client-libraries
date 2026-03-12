package com.dadp.jdbc.schema;

import com.dadp.jdbc.policy.SchemaRecognizer;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;

/**
 * 스키마 메타데이터 관리자
 * 
 * 스키마 메타데이터를 조회하고 캐싱합니다.
 * 
 * @author DADP Development Team
 * @version 3.0.0
 * @since 2025-11-07
 */
public class SchemaMetadataManager {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(SchemaMetadataManager.class);
    
    private final SchemaRecognizer schemaRecognizer;
    private final ConcurrentHashMap<String, List<SchemaRecognizer.SchemaMetadata>> schemaCache = new ConcurrentHashMap<>();
    private final AtomicLong lastSyncTime = new AtomicLong(0);
    
    // 캐시 TTL (기본 1시간)
    private static final long CACHE_TTL_MS = 3600000; // 1시간
    
    public SchemaMetadataManager() {
        this.schemaRecognizer = new SchemaRecognizer();
    }
    
    /**
     * 스키마 메타데이터 조회 (캐시 사용)
     * 
     * @param connection DB 연결
     * @return 스키마 메타데이터 목록
     */
    public List<SchemaRecognizer.SchemaMetadata> getSchemaMetadata(Connection connection) throws Exception {
        String cacheKey = getCacheKey(connection);
        
        // 캐시 확인
        List<SchemaRecognizer.SchemaMetadata> cached = schemaCache.get(cacheKey);
        long now = System.currentTimeMillis();
        
        if (cached != null && (now - lastSyncTime.get()) < CACHE_TTL_MS) {
            log.debug("Schema metadata cache hit: {} columns", cached.size());
            return cached;
        }
        
        // 캐시 미스 또는 TTL 만료 → 다시 조회
        log.info("Re-fetching schema metadata (cache miss or TTL expired)");
        // datasourceId는 null로 전달 (스키마 수집 시에는 필요 없음, Hub 전송 시에만 필요)
        List<SchemaRecognizer.SchemaMetadata> schemas = schemaRecognizer.collectSchemaMetadata(connection, null);
        
        // 캐시 갱신
        schemaCache.put(cacheKey, schemas);
        lastSyncTime.set(now);
        
        return schemas;
    }
    
    /**
     * 스키마 메타데이터 강제 갱신
     */
    public List<SchemaRecognizer.SchemaMetadata> refreshSchemaMetadata(Connection connection) throws Exception {
        log.info("Forcing schema metadata refresh");
        String cacheKey = getCacheKey(connection);
        
        // datasourceId는 null로 전달 (스키마 수집 시에는 필요 없음, Hub 전송 시에만 필요)
        List<SchemaRecognizer.SchemaMetadata> schemas = schemaRecognizer.collectSchemaMetadata(connection, null);
        schemaCache.put(cacheKey, schemas);
        lastSyncTime.set(System.currentTimeMillis());
        
        return schemas;
    }
    
    /**
     * 캐시 키 생성
     */
    private String getCacheKey(Connection connection) {
        try {
            String url = connection.getMetaData().getURL();
            String catalog = connection.getCatalog();
            return url + "::" + catalog;
        } catch (Exception e) {
            return "default";
        }
    }
    
    /**
     * 캐시 초기화
     */
    public void clearCache() {
        schemaCache.clear();
        lastSyncTime.set(0);
        log.info("Schema metadata cache cleared");
    }
}

