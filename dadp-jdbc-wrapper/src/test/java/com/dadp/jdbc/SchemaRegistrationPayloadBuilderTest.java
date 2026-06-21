package com.dadp.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.dadp.common.sync.schema.SchemaMetadata;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchemaRegistrationPayloadBuilderTest {

    @Test
    void buildsSchemaRegisterPayloadWithAliasSeparateFromJdbcUrl() throws Exception {
        String dadpUrl = "jdbc:dadp:mysql://192.168.0.33:3306/test_app_mysql_db"
                + "?useSSL=false&serverTimezone=UTC";
        String actualUrl = DadpJdbcUrlSupport.extractActualUrl(dadpUrl);

        Map<String, Object> payload = SchemaRegistrationPayloadBuilder.buildWithAlias(
                "dadp-test-app-standalone-mysql",
                actualUrl,
                connection("MySQL", "test_app_mysql_db", null),
                schemas(),
                "dadp-test-app-mysql",
                "6.0.0",
                "mysql-app-1",
                null
        );

        assertEquals("dadp-test-app-standalone-mysql", payload.get("alias"));
        assertEquals("JDBC", payload.get("wrapperType"));
        assertEquals("dadp-test-app-mysql", payload.get("appName"));
        assertEquals("6.0.0", payload.get("wrapperVersion"));
        assertEquals("mysql-app-1", payload.get("clientInstanceId"));
        assertFalse(payload.containsKey("hubUrl"), "hubUrl must not be duplicated in Hub schema registration payload");

        Map<?, ?> datasource = (Map<?, ?>) payload.get("datasource");
        assertEquals("dadp-test-app-standalone-mysql", datasource.get("datasourceKey"));
        assertEquals("mysql", datasource.get("vendor"));
        assertEquals("192.168.0.33", datasource.get("host"));
        assertEquals(3306, datasource.get("port"));
        assertEquals("test_app_mysql_db", datasource.get("databaseName"));

        Map<?, ?> schema = (Map<?, ?>) payload.get("schema");
        List<?> tables = (List<?>) schema.get("tables");
        assertEquals(1, tables.size());
        Map<?, ?> table = (Map<?, ?>) tables.get(0);
        assertEquals("customers", table.get("tableName"));
        List<?> columns = (List<?>) table.get("columns");
        assertEquals(2, columns.size());
    }

    @Test
    void buildsSchemaCacheWithoutAliasForCollectOnlyFlow() throws Exception {
        String actualUrl = "jdbc:mysql://192.168.0.33:3306/test_app_mysql_db?useSSL=false";

        Map<String, Object> payload = SchemaRegistrationPayloadBuilder.buildSchemaCache(
                null,
                actualUrl,
                connection("MySQL", "test_app_mysql_db", null),
                schemas(),
                null,
                "6.0.0",
                null
        );

        assertFalse(payload.containsKey("alias"), "schema collect must not require alias");
        assertEquals("JDBC", payload.get("wrapperType"));

        Map<?, ?> datasource = (Map<?, ?>) payload.get("datasource");
        assertFalse(datasource.containsKey("datasourceKey"), "datasourceKey is assigned when alias is registered");
        assertEquals("mysql", datasource.get("vendor"));
        assertEquals("192.168.0.33", datasource.get("host"));
        assertEquals(3306, datasource.get("port"));
        assertEquals("test_app_mysql_db", datasource.get("databaseName"));
    }

    private static List<SchemaMetadata> schemas() {
        SchemaMetadata id = schema("test_app_mysql_db", "test_app_mysql_db", "customers", "id", "BIGINT", false);
        SchemaMetadata email = schema("test_app_mysql_db", "test_app_mysql_db", "customers", "email", "VARCHAR", true);
        return Arrays.asList(id, email);
    }

    private static SchemaMetadata schema(String databaseName, String schemaName, String tableName, String columnName, String type, boolean nullable) {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName(databaseName);
        schema.setSchemaName(schemaName);
        schema.setTableName(tableName);
        schema.setColumnName(columnName);
        schema.setColumnType(type);
        schema.setIsNullable(nullable);
        return schema;
    }

    private static Connection connection(String productName, String catalog, String schema) {
        DatabaseMetaData metadata = (DatabaseMetaData) Proxy.newProxyInstance(
                SchemaRegistrationPayloadBuilderTest.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class},
                new SimpleInvocationHandler("getDatabaseProductName", productName)
        );
        return (Connection) Proxy.newProxyInstance(
                SchemaRegistrationPayloadBuilderTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("getMetaData".equals(method.getName())) {
                        return metadata;
                    }
                    if ("getCatalog".equals(method.getName())) {
                        return catalog;
                    }
                    if ("getSchema".equals(method.getName())) {
                        return schema;
                    }
                    return defaultValue(method);
                }
        );
    }

    private static final class SimpleInvocationHandler implements InvocationHandler {
        private final String methodName;
        private final Object value;

        private SimpleInvocationHandler(String methodName, Object value) {
            this.methodName = methodName;
            this.value = value;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (methodName.equals(method.getName())) {
                return value;
            }
            return defaultValue(method);
        }
    }

    private static Object defaultValue(Method method) {
        Class<?> returnType = method.getReturnType();
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == double.class) {
            return 0D;
        }
        return null;
    }
}
