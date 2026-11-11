package com.dadp.jdbc;

import com.dadp.jdbc.rewrite.ExperimentalSqlRewriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** Isolated PG/MySQL plugin evaluation path. No legacy bootstrap or value crypto objects. */
final class V8PostgresqlConnection {
    private static final String PREFIX = "dadp.v8.";
    static final String MODE_PROPERTY = PREFIX + "mode";
    private final ExperimentalSqlRewriter rewriter;
    private final boolean allowNamedGeneratedKeys;
    private final String generatedKey;
    private Connection connection;

    private V8PostgresqlConnection(ExperimentalSqlRewriter rewriter, boolean allowNamedGeneratedKeys, String generatedKey) {
        this.rewriter = rewriter;
        this.allowNamedGeneratedKeys = allowNamedGeneratedKeys;
        this.generatedKey = generatedKey;
    }

    static boolean pluginRequested(Properties properties) throws SQLException {
        if (properties == null) return false;
        String mode = properties.getProperty(MODE_PROPERTY);
        boolean pluginSettings = properties.stringPropertyNames().stream()
                .anyMatch(key -> key.startsWith(PREFIX) && !MODE_PROPERTY.equals(key));
        if ((mode == null && !properties.containsKey(MODE_PROPERTY)) || "engine".equals(mode)) {
            if (pluginSettings) throw invalidMode();
            return false;
        }
        if ("plugin".equals(mode) || "pg-evaluation".equals(mode) || "mysql-evaluation".equals(mode)) {
            return true;
        }
        throw invalidMode();
    }

    private static SQLException invalidMode() {
        return new SQLException("Invalid DADP JDBC execution mode or conflicting mode properties", "08001");
    }

    static Connection connect(String url, Properties properties) throws SQLException {
        String mode = properties.getProperty(MODE_PROPERTY);
        boolean mysql = ("plugin".equals(mode) || "mysql-evaluation".equals(mode))
                && url.startsWith("jdbc:dadp:mysql:");
        boolean pg = ("plugin".equals(mode) || "pg-evaluation".equals(mode))
                && url.startsWith("jdbc:dadp:postgresql:");
        if (!pg && !mysql) {
            throw unsupported();
        }
        DadpJdbcUrlSupport.validateNoDadpRuntimeParams(url);
        Map<String, String> columns = new HashMap<>();
        Properties nativeProperties = new Properties();
        for (String key : properties.stringPropertyNames()) {
            String value = properties.getProperty(key);
            if (key.startsWith(PREFIX + "column.")) {
                String column = key.substring((PREFIX + "column.").length());
                if ("plain".equals(value)) {
                    columns.put(column, null);
                } else if (value.startsWith("text:")) {
                    columns.put(column, value.substring(5));
                } else {
                    throw unsupported();
                }
            } else if (key.startsWith("dadp.")) {
                if (!key.equals(PREFIX + "mode") && !key.equals(PREFIX + "schema")
                        && !key.equals(PREFIX + "table")
                        && !("plugin".equals(mode) && key.equals(PREFIX + "generated-key"))) {
                    throw unsupported();
                }
            } else {
                nativeProperties.setProperty(key, value);
            }
        }
        if ("plugin".equals(mode) && columns.values().stream().noneMatch(java.util.Objects::nonNull)) {
            throw new SQLException("Plugin mode requires at least one protected column", "08001");
        }
        ExperimentalSqlRewriter.FixtureScope scope = new ExperimentalSqlRewriter.FixtureScope(
                properties.getProperty(PREFIX + "schema"), properties.getProperty(PREFIX + "table"), columns);
        ExperimentalSqlRewriter rewriter = mysql ? ExperimentalSqlRewriter.forMysql(scope)
                : ExperimentalSqlRewriter.forPostgresql(scope);
        String generatedKey = properties.getProperty(PREFIX + "generated-key");
        if (generatedKey != null) rewriter.checkGeneratedKeyColumns(new String[]{generatedKey});
        String nativeUrl = DadpJdbcUrlSupport.extractActualUrl(url);
        // Exactly one connection attempt; never enter the legacy connector's retry path.
        Connection delegate;
        try {
            delegate = DriverManager.getDriver(nativeUrl).connect(nativeUrl, nativeProperties);
        } catch (SQLException e) {
            throw sanitized(e);
        }
        if (delegate == null) {
            throw new SQLException("Native driver did not create a connection", "08001");
        }
        return wrap(delegate, rewriter, "plugin".equals(mode), generatedKey);
    }

    static Connection wrap(Connection delegate, ExperimentalSqlRewriter rewriter) {
        return wrap(delegate, rewriter, false, null);
    }

