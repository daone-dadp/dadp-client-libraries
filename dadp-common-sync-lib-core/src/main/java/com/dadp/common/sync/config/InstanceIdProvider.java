package com.dadp.common.sync.config;

import java.util.Map;

/**
 * Provides the runtime identity key for each module.
 */
public class InstanceIdProvider {

    private static final String AOP_ENV_VAR = "DADP_AOP_INSTANCE_ID";
    private static final String AOP_DEFAULT = "aop";

    private static final String WRAPPER_ALIAS_SYSTEM_PROP = "dadp.proxy.alias";
    private static final String WRAPPER_ALIAS_ENV_VAR = "DADP_PROXY_ALIAS";

    public enum ModuleType {
        AOP,
        WRAPPER
    }

    private final ModuleType moduleType;
    private final String springPropertyValue;
    private final Map<String, String> urlParams;

    public InstanceIdProvider(String springPropertyValue) {
        this.moduleType = ModuleType.AOP;
        this.springPropertyValue = springPropertyValue;
        this.urlParams = null;
    }

    public InstanceIdProvider(Map<String, String> urlParams) {
        this.moduleType = ModuleType.WRAPPER;
        this.springPropertyValue = null;
        this.urlParams = urlParams;
    }

    public String getInstanceId() {
        if (moduleType == ModuleType.AOP) {
            return getAopInstanceId();
        }
        return getWrapperInstanceId();
    }

    private String getAopInstanceId() {
        String instanceId = System.getenv(AOP_ENV_VAR);
        if (instanceId != null && !instanceId.trim().isEmpty()) {
            return instanceId.trim();
        }
        if (springPropertyValue != null && !springPropertyValue.trim().isEmpty()) {
            return springPropertyValue.trim();
        }
        return AOP_DEFAULT;
    }

    private String getWrapperInstanceId() {
        String instanceId = System.getProperty(WRAPPER_ALIAS_SYSTEM_PROP);
        if (instanceId != null && !instanceId.trim().isEmpty()) {
            return instanceId.trim();
        }

        instanceId = System.getenv(WRAPPER_ALIAS_ENV_VAR);
        if (instanceId != null && !instanceId.trim().isEmpty()) {
            return instanceId.trim();
        }

        if (urlParams != null) {
            instanceId = urlParams.get("alias");
            if (instanceId != null && !instanceId.trim().isEmpty()) {
                return instanceId.trim();
            }
        }

        throw missingRequiredAlias();
    }

    private IllegalStateException missingRequiredAlias() {
        String message = "DADP wrapper startup failed: missing required alias. Configure dadp.proxy.alias, DADP_PROXY_ALIAS, or JDBC URL alias.";
        System.err.println(message);
        return new IllegalStateException(message);
    }
}
