package com.dadp.hub.crypto.experimental;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class LocalMetadataDraftTest {
    private static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    private static LocalMetadataDraft sample() {
        return new LocalMetadataDraft("scope-a", "ABCDEFGH", 1, "key-a", 1, 0, 0, 0, "");
    }

    @Test
    void sharedVectors() throws Exception {
        String path = System.getProperty("dadp.localMetadataVectors");
        Assumptions.assumeTrue(path != null, "Set dadp.localMetadataVectors for cross-language conformance");
        int vectors = 0;
        for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8)) {
            if (line.startsWith("#") || line.isEmpty()) { continue; }
            String[] f = line.split("\t", -1);
            assertEquals(13, f.length);
            LocalMetadataDraft m = new LocalMetadataDraft(f[1], f[2], Integer.parseInt(f[3]),
                    f[4], Integer.parseInt(f[5]), Integer.parseInt(f[6]), Integer.parseInt(f[7]),
                    Integer.parseInt(f[8]), new String(unhex(f[9]), StandardCharsets.UTF_8));
            byte[] bytes = m.encode();
            assertArrayEquals(unhex(f[10]), bytes, f[0]);
            assertArrayEquals(bytes, LocalMetadataDraft.decode(bytes).encode());
            assertArrayEquals(unhex(f[11]), m.aad("DEK"));
            assertArrayEquals(unhex(f[12]), m.aad("DATA"));
            for (int i = 0; i < bytes.length; i++) {
                byte[] truncated = Arrays.copyOf(bytes, i);
                assertThrows(IllegalArgumentException.class, () -> LocalMetadataDraft.decode(truncated));
            }
            vectors++;
        }
        assertEquals(6, vectors);
    }

    @Test
    void rejectsInvalidMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new LocalMetadataDraft("", "ABCDEFGH", 1, "key", 1, 0, 0, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new LocalMetadataDraft("scope", "ABCDEFGH", 0, "key", 1, 0, 0, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new LocalMetadataDraft("scope", "ABCDEFGH", 1, "key", -1, 0, 0, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new LocalMetadataDraft("scope", "ABCDEFGH", 1, "key", 1, 6, 0, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new LocalMetadataDraft("scope", "ABCDEFGH", 1, "key", 1, 1, 1, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new LocalMetadataDraft("scope", "ABCDEFGH", 1, "key", 1, 0, 0, 1, ""));
        assertThrows(IllegalArgumentException.class, () -> new LocalMetadataDraft("scope", "ABCDEFGH", 1, "key", 1, 1, 0, 1, "\uD800"));
        char[] tooLarge = new char[LocalMetadataDraft.MAX_PLAIN + 1];
        Arrays.fill(tooLarge, 'x');
        assertThrows(IllegalArgumentException.class, () -> new LocalMetadataDraft("scope", "ABCDEFGH", 1, "key", 1, 1, 0, 1, new String(tooLarge)));
        assertThrows(IllegalArgumentException.class, () -> sample().aad("OTHER"));
    }

    @Test
    void rejectsMalformedBytes() {
        byte[] good = sample().encode();
        assertThrows(IllegalArgumentException.class, () -> LocalMetadataDraft.decode(Arrays.copyOf(good, good.length + 1)));
        assertThrows(IllegalArgumentException.class, () -> LocalMetadataDraft.decode(new byte[LocalMetadataDraft.MAX_METADATA + 1]));
        assertThrows(IllegalArgumentException.class, () -> LocalMetadataDraft.decode("hub:v1:test".getBytes(StandardCharsets.UTF_8)));
        byte[] invalidUtf8 = new LocalMetadataDraft("scope", "ABCDEFGH", 1, "key", 1, 2, 0, 1, "x").encode();
        invalidUtf8[invalidUtf8.length - 1] = (byte) 0xff;
        assertThrows(IllegalArgumentException.class, () -> LocalMetadataDraft.decode(invalidUtf8));
        // Declared length cannot allocate beyond the bounded input buffer.
        byte[] badLength = good.clone();
        badLength[14] = 0x7f;
        assertThrows(IllegalArgumentException.class, () -> LocalMetadataDraft.decode(badLength));
    }
}
