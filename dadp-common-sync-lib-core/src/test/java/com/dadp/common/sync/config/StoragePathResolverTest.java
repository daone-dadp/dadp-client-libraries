package com.dadp.common.sync.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StoragePathResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void persistedStorageDirWinsOverEnvironmentCandidate() {
        String resolved = StoragePathResolver.resolveStorageDir(
                "customer-db",
                tempDir.resolve("persisted").toString(),
                tempDir.resolve("env").toString(),
                tempDir.resolve("work").toString());

        assertEquals(tempDir.resolve("persisted").resolve("customer-db").toString(), resolved);
    }

    @Test
    void environmentCandidateIsUsedOnlyWhenStorageIsNotPersisted() {
        String resolved = StoragePathResolver.resolveStorageDir(
                "customer-db",
                "",
                tempDir.resolve("env").toString(),
                tempDir.resolve("work").toString());

        assertEquals(tempDir.resolve("env").resolve("customer-db").toString(), resolved);
    }

    @Test
    void defaultPathIsUsedWhenNoPersistedOrEnvironmentStorageExists() {
        String resolved = StoragePathResolver.resolveStorageDir(
                "customer-db",
                null,
                null,
                tempDir.resolve("work").toString());

        assertEquals(tempDir.resolve("work").resolve("dadp").resolve("wrapper").resolve("customer-db").toString(), resolved);
    }

    @Test
    void readsPersistedWrapperStorageDirFromCliConfig() throws Exception {
        Path config = tempDir.resolve(".dadp").resolve("config.json");
        Files.createDirectories(config.getParent());
        Files.write(config,
                ("{\"wrapperStorageDir\":\"" + tempDir.resolve("locked").toString().replace("\\", "\\\\") + "\"}")
                        .getBytes(StandardCharsets.UTF_8));

        assertEquals(tempDir.resolve("locked").toString(), StoragePathResolver.readPersistedWrapperStorageDir(config));
    }

    @Test
    void ignoresMissingCliConfig() {
        assertNull(StoragePathResolver.readPersistedWrapperStorageDir(tempDir.resolve(".dadp").resolve("config.json")));
    }
}
