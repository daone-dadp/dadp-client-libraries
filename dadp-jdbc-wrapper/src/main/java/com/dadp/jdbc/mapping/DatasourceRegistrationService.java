package com.dadp.jdbc.mapping;

import com.dadp.common.sync.http.HttpClientAdapter;
import com.dadp.common.sync.http.Java8HttpClientAdapterFactory;
import com.dadp.jdbc.config.DatasourceStorage;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Datasource registration service for the JDBC wrapper.
 */
public class DatasourceRegistrationService {

    private static final DadpLogger log = DadpLoggerFactory.getLogger(DatasourceRegistrationService.class);

    private final String hubUrl;
    private final String proxyInstanceId;
    private final HttpClientAdapter httpClient;
    private final ObjectMapper objectMapper;

    public DatasourceRegistrationService(String hubUrl, String proxyInstanceId) {
        this(hubUrl, proxyInstanceId, null);
    }

    public DatasourceRegistrationService(String hubUrl, String proxyInstanceId, String caCertPath) {
        this.hubUrl = hubUrl;
        this.proxyInstanceId = proxyInstanceId;
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

        String cachedDatasourceId = DatasourceStorage.loadDatasourceId(proxyInstanceId, dbVendor, host, port, database, schema);
        if (cachedDatasourceId != null) {
            log.debug("Datasource ID found in local storage: {}", cachedDatasourceId);
        }

        String registerUrl = hubUrl + "/hub/api/v1/proxy/datasources/register";

        Map<String, Object> request = new HashMap<>();
        request.put("instanceId", proxyInstanceId);
        request.put("dbVendor", dbVendor);
        request.put("host", host);
        request.put("port", port);
        request.put("database", database);
        request.put("schema", schema);
        request.put("currentVersion", currentVersion != null ? currentVersion : 0L);
        if (hubId != null && !hubId.trim().isEmpty()) {
            request.put("hubId", hubId);
        }

        try {
            HttpClientAdapter.HttpResponse response = httpClient.post(
                URI.create(registerUrl),
                objectMapper.writeValueAsString(request)
            );

            int statusCode = response.getStatusCode();
            String responseBody = response.getBody();
            log.debug("Hub datasource registration response: statusCode={}, responseBody={}", statusCode, responseBody);

            if (statusCode >= 200 && statusCode < 300 && responseBody != null) {
                try {
                    ApiResponse<DatasourceInfo> apiResponse = objectMapper.readValue(
                        responseBody,
                        new TypeReference<ApiResponse<DatasourceInfo>>() {}
                    );

                    if (apiResponse != null && apiResponse.getData() != null && apiResponse.getData().getHubId() != null) {
                        DatasourceInfo info = apiResponse.getData();
                        DatasourceStorage.saveDatasource(proxyInstanceId, info.getDatasourceId(), dbVendor, host, port, database, schema);
                        log.info("Datasource registered with Hub: datasourceId={}, displayName={}, hubId={}",
                            info.getDatasourceId(), info.getDisplayName(), info.getHubId());
                        return info;
                    }

                    log.warn("Hub datasource registration failed: invalid response format. statusCode={}, code={}, success={}, data={}, responseBody={}",
                        statusCode,
                        apiResponse != null ? apiResponse.getCode() : "null",
                        apiResponse != null ? apiResponse.isSuccess() : "null",
                        apiResponse != null ? apiResponse.getData() : "null",
                        responseBody);
                } catch (Exception parseEx) {
                    log.warn("Hub datasource registration failed: response parsing error. statusCode={}, responseBody={}, error={}",
                        statusCode, responseBody, parseEx.getMessage());
                }
            } else {
                log.warn("Hub datasource registration failed: HTTP error. statusCode={}, responseBody={}", statusCode, responseBody);
            }
        } catch (IOException e) {
            String errorMessage = e.getMessage();
            if (errorMessage == null || errorMessage.trim().isEmpty()) {
                errorMessage = e.getClass().getSimpleName();
            }
            log.warn("Hub datasource registration failed: hubUrl={}, registerUrl={}, error={}",
                hubUrl, registerUrl, errorMessage);
        }

        return null;
    }

    public static class DatasourceInfo {
        private String datasourceId;
        private String displayName;
        private String hubId;
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
