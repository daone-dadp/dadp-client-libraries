package com.dadp.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class DadpProxyConnectionSchemaLookupTest {

    @Test
    void returnsExplicitSchemaWhenProvided() {
        assertEquals("custom_schema",
                DadpProxyConnection.resolveLookupSchemaName("sqream", "custom_schema", "master", "master"));
    }

    @Test
    void usesPublicForSqreamWhenSchemaMissing() {
        assertEquals("public",
                DadpProxyConnection.resolveLookupSchemaName("sqream", null, "master", "master"));
    }

    @Test
    void usesPublicForPostgresWhenSchemaMissing() {
        assertEquals("public",
                DadpProxyConnection.resolveLookupSchemaName("postgresql", null, null, "test_db"));
    }

    @Test
    void usesDboForSqlServerWhenSchemaMissingOrEqualsDatabase() {
        assertEquals("dbo",
                DadpProxyConnection.resolveLookupSchemaName("mssql", null, "test_db", "test_db"));
        assertEquals("dbo",
                DadpProxyConnection.resolveLookupSchemaName("sql server", null, null, "test_db"));
    }

    @Test
    void usesCurrentDatabaseForMysqlWhenSchemaMissing() {
        assertEquals("test_app_mysql_db",
                DadpProxyConnection.resolveLookupSchemaName("mysql", null, null, "test_app_mysql_db"));
    }

    @Test
    void returnsNullWhenNoSchemaOrDatabaseContextExists() {
        assertNull(DadpProxyConnection.resolveLookupSchemaName("unknown", null, null, null));
    }
}
