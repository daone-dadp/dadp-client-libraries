package com.dadp.common.sync.schema;

import java.sql.Connection;
import java.util.List;

/**
 * 스키마 수집 인터페이스 (공통)
 * 
 * AOP와 Wrapper 모두에서 사용하는 스키마 수집 인터페이스입니다.
 * 각 모듈에서 구현체를 제공합니다.
 * 
 * <p>Wrapper: {@code collectSchemas(Connection)} 사용. Connection을 필드로 두지 않고 호출 시점에만 전달.</p>
 * <p>AOP: Connection 미사용 시 {@code collectSchemas(Connection)}에서 {@code collectSchemas()} 호출.</p>
 * 
 * @author DADP Development Team
 * @version 5.1.0
 * @since 2026-01-06
 */
public interface SchemaCollector {
    
    /**
     * 스키마 메타데이터 수집 (Connection 없이, 구현체가 보유한 소스 사용)
     * 
     * @return 스키마 메타데이터 목록 (비어있을 수 있음)
     * @throws Exception 수집 실패 시 예외 발생
     */
    List<SchemaMetadata> collectSchemas() throws Exception;
    
    /**
     * 스키마 메타데이터 수집 (호출 시점에 Connection 전달, instanceId당 1세트 공유 시 사용)
     * 
     * @param connection JDBC Connection (null이면 {@link #collectSchemas()} 동작에 위임 가능)
     * @return 스키마 메타데이터 목록 (비어있을 수 있음)
     * @throws Exception 수집 실패 시 예외 발생
     */
    List<SchemaMetadata> collectSchemas(Connection connection) throws Exception;
    
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

