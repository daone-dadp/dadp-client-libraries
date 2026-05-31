package com.dadp.common.sync.schema;

import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;
import com.dadp.common.sync.auth.HubInternalAuthSigner;
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
    private final String apiBasePath;  // "/hub/api/v1/runtime/wrappers" 또는 "/hub/api/v1/aop"
    private final String instanceType;  // "PROXY" 또는 "AOP" (새 API 사용 시 필수)
    private final HttpClientAdapter httpClient;
    private final ObjectMapper objectMapper;
    private final String runtimeAuthKey;
    private final String runtimeAuthSecret;
    private final String runtimeSchemaSyncUrl;
    
    public HttpClientSchemaSyncExecutor(String hubUrl, String apiBasePath, HttpClientAdapter httpClient) {
        this(hubUrl, apiBasePath, null, httpClient);
    }
    
    public HttpClientSchemaSyncExecutor(String hubUrl, String apiBasePath, String instanceType, HttpClientAdapter httpClient) {
        this(hubUrl, apiBasePath, instanceType, httpClient, null, null);
    }

    public HttpClientSchemaSyncExecutor(String hubUrl, String apiBasePath, String instanceType,
                                        HttpClientAdapter httpClient,
                                        String runtimeAuthKey, String runtimeAuthSecret) {
        this(hubUrl, apiBasePath, instanceType, httpClient, runtimeAuthKey, runtimeAuthSecret, null);
    }

    public HttpClientSchemaSyncExecutor(String hubUrl, String apiBasePath, String instanceType,
                                        HttpClientAdapter httpClient,
                                        String runtimeAuthKey, String runtimeAuthSecret,
                                        String runtimeSchemaSyncUrl) {
        this.hubUrl = hubUrl;
        this.apiBasePath = apiBasePath;
        this.instanceType = instanceType;
        this.httpClient = httpClient;
        this.runtimeAuthKey = runtimeAuthKey;
        this.runtimeAuthSecret = runtimeAuthSecret;
        this.runtimeSchemaSyncUrl = runtimeSchemaSyncUrl;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
    
    @Override
    public boolean syncToHub(List<SchemaMetadata> schemas, String hubId, String alias, Long currentVersion) throws Exception {
        // AOP는 복수형(/schemas/sync), Wrapper는 단수형(/schema/sync)
        boolean isAop = apiBasePath != null && apiBasePath.contains("/aop");
        boolean isRuntimeWrapper = apiBasePath != null && apiBasePath.contains("/runtime/wrappers");
        String syncPath = isAop ? "/schemas/sync" : "/schema/sync";
        String syncUrl = isRuntimeWrapper
                ? resolveRuntimeSchemaSyncUrl(hubId)
                : hubUrl + apiBasePath + syncPath;
        log.trace("Hub schema sync URL: {}", syncUrl);

        String requestBody;
        if (isRuntimeWrapper) {
            requestBody = objectMapper.writeValueAsString(toRuntimeSchemaSyncRequest(schemas, currentVersion));
        } else {
            SchemaSyncRequest request = new SchemaSyncRequest();
            request.setInstanceId(alias);
            request.setSchemas(schemas);
            requestBody = objectMapper.writeValueAsString(request);
        }
        
        // 헤더에 hubId, instanceId(별칭), 버전, instanceType 포함 (새 API 사용 시)
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        if (!isRuntimeWrapper && hubId != null && !hubId.trim().isEmpty()) {
            headers.put("X-DADP-Tenant-Id", hubId);
        }
        if (alias != null && !alias.trim().isEmpty()) {
            headers.put("X-Instance-Id", alias);
        }
        if (isRuntimeWrapper) {
            headers.putAll(signedHeaders("POST", URI.create(syncUrl), requestBody, hubId));
        }
        if (currentVersion != null) {
            headers.put("X-Current-Version", String.valueOf(currentVersion));
        }
        // Wrapper 사용 시 X-Instance-Type 헤더 추가
        if (!isAop && instanceType != null && !instanceType.trim().isEmpty()) {
            headers.put("X-Instance-Type", instanceType);
        }
        
        // 요청 로깅 (디버깅용)
        log.debug("Hub schema sync request: URL={}, hubId={}, schemaCount={}", syncUrl, hubId, schemas != null ? schemas.size() : 0);
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
        
        // 404 Not Found: hubId를 찾을 수 없음 (등록되지 않은 hubId) -> 재등록 필요 (예외가 아닌 정상 응답 코드)
        if (statusCode == 404) {
            log.warn("Hub returned 404 for hubId={}, re-registration required", hubId);
            // 404는 정상적인 응답 코드이므로 특별한 예외를 던져서 호출하는 쪽에서 재등록 처리
            throw new SchemaSync404Exception("Hub schema sync failed: HTTP 404 - hubId not found. Re-registration is required. hubId=" + hubId);
        }
        
        if (statusCode >= 200 && statusCode < 300 && responseBody != null) {
                if (isRuntimeWrapper) {
                    log.debug("Schema metadata synced to Hub runtime: {} columns, tenantId={}", schemas.size(), hubId);
                    return true;
                }

                // ApiResponse 래퍼 파싱
            Map<String, Object> apiResponse = objectMapper.readValue(responseBody, 
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            
            // v2: code 기반 (primary), v1: success boolean fallback
            boolean apiSuccess;
            Object codeVal = apiResponse != null ? apiResponse.get("code") : null;
            if (codeVal instanceof String) {
                apiSuccess = "SUCCESS".equals(codeVal);
            } else {
                apiSuccess = apiResponse != null && Boolean.TRUE.equals(apiResponse.get("success"));
            }
            if (apiSuccess) {
                // 응답에서 hubId 추출 (재등록 시 hubId가 응답에 포함됨)
                String receivedHubId = extractHubIdFromResponse(apiResponse);
                if (receivedHubId != null && !receivedHubId.trim().isEmpty()) {
                    log.debug("Schema metadata synced to Hub: {} columns, hubId={}", schemas.size(), receivedHubId);
                    HubIdHolder.setHubId(receivedHubId);
                } else {
                    log.debug("Schema metadata synced to Hub: {} columns", schemas.size());
                }
                return true;
            } else {
                log.warn("Schema metadata sync to Hub failed: response success=false");
                throw new RuntimeException("Hub schema sync failed: response success=false");
            }
        } else {
            log.warn("Schema metadata sync to Hub failed: HTTP {}", statusCode);
            throw new RuntimeException("Hub schema sync failed: HTTP " + statusCode + (responseBody != null ? " - " + responseBody : ""));
        }
    }

    private String resolveRuntimeSchemaSyncUrl(String tenantId) {
        if (runtimeSchemaSyncUrl != null && !runtimeSchemaSyncUrl.trim().isEmpty()) {
            if (runtimeSchemaSyncUrl.startsWith("http://") || runtimeSchemaSyncUrl.startsWith("https://")) {
                return runtimeSchemaSyncUrl;
            }
            String base = hubUrl != null && hubUrl.endsWith("/") ? hubUrl.substring(0, hubUrl.length() - 1) : hubUrl;
            String path = runtimeSchemaSyncUrl.startsWith("/") ? runtimeSchemaSyncUrl : "/" + runtimeSchemaSyncUrl;
            return base + path;
        }
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
        if (runtimeAuthKey != null && !runtimeAuthKey.trim().isEmpty()
                && runtimeAuthSecret != null && !runtimeAuthSecret.trim().isEmpty()) {
            HubInternalAuthSigner signer = new HubInternalAuthSigner(runtimeAuthKey, runtimeAuthSecret);
            headers.putAll(signer.sign(method, uri, body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0], tenantId));
        }
        return headers;
    }
    
    /**
     * 응답에서 hubId 추출
     * Hub 응답 구조: { "success": true, "data": { "hubId": "...", "success": true } }
     */
    @SuppressWarnings("unchecked")
    private String extractHubIdFromResponse(Map<String, Object> apiResponse) {
        try {
            Object dataObj = apiResponse.get("data");
            if (dataObj instanceof Map) {
                Map<String, Object> data = (Map<String, Object>) dataObj;
                Object hubIdObj = data.get("hubId");
                if (hubIdObj instanceof String) {
                    return (String) hubIdObj;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to extract hubId from response: {}", e.getMessage());
        }
        return null;
    }
    
    @Override
    public String getReceivedHubId() {
        return HubIdHolder.getHubId();
    }
    
    @Override
    public void clearReceivedHubId() {
        HubIdHolder.clear();
    }
}

/**
 * HubId를 ThreadLocal에 저장하여 상위 메서드에서 접근 가능하도록 하는 헬퍼 클래스
 */
class HubIdHolder {
    private static final ThreadLocal<String> hubIdThreadLocal = new ThreadLocal<>();
    
    static void setHubId(String hubId) {
        hubIdThreadLocal.set(hubId);
    }
    
    static String getHubId() {
        return hubIdThreadLocal.get();
    }
    
    static void clear() {
        hubIdThreadLocal.remove();
    }
}

/**
 * 404 응답을 나타내는 예외 (정상적인 응답 코드이지만 재등록이 필요함을 표시)
 */
class SchemaSync404Exception extends RuntimeException {
    SchemaSync404Exception(String message) {
        super(message);
    }
}
