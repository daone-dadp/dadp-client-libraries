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
 *
 * <p>5.9 line treats datasourceId as the canonical Hub-owned identity for one
 * alias-scoped DB group. The wrapper no longer treats host/port/database/schema
 * fingerprints as the primary datasource identity.</p>
 */
public class DatasourceStorage {

    private static final DadpLogger log = DadpLoggerFactory.getLogger(DatasourceStorage.class);
    private static final String STORAGE_FILE_NAME = "datasources.json";
    private static final String CANONICAL_DATASOURCE_ID_KEY = "canonicalDatasourceId";
    private static final String LAST_OBSERVED_FINGERPRINT_KEY = "lastObservedFingerprint";
    private static final String LAST_OBSERVED_VENDOR_KEY = "lastObservedDbVendor";
    private static final String LAST_OBSERVED_HOST_KEY = "lastObservedHost";
    private static final String LAST_OBSERVED_PORT_KEY = "lastObservedPort";
    private static final String LAST_OBSERVED_DATABASE_KEY = "lastObservedDatabase";
    private static final String LAST_OBSERVED_SCHEMA_KEY = "lastObservedSchema";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void saveDatasource(String alias, String datasourceId, String dbVendor, String host,
                                      int port, String database, String schema) {
        try {
            File file = getStorageFile(alias);
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }

            Map<String, Object> datasources = loadAll(alias);
            datasources.put(CANONICAL_DATASOURCE_ID_KEY, datasourceId);
            datasources.put(LAST_OBSERVED_FINGERPRINT_KEY, buildKey(dbVendor, host, port, database, schema));
            datasources.put(LAST_OBSERVED_VENDOR_KEY, dbVendor);
            datasources.put(LAST_OBSERVED_HOST_KEY, host);
            datasources.put(LAST_OBSERVED_PORT_KEY, port);
            datasources.put(LAST_OBSERVED_DATABASE_KEY, database);
            datasources.put(LAST_OBSERVED_SCHEMA_KEY, schema);

            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, datasources);
            log.debug("Canonical datasourceId saved: alias={}, datasourceId={}", alias, datasourceId);
        } catch (Exception e) {
            log.warn("Failed to save Datasource ID: {}", e.getMessage());
        }
    }

    public static String loadDatasourceId(String alias, String dbVendor, String host,
                                          int port, String database, String schema) {
        try {
            Map<String, Object> datasources = loadAll(alias);
            Object canonical = datasources.get(CANONICAL_DATASOURCE_ID_KEY);
            if (canonical instanceof String && !((String) canonical).trim().isEmpty()) {
                return (String) canonical;
            }

            // Legacy fallback for pre-5.9 fingerprint-keyed storage files.
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

    private static File getStorageFile(String alias) {
        Path storageDir = Paths.get(StoragePathResolver.resolveStorageDir(alias));
        return storageDir.resolve(STORAGE_FILE_NAME).toFile();
    }

    private static String buildKey(String dbVendor, String host, int port, String database, String schema) {
        return dbVendor + "://" + host + ":" + port + "/" + database + (schema != null ? "/" + schema : "");
    }

    private static Map<String, Object> loadAll(String alias) {
        try {
            File file = getStorageFile(alias);
            if (file.exists()) {
                return objectMapper.readValue(file, new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception e) {
            log.debug("Failed to load Datasource storage file: {}", e.getMessage());
        }
        return new HashMap<>();
    }
}
