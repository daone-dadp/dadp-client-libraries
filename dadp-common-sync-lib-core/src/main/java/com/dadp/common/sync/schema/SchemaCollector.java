package com.dadp.common.sync.schema;

import java.util.List;

/**
 * 스키마 수집 인터페이스 (공통)
 * 
 * AOP와 Wrapper 모두에서 사용하는 스키마 수집 인터페이스입니다.
 * 각 모듈에서 구현체를 제공합니다.
 * 
 * @author DADP Development Team
 * @version 5.1.0
 * @since 2026-01-06
 */
public interface SchemaCollector {
    
    /**
     * 스키마 메타데이터 수집
     * 
     * @return 스키마 메타데이터 목록 (비어있을 수 있음)
     * @throws Exception 수집 실패 시 예외 발생
     */
    List<SchemaMetadata> collectSchemas() throws Exception;
    
    /**
     * 스키마가 비어있는지 확인
     * 
     * @return 스키마가 비어있으면 true
     */
    default boolean isEmpty() throws Exception {
        List<SchemaMetadata> schemas = collectSchemas();
        return schemas == null || schemas.isEmpty();
    }
}

