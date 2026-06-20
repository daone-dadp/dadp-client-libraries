package com.dadp.jdbc.sync;

import com.dadp.common.sync.config.StoragePathResolver;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Applies CLI refresh results to running wrapper JVMs.
 *
 * <p>The CLI owns runtime file writes. This watcher bridges those writes into
 * the live JDBC wrapper process so manual refresh is not limited to the next
 * application restart.</p>
 */
public final class WrapperRuntimeRefreshWatcher {

    private static final DadpLogger log = DadpLoggerFactory.getLogger(WrapperRuntimeRefreshWatcher.class);
    private static final Set<RuntimeRefreshTarget> targets = ConcurrentHashMap.newKeySet();
    private static final Set<Path> watchedDirectories = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static volatile WatchService watchService;

    private WrapperRuntimeRefreshWatcher() {
    }

    public interface RuntimeRefreshTarget {
        void onWrapperRuntimeRefresh();
    }

    public static void register(RuntimeRefreshTarget target) {
        if (target == null) {
            return;
        }
        targets.add(target);
        startIfNeeded();
    }

    public static void unregister(RuntimeRefreshTarget target) {
        if (target != null) {
            targets.remove(target);
        }
    }

    private static void startIfNeeded() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        Thread watcherThread = new Thread(WrapperRuntimeRefreshWatcher::runWatcher, "dadp-wrapper-runtime-refresh-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    private static void runWatcher() {
        try {
            watchService = FileSystems.newWatchService();
            Path root = Paths.get(StoragePathResolver.resolveWrapperStorageRoot()).toAbsolutePath().normalize();
            Files.createDirectories(root);
            registerDirectory(root);
            registerExistingChildren(root);
            log.info("Wrapper runtime refresh watcher started: root={}", root);

            while (true) {
                WatchKey key = watchService.take();
                Path directory = (Path) key.watchable();
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    Path changed = directory.resolve((Path) event.context()).toAbsolutePath().normalize();
                    if (Files.isDirectory(changed)) {
                        registerDirectory(changed);
                        notifyTargets(changed);
                    }
                    if (isRuntimeFileChange(changed)) {
                        notifyTargets(changed);
                    }
                }
                if (!key.reset()) {
                    watchedDirectories.remove(directory);
                }
            }
        } catch (ClosedWatchServiceException ignored) {
            log.debug("Wrapper runtime refresh watcher closed");
        } catch (Exception e) {
            started.set(false);
            log.warn("Wrapper runtime refresh watcher stopped: {}", e.getMessage());
        }
    }

    private static void registerExistingChildren(Path root) throws IOException {
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                if (Files.isDirectory(child)) {
                    registerDirectory(child);
                }
            }
        }
    }

    private static void registerDirectory(Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!watchedDirectories.add(normalized)) {
            return;
        }
        normalized.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        log.debug("Wrapper runtime refresh watcher registered directory: {}", normalized);
    }

    private static boolean isRuntimeFileChange(Path changed) {
        Path fileName = changed.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString();
        return "proxy-config.json".equals(name)
                || "policy-mappings.json".equals(name)
                || ".dadp-refresh-trigger".equals(name);
    }

    private static void notifyTargets(Path changed) {
        log.info("Wrapper runtime storage changed: file={}, targets={}", changed, targets.size());
        for (RuntimeRefreshTarget target : targets) {
            try {
                target.onWrapperRuntimeRefresh();
            } catch (Exception e) {
                log.warn("Wrapper runtime refresh target failed: {}", e.getMessage());
            }
        }
    }

    private static final class FileSystems {
        private static WatchService newWatchService() throws IOException {
            return java.nio.file.FileSystems.getDefault().newWatchService();
        }
    }
}
