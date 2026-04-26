package com.dadp.jdbc.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatasourceStorageTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearStorageDirProperty() {
        System.clearProperty("dadp.storage.dir");
    }

    @Test
    void canonicalDatasourceIdIsSharedByAliasRegardlessOfConnectionFingerprint() {
        System.setProperty("dadp.storage.dir", tempDir.toString());

        DatasourceStorage.saveDatasource(
                "shared-db-alias",
                "ds_canonical_123",
                "postgresql",
                "10.0.0.11",
                5432,
                "customer_db",
                "public");

        String loaded = DatasourceStorage.loadDatasourceId(
                "shared-db-alias",
                "postgresql",
                "10.0.0.99",
                6432,
                "customer_db",
                "public");

        assertEquals("ds_canonical_123", loaded);
    }
}
