package com.dadp.jdbc;

import com.dadp.hub.crypto.exception.TransientCryptoException;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in idempotent prepared writes. The caller retains the same request ID and
 * immutable input until completion. No implicit replay of ordinary JDBC SQL.
 * Ledger and business write commit on one physical PostgreSQL transaction.
 * Only transactional DB effects belong in the statement (no external effects).
 */
public final class RecoverablePostgresqlWrite {
    private final String url, scope;
    private final Properties properties = new Properties();
    private final long waitMillis;

    public RecoverablePostgresqlWrite(String url, Properties properties, String scope, long waitMillis) {
        if (url == null || !url.startsWith("jdbc:dadp:postgresql:")
                || scope == null || !scope.matches("[A-Za-z0-9_.:-]{1,64}")
                || waitMillis < 1 || waitMillis > 600000)
            throw new IllegalArgumentException("Invalid PostgreSQL recovery configuration");
        this.url=url; this.scope=scope; this.waitMillis=waitMillis;
        this.properties.putAll(Objects.requireNonNull(properties, "properties"));
        String mode=this.properties.getProperty("dadp.v8.mode", "engine");
        if (!mode.equals("engine") && !mode.equals("plugin"))
            throw new IllegalArgumentException("Recovery requires an explicit supported mode");
        if (!this.properties.containsKey("connectTimeout")) this.properties.setProperty("connectTimeout", "2");
        if (!this.properties.containsKey("socketTimeout")) this.properties.setProperty("socketTimeout", "20");
    }

    public static final class Result {
        public final long affectedRows;
        public final Long generatedKey;
        public final boolean replayed;
        public final int attempts;
        Result(long rows, Long key, boolean replayed, int attempts) {
            this.affectedRows=rows; this.generatedKey=key; this.replayed=replayed; this.attempts=attempts;
        }
    }

