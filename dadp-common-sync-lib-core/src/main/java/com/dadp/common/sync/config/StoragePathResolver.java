package com.dadp.common.sync.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves the persistent storage directory for wrapper runtime files.
 *
 * <p>DADP 6 storage is fixed to the wrapper library directory:
 * {@code <wrapper-lib-dir>/dadp/wrapper/<alias>}. The wrapper runtime does not
 * read CLI config, environment variables, system properties, or arbitrary
 * storage-dir overrides.
 */
public final class StoragePathResolver {

    private StoragePathResolver() {
    }

    public static String resolveStorageDir() {
        throw new IllegalStateException("Wrapper alias is required to resolve runtime storage directory");
    }

    public static String resolveStorageDir(String alias) {
        return resolveStorageDir(alias, resolveWrapperLibDir());
    }

    public static String resolveStorageDir(String alias, String wrapperLibDir) {
        String normalizedAlias = normalize(alias);
        String normalizedLibDir = normalize(wrapperLibDir);
        if (normalizedAlias == null) {
            throw new IllegalStateException("Wrapper alias is required to resolve runtime storage directory");
        }
        if (normalizedLibDir == null) {
            throw new IllegalStateException("Wrapper library directory cannot be resolved");
        }
        return Paths.get(resolveWrapperStorageRoot(normalizedLibDir), normalizedAlias).toString();
    }

    public static String resolveWrapperStorageRoot() {
        return resolveWrapperStorageRoot(resolveWrapperLibDir());
    }

    public static String resolveWrapperStorageRoot(String wrapperLibDir) {
        String normalizedLibDir = normalize(wrapperLibDir);
        if (normalizedLibDir == null) {
            throw new IllegalStateException("Wrapper library directory cannot be resolved");
        }
        return Paths.get(normalizedLibDir, "dadp", "wrapper").toString();
    }

    static String resolveWrapperLibDir() {
        try {
            java.security.CodeSource codeSource = StoragePathResolver.class
                    .getProtectionDomain()
                    .getCodeSource();
            if (codeSource != null && codeSource.getLocation() != null) {
                Path location = Paths.get(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
                if (java.nio.file.Files.isRegularFile(location)) {
                    Path parent = location.getParent();
                    return parent != null ? parent.toString() : null;
                }
                return location.toString();
            }
        } catch (Exception ignored) {
            // Caller receives an explicit failure below.
        }
        String classPath = normalize(System.getProperty("java.class.path"));
        if (classPath != null) {
            String firstEntry = classPath.split(java.io.File.pathSeparator, 2)[0];
            if (normalize(firstEntry) != null) {
                Path location = Paths.get(firstEntry).toAbsolutePath().normalize();
                if (java.nio.file.Files.isRegularFile(location)) {
                    Path parent = location.getParent();
                    return parent != null ? parent.toString() : null;
                }
                return location.toString();
            }
        }
        return null;
    }

    public static String resolveWrapperLibDirForDiagnostics() {
        return resolveWrapperLibDir();
    }

    public static String resolveStorageDirFromLibDir(String alias, String wrapperLibDir) {
        return resolveStorageDir(alias, wrapperLibDir);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed;
    }
}
