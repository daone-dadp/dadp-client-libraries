package com.dadp.jdbc.integration;

import com.dadp.jdbc.DadpJdbcDriver;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

/** External PG17/native KMS integration probe; no mock server or SQL executor. */
public final class JdbcProcessProbe {
    private JdbcProcessProbe() { }

    public static void main(String[] args) {
        try {
            run(args);
            System.out.println("JDBC_PLUGIN_PROBE_PASS prepared=true batch=true null=true unicode=true partial=true denied=true storage=true");
        } catch (Exception | AssertionError failure) {
            // Driver messages can contain bound data or credentials.
            System.err.println("JDBC_PLUGIN_PROBE_FAIL type=" + failure.getClass().getSimpleName()
                    + (failure instanceof SQLException ? " sqlState=" + ((SQLException) failure).getSQLState() : ""));
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
        check(args.length == 5, "arguments");
        String url = args[0], full = policy(args[2]), partial = policy(args[3]), denied = policy(args[4]);
        check(url.startsWith("jdbc:postgresql:"), "native PostgreSQL URL required");
        check(!full.equals(partial) && !full.equals(denied) && !partial.equals(denied), "distinct policies required");
        Properties nativeConfig = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(args[1]))) { nativeConfig.load(in); }
        for (String key : nativeConfig.stringPropertyNames()) {
            check(!key.startsWith("dadp."), "JDBC properties must not override Agent mapping");
        }
        Class.forName("org.postgresql.Driver");
        String schema = "dadp_probe_" + UUID.randomUUID().toString().replace("-", "");
        String table = schema + ".records";
        try (Connection raw = DriverManager.getConnection(url, nativeConfig)) {
            check(raw.getMetaData().getDatabaseMajorVersion() == 17, "PG17 required");
            execute(raw, "CREATE SCHEMA " + schema);
            try {
                execute(raw, "CREATE TABLE " + table + " (id text PRIMARY KEY, secret text, partial_value text, denied_value text)");
                Properties mapped = mapping(nativeConfig, schema, full, partial, denied);
                try (Connection agent = new DadpJdbcDriver().connect("jdbc:dadp:" + url.substring(5), mapped)) {
                    check(agent != null, "Agent connection");
                    String[] samples = {"", "ASCII-value", "\uD55C\uAE00\uD83D\uDE00 caf\u00e9", "quote' backslash\\ newline\n tab\t", null};
                    for (int i = 0; i < samples.length; i++) {
                        insert(agent, table, "s" + i, samples[i]);
                        roundtrip(agent, raw, table, "s" + i, samples[i], full);
                    }
                    System.out.println("JDBC_PLUGIN_PREPARED_PASS count=5");
                    agent.setAutoCommit(false);
                    try (PreparedStatement ps = agent.prepareStatement("INSERT INTO " + table + " (id, secret) VALUES (?, ?)")) {
                        for (int i = 0; i < samples.length; i++) {
                            ps.setString(1, "b" + i); bind(ps, 2, samples[i]); ps.addBatch();
                        }
                        int[] counts = ps.executeBatch();
                        check(counts.length == samples.length, "batch count");
                        for (int count : counts) check(count == 1 || count == Statement.SUCCESS_NO_INFO, "batch item");
                    }
                    agent.commit();
                    agent.setAutoCommit(true);
                    for (int i = 0; i < samples.length; i++) roundtrip(agent, raw, table, "b" + i, samples[i], full);
                    System.out.println("JDBC_PLUGIN_BATCH_PASS count=5");
                    String partialPlain = "\uAC00\uB098\uB2E4\uD83D\uDE00abc";
                    try (PreparedStatement ps = agent.prepareStatement("INSERT INTO " + table + " (id, partial_value) VALUES (?, ?)")) {
                        ps.setString(1, "partial"); ps.setString(2, partialPlain);
                        check(ps.executeUpdate() == 1, "partial insert");
                    }
                    check(partialPlain.equals(value(agent, table, "partial_value", "partial")), "partial roundtrip");
                    String stored = value(raw, table, "partial_value", "partial");
                    check(stored != null && stored.startsWith("\uAC00\uB098::DADP_ENC:v3:")
                            && stored.contains("kms:" + partial + ":") && !stored.contains(partialPlain), "partial storage");
                    System.out.println("JDBC_PLUGIN_PARTIAL_PASS mode=PLAIN_PREFIX count=2");
                    try {
                        execute(agent, "INSERT INTO " + table + " (id, denied_value) VALUES ('denied', 'not-authorized')");
                        throw new AssertionError("denied policy accepted");
                    } catch (SQLException failure) { requireDenial(failure); }
                    check(rowCount(raw, table, "denied") == 0, "denied write persisted");
                    agent.setAutoCommit(false);
                    try (PreparedStatement ps = agent.prepareStatement("INSERT INTO " + table + " (id, denied_value) VALUES (?, ?)")) {
                        ps.setString(1, "denied_batch"); ps.setString(2, "not-authorized"); ps.addBatch();
                        try { ps.executeBatch(); throw new AssertionError("denied batch accepted"); }
                        catch (SQLException failure) { requireDenial(failure); }
                    } finally { agent.rollback(); agent.setAutoCommit(true); }
                    check(rowCount(raw, table, "denied_batch") == 0, "denied batch persisted");
                    insert(agent, table, "after_denial", "still-operational");
                    roundtrip(agent, raw, table, "after_denial", "still-operational", full);
                    System.out.println("JDBC_PLUGIN_DENIED_PASS single=true batch=true postDenial=true");
                }
            } finally { execute(raw, "DROP SCHEMA " + schema + " CASCADE"); }
        }
    }

    static String policy(String code) {
        check(code.matches("[A-Za-z0-9]{8}"), "eight character policy code required");
        return code;
    }

    static Properties mapping(Properties source, String schema, String full, String partial, String denied) {
        Properties result = new Properties(); result.putAll(source);
        result.setProperty("dadp.v8.mode", "pg-evaluation");
        result.setProperty("dadp.v8.schema", schema);
        result.setProperty("dadp.v8.table", "records");
        result.setProperty("dadp.v8.column.id", "plain");
        result.setProperty("dadp.v8.column.secret", "text:" + policy(full));
        result.setProperty("dadp.v8.column.partial_value", "text:" + policy(partial));
        result.setProperty("dadp.v8.column.denied_value", "text:" + policy(denied));
        return result;
    }

    static void requireDenial(SQLException failure) {
        // The native plugin returns 38000 for protected-operation rejection.
        // Connection, syntax, missing function and transaction errors must never pass.
        check("38000".equals(failure.getSQLState()) || "42501".equals(failure.getSQLState()), "unexpected denial SQLState");
    }

    private static void insert(Connection c, String table, String id, String plain) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table + " (id, secret) VALUES (?, ?)")) {
            ps.setString(1, id); bind(ps, 2, plain); check(ps.executeUpdate() == 1, "insert");
        }
    }

    private static void bind(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) ps.setNull(index, Types.VARCHAR); else ps.setString(index, value);
    }

    private static String value(Connection c, String table, String column, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + column + " FROM " + table + " WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                check(rs.next(), "missing row"); String result = rs.getString(1);
                check(rs.wasNull() == (result == null), "NULL semantics");
                check(!rs.next(), "duplicate row"); return result;
            }
        }
    }

    private static void roundtrip(Connection agent, Connection raw, String table, String id, String plain, String policy) throws SQLException {
        check(Objects.equals(plain, value(agent, table, "secret", id)), "roundtrip mismatch");
        String stored = value(raw, table, "secret", id);
        if (plain == null) check(stored == null, "SQL NULL must remain NULL");
        else check(stored != null && stored.startsWith("kms:" + policy + ":") && !stored.equals(plain), "plaintext storage");
    }

    private static int rowCount(Connection c, String table, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM " + table + " WHERE id = ?")) {
            ps.setString(1, id); try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) { s.executeUpdate(sql); }
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
