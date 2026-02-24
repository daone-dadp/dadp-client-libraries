package com.dadp.jdbc.schema;

import com.dadp.common.sync.schema.SchemaCollector;
import com.dadp.common.sync.schema.SchemaMetadata;
import com.dadp.jdbc.config.ProxyConfig;
import com.dadp.jdbc.policy.SchemaRecognizer;

import java.sql.Connection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JDBC 기반 스키마 수집 구현체 (Wrapper용, Java 8)
 * 
 * SchemaRecognizer를 사용하여 JDBC Connection에서 스키마를 수집합니다.
 * instanceId당 1세트 공유: Connection을 필드로 두지 않고 {@link #collectSchemas(Connection)} 호출 시점에만 전달.
 * 
 * @author DADP Development Team
 * @version 5.5.0
 * @since 2026-01-06
 */
public class JdbcSchemaCollector implements SchemaCollector {
    
    private final String datasourceId;
    private final SchemaRecognizer schemaRecognizer;
    private final ProxyConfig proxyConfig;  // 스키마 수집 안정성 설정용
    
    /**
     * 생성자 (Connection 없이, 호출 시점에 전달)
     */
    public JdbcSchemaCollector(String datasourceId, ProxyConfig proxyConfig) {
        this.datasourceId = datasourceId;
        this.proxyConfig = proxyConfig;
        this.schemaRecognizer = new SchemaRecognizer();
    }
    
    /**
     * 생성자 (ProxyConfig 없이, 하위 호환)
     */
    public JdbcSchemaCollector(String datasourceId) {
        this(datasourceId, null);
    }
    
    @Override
    public List<SchemaMetadata> collectSchemas() throws Exception {
        // Connection 없이 호출 시 빈 리스트 (instanceId당 1세트 공유 시 collectSchemas(Connection) 사용)
        return new java.util.ArrayList<>();
    }
    
    @Override
    public List<SchemaMetadata> collectSchemas(Connection connection) throws Exception {
        if (connection == null) {
            return new java.util.ArrayList<>();
        }
        // ProxyConfig에서 스키마 수집 안정성 설정 읽기
        String schemaAllowlist = proxyConfig != null ? proxyConfig.getSchemaAllowlist() : null;
        int maxSchemas = proxyConfig != null ? proxyConfig.getMaxSchemas() : -1;
        Long timeoutMs = proxyConfig != null ? proxyConfig.getSchemaCollectionTimeoutMs() : null;
        
        // SchemaRecognizer에서 스키마 수집 (안정성 설정 포함)
        List<SchemaRecognizer.SchemaMetadata> wrapperSchemas;
        try {
            wrapperSchemas = schemaRecognizer.collectSchemaMetadata(
                connection, 
                datasourceId, 
                schemaAllowlist, 
                maxSchemas, 
                timeoutMs
            );
        } catch (Exception e) {
            // 실패 모드 처리
            String failMode = proxyConfig != null ? proxyConfig.getSchemaCollectionFailMode() : "fail-open";
            if ("fail-close".equals(failMode)) {
                throw e;  // 예외 전파
            } else {
                // fail-open 모드: 빈 리스트 반환
                com.dadp.jdbc.logging.DadpLogger log = com.dadp.jdbc.logging.DadpLoggerFactory.getLogger(JdbcSchemaCollector.class);
                log.warn("⚠️ 스키마 수집 실패 (fail-open 모드): {} - 빈 리스트 반환", e.getMessage());
                return new java.util.ArrayList<>();
            }
        }
        
        // 공통 SchemaMetadata로 변환
        return wrapperSchemas.stream()
            .map(this::convertToCommonSchema)
            .collect(Collectors.toList());
    }
    
    /**
     * Wrapper SchemaMetadata를 공통 SchemaMetadata로 변환
     */
    private SchemaMetadata convertToCommonSchema(SchemaRecognizer.SchemaMetadata wrapper) {
        SchemaMetadata common = new SchemaMetadata();
        common.setDatasourceId(wrapper.getDatasourceId());
        common.setDbVendor(wrapper.getDbVendor());
        common.setDatabaseName(wrapper.getDatabaseName());
        common.setSchemaName(wrapper.getSchemaName());
        common.setTableName(wrapper.getTableName());
        common.setColumnName(wrapper.getColumnName());
        common.setColumnType(wrapper.getColumnType());
        common.setIsNullable(wrapper.getIsNullable());
        common.setColumnDefault(wrapper.getColumnDefault());
        common.setPolicyName(null);  // Wrapper는 policyName을 사용하지 않음
        return common;
    }
}

