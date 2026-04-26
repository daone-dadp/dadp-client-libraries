package com.dadp.common.sync.schema;

import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;
import com.dadp.common.sync.config.HubIdSaver;

import java.security.MessageDigest;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 재시도 로직이 포함된 스키마 동기화 서비스 (공통)
 * 
 * AOP와 Wrapper 모두에서 사용하는 공통 스키마 동기화 서비스입니다.
 * 재시도 로직을 포함하여 스키마가 비어있을 때 자동으로 재시도합니다.
 * 
 * 모든 공통 로직은 여기에 있고, 통신 부분은 SchemaSyncExecutor 인터페이스를 통해 분리됩니다.
 * hubId 저장은 HubIdSaver 콜백을 통해 각 최종 모듈에서 처리합니다.
 * 
 * @author DADP Development Team
 * @version 5.1.0
 * @since 2026-01-06
 */
public class RetryableSchemaSyncService {
    
    protected static final DadpLogger log = DadpLoggerFactory.getLogger(RetryableSchemaSyncService.class);
    
    // Instance별 마지막 동기화된 스키마 해시 (중복 동기화 방지)
    protected static final ConcurrentHashMap<String, String> lastSchemaHash = new ConcurrentHashMap<>();
    
    protected final String hubUrl;
    protected final SchemaCollector schemaCollector;
    protected final SchemaSyncExecutor schemaSyncExecutor;
    protected final HubIdSaver hubIdSaver;  // hubId 저장 콜백 (각 최종 모듈에서 구현)
    protected final SchemaStorage schemaStorage;  // 스키마 영구 저장소 (null 가능)
    
    // 재시도 설정
    protected final int maxRetries;
    protected final long initialDelayMs;
    protected final long backoffMs;
    
    public RetryableSchemaSyncService(String hubUrl, 
                                     SchemaCollector schemaCollector,
                                     SchemaSyncExecutor schemaSyncExecutor,
                                     HubIdSaver hubIdSaver) {
        this(hubUrl, schemaCollector, schemaSyncExecutor, hubIdSaver, null, 5, 3000, 2000);
    }
    
    public RetryableSchemaSyncService(String hubUrl,
                                     SchemaCollector schemaCollector,
                                     SchemaSyncExecutor schemaSyncExecutor,
                                     HubIdSaver hubIdSaver,
                                     int maxRetries,
                                     long initialDelayMs,
                                     long backoffMs) {
        this(hubUrl, schemaCollector, schemaSyncExecutor, hubIdSaver, null, maxRetries, initialDelayMs, backoffMs);
    }
    
    public RetryableSchemaSyncService(String hubUrl,
                                     SchemaCollector schemaCollector,
                                     SchemaSyncExecutor schemaSyncExecutor,
                                     HubIdSaver hubIdSaver,
                                     SchemaStorage schemaStorage,
                                     int maxRetries,
                                     long initialDelayMs,
                                     long backoffMs) {
        this.hubUrl = hubUrl;
        this.schemaCollector = schemaCollector;
        this.schemaSyncExecutor = schemaSyncExecutor;
        this.hubIdSaver = hubIdSaver;
        this.schemaStorage = schemaStorage;
        this.maxRetries = maxRetries;
        this.initialDelayMs = initialDelayMs;
        this.backoffMs = backoffMs;
    }
    
