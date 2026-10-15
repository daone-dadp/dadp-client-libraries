package com.dadp.hub.crypto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HubCryptoServiceCiphertextDetectionTest {
    private final HubCryptoService service = new HubCryptoService();

    @Test
    void recognizesOnlyCurrentProviderTransparentEnvelope() {
        String envelope = "hub:v1:NXSF2345:ZWRr:opaque-provider-payload";
        assertTrue(service.isEncryptedData(envelope));
        assertTrue(service.isEncryptedData("kms:v1:NXSF2345:ZWRr:cGF5bG9hZA=="));
        String kms = "kms:v1:NXSF2345:ZWRr:cGF5bG9hZA==";
        assertTrue(service.isEncryptedData("ABC::DADP_ENC:v3:" + kms.length() + ":" + kms));
        assertFalse(service.isEncryptedData("kms:NXSF2345:ZWRr:opaque-provider-payload"));
        assertFalse(service.isEncryptedData("kms:v2:NXSF2345:ZWRr:cGF5bG9hZA=="));
        assertFalse(service.isEncryptedData("kms:v1:NXSF2345:ZWRr:not-base64"));
        assertTrue(service.isEncryptedData("vlt:NXSF2345:opaque-provider-payload"));
        int length = envelope.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(service.isEncryptedData("ABC::DADP_ENC:v3:" + length + ":" + envelope));
        assertFalse(service.isEncryptedData("ABC::ENC::" + envelope));
        assertFalse(service.isEncryptedData("hub:550e8400-e29b-41d4-a716-446655440000:cGF5bG9hZA=="));
    }
}
