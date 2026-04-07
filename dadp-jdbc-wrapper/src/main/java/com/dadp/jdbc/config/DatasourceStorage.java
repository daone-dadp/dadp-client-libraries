package com.dadp.jdbc.config;

import com.dadp.common.sync.config.StoragePathResolver;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Datasource ID local persistent storage.
 */
public class DatasourceStorage {

    private static final DadpLogger log = DadpLoggerFactory.getLogger(DatasourceStorage.class);
    private static final String STORAGE_FILE_NAME = "datasources.json";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void saveDatasource(String instanceId, String datasourceId, String dbVendor, String host,
                                      int port, String database, String schema) {
        try {
            File file = getStorageFile(instanceId);
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }

            Map<String, Object> datasources = loadAll(instanceId);
            String key = buildKey(dbVendor, host, port, database, schema);
            datasources.put(key, datasourceId);

            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, datasources);
            log.debug("Datasource ID saved: instanceId={}, {} -> {}", instanceId, key, datasourceId);
        } catch (Exception e) {
            log.warn("Failed to save Datasource ID: {}", e.getMessage());
        }
    }

    public static String loadDatasourceId(String instanceId, String dbVendor, String host,
                                          int port, String database, String schema) {
        try {
            Map<String, Object> datasources = loadAll(instanceId);
            String key = buildKey(dbVendor, host, port, database, schema);
            return (String) datasources.get(key);
        } catch (Exception e) {
            log.debug("Failed to load Datasource ID: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Legacy compatibility path. New code should always pass instanceId.
     */
    @Deprecated
    public static void saveDatasource(String datasourceId, String dbVendor, String host,
                                      int port, String database, String schema) {
        saveDatasource(null, datasourceId, dbVendor, host, port, database, schema);
    }

    /**
     * Legacy compatibility path. New code should always pass instanceId.
     */
    @Deprecated
    public static String loadDatasourceId(String dbVendor, String host,
                                          int port, String database, String schema) {
        return loadDatasourceId(null, dbVendor, host, port, database, schema);
    }

    private static File getStorageFile(String instanceId) {
        Path storageDir = Paths.get(StoragePathResolver.resolveStorageDir(instanceId));
        return storageDir.resolve(STORAGE_FILE_NAME).toFile();
    }

    private static String buildKey(String dbVendor, String host, int port, String database, String schema) {
        return dbVendor + "://" + host + ":" + port + "/" + database + (schema != null ? "/" + schema : "");
    }

    private static Map<String, Object> loadAll(String instanceId) {
        try {
            File file = getStorageFile(instanceId);
            if (file.exists()) {
                return objectMapper.readValue(file, new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception e) {
            log.debug("Failed to load Datasource storage file: {}", e.getMessage());
        }
        return new HashMap<>();
    }
}
