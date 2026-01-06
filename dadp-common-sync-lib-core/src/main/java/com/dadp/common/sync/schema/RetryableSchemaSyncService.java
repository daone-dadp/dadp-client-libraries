package com.dadp.common.sync.schema;

import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;

import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 재시도 로직이 포함된 스키마 동기화 서비스 (공통)
 * 
 * AOP와 Wrapper 모두에서 사용하는 공통 스키마 동기화 서비스입니다.
 * 재시도 로직을 포함하여 스키마가 비어있을 때 자동으로 재시도합니다.
 * 
 * @author DADP Development Team
 * @version 5.1.0
 * @since 2026-01-06
 */
public abstract class RetryableSchemaSyncService {
    
    protected static final DadpLogger log = DadpLoggerFactory.getLogger(RetryableSchemaSyncService.class);
    
    // Instance별 마지막 동기화된 스키마 해시 (중복 동기화 방지)
    protected static final ConcurrentHashMap<String, String> lastSchemaHash = new ConcurrentHashMap<>();
    
    protected final String hubUrl;
    protected final SchemaCollector schemaCollector;
    protected final SchemaSyncExecutor schemaSyncExecutor;
    
    // 재시도 설정
    protected final int maxRetries;
    protected final long initialDelayMs;
    protected final long backoffMs;
    
    public RetryableSchemaSyncService(String hubUrl, 
                                     SchemaCollector schemaCollector,
                                     SchemaSyncExecutor schemaSyncExecutor) {
        this(hubUrl, schemaCollector, schemaSyncExecutor, 5, 3000, 2000);
    }
    
    public RetryableSchemaSyncService(String hubUrl,
                                     SchemaCollector schemaCollector,
                                     SchemaSyncExecutor schemaSyncExecutor,
                                     int maxRetries,
                                     long initialDelayMs,
                                     long backoffMs) {
        this.hubUrl = hubUrl;
        this.schemaCollector = schemaCollector;
        this.schemaSyncExecutor = schemaSyncExecutor;
        this.maxRetries = maxRetries;
        this.initialDelayMs = initialDelayMs;
        this.backoffMs = backoffMs;
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
                    // 스키마 해시 계산 (변경 감지용)
                    String currentHash = calculateSchemaHash(schemas);
                    String lastHash = lastSchemaHash.get(hubId);
                    
                    // 스키마가 변경되지 않았으면 동기화 건너뛰기
                    if (lastHash != null && currentHash.equals(lastHash)) {
                        log.trace("⏭️ 스키마 변경 없음, 동기화 건너뜀: hubId={} (해시: {})", 
                                hubId, currentHash.substring(0, Math.min(8, currentHash.length())) + "...");
                        return true;
                    }
                    
                    // 3. Hub로 스키마 전송
                    boolean synced = schemaSyncExecutor.syncToHub(schemas, hubId, instanceId, currentVersion);
                    
                    if (synced) {
                        lastSchemaHash.put(hubId, currentHash);
                        success = true;
                        log.info("✅ 스키마 메타데이터 동기화 성공: hubId={}, 시도 횟수={}/{}", hubId, retryCount + 1, maxRetries);
                    } else {
                        throw new RuntimeException("Schema sync failed: syncToHub returned false");
                    }
                    
                } catch (Exception e) {
                    retryCount++;
                    boolean isSchemaEmpty = schemaSyncExecutor.isSchemaEmptyException(e);
                    
                    if (retryCount < maxRetries) {
                        if (isSchemaEmpty) {
                            log.debug("🔄 스키마 동기화 재시도: {}/{} (테이블 생성 대기 중...)", retryCount, maxRetries);
                        } else {
                            log.debug("🔄 스키마 동기화 재시도: {}/{} (오류: {})", retryCount, maxRetries, e.getMessage());
                        }
                        Thread.sleep(backoffMs); // 대기 후 재시도
                    } else {
                        if (isSchemaEmpty) {
                            log.warn("⚠️ 스키마 메타데이터 동기화 실패: 테이블이 생성되지 않았습니다 (최대 재시도 횟수 초과: {}/{}). Hub에서 수동으로 스키마를 등록하거나, 애플리케이션 시작 후 수동 동기화를 수행하세요.", retryCount, maxRetries);
                        } else {
                            log.warn("⚠️ 스키마 메타데이터 동기화 실패 (최대 재시도 횟수 초과: {}/{}): {}", retryCount, maxRetries, e.getMessage());
                        }
                        return false;
                    }
                }
            }
            
            return success;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("⚠️ 스키마 메타데이터 동기화 중단됨");
            return false;
        } catch (Exception e) {
            log.warn("⚠️ 스키마 메타데이터 동기화 실패: {}", e.getMessage());
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
            // 스키마를 문자열로 직렬화
            StringBuilder sb = new StringBuilder();
            for (SchemaMetadata schema : schemas) {
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
            log.warn("⚠️ 스키마 해시 계산 실패, 기본값 사용: {}", e.getMessage());
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
}

