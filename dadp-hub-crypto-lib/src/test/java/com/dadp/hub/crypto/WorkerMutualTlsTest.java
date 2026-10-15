package com.dadp.hub.crypto;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class WorkerMutualTlsTest {
    @TempDir static Path dir;
    private HttpsServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private static final String FRAME = "kms:NXSF2345:ZWRr:cGF5bG9hZA==";
    private volatile int status = 200;
    private volatile String response;
    private volatile String request;

    @BeforeAll static void certificates() throws Exception {
        generate("server", "localhost", "SAN=dns:localhost");
        generate("client", "agent", "EKU=clientAuth");
        generate("stranger", "stranger", "EKU=clientAuth");
        trust("server", "server-trust");
        trust("client", "client-trust");
        trust("stranger", "wrong-trust");
        write("password", "changeit");
        config("valid", "client", "server-trust");
        config("wrong-client", "stranger", "server-trust");
        config("wrong-ca", "client", "wrong-trust");
        config("no-key", "server-trust", "server-trust");
    }

    @BeforeEach void start() throws Exception {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store("server"), "changeit".toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store("client-trust"));
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context) {
            @Override public void configure(HttpsParameters parameters) {
                SSLParameters ssl = context.getDefaultSSLParameters();
                ssl.setNeedClientAuth(true);
                parameters.setSSLParameters(ssl);
            }
        });
        server.createContext("/api", exchange -> {
            HttpsExchange https = (HttpsExchange) exchange;
            assertTrue(https.getSSLSession().getPeerPrincipal().getName().contains("agent"));
            calls.incrementAndGet();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int n;
            while ((n = exchange.getRequestBody().read(buffer)) != -1) body.write(buffer, 0, n);
            request = new String(body.toByteArray(), StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/leak");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        response = "{\"code\":\"SUCCESS\",\"data\":\"" + FRAME + "\"}";
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private HubCryptoService client(String config, String host) {
        return HubCryptoService.createInstance("https://" + host + ":" + server.getAddress().getPort(),
                "/api", 2000, false, dir.resolve(config).toString());
    }

    @Test void actualClientUsesMutualTlsForSingleAndBatch() {
        HubCryptoService service = client("valid", "localhost");
        assertEquals(FRAME, service.encrypt("secret", "NXSF2345"));
        assertTrue(request.contains("secret"));
        assertTrue(service.isEncryptedData(FRAME));
        response = "{\"code\":\"SUCCESS\",\"data\":\"secret\"}";
        assertEquals("secret", service.decrypt(FRAME));
        assertTrue(request.contains(FRAME));
        response = "{\"code\":\"SUCCESS\",\"data\":{\"results\":[{\"success\":true,\"encryptedData\":\"" + FRAME + "\"}]}}";
        assertEquals(Collections.singletonList(FRAME), service.batchEncrypt(Collections.singletonList("secret"), Collections.singletonList("NXSF2345")));
        response = "{\"code\":\"SUCCESS\",\"data\":{\"results\":[{\"success\":true,\"decryptedData\":\"secret\"}]}}";
        assertEquals(Collections.singletonList("secret"), service.batchDecrypt(Collections.singletonList(FRAME), null, null, false));
        assertEquals(4, calls.get());
    }

    @Test void rejectsPlaintextAndInvalidEndpointsBeforeSending() {
        assertThrows(IllegalArgumentException.class, () -> HubCryptoService.createInstance("http://localhost:1", 500, false));
        assertThrows(IllegalArgumentException.class, () -> HubCryptoService.createInstance("https://user@localhost", 500, false));
        assertThrows(IllegalArgumentException.class, () -> HubCryptoService.createInstance("https://localhost?target=other", 500, false));
        assertEquals(0, calls.get());
    }

    @Test void rejectsWrongHostnameChainAndClientIdentity() {
        assertThrows(RuntimeException.class, () -> client("valid", "127.0.0.1").encrypt("secret", "NXSF2345"));
        assertThrows(RuntimeException.class, () -> client("wrong-ca", "localhost").encrypt("secret", "NXSF2345"));
        assertThrows(RuntimeException.class, () -> client("wrong-client", "localhost").encrypt("secret", "NXSF2345"));
        assertThrows(RuntimeException.class, () -> client("no-key", "localhost").encrypt("secret", "NXSF2345"));
        assertEquals(0, calls.get());
    }

    @Test void rejectsMissingOrInsecurePrivateConfiguration() throws Exception {
        assertThrows(RuntimeException.class, () -> client("missing", "localhost").encrypt("secret", "NXSF2345"));
        Path insecure = dir.resolve("insecure");
        Files.copy(dir.resolve("valid"), insecure);
        Files.setPosixFilePermissions(insecure, PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(RuntimeException.class, () -> client("insecure", "localhost").encrypt("secret", "NXSF2345"));
        Files.createSymbolicLink(dir.resolve("linked"), dir.resolve("valid"));
        assertThrows(RuntimeException.class, () -> client("linked", "localhost").encrypt("secret", "NXSF2345"));
        assertEquals(0, calls.get());
    }

    @Test void neverFollowsRedirectOrReturnsPlaintextOnFailure() {
        HubCryptoService service = client("valid", "localhost");
        for (int code : new int[]{301, 302, 307, 308, 401, 403, 500}) {
            status = code;
            assertThrows(RuntimeException.class, () -> service.encrypt("secret", "NXSF2345"));
        }
        assertEquals(7, calls.get());
        status = 503;
        assertThrows(RuntimeException.class, () -> service.encryptForSearch("secret", "NXSF2345"));
        status = 200;
        response = "{\"code\":\"SUCCESS\",\"data\":{\"results\":[{\"success\":false,\"originalData\":\"secret\"}]}}";
        assertThrows(RuntimeException.class, () -> service.batchEncrypt(Collections.singletonList("secret"), Collections.singletonList("NXSF2345")));
        assertThrows(RuntimeException.class, () -> service.batchDecrypt(Collections.singletonList(FRAME), null, null, false));
    }

    private static KeyStore store(String name) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(dir.resolve(name + ".p12"))) { store.load(in, "changeit".toCharArray()); }
        return store;
    }
    private static void config(String name, String identity, String trust) throws Exception {
        write(name, "keyStore=" + dir.resolve(identity + ".p12") + "\nkeyStorePasswordFile=" + dir.resolve("password")
                + "\ntrustStore=" + dir.resolve(trust + ".p12") + "\ntrustStorePasswordFile=" + dir.resolve("password") + "\n");
    }
    private static void write(String name, String value) throws Exception {
        Files.write(dir.resolve(name), value.getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(dir.resolve(name), PosixFilePermissions.fromString("rw-------"));
    }
    private static void generate(String name, String cn, String extension) throws Exception {
        keytool("-genkeypair", "-alias", name, "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=" + cn,
                "-validity", "2", "-ext", extension, "-keystore", dir.resolve(name + ".p12").toString(), "-storetype", "PKCS12",
                "-storepass", "changeit", "-keypass", "changeit");
        Files.setPosixFilePermissions(dir.resolve(name + ".p12"), PosixFilePermissions.fromString("rw-------"));
        keytool("-exportcert", "-alias", name, "-keystore", dir.resolve(name + ".p12").toString(), "-storepass", "changeit",
                "-file", dir.resolve(name + ".cer").toString());
    }
    private static void trust(String cert, String name) throws Exception {
        keytool("-importcert", "-noprompt", "-alias", cert, "-file", dir.resolve(cert + ".cer").toString(),
                "-keystore", dir.resolve(name + ".p12").toString(), "-storetype", "PKCS12", "-storepass", "changeit");
        Files.setPosixFilePermissions(dir.resolve(name + ".p12"), PosixFilePermissions.fromString("rw-------"));
    }
    private static void keytool(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
    }
}
