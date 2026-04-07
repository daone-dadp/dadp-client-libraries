package com.dadp.common.sync.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves the persistent storage directory for wrapper/aop runtime files.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>System property {@code dadp.storage.dir}</li>
 *   <li>Environment variable {@code DADP_STORAGE_DIR}</li>
 *   <li>{@code {user.dir}/dadp/wrapper/{instanceId}}</li>
 *   <li>{@code {user.dir}/dadp/wrapper/shared}</li>
 * </ol>
 *
 * <p>When an explicit storage root is provided and an instanceId exists, the
 * instanceId is appended unless the explicit path already ends with it.
 */
public final class StoragePathResolver {

    private static final String STORAGE_DIR_PROPERTY = "dadp.storage.dir";
    private static final String STORAGE_DIR_ENV = "DADP_STORAGE_DIR";

    private StoragePathResolver() {
    }

    public static String resolveStorageDir() {
        return resolveStorageDir(null);
    }

    public static String resolveStorageDir(String instanceId) {
        String normalizedInstanceId = normalize(instanceId);

        String configuredRoot = normalize(System.getProperty(STORAGE_DIR_PROPERTY));
        if (configuredRoot == null) {
            configuredRoot = normalize(System.getenv(STORAGE_DIR_ENV));
        }

        if (configuredRoot != null) {
            return appendInstanceIdIfNeeded(configuredRoot, normalizedInstanceId);
        }

        Path basePath = Paths.get(System.getProperty("user.dir"), "dadp", "wrapper");
        if (normalizedInstanceId != null) {
            return basePath.resolve(normalizedInstanceId).toString();
        }
        return basePath.resolve("shared").toString();
    }

    private static String appendInstanceIdIfNeeded(String rootPath, String instanceId) {
        if (instanceId == null) {
            return rootPath;
        }

        Path root = Paths.get(rootPath);
        Path fileName = root.getFileName();
        if (fileName != null && instanceId.equals(fileName.toString())) {
            return rootPath;
        }
        return root.resolve(instanceId).toString();
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
