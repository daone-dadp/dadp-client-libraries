package com.dadp.common.sync.schema;

import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;
import com.dadp.common.sync.http.HttpClientAdapter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HttpClientAdapter 기반 스키마 동기화 실행 구현체 (Java 8/17 공통)
 * 
 * Wrapper에서 사용하는 HttpClientAdapter 기반 구현입니다.
 * 
 * @author DADP Development Team
 * @version 5.1.0
 * @since 2026-01-06
 */
public class HttpClientSchemaSyncExecutor implements SchemaSyncExecutor {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(HttpClientSchemaSyncExecutor.class);
    
    private final String hubUrl;
    private final String apiBasePath;
    private final String instanceType;
    private final HttpClientAdapter httpClient;
    private final ObjectMapper objectMapper;
    
    public HttpClientSchemaSyncExecutor(String hubUrl, String apiBasePath, HttpClientAdapter httpClient) {
        this(hubUrl, apiBasePath, null, httpClient);
    }
    
    public HttpClientSchemaSyncExecutor(String hubUrl, String apiBasePath, String instanceType, HttpClientAdapter httpClient) {
        this.hubUrl = hubUrl;
        this.apiBasePath = apiBasePath;
        this.instanceType = instanceType;
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
    
    @Override
    public boolean syncToHub(List<SchemaMetadata> schemas, String tenantId, String alias, Long currentVersion) throws Exception {
        boolean isRuntimeWrapper = apiBasePath != null && apiBasePath.contains("/runtime/wrappers");
        if (!isRuntimeWrapper) {
            throw new IllegalStateException("Wrapper schema sync requires /hub/api/v1/runtime/wrappers API base path");
        }
        String syncUrl = resolveRuntimeSchemaSyncUrl(tenantId);
        log.trace("Hub schema sync URL: {}", syncUrl);

        String requestBody = objectMapper.writeValueAsString(toRuntimeSchemaSyncRequest(schemas, currentVersion));
        
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        if (alias != null && !alias.trim().isEmpty()) {
            headers.put("X-Instance-Id", alias);
        }
        headers.putAll(signedHeaders("POST", URI.create(syncUrl), requestBody, tenantId));
        if (currentVersion != null) {
            headers.put("X-Current-Version", String.valueOf(currentVersion));
        }
        if (instanceType != null && !instanceType.trim().isEmpty()) {
            headers.put("X-Instance-Type", instanceType);
        }
        
        // 요청 로깅 (디버깅용)
        log.debug("Hub schema sync request: URL={}, tenantId={}, schemaCount={}", syncUrl, tenantId, schemas != null ? schemas.size() : 0);
        log.debug("Request headers: {}", headers);
        log.debug("Request body: {}", requestBody);
        
        // 각 스키마의 datasourceId 포함 로그 (INFO 레벨)
        if (schemas != null && !schemas.isEmpty()) {
            for (SchemaMetadata schema : schemas) {
                log.debug("Schema data to send: schema={}.{}.{}, datasourceId={}, database={}, dbVendor={}",
                    schema.getSchemaName(), schema.getTableName(), schema.getColumnName(),
                    schema.getDatasourceId(), schema.getDatabaseName(), schema.getDbVendor());
            }
        }
        
        // HTTP POST 요청
        URI uri = URI.create(syncUrl);
        HttpClientAdapter.HttpResponse response = httpClient.post(uri, requestBody, headers);
        
        int statusCode = response.getStatusCode();
        String responseBody = response.getBody();
        
        // 304 Not Modified 처리 (버전이 같으면 스키마 데이터 없이 반환)
        if (statusCode == 304) {
            log.debug("Schema sync not needed (304): version unchanged, currentVersion={}", currentVersion);
            return true;
        }
        
        if (statusCode == 404) {
            log.warn("Hub runtime schema sync returned 404 for tenantId={}, CLI enrollment is required", tenantId);
            throw new SchemaSync404Exception("Hub runtime schema sync failed: HTTP 404 - tenantId not found. tenantId=" + tenantId);
        }
        
        if (statusCode >= 200 && statusCode < 300 && responseBody != null) {
            log.debug("Schema metadata synced to Hub runtime: {} columns, tenantId={}", schemas.size(), tenantId);
            return true;
        } else {
            log.warn("Schema metadata sync to Hub failed: HTTP {}", statusCode);
            throw new RuntimeException("Hub schema sync failed: HTTP " + statusCode + (responseBody != null ? " - " + responseBody : ""));
        }
    }

    private String resolveRuntimeSchemaSyncUrl(String tenantId) {
        return hubUrl + apiBasePath + "/" + tenantId + "/schema-sync";
    }

    private Map<String, Object> toRuntimeSchemaSyncRequest(List<SchemaMetadata> schemas,
                                                           Long currentVersion) throws Exception {
        Map<String, Object> request = new HashMap<>();
        long version = currentVersion != null && currentVersion > 0 ? currentVersion : 1L;
        request.put("version", version);
        request.put("capturedAt", Instant.now().toString());
        request.put("schemaJson", objectMapper.writeValueAsString(toRuntimeSchemaDocument(schemas)));
        return request;
    }

    private Map<String, Object> toRuntimeSchemaDocument(List<SchemaMetadata> schemas) {
        Map<String, Object> root = new HashMap<>();
        root.put("columns", schemas != null ? schemas : new ArrayList<SchemaMetadata>());
        return root;
    }

    private Map<String, String> signedHeaders(String method, URI uri, String body, String tenantId) {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DADP-Tenant-Id", tenantId);
        return headers;
    }
    
    @Override
    public String getReceivedTenantId() {
        return null;
    }
    
    @Override
    public void clearReceivedTenantId() {
    }
}

/**
 * 404 response for a missing DADP 6 wrapper enrollment.
 */
class SchemaSync404Exception extends RuntimeException {
    SchemaSync404Exception(String message) {
        super(message);
    }
}