    /** One prepared INSERT/UPDATE/DELETE. The same ID with different input is rejected. */
    public Result execute(UUID requestId, String sql, String generatedKeyColumn, Object... parameters) throws SQLException {
        Objects.requireNonNull(requestId, "requestId");
        if (sql == null || !sql.trim().matches("(?is)^(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+.+")
                || sql.indexOf(';') >= 0 || sql.indexOf('\'') >= 0 || sql.contains("--") || sql.contains("/*"))
            throw new SQLException("Recovery supports one parameterized write statement", "0A000");
        if (generatedKeyColumn != null && (!generatedKeyColumn.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")
                || !sql.trim().toUpperCase(Locale.ROOT).startsWith("INSERT ")))
            throw new SQLException("Invalid generated key configuration", "0A000");
        Object[] values=snapshot(parameters);
        byte[] fingerprint=fingerprint(requestId, sql, generatedKeyColumn, values);
        long started=System.nanoTime();
        int attempts=0;
        long delayMillis=100;
        while (true) {
            if (Thread.currentThread().isInterrupted())
                throw new SQLException("Recovery interrupted; retain request ID for reconciliation", "57014");
            attempts++;
            try {
                return once(requestId, fingerprint, sql, generatedKeyColumn, values, attempts);
            } catch (SQLException | RuntimeException failure) {
                if (!retryable(failure)) {
                    if (failure instanceof SQLException) throw (SQLException) failure;
                    throw new SQLException("Protected write rejected; no recovery replay", "38000", failure);
                }
                long remaining=waitMillis-TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
                if (remaining <= 0)
                    throw new SQLTransientException("Recovery deadline exceeded; outcome must be reconciled with the same request ID", "DPT02", failure);
                long jitter=java.util.concurrent.ThreadLocalRandom.current().nextLong(Math.max(1,delayMillis/4));
                try { Thread.sleep(Math.min(delayMillis+jitter, remaining)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Recovery interrupted; retain request ID for reconciliation", "57014", interrupted);
                }
                delayMillis=Math.min(2000,delayMillis*2);
            }
        }
    }

    private Result once(UUID id, byte[] hash, String sql, String key, Object[] values, int attempts) throws SQLException {
        try (Connection wrapped=DriverManager.getConnection(url, properties)) {
            Connection raw=wrapped instanceof DadpProxyConnection
                    ? ((DadpProxyConnection) wrapped).getActualConnection()
                    : V8PostgresqlConnection.recoveryDelegate(wrapped);
            if (raw.getMetaData().getDatabaseMajorVersion()!=17
                    || !raw.getMetaData().getURL().startsWith("jdbc:postgresql:"))
                throw new SQLException("Recovery currently supports PostgreSQL 17", "0A000");
            raw.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            raw.setReadOnly(false);
            raw.setAutoCommit(false);
            boolean committed=false;
            try {
                int claimed;
                try (PreparedStatement claim=raw.prepareStatement(
                        "INSERT INTO dadp_recovery.write_requests(principal,scope,request_id,request_hash) "
                        +"VALUES(current_user,?,?,?) ON CONFLICT(principal,scope,request_id) DO NOTHING")) {
                    claim.setString(1,scope); claim.setObject(2,id); claim.setBytes(3,hash);
                    claim.setQueryTimeout(querySeconds());
                    claimed=claim.executeUpdate();
                }
                if (claimed==0) {
                    try (PreparedStatement saved=raw.prepareStatement(
                            "SELECT request_hash,affected_rows,generated_key FROM dadp_recovery.write_requests "
                            +"WHERE principal=current_user AND scope=? AND request_id=?")) {
                        saved.setString(1,scope); saved.setObject(2,id); saved.setQueryTimeout(querySeconds());
                        try (ResultSet row=saved.executeQuery()) {
                            if (!row.next()) throw new SQLException("Recovery ledger invariant failed", "DPI02");
                            if (!MessageDigest.isEqual(hash,row.getBytes(1)))
                                throw new SQLException("Request ID already used for different input", "22023");
                            long count=row.getLong(2);
                            if (row.wasNull()) throw new SQLException("Incomplete committed recovery entry", "DPI02");
                            long generated=row.getLong(3); Long savedKey=row.wasNull()?null:generated;
                            raw.rollback();
                            return new Result(count,savedKey,true,attempts);
                        }
                    }
                }
                long affected;
                Long generated=null;
                try (PreparedStatement statement=key==null ? wrapped.prepareStatement(sql)
                        : wrapped.prepareStatement(sql,new String[]{key})) {
                    statement.setQueryTimeout(querySeconds());
                    for (int i=0;i<values.length;i++) bind(statement,i+1,values[i]);
                    affected=statement.executeUpdate();
                    if (key!=null && affected>0) {
                        try (ResultSet keys=statement.getGeneratedKeys()) {
                            if (!keys.next()) throw new SQLException("Missing generated key", "DPI02");
                            generated=keys.getLong(1);
                            if (keys.wasNull() || keys.next()) throw new SQLException("Expected one generated key", "0A000");
                        }
                    }
                }
                try (PreparedStatement complete=raw.prepareStatement(
                        "UPDATE dadp_recovery.write_requests SET affected_rows=?,generated_key=? "
                        +"WHERE principal=current_user AND scope=? AND request_id=?")) {
                    complete.setLong(1,affected);
                    if (generated==null) complete.setNull(2,Types.BIGINT); else complete.setLong(2,generated);
                    complete.setString(3,scope); complete.setObject(4,id); complete.setQueryTimeout(querySeconds());
                    if (complete.executeUpdate()!=1) throw new SQLException("Recovery ledger invariant failed","DPI02");
                }
                raw.commit();
                committed=true;
                return new Result(affected,generated,false,attempts);
            } finally {
                if (!committed) {
                    try { raw.rollback(); } catch (SQLException ignored) { /* next attempt reconciles on new connection */ }
                }
            }
        }
    }

    private int querySeconds() { return (int)Math.max(1,Math.min(20,(waitMillis+999)/1000)); }

    /** Security refusals, cancellations, ambiguous generic 38000 and TLS stay terminal. */
    static boolean retryable(Throwable failure) {
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        List<Throwable> chain=new ArrayList<>();
        for (Throwable f=failure; f!=null && seen.add(f); f=f.getCause()) chain.add(f);
        for (Throwable f:chain) {
            if (f instanceof javax.net.ssl.SSLException || f instanceof SQLNonTransientException) return false;
            if (f instanceof SQLException) {
                String s=((SQLException)f).getSQLState();
                if (s!=null && (s.startsWith("28") || s.startsWith("23") || s.startsWith("22")
                        || s.startsWith("42") || s.equals("57014") || s.equals("DPT02"))) return false;
            }
        }
        for (Throwable f:chain) {
            if (f instanceof TransientCryptoException) return true;
            if (f instanceof SQLException) {
                String s=((SQLException)f).getSQLState();
                if (Arrays.asList("08000","08001","08003","08006","08007","57P01","57P02","57P03",
                        "25006","40001","40P01","DPT01").contains(s)) return true;
            }
        }
        return false;
    }

    private static Object[] snapshot(Object[] parameters) throws SQLException {
        if (parameters==null) throw new SQLException("Parameters required", "22023");
        Object[] result=parameters.clone();
        for (int i=0;i<result.length;i++) {
            Object v=result[i];
            if (v instanceof byte[]) result[i]=((byte[])v).clone();
            else if (v instanceof Timestamp) {
                Timestamp t=new Timestamp(((Timestamp)v).getTime()); t.setNanos(((Timestamp)v).getNanos()); result[i]=t;
            } else if (v instanceof java.sql.Date) result[i]=new java.sql.Date(((java.sql.Date)v).getTime());
            else if (v instanceof Time) result[i]=new Time(((Time)v).getTime());
            else if (v!=null && !(v instanceof String || v instanceof Integer || v instanceof Long
                    || v instanceof Short || v instanceof Byte || v instanceof Boolean || v instanceof BigDecimal
                    || v instanceof UUID))
                throw new SQLException("Unsupported recovery parameter type", "0A000");
        }
        return result;
    }

    private static void bind(PreparedStatement s,int i,Object v) throws SQLException {
        if (v==null) s.setNull(i,Types.VARCHAR);
        else if (v instanceof String) s.setString(i,(String)v);
        else if (v instanceof byte[]) s.setBytes(i,(byte[])v);
        else s.setObject(i,v);
    }

    private byte[] fingerprint(UUID id,String sql,String key,Object[] values) throws SQLException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            DataOutputStream out=new DataOutputStream(bytes);
            text(out,"dadp-pg-write-v1"); text(out,scope); text(out,id.toString()); text(out,sql); text(out,key);
            for (Object v:values) {
                text(out,v==null ? "null" : v.getClass().getName());
                if (v instanceof byte[]) { out.writeInt(((byte[])v).length); out.write((byte[])v); }
                else text(out,v==null ? null : v.toString());
            }
            out.flush();
            return digest.digest(bytes.toByteArray());
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new SQLException("Cannot create request fingerprint", "DPI02", impossible);
        }
    }

    private static void text(DataOutputStream out,String value) throws IOException {
        if (value==null) { out.writeInt(-1); return; }
        byte[] data=value.getBytes(StandardCharsets.UTF_8);out.writeInt(data.length);out.write(data);
    }
}
