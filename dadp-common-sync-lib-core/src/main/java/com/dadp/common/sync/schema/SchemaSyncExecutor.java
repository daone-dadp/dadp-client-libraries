package com.dadp.common.sync.schema;

import java.util.List;

/**
 * 스키마 동기화 실행 인터페이스 (공통)
 * 
 * AOP와 Wrapper 모두에서 사용하는 스키마 동기화 실행 인터페이스입니다.
 * 각 모듈에서 구현체를 제공합니다.
 * 
 * @author DADP Development Team
 * @version 5.1.0
 * @since 2026-01-06
 */
public interface SchemaSyncExecutor {
    
    /**
     * Hub로 스키마 메타데이터 동기화
     * 
     * @param schemas 동기화할 스키마 메타데이터 목록
     * @param hubId Hub ID
     * @param instanceId 인스턴스 ID
     * @param currentVersion 현재 버전 (null 가능)
     * @return 동기화 성공 여부
     * @throws Exception 동기화 실패 시 예외 발생 (재시도 가능)
     */
    boolean syncToHub(List<SchemaMetadata> schemas, String hubId, String instanceId, Long currentVersion) throws Exception;
    
    /**
     * 스키마가 비어있을 때 발생하는 예외인지 확인
     * 
     * @param e 예외
     * @return 스키마가 비어있어서 발생한 예외면 true
     */
    default boolean isSchemaEmptyException(Exception e) {
        if (e == null) {
            return false;
        }
        String errorMsg = e.getMessage();
        return errorMsg != null && errorMsg.contains("Schema is empty");
    }
}

