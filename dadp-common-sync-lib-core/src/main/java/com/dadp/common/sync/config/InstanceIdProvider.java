package com.dadp.common.sync.config;

import java.util.Map;

/**
 * Provides the wrapper alias used as the local storage key.
 */
public class InstanceIdProvider {

    private final String explicitAlias;
    private final Map<String, String> urlParams;

    public InstanceIdProvider(String alias) {
        this.explicitAlias = alias;
        this.urlParams = null;
    }

    public InstanceIdProvider(Map<String, String> urlParams) {
        this.explicitAlias = null;
        this.urlParams = urlParams;
    }

    public String getInstanceId() {
        if (explicitAlias != null && !explicitAlias.trim().isEmpty()) {
            return explicitAlias.trim();
        }
        return getWrapperInstanceId();
    }

    private String getWrapperInstanceId() {
        if (urlParams != null) {
            String instanceId = urlParams.get("alias");
            if (instanceId != null && !instanceId.trim().isEmpty()) {
                return instanceId.trim();
            }
        }

        throw missingRequiredAlias();
    }

    private IllegalStateException missingRequiredAlias() {
        String message = "DADP wrapper startup failed: missing required alias. Configure JDBC URL alias.";
        System.err.println(message);
        return new IllegalStateException(message);
    }
}
