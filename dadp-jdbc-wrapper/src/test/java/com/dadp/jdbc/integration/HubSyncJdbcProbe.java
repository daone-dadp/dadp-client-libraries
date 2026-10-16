package com.dadp.jdbc.integration;

import com.dadp.common.sync.config.InstanceConfigStorage;
import com.dadp.common.sync.config.StoragePathResolver;
import com.dadp.common.sync.mapping.MappingSyncService;
import com.dadp.common.sync.policy.PolicyResolver;
import com.dadp.jdbc.DadpJdbcDriver;
import com.dadp.jdbc.DadpProxyConnection;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.Objects;
import java.util.Properties;

/** Real Hub refresh -> Engine JDBC -> Worker mTLS -> PG17; no mapping injection. */
public final class HubSyncJdbcProbe {
    public static void main(String[] args) throws Exception {
        require(args.length == 1, "private configuration required");
        Properties p = new Properties();
        try (InputStream input = Files.newInputStream(Paths.get(args[0]))) { p.load(input); }
        String alias = p.getProperty("alias"), tenant = p.getProperty("tenantId");
        String hub = p.getProperty("hubUrl"), worker = p.getProperty("workerUrl");
        Path storage = Paths.get(StoragePathResolver.resolveStorageDir(alias));
        require(!Files.exists(storage), "fresh Agent storage required");
        Files.createDirectories(storage);
        PolicyResolver resolver = new PolicyResolver(storage.toString(), "policy-mappings.json");
        require(resolver.getAllMappings().isEmpty(), "no seeded mappings");
        MappingSyncService sync = new MappingSyncService(hub, tenant, alias, resolver);
        require(sync.syncPolicyMappingsAndUpdateVersion(null) == 1, "one actual Hub binding required");
        require(p.getProperty("policyCode").equals(resolver.getAllMappings().get("public.records.secret")), "actual binding");
        MappingSyncService.PolicySnapshot snapshot = sync.getLastSnapshot();
        require(snapshot != null && snapshot.getEndpoint() != null, "Hub endpoint required");
        require(worker.equals(snapshot.getEndpoint().getCryptoUrl()) && worker.startsWith("https://"), "Hub Worker endpoint");
        require(Boolean.TRUE.equals(snapshot.getWrapperConfig().getEnabled()), "Agent enabled");
        require(!Boolean.TRUE.equals(snapshot.getWrapperConfig().getFailOpen()), "fail closed");
        InstanceConfigStorage config = new InstanceConfigStorage(storage.toString(), "proxy-config.json");
        String version = resolver.getCurrentVersion().toString();
        require(config.saveEnrollment(tenant, alias, version, hub, null, null, null), "enrollment");
        require(config.saveRuntimeOptions("remote", false, false, version, snapshot.getEndpoint().getCryptoUrl(),
                tenant, hub, null, null, null, true), "runtime");
        System.out.println("REAL_HUB_AGENT_SYNC_PASS bindings=1 endpointFromHub=true");
        Properties db = new Properties();
        db.setProperty("user", p.getProperty("user")); db.setProperty("password", p.getProperty("password"));
        db.setProperty("sslmode", "disable"); // Isolated loopback PG fixture, not deployment guidance.
        Class.forName("org.postgresql.Driver");
        String url = p.getProperty("jdbcUrl");
        try (Connection raw = DriverManager.getConnection(url, db)) {
            require(raw.getMetaData().getDatabaseMajorVersion() == 17, "PG17 required");
            Properties mapped = new Properties(); mapped.putAll(db); mapped.setProperty("dadp.wrapper.alias", alias);
            try (Connection agent = new DadpJdbcDriver().connect("jdbc:dadp:" + url.substring(5), mapped)) {
                require(agent instanceof DadpProxyConnection, "actual Engine JDBC entry");
                String[] values = {"ASCII", "", "\uD55C\uAE00\uD83D\uDE00", null};
                for (int batch = 0; batch < 2; batch++) {
                    try (PreparedStatement insert = agent.prepareStatement("INSERT INTO public.records (id, secret) VALUES (?, ?)")) {
                        for (int i = 0; i < values.length; i++) {
                            insert.setInt(1, batch * 10 + i);
                            if (values[i] == null) insert.setNull(2, Types.VARCHAR); else insert.setString(2, values[i]);
                            if (batch == 0) require(insert.executeUpdate() == 1, "prepared insert"); else insert.addBatch();
                        }
                        if (batch == 1) require(insert.executeBatch().length == values.length, "batch insert");
                    }
                    for (int i = 0; i < values.length; i++) {
                        String stored = read(raw, batch * 10 + i);
                        require(values[i] == null ? stored == null : stored != null && stored.startsWith("kms:" + p.getProperty("policyCode") + ":"), "raw ciphertext");
                        require(Objects.equals(values[i], read(agent, batch * 10 + i)), "Agent roundtrip");
                    }
                }
            }
        }
        System.out.println("REAL_HUB_ENGINE_JDBC_PASS pg17=true prepared=true batch=true null=true empty=true unicode=true rawCiphertext=true repeatedGetters=true");
    }

    private static String read(Connection c, int id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT secret FROM public.records WHERE id = ?")) {
            s.setInt(1, id);
            try (ResultSet r = s.executeQuery()) {
                require(r.next(), "row exists"); String text = r.getString(1);
                require(r.wasNull() == (text == null), "NULL semantics");
                require(Objects.equals(text, r.getString("secret")), "repeated string getter");
                require(Objects.equals(text, r.getObject(1)), "repeated object getter");
                require(!r.next(), "one row"); return text;
            }
        }
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
