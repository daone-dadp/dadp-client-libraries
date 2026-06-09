package com.dadp.common.sync.config;

import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;

/**
 * Single manager for DADP 6 wrapper runtime configuration.
 *
 * Runtime identity is loaded from CLI-owned proxy-config.json.
 *
 * Persistent runtime values:
 * - tenantId
 * - cryptoMode, failOpen, policySyncAutoEnabled
 * - runtimeVersion
 *
 * Memory-only runtime values:
 * - enabled
 */
public class WrapperRuntimeConfigManager {

    private static final DadpLogger log = DadpLoggerFactory.getLogger(WrapperRuntimeConfigManager.class);
    private static final String DEFAULT_CRYPTO_MODE = "remote";

    private final InstanceConfigStorage configStorage;
    private final String hubUrl;
    private final InstanceIdProvider aliasProvider;
    private final TenantIdChangeCallback changeCallback;

    private volatile String cachedTenantId;
    private volatile String cachedRuntimeVersion;
    private volatile String cryptoMode = DEFAULT_CRYPTO_MODE;
    private volatile boolean failOpen = false;
    private volatile boolean policySyncAutoEnabled = false;
    private volatile boolean enabled = true;
    private volatile String refreshUrl;

    public interface TenantIdChangeCallback {
        void onTenantIdChanged(String oldTenantId, String newTenantId);
    }

    public WrapperRuntimeConfigManager(InstanceConfigStorage configStorage,
                                       String hubUrl,
                                       InstanceIdProvider aliasProvider,
                                       TenantIdChangeCallback changeCallback) {
        this.configStorage = configStorage;
        this.hubUrl = hubUrl;
        this.aliasProvider = aliasProvider;
        this.changeCallback = changeCallback;
        this.refreshUrl = trimToNull(hubUrl);
    }

    public String loadFromStorage() {
        String alias = aliasProvider.getInstanceId();
        InstanceConfigStorage.ConfigData stored = configStorage.loadConfig(hubUrl, alias);
        if (stored == null) {
            log.debug("No wrapper runtime config in persistent storage");
            return null;
        }

        this.cachedRuntimeVersion = trimToNull(stored.getRuntimeVersion());
        this.refreshUrl = firstNonBlank(stored.getRefreshUrl(), this.refreshUrl);
        this.cryptoMode = normalizeCryptoMode(stored.getCryptoMode());
        this.failOpen = Boolean.TRUE.equals(stored.getFailOpen());
        this.policySyncAutoEnabled = Boolean.TRUE.equals(stored.getPolicySyncAutoEnabled());

        String storedTenantId = trimToNull(stored.getTenantId());
        if (storedTenantId != null) {
            setTenantId(storedTenantId, false);
        }
        log.debug("Wrapper runtime config loaded: tenantId={}, cryptoMode={}, failOpen={}, policySyncAutoEnabled={}",
                cachedTenantId, cryptoMode, failOpen, policySyncAutoEnabled);
        return storedTenantId;
    }

    public void setTenantId(String tenantId, boolean saveToStorage) {
        String normalizedTenantId = trimToNull(tenantId);
        if (normalizedTenantId == null) {
            return;
        }

        String oldTenantId = this.cachedTenantId;
        if (oldTenantId != null && !oldTenantId.equals(normalizedTenantId)) {
            log.warn("Ignoring tenantId change. DADP 6 wrapper tenantId is immutable once stored: current={}, requested={}",
                    oldTenantId, normalizedTenantId);
            return;
        }
        if (oldTenantId != null) {
            return;
        }

        this.cachedTenantId = normalizedTenantId;
        if (saveToStorage) {
            saveEnrollmentToStorage();
        }
        if (changeCallback != null && saveToStorage) {
            try {
                changeCallback.onTenantIdChanged(null, normalizedTenantId);
            } catch (Exception e) {
                log.warn("tenantId callback failed: {}", e.getMessage());
            }
        }
    }

