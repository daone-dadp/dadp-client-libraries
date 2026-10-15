package com.dadp.jdbc;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs after packaging, inspecting shipped bytes rather than just the source tree. */
class AgentArtifactBoundaryIT {
    private static final List<String> MODULES = Arrays.asList("dadp-common-logging-lib",
            "dadp-common-sync-lib-core", "dadp-common-sync-lib-j8", "dadp-hub-crypto-lib", "dadp-jdbc-wrapper");
    private static final String[] FORBIDDEN = {
            "com/dadp/crypto/core/", "com/dadp/wrapper/crypto/",
            "dadp-crypto-core", "dadp-wrapper-crypto", "LocalAesGcmCrypto",
            "RuntimeExecutionKeyClient", "RuntimeExecutionKeyMaterial", "KeyMaterial.class",
            "WrapperLocalCryptoService", "LocalMetadataDraft"
    };

    @Test void shippedArtifactsHaveNoBusinessCrypto() throws Exception {
        Path root = Paths.get("..").toRealPath();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document pom = factory.newDocumentBuilder().parse(root.resolve("pom.xml").toFile());
        NodeList moduleNodes = pom.getElementsByTagName("module");
        Set<String> actual = new HashSet<>();
        for (int i = 0; i < moduleNodes.getLength(); i++) actual.add(moduleNodes.item(i).getTextContent().trim());
        assertEquals(new HashSet<>(MODULES), actual, "GS Agent reactor must contain only approved modules");
        int scanned = 0;
        for (String module : MODULES) {
            int moduleJars = 0;
            try (DirectoryStream<Path> jars = Files.newDirectoryStream(root.resolve(module).resolve("target"), "*.jar")) {
                for (Path jar : jars) {
                    assertClean(jar);
                    System.out.println("GS Agent artifact verified: " + jar.getFileName());
                    scanned++;
                    moduleJars++;
                }
            }
            assertTrue(moduleJars > 0, "Missing built artifact: " + module);
        }
        assertTrue(scanned >= MODULES.size());
        Path fatJar = root.resolve("dadp-jdbc-wrapper/target/dadp-jdbc-wrapper-8.0.0-SNAPSHOT-all.jar");
        try (JarFile jar = new JarFile(fatJar.toFile())) {
            assertNotNull(jar.getEntry("com/dadp/common/sync/http/WorkerTls.class"), "TLS must remain packaged");
            assertNotNull(jar.getEntry("com/dadp/jdbc/V8PostgresqlConnection.class"), "Plugin agent must remain packaged");
            assertNotNull(jar.getEntry("com/dadp/hub/crypto/HubCryptoService.class"), "Remote Worker client must remain packaged");
        }
    }

    private static void assertClean(Path path) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                check(entry.getName(), path.toString());
                assertFalse(entry.getName().endsWith(".jar"), "Unexpected nested dependency: " + entry.getName());
                if (!entry.isDirectory()) {
                    try (InputStream in = jar.getInputStream(entry); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[8192];
                        int n;
                        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                        String contents = new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
                        check(contents, path + "!" + entry.getName());
                        // Inspect DADP bytecode only: transport TLS/hash dependencies are not banned.
                        if (entry.getName().startsWith("com/dadp/") && entry.getName().endsWith(".class")
                                && !entry.getName().contains("/shaded/")) {
                            assertFalse(contents.contains("javax/crypto/Cipher"), "Business cipher reference: " + entry.getName());
                            assertFalse(contents.contains("/runtime/execution-key"), "Business key fetch: " + entry.getName());
                        }
                    }
                }
            }
        }
    }

    private static void check(String contents, String location) {
        for (String forbidden : FORBIDDEN) assertFalse(contents.contains(forbidden), location + " contains " + forbidden);
    }

    @Test void scannerRejectsForbiddenBusinessCrypto() {
        for (String forbidden : FORBIDDEN) {
            assertThrows(AssertionError.class, () -> check(forbidden, "injected artifact"));
        }
    }
}
