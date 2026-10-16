package com.dadp.jdbc.integration;

import com.dadp.jdbc.DadpJdbcDriver;
import com.dadp.jdbc.DadpProxyConnection;
import com.dadp.common.sync.config.StoragePathResolver;
import com.dadp.common.sync.policy.PolicyMappingStorage;
import com.dadp.hub.crypto.exception.HubCryptoException;
import com.dadp.hub.crypto.exception.HubConnectionException;
import com.dadp.common.sync.config.InstanceConfigStorage;
import java.util.HashMap;
import java.util.Map;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

/** External PG17/MySQL84 native KMS probe; no mock server or SQL executor. */
public final class JdbcProcessProbe {
    private JdbcProcessProbe() { }

    public static void main(String[] args) {
        try {
            run(args);
            System.out.println((engineMode() ? "JDBC_ENGINE_PROBE_PASS" : "JDBC_PLUGIN_PROBE_PASS")
                    + " prepared=true batch=true null=true unicode=true partial=true denied=true storage=true repeatedGetters=true");
        } catch (Exception | AssertionError failure) {
            // Driver messages can contain bound data or credentials.
            System.err.println("JDBC_PLUGIN_PROBE_FAIL type=" + failure.getClass().getSimpleName()
                    + (failure instanceof SQLException ? " sqlState=" + ((SQLException) failure).getSQLState() : ""));
            if (failure instanceof AssertionError) System.err.println(failure.getMessage());
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
        check(args.length == 5, "arguments");
        String url = args[0], full = policy(args[2]), partial = policy(args[3]), denied = policy(args[4]);
        boolean mysql = url.startsWith("jdbc:mysql:");
        boolean engine = engineMode();
        check(mysql || url.startsWith("jdbc:postgresql:"), "native PG or MySQL URL required");
        check(!full.equals(partial) && !full.equals(denied) && !partial.equals(denied), "distinct policies required");
        Properties nativeConfig = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(args[1]))) { nativeConfig.load(in); }
        for (String key : nativeConfig.stringPropertyNames()) {
            check(!key.startsWith("dadp."), "JDBC properties must not override Agent mapping");
        }
        Class.forName(mysql ? "com.mysql.cj.jdbc.Driver" : "org.postgresql.Driver");
        String schema = "dadp_probe_" + UUID.randomUUID().toString().replace("-", "");
        String table = schema + ".records";
        try (Connection raw = DriverManager.getConnection(url, nativeConfig)) {
            requireVersion(mysql, raw.getMetaData().getDatabaseMajorVersion(), raw.getMetaData().getDatabaseMinorVersion());
            System.out.println("JDBC_PLUGIN_TARGET " + (mysql ? "mysql84" : "pg17"));
            execute(raw, "CREATE SCHEMA " + schema);
            try {
                execute(raw, "CREATE TABLE " + table + " (id varchar(64) PRIMARY KEY, secret text, partial_value text, denied_value text)"
                        + (mysql ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : ""));
                Properties mapped = engine ? engineMapping(nativeConfig, schema, full, partial, denied)
                        : mapping(nativeConfig, schema, full, partial, denied);
                if (mysql && !engine) mapped.setProperty("dadp.v8.mode", "mysql-evaluation");
                try (Connection agent = new DadpJdbcDriver().connect("jdbc:dadp:" + url.substring(5), mapped)) {
                    check(agent != null, "Agent connection");
                    check(!engine || agent instanceof DadpProxyConnection, "actual Engine JDBC entry required");
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
                    try (PreparedStatement ps = agent.prepareStatement("INSERT INTO " + table + " (id, denied_value) VALUES (?, ?)")) {
                        ps.setString(1, "denied"); ps.setString(2, "not-authorized"); ps.executeUpdate();
                        throw new AssertionError("denied policy accepted");
                    } catch (SQLException | RuntimeException failure) { verifyDenial(failure, mysql, engine); }
                    check(rowCount(raw, table, "denied") == 0, "denied write persisted");
                    if (engine) {
                        try {
                            execute(agent, "INSERT INTO " + table + " (id, secret) VALUES ('literal', 'must-not-persist')");
                            throw new AssertionError("literal write bypassed Engine");
                        } catch (SQLException rejected) { check("0A000".equals(rejected.getSQLState()), "literal write guard"); }
                        check(rowCount(raw, table, "literal") == 0, "literal plaintext persisted");
                    }
                    agent.setAutoCommit(false);
                    try (PreparedStatement ps = agent.prepareStatement("INSERT INTO " + table + " (id, denied_value) VALUES (?, ?)")) {
                        try {
                            ps.setString(1, "denied_batch"); ps.setString(2, "not-authorized"); ps.addBatch();
                            ps.executeBatch(); throw new AssertionError("denied batch accepted");
                        } catch (SQLException | RuntimeException failure) { verifyDenial(failure, mysql, engine); }
                    } finally { agent.rollback(); agent.setAutoCommit(true); }
                    check(rowCount(raw, table, "denied_batch") == 0, "denied batch persisted");
                    insert(agent, table, "after_denial", "still-operational");
                    roundtrip(agent, raw, table, "after_denial", "still-operational", full);
                    System.out.println("JDBC_PLUGIN_DENIED_PASS single=true batch=true postDenial=true");
                }
            } finally { execute(raw, "DROP SCHEMA " + schema + (mysql ? "" : " CASCADE")); }
        }
    }

    static String policy(String code) {
        check(code.matches("[A-Za-z0-9]{8}"), "eight character policy code required");
        return code;
    }

    static boolean engineMode() {
        String mode = System.getProperty("dadp.probe.mode", "plugin");
        check(mode.equals("engine") || mode.equals("plugin"), "unsupported probe mode");
        return mode.equals("engine");
    }

    private static Properties engineMapping(Properties source, String schema, String full, String partial, String denied) throws Exception {
        String endpoint = System.getProperty("dadp.probe.workerUrl", "");
        check(endpoint.startsWith("https://"), "HTTPS Worker required");
        check(System.getProperty("dadp.worker.tls.config") != null, "Worker mTLS required");
        java.nio.file.Path storage = Paths.get(StoragePathResolver.resolveStorageDir(schema));
        Files.createDirectories(storage);
        InstanceConfigStorage enrollment = new InstanceConfigStorage(storage.toString(), "proxy-config.json");
        check(enrollment.saveEnrollment("tenant", schema, "1", endpoint, null, null, null), "enrollment persistence");
        check(enrollment.saveRuntimeOptions("remote", false, false, "1", endpoint, "tenant", endpoint,
                null, null, null, true), "runtime persistence");
        Map<String, String> mappings = new HashMap<>();
        mappings.put(schema + ".records.secret", full);
        mappings.put(schema + ".records.partial_value", partial);
        mappings.put(schema + ".records.denied_value", denied);
        check(new PolicyMappingStorage(storage.toString(), "policy-mappings.json").saveMappings(mappings, 1L), "mapping persistence");
        Properties result = new Properties(); result.putAll(source);
        result.setProperty("dadp.wrapper.alias", schema);
        return result;
    }

    static void verifyDenial(Exception failure, boolean mysql, boolean engine) {
        if (!engine) { check(failure instanceof SQLException, "expected SQL rejection"); requireDenial((SQLException) failure, mysql); return; }
        boolean rejected = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            check(!(cause instanceof HubConnectionException || cause instanceof java.io.IOException), "transport failure is not denial");
            if (cause instanceof HubCryptoException && cause.getMessage() != null) {
                rejected |= cause.getMessage().startsWith("Engine API call failed: 400 ")
                        || cause.getMessage().startsWith("Engine API call failed: 403 ");
            }
        }
        check(rejected, "expected Worker policy rejection");
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
        requireDenial(failure, false);
    }

    static void requireVersion(boolean mysql, int major, int minor) {
        check(mysql ? major == 8 && minor == 4 : major == 17, "unsupported DB version");
    }

    static void requireDenial(SQLException failure, boolean mysql) {
        // The native plugin returns 38000 for protected-operation rejection.
        // Connection, syntax, missing function and transaction errors must never pass.
        check(mysql ? "45000".equals(failure.getSQLState()) :
                "38000".equals(failure.getSQLState()) || "42501".equals(failure.getSQLState()), "unexpected denial SQLState");
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
                check(Objects.equals(result, rs.getString(column)), "repeated named getter");
                check(Objects.equals(result, rs.getString(1)), "repeated indexed getter");
                check(Objects.equals(result, objectText(rs.getObject(1))), "indexed object getter");
                check(Objects.equals(result, objectText(rs.getObject(column))), "named object getter");
                check(rs.wasNull() == (result == null), "repeated getter NULL semantics");
                check(!rs.next(), "duplicate row"); return result;
            }
        }
    }

    static String objectText(Object value) {
        if (value instanceof byte[]) return new String((byte[]) value, java.nio.charset.StandardCharsets.UTF_8);
        check(value == null || value instanceof String, "unexpected JDBC text representation");
        return (String) value;
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