    public void setWrapperEnrollment(String tenantId,
                                     String runtimeVersion,
                                     boolean saveToStorage) {
        String normalizedTenantId = trimToNull(tenantId);
        if (normalizedTenantId == null) {
            return;
        }
        setTenantId(normalizedTenantId, false);
        String normalizedRuntimeVersion = trimToNull(runtimeVersion);
        if (normalizedRuntimeVersion != null) {
            this.cachedRuntimeVersion = normalizedRuntimeVersion;
        }
        if (saveToStorage) {
            saveEnrollmentToStorage();
        }
    }

    public void applyRefreshOptions(Boolean enabled,
                                    String cryptoMode,
                                    Boolean failOpen,
                                    Boolean policySyncAutoEnabled,
                                    String runtimeVersion,
                                    boolean saveToStorage) {
        if (enabled != null) {
            this.enabled = enabled.booleanValue();
        }
        String normalizedCryptoMode = trimToNull(cryptoMode);
        if (normalizedCryptoMode != null) {
            this.cryptoMode = normalizeCryptoMode(normalizedCryptoMode);
        }
        if (failOpen != null) {
            this.failOpen = failOpen.booleanValue();
        }
        if (policySyncAutoEnabled != null) {
            this.policySyncAutoEnabled = policySyncAutoEnabled.booleanValue();
        }
        String normalizedRuntimeVersion = trimToNull(runtimeVersion);
        if (normalizedRuntimeVersion != null) {
            this.cachedRuntimeVersion = normalizedRuntimeVersion;
        }
        if (saveToStorage) {
            configStorage.saveRuntimeOptions(
                    this.cryptoMode,
                    Boolean.valueOf(this.failOpen),
                    Boolean.valueOf(this.policySyncAutoEnabled),
                    this.cachedRuntimeVersion);
        }
    }

    public String canonicalRefreshUrl() {
        String canonical = trimToNull(refreshUrl);
        if (canonical != null) {
            return canonical;
        }
        if (hubUrl == null || hubUrl.trim().isEmpty() || cachedTenantId == null) {
            return null;
        }
        String base = hubUrl.endsWith("/") ? hubUrl.substring(0, hubUrl.length() - 1) : hubUrl;
        return base + "/hub/api/v1/runtime/wrappers/" + cachedTenantId + "/refresh";
    }

    public boolean hasRuntimeEnrollment() {
        return hasTenantId();
    }

    public boolean hasTenantId() {
        return trimToNull(cachedTenantId) != null;
    }

    public String getCachedTenantId() {
        return cachedTenantId;
    }

    public String getCachedRuntimeVersion() {
        return cachedRuntimeVersion;
    }

    public String getCryptoMode() {
        return cryptoMode;
    }

    public boolean isFailOpen() {
        return failOpen;
    }

    public boolean isPolicySyncAutoEnabled() {
        return policySyncAutoEnabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void clear() {
        String oldTenantId = this.cachedTenantId;
        this.cachedTenantId = null;
        this.cachedRuntimeVersion = null;
        this.cryptoMode = DEFAULT_CRYPTO_MODE;
        this.failOpen = false;
        this.policySyncAutoEnabled = false;
        this.enabled = true;
        this.refreshUrl = trimToNull(hubUrl);
        if (changeCallback != null && oldTenantId != null) {
            try {
                changeCallback.onTenantIdChanged(oldTenantId, null);
            } catch (Exception e) {
                log.warn("tenantId clear callback failed: {}", e.getMessage());
            }
        }
    }

    private void saveEnrollmentToStorage() {
        if (cachedTenantId == null) {
            return;
        }
        configStorage.saveConfig(
                cachedTenantId,
                hubUrl,
                null,
                Boolean.valueOf(failOpen),
                cachedRuntimeVersion);
    }

    private static String normalizeCryptoMode(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return DEFAULT_CRYPTO_MODE;
        }
        normalized = normalized.toLowerCase();
        return "local".equals(normalized) ? "local" : DEFAULT_CRYPTO_MODE;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }
}
