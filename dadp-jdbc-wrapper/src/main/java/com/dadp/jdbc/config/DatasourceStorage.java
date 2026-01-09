package com.dadp.jdbc.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Datasource ID 로컬 저장소
 * 
 * Hub가 죽어 있어도 다음 기동 시 datasourceId를 사용할 수 있도록 로컬에 저장
 * 
 * @author DADP Development Team
 * @version 4.17.0
 * @since 2025-12-05
 */
public class DatasourceStorage {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(DatasourceStorage.class);
    private static final String STORAGE_FILE = System.getProperty("user.home") + "/.dadp-wrapper/datasources.json";
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Datasource ID 저장
     * 
     * @param datasourceId Datasource ID
     * @param dbVendor DB 벤더
     * @param host 호스트
     * @param port 포트
     * @param database 데이터베이스명
     * @param schema 스키마명
     */
    public static void saveDatasource(String datasourceId, String dbVendor, String host, 
                                     int port, String database, String schema) {
        try {
            File file = new File(STORAGE_FILE);
            file.getParentFile().mkdirs();
            
            Map<String, Object> datasources = loadAll();
            String key = buildKey(dbVendor, host, port, database, schema);
            datasources.put(key, datasourceId);
            
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, datasources);
            log.debug("Datasource ID 저장: {} → {}", key, datasourceId);
        } catch (Exception e) {
            log.warn("Datasource ID 저장 실패: {}", e.getMessage());
        }
    }
    
    /**
     * Datasource ID 로드
     * 
     * @param dbVendor DB 벤더
     * @param host 호스트
     * @param port 포트
     * @param database 데이터베이스명
     * @param schema 스키마명
     * @return Datasource ID (없으면 null)
     */
    public static String loadDatasourceId(String dbVendor, String host, 
                                          int port, String database, String schema) {
        try {
            Map<String, Object> datasources = loadAll();
            String key = buildKey(dbVendor, host, port, database, schema);
            return (String) datasources.get(key);
        } catch (Exception e) {
            log.debug("Datasource ID 로드 실패: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 물리 키 생성
     */
    private static String buildKey(String dbVendor, String host, int port, String database, String schema) {
        return dbVendor + "://" + host + ":" + port + "/" + database + (schema != null ? "/" + schema : "");
    }
    
    /**
     * 모든 Datasource 정보 로드
     */
    private static Map<String, Object> loadAll() {
        try {
            File file = new File(STORAGE_FILE);
            if (file.exists()) {
                return objectMapper.readValue(file, new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception e) {
            log.debug("Datasource 저장 파일 로드 실패: {}", e.getMessage());
        }
        return new HashMap<>();
    }
}

