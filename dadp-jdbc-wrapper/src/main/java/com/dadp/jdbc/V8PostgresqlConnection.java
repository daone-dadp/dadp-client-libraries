package com.dadp.jdbc;

import com.dadp.jdbc.rewrite.ExperimentalSqlRewriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** Isolated, opt-in PG evaluation path. No legacy bootstrap or value crypto objects. */
final class V8PostgresqlConnection {
    private static final String PREFIX = "dadp.v8.";
    private final ExperimentalSqlRewriter rewriter;
    private Connection connection;

    private V8PostgresqlConnection(ExperimentalSqlRewriter rewriter) {
        this.rewriter = rewriter;
    }

    static boolean requested(Properties properties) {
        return properties != null && properties.stringPropertyNames().stream().anyMatch(k -> k.startsWith(PREFIX));
    }

    static Connection connect(String url, Properties properties) throws SQLException {
        if (!"pg-evaluation".equals(properties.getProperty(PREFIX + "mode"))
                || !url.startsWith("jdbc:dadp:postgresql:")) {
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
                        && !key.equals(PREFIX + "table")) {
                    throw unsupported();
                }
            } else {
                nativeProperties.setProperty(key, value);
            }
        }
        ExperimentalSqlRewriter rewriter = ExperimentalSqlRewriter.forPostgresql(
                new ExperimentalSqlRewriter.FixtureScope(properties.getProperty(PREFIX + "schema"),
                        properties.getProperty(PREFIX + "table"), columns));
        String nativeUrl = DadpJdbcUrlSupport.extractActualUrl(url);
        // Exactly one connection attempt; never enter the legacy connector's retry path.
        Connection delegate;
        try {
            delegate = DriverManager.getDriver(nativeUrl).connect(nativeUrl, nativeProperties);
        } catch (SQLException e) {
            throw sanitized(e);
        }
        if (delegate == null) {
            throw new SQLException("PostgreSQL driver did not create a connection", "08001");
        }
        return wrap(delegate, rewriter);
    }

    static Connection wrap(Connection delegate, ExperimentalSqlRewriter rewriter) {
        V8PostgresqlConnection runtime = new V8PostgresqlConnection(rewriter);
        runtime.connection = runtime.proxy(Connection.class, delegate, null);
        return runtime.connection;
    }

    private <T> T proxy(Class<T> type, T delegate, Statement owner) {
        return type.cast(Proxy.newProxyInstance(V8PostgresqlConnection.class.getClassLoader(),
                new Class<?>[]{type}, (self, method, args) -> invoke(self, method, args, delegate, owner)));
    }

    private Object invoke(Object self, Method method, Object[] args, Object delegate, Statement owner) throws Throwable {
        String name = method.getName();
        if (method.getDeclaringClass() == Object.class) {
            if (name.equals("toString")) return "DADP v8 PostgreSQL JDBC wrapper";
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
        if (delegate instanceof Statement && name.equals("getGeneratedKeys")) throw unsupported();
        Object[] forwarded = args == null ? null : args.clone();
        if (delegate instanceof Connection) {
            if (name.equals("prepareCall") || name.equals("nativeSQL") || name.equals("setTypeMap")
                    || name.equals("getTypeMap") || name.equals("createArrayOf") || name.equals("createStruct")
                    || name.equals("createBlob") || name.equals("createClob") || name.equals("createNClob")
                    || name.equals("createSQLXML")) {
                throw unsupported();
            }
            if (name.equals("createStatement") || name.equals("prepareStatement")) {
                if (name.equals("prepareStatement") && args.length == 2) {
                    noGeneratedKeys(args[1]);
                }
                // Updatable result sets are a second write path, outside the SQL allowlist.
                int concurrencyIndex = name.equals("createStatement") ? 1 : 2;
                if (args != null && args.length > concurrencyIndex
                        && args[concurrencyIndex] instanceof Integer
                        && (Integer) args[concurrencyIndex] != ResultSet.CONCUR_READ_ONLY) {
                    throw unsupported();
                }
                if (name.equals("prepareStatement")) forwarded[0] = rewriter.rewrite((String) args[0]);
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
            return new SQLWarning("PostgreSQL JDBC warning in v8 evaluation mode", warning.getSQLState(), warning.getErrorCode());
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
        return new SQLFeatureNotSupportedException("Operation outside v8 PostgreSQL evaluation allowlist", "0A000");
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
        String message = "PostgreSQL JDBC operation failed in v8 evaluation mode";
        if (error instanceof BatchUpdateException) {
            return new BatchUpdateException(message, error.getSQLState(), error.getErrorCode(),
                    ((BatchUpdateException) error).getLargeUpdateCounts(), null);
        }
        return new SQLException(message, error.getSQLState(), error.getErrorCode());
    }
}
