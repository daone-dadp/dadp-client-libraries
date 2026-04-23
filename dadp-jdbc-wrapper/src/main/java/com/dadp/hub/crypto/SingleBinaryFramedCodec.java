package com.dadp.hub.crypto;

import com.dadp.hub.crypto.dto.DecryptRequest;
import com.dadp.hub.crypto.dto.EncryptRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Wrapper-side single-item binary frame codec aligned to the Engine contract.
 */
final class SingleBinaryFramedCodec {

    static final String CONTENT_TYPE = "application/x-dadp-binary-frame";

    private static final byte[] MAGIC = "DADPBF01".getBytes(StandardCharsets.US_ASCII);
    private static final byte OP_SINGLE_ENCRYPT_REQUEST = 5;
    private static final byte OP_SINGLE_DECRYPT_REQUEST = 6;
    private static final byte OP_SINGLE_ENCRYPT_RESPONSE = 7;
    private static final byte OP_SINGLE_DECRYPT_RESPONSE = 8;
    private static final int NULL_INT = -1;
    private static final byte NULL_BOOL = 0;
    private static final byte FALSE_BOOL = 1;
    private static final byte TRUE_BOOL = 2;

    private SingleBinaryFramedCodec() {
    }

    static byte[] writeEncryptRequest(EncryptRequest request) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            writeHeader(output, OP_SINGLE_ENCRYPT_REQUEST);
            writeString(output, request.getData());
            writeString(output, null);
            writeString(output, request.getPolicyName());
            writeNullableBoolean(output, null);
            writeNullableInt(output, null);
            writeNullableInt(output, null);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode single binary framed encrypt request", e);
        }
    }

    static byte[] writeDecryptRequest(DecryptRequest request) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            writeHeader(output, OP_SINGLE_DECRYPT_REQUEST);
            writeString(output, request.getEncryptedData());
            writeString(output, request.getPolicyName());
            writeString(output, null);
            writeString(output, request.getMaskPolicyName());
            writeString(output, request.getMaskPolicyUid());
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode single binary framed decrypt request", e);
        }
    }

    static JsonNode readEncryptResponse(byte[] payload, ObjectMapper objectMapper) {
        return readSingleResponse(payload, OP_SINGLE_ENCRYPT_RESPONSE, objectMapper);
    }

    static JsonNode readDecryptResponse(byte[] payload, ObjectMapper objectMapper) {
        return readSingleResponse(payload, OP_SINGLE_DECRYPT_RESPONSE, objectMapper);
    }

    private static JsonNode readSingleResponse(byte[] payload, byte expectedOpCode, ObjectMapper objectMapper) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            readHeader(input, expectedOpCode);

            boolean success = readRequiredBoolean(input);
            String message = readString(input);
            String data = readString(input);
            String timingsJson = readString(input);
            String observabilityJson = readString(input);
            String statsJson = readString(input);
            ensureFullyConsumed(input);

            com.fasterxml.jackson.databind.node.ObjectNode root = objectMapper.createObjectNode();
            root.put("success", success);
            if (message != null) {
                root.put("message", message);
            }
            if (data != null) {
                root.put("data", data);
            }
            if (timingsJson != null && !timingsJson.isEmpty()) {
                root.set("timings", objectMapper.readTree(timingsJson));
            }
            if (observabilityJson != null && !observabilityJson.isEmpty()) {
                root.set("observability", objectMapper.readTree(observabilityJson));
            }
            if (statsJson != null && !statsJson.isEmpty()) {
                root.set("stats", objectMapper.readTree(statsJson));
            }
            return root;
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid single binary framed response: " + e.getMessage(), e);
        }
    }

    private static void writeHeader(DataOutputStream output, byte opCode) throws IOException {
        output.write(MAGIC);
        output.writeByte(opCode);
        output.writeInt(1);
    }

    private static void readHeader(DataInputStream input, byte expectedOpCode) throws IOException {
        byte[] magic = new byte[MAGIC.length];
        input.readFully(magic);
        if (!java.util.Arrays.equals(magic, MAGIC)) {
            throw new IOException("Unexpected binary frame magic");
        }

        byte actualOpCode = input.readByte();
        if (actualOpCode != expectedOpCode) {
            throw new IOException("Unexpected binary frame opCode: " + actualOpCode);
        }

        int itemCount = input.readInt();
        if (itemCount != 1) {
            throw new IOException("Unexpected single binary frame item count: " + itemCount);
        }
    }

    private static void ensureFullyConsumed(DataInputStream input) throws IOException {
        if (input.available() > 0) {
            throw new IOException("Binary frame has trailing bytes");
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        if (value == null) {
            output.writeInt(NULL_INT);
            return;
        }

        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length == NULL_INT) {
            return null;
        }
        if (length < 0) {
            throw new IOException("Invalid string length: " + length);
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeNullableBoolean(DataOutputStream output, Boolean value) throws IOException {
        if (value == null) {
            output.writeByte(NULL_BOOL);
        } else {
            output.writeByte(value ? TRUE_BOOL : FALSE_BOOL);
        }
    }

    private static boolean readRequiredBoolean(DataInputStream input) throws IOException {
        byte encoded = input.readByte();
        if (encoded == TRUE_BOOL) {
            return true;
        }
        if (encoded == FALSE_BOOL) {
            return false;
        }
        throw new IOException("Invalid required boolean encoding: " + encoded);
    }

    private static void writeNullableInt(DataOutputStream output, Integer value) throws IOException {
        output.writeInt(value != null ? value : NULL_INT);
    }
}