    private static Connection wrap(Connection delegate, ExperimentalSqlRewriter rewriter, boolean allowNamedGeneratedKeys, String generatedKey) {
        V8PostgresqlConnection runtime = new V8PostgresqlConnection(rewriter, allowNamedGeneratedKeys, generatedKey);
        runtime.connection = runtime.proxy(Connection.class, delegate, null);
        return runtime.connection;
    }

    private <T> T proxy(Class<T> type, T delegate, Statement owner) {
        return type.cast(Proxy.newProxyInstance(V8PostgresqlConnection.class.getClassLoader(),
                new Class<?>[]{type}, new NativeInvocation(delegate, owner)));
    }

    private final class NativeInvocation implements java.lang.reflect.InvocationHandler {
        private final Object delegate;
        private final Statement owner;
        NativeInvocation(Object delegate, Statement owner) { this.delegate=delegate; this.owner=owner; }
        public Object invoke(Object self, Method method, Object[] args) throws Throwable {
            return V8PostgresqlConnection.this.invoke(self, method, args, delegate, owner);
        }
    }

    // Package-only access for the atomic recovery ledger. Public unwrap stays blocked.
    static Connection recoveryDelegate(Connection wrapped) throws SQLException {
        if (Proxy.isProxyClass(wrapped.getClass())) {
            java.lang.reflect.InvocationHandler h=Proxy.getInvocationHandler(wrapped);
            if (h instanceof V8PostgresqlConnection.NativeInvocation) {
                Object delegate=((NativeInvocation) h).delegate;
                if (delegate instanceof Connection) return (Connection) delegate;
            }
        }
        throw new SQLException("Recovery requires a DADP PostgreSQL connection", "0A000");
    }

