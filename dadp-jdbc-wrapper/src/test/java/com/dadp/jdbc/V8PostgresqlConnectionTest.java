package com.dadp.jdbc;

import com.dadp.jdbc.rewrite.ExperimentalSqlRewriter;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class V8PostgresqlConnectionTest {
    private static final String INSERT = "INSERT INTO app.users (id, secret) VALUES (?, ?)";
    private static final String ENCRYPTED = "INSERT INTO app.users (id, secret) VALUES (?, dadp.dadp_encrypt(?, 'TEST0001'))";

    private Connection wrap(Connection nativeConnection) throws Exception {
        Map<String, String> columns = new HashMap<>();
        columns.put("id", null);
        columns.put("secret", "TEST0001");
        return V8PostgresqlConnection.wrap(nativeConnection, ExperimentalSqlRewriter.forPostgresql(
                new ExperimentalSqlRewriter.FixtureScope("app", "users", columns)));
    }

    private Properties settings() {
        Properties p = new Properties();
        p.setProperty("dadp.v8.mode", "pg-evaluation");
        p.setProperty("dadp.v8.schema", "app");
        p.setProperty("dadp.v8.table", "users");
        p.setProperty("dadp.v8.column.id", "plain");
        p.setProperty("dadp.v8.column.secret", "text:TEST0001");
        return p;
    }

    @Test
    void everyPrepareOverloadRewritesBeforeDelegation() throws Exception {
        Connection raw = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(raw.prepareStatement(anyString())).thenReturn(ps);
        when(raw.prepareStatement(anyString(), anyInt())).thenReturn(ps);
        when(raw.prepareStatement(anyString(), any(int[].class))).thenReturn(ps);
        when(raw.prepareStatement(anyString(), any(String[].class))).thenReturn(ps);
        when(raw.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(ps);
        when(raw.prepareStatement(anyString(), anyInt(), anyInt(), anyInt())).thenReturn(ps);
        Connection c = wrap(raw);
        assertNotSame(ps, c.prepareStatement(INSERT));
        c.prepareStatement(INSERT, Statement.NO_GENERATED_KEYS);
        assertThrows(SQLException.class, () -> c.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS));
        assertThrows(SQLException.class, () -> c.prepareStatement(INSERT, new int[]{1}));
        assertThrows(SQLException.class, () -> c.prepareStatement(INSERT, new String[]{"id"}));
        c.prepareStatement(INSERT, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        c.prepareStatement(INSERT, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT);
        verify(raw).prepareStatement(ENCRYPTED);
        verify(raw).prepareStatement(ENCRYPTED, Statement.NO_GENERATED_KEYS);
        verify(raw, never()).prepareStatement(anyString(), any(int[].class));
        verify(raw, never()).prepareStatement(anyString(), any(String[].class));
        verify(raw).prepareStatement(ENCRYPTED, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        verify(raw).prepareStatement(ENCRYPTED, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    @Test
    void allStatementSqlOverloadsAndBatchesRewrite() throws Exception {
        Connection raw = mock(Connection.class);
        Statement delegate = mock(Statement.class);
        when(raw.createStatement()).thenReturn(delegate);
        when(raw.createStatement(anyInt(), anyInt())).thenReturn(delegate);
        when(raw.createStatement(anyInt(), anyInt(), anyInt())).thenReturn(delegate);
        Connection c = wrap(raw);
        Statement s = c.createStatement();
        assertSame(c, c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY).getConnection());
        assertSame(c, c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
                ResultSet.CLOSE_CURSORS_AT_COMMIT).getConnection());
        s.execute(INSERT);
        s.execute(INSERT, Statement.NO_GENERATED_KEYS);
        assertThrows(SQLException.class, () -> s.execute(INSERT, new int[]{1}));
        assertThrows(SQLException.class, () -> s.execute(INSERT, new String[]{"id"}));
        assertThrows(SQLException.class, () -> s.execute(INSERT, Statement.RETURN_GENERATED_KEYS));
        s.executeUpdate(INSERT);
        s.executeUpdate(INSERT, Statement.NO_GENERATED_KEYS);
        assertThrows(SQLException.class, () -> s.executeUpdate(INSERT, new int[]{1}));
        assertThrows(SQLException.class, () -> s.executeUpdate(INSERT, new String[]{"id"}));
        assertThrows(SQLException.class, () -> s.executeUpdate(INSERT, Statement.RETURN_GENERATED_KEYS));
        s.executeLargeUpdate(INSERT);
        s.executeLargeUpdate(INSERT, Statement.NO_GENERATED_KEYS);
        assertThrows(SQLException.class, () -> s.executeLargeUpdate(INSERT, new int[]{1}));
        assertThrows(SQLException.class, () -> s.executeLargeUpdate(INSERT, new String[]{"id"}));
        assertThrows(SQLException.class, () -> s.executeLargeUpdate(INSERT, Statement.RETURN_GENERATED_KEYS));
        s.addBatch(INSERT);
        s.executeBatch();
        s.executeLargeBatch();
        s.clearBatch();
        s.executeQuery("SELECT secret FROM app.users WHERE id = 'x'");
        verify(delegate).execute(ENCRYPTED);
        verify(delegate).execute(ENCRYPTED, Statement.NO_GENERATED_KEYS);
        verify(delegate, never()).execute(anyString(), any(int[].class));
        verify(delegate, never()).execute(anyString(), any(String[].class));
        verify(delegate).executeUpdate(ENCRYPTED);
        verify(delegate).executeUpdate(ENCRYPTED, Statement.NO_GENERATED_KEYS);
        verify(delegate, never()).executeUpdate(anyString(), any(int[].class));
        verify(delegate, never()).executeUpdate(anyString(), any(String[].class));
        verify(delegate).executeLargeUpdate(ENCRYPTED);
        verify(delegate).executeLargeUpdate(ENCRYPTED, Statement.NO_GENERATED_KEYS);
        verify(delegate, never()).executeLargeUpdate(anyString(), any(int[].class));
        verify(delegate, never()).executeLargeUpdate(anyString(), any(String[].class));
        verify(delegate).addBatch(ENCRYPTED);
        verify(delegate).executeBatch();
        verify(delegate).executeLargeBatch();
        verify(delegate).clearBatch();
        verify(delegate).executeQuery("SELECT dadp.dadp_decrypt(secret) AS secret FROM app.users WHERE id = 'x'");
    }

    @Test
    void settersReuseNullBatchAndTransactionsAreUnchanged() throws Exception {
        Connection raw = mock(Connection.class);
        PreparedStatement delegate = mock(PreparedStatement.class);
        when(raw.prepareStatement(anyString())).thenReturn(delegate);
        Connection c = wrap(raw);
        c.setAutoCommit(false);
        PreparedStatement ps = c.prepareStatement(INSERT);
        ps.setString(2, "private UTF8 \uD55C\uAE00");
        ps.setString(1, "one");
        ps.addBatch();
        ps.setNull(2, Types.VARCHAR);
        ps.setObject(1, "two", Types.VARCHAR);
        ps.addBatch();
        ps.executeBatch();
        ps.clearParameters();
        ps.setString(1, "three");
        ps.setString(2, "");
        ps.executeUpdate();
        ps.executeLargeUpdate();
        ps.execute();
        ps.executeQuery();
        c.commit();
        c.rollback();
        verify(delegate).setString(2, "private UTF8 \uD55C\uAE00");
        verify(delegate).setString(1, "one");
        verify(delegate).setNull(2, Types.VARCHAR);
        verify(delegate).setObject(1, "two", Types.VARCHAR);
        verify(delegate, times(2)).addBatch();
        verify(delegate).executeBatch();
        verify(delegate).clearParameters();
        verify(delegate).executeUpdate();
        verify(delegate).executeLargeUpdate();
        verify(delegate).execute();
        verify(delegate).executeQuery();
        verify(raw).setAutoCommit(false);
        verify(raw).commit();
        verify(raw).rollback();
        verify(raw, times(1)).prepareStatement(anyString());
    }

    @Test
    void jdbcObjectGraphNeverExposesDelegateOrDecryptsValues() throws Exception {
        Connection raw = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData rm = mock(ResultSetMetaData.class);
        ParameterMetaData pm = mock(ParameterMetaData.class);
        when(raw.prepareStatement(anyString())).thenReturn(ps);
        when(raw.getMetaData()).thenReturn(md);
        when(ps.executeQuery()).thenReturn(rs);
        when(ps.getResultSet()).thenReturn(rs);
        when(ps.getGeneratedKeys()).thenReturn(rs);
        when(ps.getMetaData()).thenReturn(rm);
        when(ps.getParameterMetaData()).thenReturn(pm);
        when(md.getTables(any(), any(), any(), any())).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(rm);
        when(rs.getString(1)).thenReturn("DB result unchanged");
        Connection c = wrap(raw);
        PreparedStatement s = c.prepareStatement("SELECT secret FROM app.users");
        ResultSet r = s.executeQuery();
        assertSame(c, s.getConnection());
        assertSame(s, r.getStatement());
        assertSame(s, s.getResultSet().getStatement());
        assertThrows(SQLException.class, s::getGeneratedKeys);
        assertSame(c, c.getMetaData().getConnection());
        assertNull(c.getMetaData().getTables(null, null, null, null).getStatement());
        assertEquals("DB result unchanged", r.getString(1));
        assertNotSame(rm, r.getMetaData());
        assertNotSame(rm, s.getMetaData());
        assertNotSame(pm, s.getParameterMetaData());
        for (Wrapper object : new Wrapper[]{c, s, r, c.getMetaData(), r.getMetaData(), s.getParameterMetaData()}) {
            assertFalse(object.isWrapperFor(Runnable.class));
            assertThrows(SQLFeatureNotSupportedException.class, () -> object.unwrap(Runnable.class));
        }
        assertSame(c, c.unwrap(Connection.class));
        assertSame(s, s.unwrap(Statement.class));
        assertSame(r, r.unwrap(ResultSet.class));
        assertThrows(SQLException.class, () -> r.updateString(1, "plaintext"));
        assertThrows(SQLException.class, r::insertRow);
        assertThrows(SQLException.class, r::deleteRow);
        assertThrows(SQLException.class, r::moveToInsertRow);
        assertFalse(s.toString().contains("SELECT"));
    }

    @Test
    void unsupportedOperationsFailBeforeNativeCall() throws Exception {
        Connection raw = mock(Connection.class);
        Connection c = wrap(raw);
        assertThrows(SQLException.class, () -> c.prepareCall("CALL x()"));
        assertThrows(SQLException.class, () -> c.prepareCall("CALL x()", 1, 2));
        assertThrows(SQLException.class, () -> c.prepareCall("CALL x()", 1, 2, 3));
        assertThrows(SQLException.class, () -> c.nativeSQL(INSERT));
        assertThrows(SQLException.class, () -> c.prepareStatement("SELECT * FROM app.users"));
        assertThrows(SQLException.class, () -> c.prepareStatement(INSERT, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE));
        assertThrows(SQLException.class, () -> c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE));
        verifyNoInteractions(raw);
        Statement delegate = mock(Statement.class);
        PreparedStatement prepared = mock(PreparedStatement.class);
        when(raw.createStatement()).thenReturn(delegate);
        when(raw.prepareStatement(anyString())).thenReturn(prepared);
        Statement s = c.createStatement();
        assertThrows(SQLException.class, () -> s.addBatch("UPDATE app.users SET secret = ? WHERE secret = ?"));
        assertThrows(SQLException.class, () -> s.executeLargeUpdate("COPY app.users FROM STDIN"));
        PreparedStatement p = c.prepareStatement(INSERT);
        assertThrows(SQLException.class, () -> p.execute(INSERT));
        assertThrows(SQLException.class, () -> p.addBatch(INSERT));
        verifyNoInteractions(delegate, prepared);
    }

    @Test
    void nativeErrorsArePropagatedExactlyOnceWithoutFallback() throws Exception {
        Connection raw = mock(Connection.class);
        Statement delegate = mock(Statement.class);
        when(raw.createStatement()).thenReturn(delegate);
        SQLException error = new SQLException("private bind value", "42501", 99, new RuntimeException("secret"));
        error.setNextException(new SQLException("private SQL"));
        when(delegate.executeUpdate(anyString())).thenThrow(error);
        SQLException sanitized = assertThrows(SQLException.class, () -> wrap(raw).createStatement().executeUpdate(INSERT));
        assertEquals("42501", sanitized.getSQLState());
        assertEquals(99, sanitized.getErrorCode());
        assertNull(sanitized.getCause());
        assertNull(sanitized.getNextException());
        assertFalse(sanitized.getMessage().contains("private"));
        verify(delegate, times(1)).executeUpdate(ENCRYPTED);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    void batchCountsAndCancellationSurviveSanitizationAndRollback() throws Exception {
        Connection raw = mock(Connection.class);
        Statement delegate = mock(Statement.class);
        when(raw.createStatement()).thenReturn(delegate);
        long[] counts = {1, Statement.EXECUTE_FAILED, (long) Integer.MAX_VALUE + 1};
        BatchUpdateException failure = new BatchUpdateException("private data", "22000", 17, counts,
                new SQLException("private cause"));
        failure.setNextException(new SQLException("private next"));
        when(delegate.executeLargeBatch()).thenThrow(failure);
        Connection c = wrap(raw);
        Statement s = c.createStatement();
        BatchUpdateException sanitized = assertThrows(BatchUpdateException.class, s::executeLargeBatch);
        assertArrayEquals(counts, sanitized.getLargeUpdateCounts());
        assertArrayEquals(failure.getUpdateCounts(), sanitized.getUpdateCounts());
        assertEquals("22000", sanitized.getSQLState());
        assertEquals(17, sanitized.getErrorCode());
        assertNull(sanitized.getCause());
        assertNull(sanitized.getNextException());
        when(delegate.executeUpdate(anyString())).thenThrow(new SQLException("private canceled SQL", "57014"));
        assertEquals("57014", assertThrows(SQLException.class, () -> s.executeUpdate(INSERT)).getSQLState());
        c.rollback();
        verify(raw).rollback();
        verify(delegate).executeLargeBatch();
        verify(delegate).executeUpdate(ENCRYPTED);
    }

    @Test
    void driverEntryUsesOnlyNativeDriverAndStripsV8Settings() throws Exception {
        Driver nativeDriver = mock(Driver.class);
        Connection raw = mock(Connection.class);
        when(nativeDriver.acceptsURL("jdbc:postgresql:v8-test")).thenReturn(true);
        when(nativeDriver.connect(eq("jdbc:postgresql:v8-test"), any())).thenReturn(raw);
        DriverManager.registerDriver(nativeDriver);
        try {
            Properties p = settings();
            p.setProperty("sslmode", "verify-full");
            p.setProperty("user", "evaluation");
            p.setProperty("password", "not-logged");
            Connection c = new DadpJdbcDriver().connect("jdbc:dadp:postgresql:v8-test", p);
            assertNotSame(raw, c);
            Properties expected = new Properties();
            expected.setProperty("sslmode", "verify-full");
            expected.setProperty("user", "evaluation");
            expected.setProperty("password", "not-logged");
            verify(nativeDriver).connect("jdbc:postgresql:v8-test", expected);
            verifyNoInteractions(raw);
            c.close();
            verify(raw).close();
        } finally {
            DriverManager.deregisterDriver(nativeDriver);
        }
    }

    @Test
    void badOrMissingMappingCannotReachDatabaseOrLegacyBootstrap() throws Exception {
        DadpJdbcDriver driver = new DadpJdbcDriver();
        for (String key : new String[]{"dadp.v8.mode", "dadp.v8.schema", "dadp.v8.table"}) {
            Properties p = settings();
            p.remove(key);
            assertThrows(SQLException.class, () -> driver.connect("jdbc:dadp:postgresql:v8-test", p));
        }
        for (String value : new String[]{"", "false", "pg", "typo"}) {
            Properties p = settings();
            p.setProperty("dadp.v8.mode", value);
            assertThrows(SQLException.class, () -> driver.connect("jdbc:dadp:postgresql:v8-test", p));
        }
        Properties p = settings();
        p.remove("dadp.v8.column.id");
        p.remove("dadp.v8.column.secret");
        assertThrows(SQLException.class, () -> driver.connect("jdbc:dadp:postgresql:v8-test", p));
        p.setProperty("dadp.v8.column.secret", "bytea:TEST0001");
        assertThrows(SQLException.class, () -> driver.connect("jdbc:dadp:postgresql:v8-test", p));
        assertThrows(SQLException.class, () -> driver.connect("jdbc:dadp:mysql:v8-test", settings()));
        Properties bad = settings();
        bad.setProperty("dadp.v8.unknown", "x");
        assertThrows(SQLException.class, () -> driver.connect("jdbc:dadp:postgresql:v8-test", bad));
    }

    @Test
    void complexJdbcObjectsCannotExposeNativeConnections() throws Exception {
        Connection raw = mock(Connection.class);
        Connection c = wrap(raw);
        assertThrows(SQLException.class, () -> c.createArrayOf("text", new String[]{"x"}));
        assertThrows(SQLException.class, () -> c.createStruct("x", new Object[]{"x"}));
        assertThrows(SQLException.class, c::createBlob);
        assertThrows(SQLException.class, c::createClob);
        assertThrows(SQLException.class, c::createNClob);
        assertThrows(SQLException.class, c::createSQLXML);
        assertThrows(SQLException.class, c::getTypeMap);
        assertThrows(SQLException.class, () -> c.setTypeMap(new HashMap<>()));
        verifyNoInteractions(raw);
        Statement delegate = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(raw.createStatement()).thenReturn(delegate);
        when(delegate.executeQuery(anyString())).thenReturn(rs);
        ResultSet r = c.createStatement().executeQuery("SELECT secret FROM app.users");
        assertThrows(SQLException.class, () -> r.getArray(1));
        assertThrows(SQLException.class, () -> r.getArray("secret"));
        assertThrows(SQLException.class, () -> r.getBlob(1));
        assertThrows(SQLException.class, () -> r.getClob(1));
        assertThrows(SQLException.class, () -> r.getRef(1));
        assertThrows(SQLException.class, () -> r.getSQLXML(1));
        verifyNoInteractions(rs);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnType(anyInt())).thenReturn(Types.VARCHAR);
        when(rs.getObject(1)).thenReturn(mock(java.sql.Array.class));
        assertThrows(SQLException.class, () -> r.getObject(1));
        when(rs.getObject("secret")).thenReturn(mock(Blob.class));
        assertThrows(SQLException.class, () -> r.getObject("secret"));
        when(rs.getObject(1, Object.class)).thenReturn(new Object());
        assertThrows(SQLException.class, () -> r.getObject(1, Object.class));
        when(rs.getObject(1)).thenReturn("text");
        assertEquals("text", r.getObject(1));
    }

    @Test
    void refcursorGetObjectIsRejectedBeforeDriverCanFetch() throws Exception {
        Connection raw = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(raw.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.findColumn("id")).thenReturn(1);
        when(metadata.getColumnType(1)).thenReturn(Types.REF_CURSOR);
        ResultSet r = wrap(raw).createStatement().executeQuery("SELECT id FROM app.users");
        assertThrows(SQLException.class, () -> r.getObject(1));
        assertThrows(SQLException.class, () -> r.getObject("id"));
        assertThrows(SQLException.class, () -> r.getObject(1, Object.class));
        assertThrows(SQLException.class, () -> r.getObject("id", Object.class));
        assertThrows(SQLException.class, () -> r.getObject(1, new HashMap<>()));
        assertThrows(SQLException.class, () -> r.getObject("id", new HashMap<>()));
        verify(rs, never()).getObject(anyInt());
        verify(rs, never()).getObject(anyString());
        verify(rs, never()).getObject(anyInt(), any(Class.class));
        verify(rs, never()).getObject(anyString(), any(Class.class));
        verify(rs, never()).getObject(anyInt(), anyMap());
        verify(rs, never()).getObject(anyString(), anyMap());
    }

    @Test
    void warningsAreSanitizedWithoutValueBearingChains() throws Exception {
        Connection raw = mock(Connection.class);
        SQLWarning warning = new SQLWarning("private value", "01000", 7);
        warning.setNextWarning(new SQLWarning("private next"));
        when(raw.getWarnings()).thenReturn(warning);
        SQLWarning sanitized = wrap(raw).getWarnings();
        assertEquals("01000", sanitized.getSQLState());
        assertEquals(7, sanitized.getErrorCode());
        assertNull(sanitized.getCause());
        assertNull(sanitized.getNextWarning());
        assertFalse(sanitized.getMessage().contains("private"));
    }

    @Test
    void urlV8SettingsNeverFallThroughToLegacyBootstrap() {
        for (String key : new String[]{"dadp.v8.mode", "dadp%2Ev8%2Emode", "DADP.V8.MODE", "dadp.v8.mode="}) {
            assertThrows(IllegalArgumentException.class, () -> new DadpJdbcDriver().connect(
                    "jdbc:dadp:postgresql:v8-test?" + key + "=pg-evaluation", new Properties()));
        }
        assertThrows(IllegalArgumentException.class, () -> new DadpJdbcDriver().connect(
                "jdbc:dadp:postgresql:v8-test?dadp.v8.mode", new Properties()));
    }
}
