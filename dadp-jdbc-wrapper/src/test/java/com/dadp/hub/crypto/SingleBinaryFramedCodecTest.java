package com.dadp.hub.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dadp.hub.crypto.dto.DecryptRequest;
import com.dadp.hub.crypto.dto.EncryptRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SingleBinaryFramedCodecTest {

    private static final byte[] MAGIC = "DADPBF01".getBytes(StandardCharsets.US_ASCII);
    private static final int NULL_INT = -1;
    private static final byte FALSE_BOOL = 1;
    private static final byte OP_SINGLE_ENCRYPT_RESPONSE = 7;
    private static final byte OP_SINGLE_DECRYPT_RESPONSE = 8;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void encryptRequestUsesExpectedSingleBinaryFrameLayout() throws Exception {
        EncryptRequest request = new EncryptRequest();
        request.setData("alice");
        request.setPolicyName("policy-email");

        byte[] encoded = SingleBinaryFramedCodec.writeEncryptRequest(request);

        assertEquals('D', encoded[0]);
        assertEquals(OP_SINGLE_ENCRYPT_RESPONSE - 2, encoded[8]);

        DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
        byte[] magic = new byte[MAGIC.length];
        input.readFully(magic);
        input.readByte();
        input.readInt();
        assertEquals("alice", readString(input));
        assertEquals(null, readString(input));
        assertEquals("policy-email", readString(input));
    }

    @Test
    void encryptRequestCanCarryRuntimePolicyCodeWithoutPolicyName() throws Exception {
        EncryptRequest request = new EncryptRequest();
        request.setData("alice");
        request.setPolicyCode("WZD4KTMA");

        byte[] encoded = SingleBinaryFramedCodec.writeEncryptRequest(request);

        DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
        byte[] magic = new byte[MAGIC.length];
        input.readFully(magic);
        input.readByte();
        input.readInt();
        assertEquals("alice", readString(input));
        assertEquals("WZD4KTMA", readString(input));
        assertEquals(null, readString(input));
    }

    @Test
    void decryptRequestUsesExpectedSingleBinaryFrameLayout() throws Exception {
        DecryptRequest request = new DecryptRequest();
        request.setEncryptedData("cipher");
        request.setPolicyName("policy-email");
        request.setMaskPolicyName("mask-1");

        byte[] encoded = SingleBinaryFramedCodec.writeDecryptRequest(request);

        assertEquals('D', encoded[0]);
        assertEquals(OP_SINGLE_DECRYPT_RESPONSE - 2, encoded[8]);

        DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
        byte[] magic = new byte[MAGIC.length];
        input.readFully(magic);
        input.readByte();
        input.readInt();
        assertEquals("cipher", readString(input));
        assertEquals(null, readString(input));
        assertEquals(null, readString(input));
        assertEquals(null, readString(input));
        assertEquals(null, readString(input));
    }

    @Test
    void jsonEngineRequestsDoNotContainRemovedIncludeStatsField() throws Exception {
        EncryptRequest encryptRequest = new EncryptRequest();
        encryptRequest.setData("alice");
        encryptRequest.setPolicyName("policy-email");
        String encryptJson = objectMapper.writeValueAsString(encryptRequest);
        assertTrue(encryptJson.contains("\"policyName\""));
        assertFalse(encryptJson.contains("includeStats"));
        assertFalse(encryptJson.contains("forSearch"));
        assertFalse(encryptJson.contains("policyVersion"));

        DecryptRequest decryptRequest = new DecryptRequest();
        decryptRequest.setEncryptedData("hub:policy-email:cipher");
        decryptRequest.setPolicyName("policy-email");
        decryptRequest.setMaskPolicyName("mask-policy");
        decryptRequest.setMaskPolicyCode("mask-code");
        String decryptJson = objectMapper.writeValueAsString(decryptRequest);
        assertFalse(decryptJson.contains("includeStats"));
        assertFalse(decryptJson.contains("encryptedData"));
        assertFalse(decryptJson.contains("policyName"));
        assertFalse(decryptJson.contains("maskPolicyName"));
        assertFalse(decryptJson.contains("maskPolicyCode"));
        assertTrue(decryptJson.contains("\"data\""));
    }

    @Test
    void jsonEngineRequestBuildersEmitOnlyGoEngineFields() throws Exception {
        HubCryptoService service = new HubCryptoService();

        Method encryptBuilder = HubCryptoService.class.getDeclaredMethod("buildEncryptRequestBody", String.class, String.class);
        encryptBuilder.setAccessible(true);
        JsonNode encrypt = objectMapper.readTree((String) encryptBuilder.invoke(service, "alice", "policy-email"));
        assertEquals("alice", encrypt.get("data").asText());
        assertEquals("policy-email", encrypt.get("policyName").asText());
        assertFalse(encrypt.has("policyCode"));
        JsonNode encryptByCode = objectMapper.readTree((String) encryptBuilder.invoke(service, "alice", "WZD4KTMA"));
        assertEquals("alice", encryptByCode.get("data").asText());
        assertEquals("WZD4KTMA", encryptByCode.get("policyCode").asText());
        assertFalse(encryptByCode.has("policyName"));
        assertFalse(encrypt.has("includeStats"));
        assertFalse(encrypt.has("forSearch"));
        assertFalse(encrypt.has("policyVersion"));

        Method decryptBuilder = HubCryptoService.class.getDeclaredMethod("buildDecryptRequestBody", String.class);
        decryptBuilder.setAccessible(true);
        JsonNode decrypt = objectMapper.readTree((String) decryptBuilder.invoke(service, "hub:policy-email:cipher"));
        assertEquals("hub:policy-email:cipher", decrypt.get("data").asText());
        assertFalse(decrypt.has("policyName"));
        assertFalse(decrypt.has("encryptedData"));
        assertFalse(decrypt.has("maskPolicyName"));
        assertFalse(decrypt.has("maskPolicyCode"));
        assertFalse(decrypt.has("includeStats"));
    }

    @Test
    void readSingleEncryptResponseParsesTimingAndStats() throws Exception {
        byte[] payload = buildSingleResponse(
                OP_SINGLE_ENCRYPT_RESPONSE,
                true,
                "ok",
                "cipher",
                "{\"apiProcessingTimeMs\":12}",
                "{\"transportMode\":\"binary-framed\"}",
                "{\"cryptoOperationTime\":7}");

        JsonNode root = SingleBinaryFramedCodec.readEncryptResponse(payload, objectMapper);

        assertTrue(root.get("success").asBoolean());
        assertEquals("cipher", root.get("data").asText());
        assertEquals(12L, root.get("timings").get("apiProcessingTimeMs").asLong());
        assertEquals("binary-framed", root.get("observability").get("transportMode").asText());
    }

    @Test
    void readSingleDecryptResponseParsesPayload() throws Exception {
        byte[] payload = buildSingleResponse(
                OP_SINGLE_DECRYPT_RESPONSE,
                true,
                "ok",
                "plain",
                null,
                null,
                "{\"totalProcessingTime\":9}");

        JsonNode root = SingleBinaryFramedCodec.readDecryptResponse(payload, objectMapper);

        assertTrue(root.get("success").asBoolean());
        assertEquals("plain", root.get("data").asText());
        assertEquals(9L, root.get("stats").get("totalProcessingTime").asLong());
    }

    private static byte[] buildSingleResponse(byte opCode,
                                              boolean success,
                                              String message,
                                              String data,
                                              String timingsJson,
                                              String observabilityJson,
                                              String statsJson) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.write(MAGIC);
        output.writeByte(opCode);
        output.writeInt(1);
        output.writeByte(success ? 2 : FALSE_BOOL);
        writeString(output, message);
        writeString(output, data);
        writeString(output, timingsJson);
        writeString(output, observabilityJson);
        writeString(output, statsJson);
        output.flush();
        return bytes.toByteArray();
    }

    private static void writeString(DataOutputStream output, String value) throws Exception {
        if (value == null) {
            output.writeInt(NULL_INT);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws Exception {
        int length = input.readInt();
        if (length == NULL_INT) {
            return null;
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
