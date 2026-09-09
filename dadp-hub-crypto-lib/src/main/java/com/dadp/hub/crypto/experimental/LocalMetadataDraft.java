package com.dadp.hub.crypto.experimental;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 2026-09-09: canonical metadata experiment for native local interoperability.
 * Not an enabled ciphertext format, grant verifier, or legacy Java crypto path.
 * Fields remain untrusted until future native authentication succeeds.
 */
public final class LocalMetadataDraft {
    private static final byte[] MAGIC = "DADP-LM-DRAFT1\0".getBytes(StandardCharsets.US_ASCII);
    public static final int MAX_PLAIN = 1 << 20;
    public static final int MAX_METADATA = MAX_PLAIN + 1024;

    public final String keyScopeId;
    public final String policyCode;
    public final int policyRevision;
    public final String keyId;
    public final int keyVersion;
    public final int mode;
    public final int start;
    public final int count;
    public final String selectedPlaintext;

    public LocalMetadataDraft(String keyScopeId, String policyCode, int policyRevision,
                              String keyId, int keyVersion, int mode, int start,
                              int count, String selectedPlaintext) {
        this.keyScopeId = keyScopeId;
        this.policyCode = policyCode;
        this.policyRevision = policyRevision;
        this.keyId = keyId;
        this.keyVersion = keyVersion;
        this.mode = mode;
        this.start = start;
        this.count = count;
        this.selectedPlaintext = selectedPlaintext;
        validate();
    }

    private void validate() {
        if (keyScopeId == null || !keyScopeId.matches("[A-Za-z0-9_-]{1,128}")
                || keyId == null || !keyId.matches("[A-Za-z0-9_-]{1,128}")
                || policyCode == null || !policyCode.matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}")) {
            throw new IllegalArgumentException("Invalid metadata identity");
        }
        if (policyRevision <= 0 || keyVersion <= 0 || mode < 0 || mode > 5
                || start < 0 || count < 0 || (mode != 2 && start != 0)) {
            throw new IllegalArgumentException("Invalid revision, version or partial position");
        }
        utf8(selectedPlaintext, MAX_PLAIN);
        if (mode == 0 && (count != 0 || !selectedPlaintext.isEmpty())) {
            throw new IllegalArgumentException("FULL cannot contain a partial selection");
        }
    }

    private static byte[] utf8(String value, int limit) {
        if (value == null || value.length() > limit) {
            throw new IllegalArgumentException("Invalid string size");
        }
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
            if (encoded.remaining() > limit) {
                throw new IllegalArgumentException("UTF-8 string exceeds limit");
            }
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid Unicode", e);
        }
    }

    private static void number(ByteArrayOutputStream out, int n) {
        out.write((n >>> 24) & 255); out.write((n >>> 16) & 255);
        out.write((n >>> 8) & 255); out.write(n & 255);
    }

    private static void text(ByteArrayOutputStream out, String value, int limit) {
        byte[] bytes = utf8(value, limit);
        number(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    public byte[] encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MAGIC, 0, MAGIC.length);
        text(out, keyScopeId, 128); text(out, policyCode, 8); number(out, policyRevision);
        text(out, keyId, 128); number(out, keyVersion);
        text(out, "DADP_CRYPTO", 32); text(out, "ARIA256_GCM", 32); text(out, "HUB", 32);
        out.write(mode); number(out, start); number(out, count);
        text(out, selectedPlaintext, MAX_PLAIN);
        return out.toByteArray();
    }

    private static int number(ByteBuffer in) {
        if (in.remaining() < 4) {
            throw new IllegalArgumentException("Truncated number");
        }
        int n = in.getInt();
        if (n < 0) {
            throw new IllegalArgumentException("Integer exceeds draft limit");
        }
        return n;
    }

    private static String text(ByteBuffer in, int limit) {
        int n = number(in);
        if (n > limit || n > in.remaining()) {
            throw new IllegalArgumentException("Invalid string length");
        }
        ByteBuffer field = in.slice();
        field.limit(n);
        in.position(in.position() + n);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(field).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid UTF-8", e);
        }
    }

    public static LocalMetadataDraft decode(byte[] raw) {
        if (raw == null || raw.length < MAGIC.length || raw.length > MAX_METADATA
                || !Arrays.equals(Arrays.copyOf(raw, MAGIC.length), MAGIC)) {
            throw new IllegalArgumentException("Invalid metadata header/size");
        }
        ByteBuffer in = ByteBuffer.wrap(raw);
        in.position(MAGIC.length);
        String scope = text(in, 128);
        String policy = text(in, 8);
        int revision = number(in);
        String key = text(in, 128);
        int version = number(in);
        String provider = text(in, 32);
        String algorithm = text(in, 32);
        String keyProvider = text(in, 32);
        if (!"DADP_CRYPTO".equals(provider) || !"ARIA256_GCM".equals(algorithm)
                || !"HUB".equals(keyProvider)) {
            throw new IllegalArgumentException("Unsupported local combination");
        }
        if (!in.hasRemaining()) {
            throw new IllegalArgumentException("Missing mode");
        }
        int mode = in.get() & 255;
        int start = number(in);
        int count = number(in);
        String selected = text(in, MAX_PLAIN);
        if (in.hasRemaining()) {
            throw new IllegalArgumentException("Trailing metadata bytes");
        }
        return new LocalMetadataDraft(scope, policy, revision, key, version, mode, start, count, selected);
    }

    /** Returns authentication input only; it does not perform authentication. */
    public byte[] aad(String purpose) {
        if (!"DEK".equals(purpose) && !"DATA".equals(purpose)) {
            throw new IllegalArgumentException("Invalid AAD purpose");
        }
        byte[] domain = ("DADP-LM-DRAFT1:" + purpose + "\0").getBytes(StandardCharsets.US_ASCII);
        byte[] metadata = encode();
        byte[] result = Arrays.copyOf(domain, domain.length + metadata.length);
        System.arraycopy(metadata, 0, result, domain.length, metadata.length);
        return result;
    }
}
