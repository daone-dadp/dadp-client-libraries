package com.dadp.common.sync.http;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyStore;
import java.util.*;

/**
 * Per-client transport credentials, never business encryption keys.
 * The private properties file contains keyStore, keyStorePasswordFile,
 * trustStore and trustStorePasswordFile (absolute paths, PKCS12 stores).
 * Instances capture their configuration path; no global JSSE settings are changed.
 */
public final class WorkerTls {
    public static final String CONFIG_PROPERTY = "dadp.worker.tls.config";
    private final Path config;
    private SSLSocketFactory sockets;

    public WorkerTls() {
        this(System.getProperty(CONFIG_PROPERTY));
    }

    public WorkerTls(String configPath) {
        config = configPath == null ? null : Paths.get(configPath);
    }

    public static void validateEndpoint(String endpoint) {
        URI uri = URI.create(endpoint);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Worker requires an HTTPS endpoint without userinfo, query or fragment");
        }
    }

    public HttpsURLConnection open(URL url) throws IOException {
        validateEndpoint(url.toString());
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection(Proxy.NO_PROXY);
        configure(connection);
        return connection;
    }

    public void configure(HttpURLConnection connection) throws IOException {
        validateEndpoint(connection.getURL().toString());
        if (!(connection instanceof HttpsURLConnection)) throw new IOException("Worker requires HTTPS");
        HttpsURLConnection https = (HttpsURLConnection) connection;
        https.setSSLSocketFactory(socketFactory());
        // Keep JSSE hostname verification; never accept a hostname mismatch.
        https.setHostnameVerifier((host, session) -> false);
        https.setInstanceFollowRedirects(false);
    }

    private synchronized SSLSocketFactory socketFactory() throws IOException {
        if (sockets != null) return sockets;
        char[] keyPassword = null;
        char[] trustPassword = null;
        try {
            Properties properties = new Properties();
            try (InputStream in = privateFile(config)) { properties.load(in); }
            Set<String> fields = new HashSet<>(Arrays.asList("keyStore", "keyStorePasswordFile", "trustStore", "trustStorePasswordFile"));
            if (!properties.stringPropertyNames().equals(fields)) throw new IOException("Invalid Worker TLS configuration fields");
            keyPassword = password(properties.getProperty("keyStorePasswordFile"));
            trustPassword = password(properties.getProperty("trustStorePasswordFile"));
            KeyStore keys = load(properties.getProperty("keyStore"), keyPassword);
            boolean hasKey = false;
            Enumeration<String> aliases = keys.aliases();
            while (aliases.hasMoreElements()) hasKey |= keys.isKeyEntry(aliases.nextElement());
            if (!hasKey) throw new IOException("Worker client identity is missing");
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keys, keyPassword);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(load(properties.getProperty("trustStore"), trustPassword));
            SSLContext context = SSLContext.getInstance("TLSv1.2");
            context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            sockets = context.getSocketFactory();
            return sockets;
        } catch (Exception e) {
            throw new IOException("Worker mTLS configuration unavailable", e);
        } finally {
            if (keyPassword != null) Arrays.fill(keyPassword, '\0');
            if (trustPassword != null) Arrays.fill(trustPassword, '\0');
        }
    }

    private static KeyStore load(String path, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = privateFile(Paths.get(path))) { store.load(in, password); }
        return store;
    }

    private static char[] password(String path) throws IOException {
        try (InputStream in = privateFile(Paths.get(path)); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            int value;
            while ((value = in.read()) != -1) {
                if (out.size() >= 4096) throw new IOException("Password file too large");
                out.write(value);
            }
            byte[] bytes = out.toByteArray();
            try { return new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim().toCharArray(); }
            finally { Arrays.fill(bytes, (byte) 0); }
        }
    }

    private static InputStream privateFile(Path path) throws IOException {
        if (path == null || !path.isAbsolute() || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Worker TLS requires absolute regular private files");
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            for (PosixFilePermission permission : permissions) {
                if (permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_"))
                    throw new IOException("Worker TLS file must be owner-only");
            }
        } catch (UnsupportedOperationException e) {
            throw new IOException("Worker TLS private file permission checks unavailable", e);
        }
        return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
    }
}