    /**
     * 스키마 수집 완료까지 대기 (재시도 로직 포함)
     * 
     * 명시된 플로우 1단계: 스키마 로드 (DB의 스키마 수집 - 0인 경우 반복)
     * 
     * @param maxRetries 최대 재시도 횟수
     * @param retryDelayMs 재시도 간격 (밀리초)
     * @return 스키마 수집 성공 여부
     */
    public boolean waitForSchemaCollection(int maxRetries, long retryDelayMs) {
        int retryCount = 0;
        
        while (retryCount < maxRetries) {
            try {
                List<SchemaMetadata> schemas = schemaCollector.collectSchemas();
                if (schemas != null && !schemas.isEmpty()) {
                    log.debug("Schema collection completed: {} columns", schemas.size());
                    return true;
                } else {
                    retryCount++;
                    if (retryCount < maxRetries) {
                        log.debug("Schema collection result: 0 (retry {}/{})", retryCount, maxRetries);
                        Thread.sleep(retryDelayMs);
                    } else {
                        log.warn("Schema collection failed: 0 schemas (max retries exceeded)");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Schema collection interrupted");
                return false;
            } catch (Exception e) {
                retryCount++;
                if (retryCount < maxRetries) {
                    log.debug("Schema collection failed (retry {}/{}): {}", retryCount, maxRetries, e.getMessage());
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                } else {
                    log.warn("Schema collection failed (max retries exceeded): {}", e.getMessage());
                }
            }
        }

        return false;
    }

    /**
     * 스키마 수집 (재시도) 후 수집 결과 반환.
     * 논리 순서: 1) DB 스키마 1회 수집 → 2) 영구저장소 로드 → 3) 비교 시 이 결과 재사용 (재수집 금지).
     *
     * @param maxRetries 최대 재시도 횟수
     * @param retryDelayMs 재시도 간격 (밀리초)
     * @return 수집된 스키마 목록 (실패 또는 0개면 null)
     */
    public List<SchemaMetadata> collectSchemasWithRetry(int maxRetries, long retryDelayMs) {
        return collectSchemasWithRetry(null, maxRetries, retryDelayMs);
    }
    
    /**
     * 스키마 수집 (재시도, Connection 전달). instanceId당 1세트 공유 시 호출 시점에 Connection 전달.
     *
     * @param connection JDBC Connection (null이면 collectSchemas() 사용)
     * @param maxRetries 최대 재시도 횟수
     * @param retryDelayMs 재시도 간격 (밀리초)
     * @return 수집된 스키마 목록 (실패 또는 0개면 null)
     */
    public List<SchemaMetadata> collectSchemasWithRetry(Connection connection, int maxRetries, long retryDelayMs) {
        int retryCount = 0;
        while (retryCount < maxRetries) {
            try {
                List<SchemaMetadata> schemas = connection != null
                        ? schemaCollector.collectSchemas(connection)
                        : schemaCollector.collectSchemas();
                if (schemas != null && !schemas.isEmpty()) {
                    log.debug("Schema collection completed: {} columns", schemas.size());
                    return schemas;
                }
                retryCount++;
                if (retryCount < maxRetries) {
                    log.debug("Schema collection result: 0 (retry {}/{})", retryCount, maxRetries);
                    Thread.sleep(retryDelayMs);
                } else {
                    log.warn("Schema collection failed: 0 schemas (max retries exceeded)");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Schema collection interrupted");
                return null;
            } catch (Exception e) {
                retryCount++;
                if (retryCount < maxRetries) {
                    log.debug("Schema collection failed (retry {}/{}): {}", retryCount, maxRetries, e.getMessage());
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                } else {
                    log.warn("Schema collection failed (max retries exceeded): {}", e.getMessage());
                }
            }
        }
        return null;
    }
    
    /**
     * 스키마 메타데이터를 Hub로 동기화 (재시도 로직 포함)
     * 
     * 처리 흐름:
     * 1. 스키마 로드 (만약 0개 획득시 대기+재시도)
     * 2. 스키마 로드 성공
     * 3. Hub로 스키마 전송
     * 
     * @param hubId Hub ID
     * @param instanceId 인스턴스 ID
     * @param currentVersion 현재 버전 (null 가능)
     * @return 동기화 성공 여부
     */
    public boolean syncSchemaToHub(String hubId, String instanceId, Long currentVersion) {
        try {
            // 초기 대기 (테이블 생성 대기, Hibernate DDL 실행 시간 고려)
            Thread.sleep(initialDelayMs);
            
            int retryCount = 0;
            boolean success = false;
            
            while (retryCount < maxRetries && !success) {
                try {
                    // 1. 스키마 로드
                    List<SchemaMetadata> schemas = schemaCollector.collectSchemas();
                    
                    // 스키마가 0개이면 재시도
                    if (schemas == null || schemas.isEmpty()) {
                        throw new IllegalStateException("Schema is empty - tables may not be created yet");
                    }
                    
                    // 2. 스키마 로드 성공
                    
                    // hubId가 null이면 재등록 중이므로 해시 캐시를 사용하지 않고 바로 동기화 수행
                    // hubId가 있으면 해시 캐시를 사용하여 중복 동기화 방지
                    if (hubId != null && !hubId.trim().isEmpty()) {
                        // 스키마 해시 계산 (변경 감지용)
                        String currentHash = calculateSchemaHash(schemas);
                        String lastHash = lastSchemaHash.get(hubId);
                        
                        // 스키마가 변경되지 않았으면 동기화 건너뛰기
                        if (lastHash != null && currentHash.equals(lastHash)) {
                            log.trace("Schema unchanged, skipping sync: hubId={} (hash: {})",
                                    hubId, currentHash.substring(0, Math.min(8, currentHash.length())) + "...");
                            return true;
                        }
                    }
                    
                    // 3. Hub로 스키마 전송 (hubId가 null이면 재등록, 있으면 업데이트)
                    // 전송 전에 각 스키마의 datasourceId 포함 로그 (INFO 레벨)
                    if (schemas != null && !schemas.isEmpty()) {
                        for (SchemaMetadata schema : schemas) {
                            log.trace("Schema sync data: schema={}.{}.{}, datasourceId={}, database={}, dbVendor={}",
                                schema.getSchemaName(), schema.getTableName(), schema.getColumnName(),
                                schema.getDatasourceId(), schema.getDatabaseName(), schema.getDbVendor());
                        }
                    }
                    boolean synced = schemaSyncExecutor.syncToHub(schemas, hubId, instanceId, currentVersion);
                    
                    if (synced) {
                        // 응답에서 받은 hubId 추출 (재등록 시 hubId가 응답에 포함됨)
                        String receivedHubId = schemaSyncExecutor.getReceivedHubId();
                        
                        // hubId가 응답에 포함되어 있으면 저장 (재등록 시)
                        if (receivedHubId != null && !receivedHubId.trim().isEmpty()) {
                            // hubId 저장 (HubIdSaver 콜백 사용)
                            if (hubIdSaver != null) {
                                hubIdSaver.saveHubId(receivedHubId, instanceId);
                                log.debug("Received hubId saved: hubId={}", receivedHubId);
                            }
                            hubId = receivedHubId; // 이후 로직에서 사용할 hubId 업데이트
                        }
                        
                        // ThreadLocal 정리
                        schemaSyncExecutor.clearReceivedHubId();
                        
                        // hubId가 있으면 해시 캐시에 저장 (중복 동기화 방지)
                        if (hubId != null && !hubId.trim().isEmpty()) {
                            String currentHash = calculateSchemaHash(schemas);
                            lastSchemaHash.put(hubId, currentHash);
                        }
                        
                        // 스키마 영구저장소에 저장 (재시작 시 변경 여부 확인용)
                        if (schemaStorage != null) {
                            schemaStorage.saveSchemas(schemas);
                        }
                        
                        success = true;
                        log.info("Schema metadata sync succeeded: hubId={}, alias={}, attempts={}/{}", hubId, instanceId, retryCount + 1, maxRetries);
                    } else {
                        throw new RuntimeException("Schema sync failed: syncToHub returned false");
                    }
                    
                } catch (Exception e) {
                    retryCount++;
                    boolean isSchemaEmpty = schemaSyncExecutor.isSchemaEmptyException(e);
                    boolean is404 = is404Exception(e);
                    
                    // 404 응답: hubId를 찾을 수 없음 -> 재등록 필요 (예외가 아닌 정상 응답 코드)
                    if (is404) {
                        log.warn("Hub could not find hubId (404), re-registration required");
                        // false 반환하여 호출하는 쪽에서 재등록 처리
                        return false;
                    }
                    
                    if (retryCount < maxRetries) {
                        if (isSchemaEmpty) {
                            log.debug("Schema sync retry: {}/{} (waiting for table creation...)", retryCount, maxRetries);
                        } else {
                            log.debug("Schema sync retry: {}/{} (error: {})", retryCount, maxRetries, e.getMessage());
                        }
                        Thread.sleep(backoffMs); // 대기 후 재시도
                    } else {
                        if (isSchemaEmpty) {
                            log.warn("Schema metadata sync failed: tables not created (max retries exceeded: {}/{}). Register schemas manually in Hub or perform manual sync after application startup.", retryCount, maxRetries);
                        } else {
                            log.warn("Schema metadata sync failed (max retries exceeded: {}/{}): {}", retryCount, maxRetries, e.getMessage());
                        }
                        return false;
                    }
                }
            }
            
            return success;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Schema metadata sync interrupted");
            return false;
        } catch (Exception e) {
            log.warn("Schema metadata sync failed: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 스키마 해시 계산 (변경 감지용)
     * 
     * @param schemas 스키마 메타데이터 목록
     * @return 해시 값 (SHA-256)
     */
    protected String calculateSchemaHash(List<SchemaMetadata> schemas) {
        try {
            if (schemas == null || schemas.isEmpty()) {
                return "";
            }
            
            // 스키마를 문자열로 직렬화
            StringBuilder sb = new StringBuilder();
            for (SchemaMetadata schema : schemas) {
                if (schema == null) {
                    continue; // null 스키마는 건너뜀
                }
                sb.append(schema.getDatabaseName() != null ? schema.getDatabaseName() : "").append("|");
                sb.append(schema.getSchemaName() != null ? schema.getSchemaName() : "").append("|");
                sb.append(schema.getTableName() != null ? schema.getTableName() : "").append("|");
                sb.append(schema.getColumnName() != null ? schema.getColumnName() : "").append("|");
                sb.append(schema.getColumnType() != null ? schema.getColumnType() : "").append("|");
                sb.append(schema.getIsNullable() != null ? schema.getIsNullable() : "").append("|");
                sb.append(schema.getColumnDefault() != null ? schema.getColumnDefault() : "").append("|");
                sb.append(schema.getPolicyName() != null ? schema.getPolicyName() : "").append("\n");
            }
            
            // SHA-256 해시 계산
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(sb.toString().getBytes("UTF-8"));
            
            // 16진수 문자열로 변환
            StringBuilder hashString = new StringBuilder();
            for (byte b : hashBytes) {
                hashString.append(String.format("%02x", b));
            }
            
            return hashString.toString();
        } catch (Exception e) {
            log.warn("Schema hash calculation failed, using default: {}", e.getMessage());
            // 해시 계산 실패 시 타임스탬프 사용 (항상 변경된 것으로 간주)
            return String.valueOf(System.currentTimeMillis());
        }
    }
    
    /**
     * 스키마 해시 캐시 초기화
     * 
     * @param hubId Hub ID
     */
    public void clearSchemaHash(String hubId) {
        lastSchemaHash.remove(hubId);
    }
    
    /**
     * 404 예외인지 확인 (정상적인 응답 코드이지만 재등록이 필요함을 표시)
     * 
     * @param e 예외
     * @return 404 예외면 true
     */
    protected boolean is404Exception(Exception e) {
        if (e == null) {
            return false;
        }
        // SchemaSync404Exception 또는 메시지에 "404"가 포함된 경우
        String className = e.getClass().getSimpleName();
        if ("SchemaSync404Exception".equals(className)) {
            return true;
        }
        String errorMsg = e.getMessage();
        return errorMsg != null && (errorMsg.contains("404") || errorMsg.contains("re-registration is required"));
    }
}
