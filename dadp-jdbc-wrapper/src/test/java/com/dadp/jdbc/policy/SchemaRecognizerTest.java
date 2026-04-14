package com.dadp.jdbc.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaRecognizerTest {

    @Test
    void normalizesSqreamVendorAndSkipsMissingOptionalMetadataColumns() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        ResultSet tables = mock(ResultSet.class);
        ResultSet columns = mock(ResultSet.class);
        ResultSetMetaData columnsMetaData = mock(ResultSetMetaData.class);

        when(connection.getMetaData()).thenReturn(metaData);
        when(connection.getCatalog()).thenReturn("master");
        when(metaData.getDatabaseProductName()).thenReturn("SqreamDB");
        when(metaData.getTables(eq("master"), isNull(), eq("%"), org.mockito.ArgumentMatchers.<String[]>any()))
            .thenReturn(tables);
        when(metaData.getColumns("master", "public", "events", "%")).thenReturn(columns);

        when(tables.next()).thenReturn(true, false);
        when(tables.getString("TABLE_NAME")).thenReturn("events");
        when(tables.getString("TABLE_SCHEM")).thenReturn("public");

        when(columns.getMetaData()).thenReturn(columnsMetaData);
        when(columnsMetaData.getColumnCount()).thenReturn(3);
        when(columnsMetaData.getColumnLabel(1)).thenReturn("COLUMN_NAME");
        when(columnsMetaData.getColumnName(1)).thenReturn("COLUMN_NAME");
        when(columnsMetaData.getColumnLabel(2)).thenReturn("TYPE_NAME");
        when(columnsMetaData.getColumnName(2)).thenReturn("TYPE_NAME");
        when(columnsMetaData.getColumnLabel(3)).thenReturn("IS_NULLABLE");
        when(columnsMetaData.getColumnName(3)).thenReturn("IS_NULLABLE");

        when(columns.next()).thenReturn(true, false);
        when(columns.getString(anyString())).thenAnswer(invocation -> {
            String columnLabel = invocation.getArgument(0, String.class);
            if ("COLUMN_NAME".equals(columnLabel)) {
                return "payload";
            }
            if ("TYPE_NAME".equals(columnLabel)) {
                return "VARCHAR";
            }
            if ("IS_NULLABLE".equals(columnLabel)) {
                return "YES";
            }
            throw new SQLException("Unexpected metadata column lookup: " + columnLabel);
        });

        SchemaRecognizer recognizer = new SchemaRecognizer();
        List<SchemaRecognizer.SchemaMetadata> schemas = recognizer.collectSchemaMetadata(connection, "datasource-1");

        assertEquals(1, schemas.size());
        SchemaRecognizer.SchemaMetadata schema = schemas.get(0);
        assertEquals("sqream", schema.getDbVendor());
        assertEquals("master", schema.getDatabaseName());
        assertEquals("public", schema.getSchemaName());
        assertEquals("events", schema.getTableName());
        assertEquals("payload", schema.getColumnName());
        assertTrue(schema.getIsNullable());
        assertNull(schema.getColumnDefault());
    }
}
