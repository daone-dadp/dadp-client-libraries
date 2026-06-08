package com.dadp.common.sync.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves the persistent storage directory for wrapper runtime files.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>CLI-persisted wrapper storage dir in {@code ~/.dadp/config.json}</li>
 *   <li>Environment variable {@code DADP_STORAGE_DIR}</li>
 *   <li>{@code {user.dir}/dadp/wrapper/{instanceId}}</li>
 *   <li>{@code {user.dir}/dadp/wrapper/shared}</li>
 * </ol>
 *
 * <p>{@code DADP_STORAGE_DIR} is an initial storage candidate only. After the
 * CLI has persisted a storage directory, changing the environment variable
 * must not move the runtime to a different storage path. Use the CLI force
 * command to change the persisted storage directory.
 *
 * <p>When an explicit storage root is provided and an instanceId exists, the
 * instanceId is appended unless the explicit path already ends with it.
 */
public final class StoragePathResolver {

    private static final String STORAGE_DIR_ENV = "DADP_STORAGE_DIR";
    private static final String CLI_CONFIG_DIR = ".dadp";
    private static final String CLI_CONFIG_FILE = "config.json";
    private static final String CLI_WRAPPER_STORAGE_FIELD = "wrapperStorageDir";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private StoragePathResolver() {
    }

    public static String resolveStorageDir() {
        return resolveStorageDir(null);
    }

    public static String resolveStorageDir(String instanceId) {
        String normalizedInstanceId = normalize(instanceId);
        String configuredRoot = readPersistedWrapperStorageDir();
        String envRoot = normalize(System.getenv(STORAGE_DIR_ENV));
        String userDir = System.getProperty("user.dir");

        return resolveStorageDir(normalizedInstanceId, configuredRoot, envRoot, userDir);
    }

    static String resolveStorageDir(String instanceId, String persistedRoot, String envRoot, String userDir) {
        String normalizedInstanceId = normalize(instanceId);
        String configuredRoot = normalize(persistedRoot);
        if (configuredRoot != null) {
            return appendInstanceIdIfNeeded(configuredRoot, normalizedInstanceId);
        }

        configuredRoot = normalize(envRoot);
        if (configuredRoot != null) {
            return appendInstanceIdIfNeeded(configuredRoot, normalizedInstanceId);
        }

        String normalizedUserDir = normalize(userDir);
        Path basePath = Paths.get(normalizedUserDir == null ? "." : normalizedUserDir, "dadp", "wrapper");
        if (normalizedInstanceId != null) {
            return basePath.resolve(normalizedInstanceId).toString();
        }
        return basePath.resolve("shared").toString();
    }

    private static String readPersistedWrapperStorageDir() {
        String userHome = normalize(System.getProperty("user.home"));
        if (userHome == null) {
            return null;
        }
        return readPersistedWrapperStorageDir(Paths.get(userHome, CLI_CONFIG_DIR, CLI_CONFIG_FILE));
    }

    static String readPersistedWrapperStorageDir(Path configPath) {
        if (configPath == null || !Files.isRegularFile(configPath)) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(configPath.toFile());
            JsonNode value = root == null ? null : root.get(CLI_WRAPPER_STORAGE_FIELD);
            if (value == null || !value.isTextual()) {
                return null;
            }
            return normalize(value.asText());
        } catch (IOException e) {
            return null;
        }
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