    private Object invoke(Object self, Method method, Object[] args, Object delegate, Statement owner) throws Throwable {
        String name = method.getName();
        if (method.getDeclaringClass() == Object.class) {
            if (name.equals("toString")) return "DADP v8 Plugin JDBC wrapper";
            if (name.equals("hashCode")) return System.identityHashCode(self);
            if (name.equals("equals")) return self == args[0];
        }
        if (name.equals("unwrap")) {
            if (((Class<?>) args[0]).isInstance(self)) return self;
            throw unsupported();
        }
        if (name.equals("isWrapperFor")) return ((Class<?>) args[0]).isInstance(self);
        if (name.equals("getConnection")) return connection;
        if (delegate instanceof ResultSet && name.equals("getStatement")) return owner;
        if (delegate instanceof Statement && name.equals("getGeneratedKeys") && !allowNamedGeneratedKeys) {
            throw unsupported();
        }
        Object[] forwarded = args == null ? null : args.clone();
        if (delegate instanceof Connection) {
            if (name.equals("prepareCall") || name.equals("nativeSQL") || name.equals("setTypeMap")
                    || name.equals("getTypeMap") || name.equals("createArrayOf") || name.equals("createStruct")
                    || name.equals("createBlob") || name.equals("createClob") || name.equals("createNClob")
                    || name.equals("createSQLXML")) {
                throw unsupported();
            }
            if (name.equals("createStatement") || name.equals("prepareStatement")) {
                if (name.equals("prepareStatement")) {
                    String rewritten = rewriter.rewrite((String) args[0]);
                    forwarded[0] = rewritten;
                    if (args.length == 2) {
                        String[] columns = null;
                        if (allowNamedGeneratedKeys && args[1] instanceof String[]) {
                            columns = ((String[]) args[1]).clone();
                        } else if (generatedKey != null && Integer.valueOf(Statement.RETURN_GENERATED_KEYS).equals(args[1])) {
                            columns = new String[]{generatedKey};
                            method = Connection.class.getMethod("prepareStatement", String.class, String[].class);
                        } else {
                            noGeneratedKeys(args[1]);
                        }
                        if (columns != null) {
                            if (!rewritten.startsWith("INSERT INTO ")) throw unsupported();
                            rewriter.checkGeneratedKeyColumns(columns);
                            forwarded[1] = columns;
                        }
                    }
                }
                // Updatable result sets are a second write path, outside the SQL allowlist.
                int concurrencyIndex = name.equals("createStatement") ? 1 : 2;
                if (args != null && args.length > concurrencyIndex
                        && args[concurrencyIndex] instanceof Integer
                        && (Integer) args[concurrencyIndex] != ResultSet.CONCUR_READ_ONLY) {
                    throw unsupported();
                }
            }
        }
        if (delegate instanceof Statement && args != null && args.length > 0
                && (name.startsWith("execute") || name.equals("addBatch")) && args[0] instanceof String) {
            if (delegate instanceof PreparedStatement) throw unsupported();
            if (args.length == 2) noGeneratedKeys(args[1]);
            forwarded[0] = rewriter.rewrite((String) args[0]);
        }
        if (delegate instanceof ResultSet && (name.startsWith("update") || name.equals("insertRow")
                || name.equals("deleteRow") || name.equals("moveToInsertRow") || name.equals("getArray")
                || name.equals("getRef") || name.equals("getBlob") || name.equals("getClob")
                || name.equals("getNClob") || name.equals("getSQLXML"))) {
            throw unsupported();
        }
        if (delegate instanceof ResultSet && name.equals("getObject")) {
            scalarObjectColumn((ResultSet) delegate, args[0]);
        }
        Object result;
        try {
            result = method.invoke(delegate, forwarded);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof SQLException) throw sanitized((SQLException) e.getCause());
            throw e.getCause();
        }
        if (result instanceof PreparedStatement) return proxy(PreparedStatement.class, (PreparedStatement) result, null);
        if (result instanceof Statement) return proxy(Statement.class, (Statement) result, null);
        if (result instanceof ResultSet) {
            return proxy(ResultSet.class, (ResultSet) result, self instanceof Statement ? (Statement) self : owner);
        }
        if (result instanceof DatabaseMetaData) return proxy(DatabaseMetaData.class, (DatabaseMetaData) result, null);
        if (result instanceof ResultSetMetaData) return proxy(ResultSetMetaData.class, (ResultSetMetaData) result, null);
        if (result instanceof ParameterMetaData) return proxy(ParameterMetaData.class, (ParameterMetaData) result, null);
        if (result instanceof SQLWarning) {
            SQLWarning warning = (SQLWarning) result;
            return new SQLWarning("JDBC warning in v8 plugin evaluation mode", warning.getSQLState(), warning.getErrorCode());
        }
        // PG Array/LOB/vendor objects can retain a native connection. Text evaluation
        // does not expose them, including through untyped getObject().
        if (delegate instanceof ResultSet && name.equals("getObject") && result != null
                && !(result instanceof String || result instanceof Number || result instanceof Boolean
                || result instanceof byte[] || result instanceof java.util.Date
                || result instanceof java.util.UUID || result instanceof java.time.temporal.TemporalAccessor)) {
            throw unsupported();
        }
        return result;
    }

    private static SQLFeatureNotSupportedException unsupported() {
        return new SQLFeatureNotSupportedException("Operation outside v8 plugin evaluation allowlist", "0A000");
    }

    private static void noGeneratedKeys(Object option) throws SQLException {
        if (!(option instanceof Integer) || (Integer) option != Statement.NO_GENERATED_KEYS) throw unsupported();
    }

    private static void scalarObjectColumn(ResultSet result, Object column) throws SQLException {
        int type;
        try {
            int index = column instanceof Integer ? (Integer) column : result.findColumn((String) column);
            ResultSetMetaData metadata = result.getMetaData();
            if (metadata == null) throw unsupported();
            type = metadata.getColumnType(index);
        } catch (SQLException e) {
            throw sanitized(e);
        }
        // In PG, getObject(refcursor) executes FETCH before returning. Validate type
        // before invoking it, not just the returned Java object's type.
        switch (type) {
            case Types.NULL:
            case Types.CHAR: case Types.VARCHAR: case Types.LONGVARCHAR:
            case Types.NCHAR: case Types.NVARCHAR: case Types.LONGNVARCHAR:
            case Types.BOOLEAN: case Types.BIT:
            case Types.TINYINT: case Types.SMALLINT: case Types.INTEGER: case Types.BIGINT:
            case Types.REAL: case Types.FLOAT: case Types.DOUBLE: case Types.NUMERIC: case Types.DECIMAL:
            case Types.BINARY: case Types.VARBINARY: case Types.LONGVARBINARY:
            case Types.DATE: case Types.TIME: case Types.TIMESTAMP:
            case Types.TIME_WITH_TIMEZONE: case Types.TIMESTAMP_WITH_TIMEZONE:
                return;
            default:
                throw unsupported();
        }
    }

    private static SQLException sanitized(SQLException error) {
        String message = "JDBC operation failed in v8 plugin evaluation mode";
        if (error instanceof BatchUpdateException) {
            return new BatchUpdateException(message, error.getSQLState(), error.getErrorCode(),
                    ((BatchUpdateException) error).getLargeUpdateCounts(), null);
        }
        return new SQLException(message, error.getSQLState(), error.getErrorCode());
    }
}
