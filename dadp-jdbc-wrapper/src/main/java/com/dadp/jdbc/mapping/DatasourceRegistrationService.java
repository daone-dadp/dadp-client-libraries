package com.dadp.jdbc.mapping;

import com.dadp.common.sync.auth.HubInternalAuthSigner;
import com.dadp.common.sync.http.HttpClientAdapter;
import com.dadp.common.sync.http.Java8HttpClientAdapterFactory;
import com.dadp.jdbc.config.DatasourceStorage;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Datasource registration service for the JDBC wrapper.
 */
public class DatasourceRegistrationService {

    private static final DadpLogger log = DadpLoggerFactory.getLogger(DatasourceRegistrationService.class);

    private final String hubUrl;
    private final String alias;
    private final String runtimeAuthKey;
    private final String runtimeAuthSecret;
    private final HttpClientAdapter httpClient;
    private final ObjectMapper objectMapper;

    public DatasourceRegistrationService(String hubUrl, String alias) {
        this(hubUrl, alias, null, null, null);
    }

    public DatasourceRegistrationService(String hubUrl, String alias, String caCertPath) {
        this(hubUrl, alias, caCertPath, null, null);
    }

    public DatasourceRegistrationService(String hubUrl, String alias, String caCertPath,
                                         String runtimeAuthKey, String runtimeAuthSecret) {
        this.hubUrl = hubUrl;
        this.alias = alias;
        this.runtimeAuthKey = runtimeAuthKey;
        this.runtimeAuthSecret = runtimeAuthSecret;
        this.httpClient = Java8HttpClientAdapterFactory.create(5000, 10000, caCertPath);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public DatasourceInfo registerOrGetDatasource(
            String dbVendor, String host, int port,
            String database, String schema, Long currentVersion) {
        return registerOrGetDatasource(dbVendor, host, port, database, schema, currentVersion, null);
    }

    /**
     * Reuse the supplied hubId when this wrapper already owns one.
     *
     * hubUrl must be the Hub base URL without /hub.
     * This service appends /hub/api/v1/... internally.
     */
    public DatasourceInfo registerOrGetDatasource(
            String dbVendor, String host, int port,
            String database, String schema, Long currentVersion, String hubId) {
        log.warn("Datasource registration is disabled in DADP 6.0. Use CLI schema-register enrollment and persist the returned datasourceId.");
        return null;
    }

    private DatasourceInfo parseRuntimeRegistrationResponse(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode wrapper = root.path("wrapper");
        JsonNode datasource = root.path("datasource");
        String tenantId = text(wrapper.path("tenantId"));
        String datasourceId = text(datasource.path("id"));
        if (datasourceId == null) {
            datasourceId = text(datasource.path("datasourceKey"));
        }
        String displayName = text(datasource.path("metadata").path("displayName"));
        if (displayName == null) {
            displayName = text(datasource.path("datasourceKey"));
        }
        DatasourceInfo info = new DatasourceInfo(datasourceId, displayName, tenantId);
        info.setWrapperHubId(tenantId);
        return info;
    }

    private Map<String, String> signedHeaders(String method, URI uri, String body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        if (runtimeAuthKey == null || runtimeAuthKey.trim().isEmpty()
                || runtimeAuthSecret == null || runtimeAuthSecret.trim().isEmpty()) {
            throw new IllegalStateException("DADP 6.0 wrapper runtime calls require wrapper enrollment auth.");
        }
        HubInternalAuthSigner signer = new HubInternalAuthSigner(runtimeAuthKey, runtimeAuthSecret);
        headers.putAll(signer.sign(method, uri, body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0]));
        return headers;
    }

    private String buildDatasourceKey(String dbVendor, String host, int port, String database, String schema) {
        StringBuilder sb = new StringBuilder();
        sb.append(alias != null ? alias.trim() : "wrapper");
        sb.append(":").append(dbVendor != null ? dbVendor.trim().toLowerCase() : "unknown");
        sb.append(":").append(host != null ? host.trim().toLowerCase() : "");
        sb.append(":").append(port);
        sb.append(":").append(database != null ? database.trim().toLowerCase() : "");
        sb.append(":").append(schema != null ? schema.trim().toLowerCase() : "");
        return sb.toString();
    }

    private static void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            target.put(key, value.trim());
        }
    }

    private static String text(JsonNode node) {
        return node != null && !node.isMissingNode() && !node.isNull() ? node.asText() : null;
    }

    public static class DatasourceInfo {
        private String datasourceId;
        private String displayName;
        private String hubId;
        private String wrapperHubId;
        private String wrapperAuthSecret;
        private String rootCaCertificate;

        public DatasourceInfo() {
        }

        public DatasourceInfo(String datasourceId, String displayName) {
            this.datasourceId = datasourceId;
            this.displayName = displayName;
        }

        public DatasourceInfo(String datasourceId, String displayName, String hubId) {
            this.datasourceId = datasourceId;
            this.displayName = displayName;
            this.hubId = hubId;
        }

        public String getDatasourceId() {
            return datasourceId;
        }

        public void setDatasourceId(String datasourceId) {
            this.datasourceId = datasourceId;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getHubId() {
            return hubId;
        }

        public void setHubId(String hubId) {
            this.hubId = hubId;
        }

        public String getRootCaCertificate() {
            return rootCaCertificate;
        }

        public void setRootCaCertificate(String rootCaCertificate) {
            this.rootCaCertificate = rootCaCertificate;
        }

        public String getWrapperHubId() {
            return wrapperHubId;
        }

        public void setWrapperHubId(String wrapperHubId) {
            this.wrapperHubId = wrapperHubId;
        }

        public String getWrapperAuthSecret() {
            return wrapperAuthSecret;
        }

        public void setWrapperAuthSecret(String wrapperAuthSecret) {
            this.wrapperAuthSecret = wrapperAuthSecret;
        }
    }

    public static class ApiResponse<T> {
        private boolean success;
        private String code;
        private T data;
        private String message;

        public boolean isSuccess() {
            if (code != null) {
                return "SUCCESS".equals(code);
            }
            return success;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public T getData() {
            return data;
        }

        public void setData(T data) {
            this.data = data;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}
