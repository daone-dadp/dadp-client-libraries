package com.dadp.common.sync.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StoragePathResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void storageDirIsResolvedFromWrapperLibDirAndAlias() {
        String resolved = StoragePathResolver.resolveStorageDir(
                "customer-db",
                tempDir.resolve("lib").toString());

        assertEquals(tempDir.resolve("lib").resolve("dadp").resolve("wrapper").resolve("customer-db").toString(), resolved);
    }

    @Test
    void sharedStorageDirIsResolvedFromWrapperLibDirWhenAliasIsMissing() {
        String resolved = StoragePathResolver.resolveStorageDir(
                "",
                tempDir.resolve("lib").toString());

        assertEquals(tempDir.resolve("lib").resolve("dadp").resolve("wrapper").resolve("shared").toString(), resolved);
    }

    @Test
    void explicitWrapperLibDirMustBePresent() {
        try {
            StoragePathResolver.resolveStorageDir("customer-db", "");
        } catch (IllegalStateException e) {
            assertEquals("Wrapper library directory cannot be resolved", e.getMessage());
            return;
        }
        throw new AssertionError("Expected IllegalStateException");
    }
}
