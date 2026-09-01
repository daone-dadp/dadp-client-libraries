package com.dadp.hub.crypto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HubCryptoServiceCiphertextDetectionTest {
    private final HubCryptoService service = new HubCryptoService();

    @Test
    void acceptsProviderTransparentHubV1Envelope() {
        String envelope = "hub:v1:NXSF2345:ZWRr:provider-specific-payload";
        assertTrue(service.isEncryptedData(envelope));
        assertTrue(service.isEncryptedData("kms:NXSF2345:ZWRr:opaque-provider-payload"));
        assertTrue(service.isEncryptedData("vlt:NXSF2345:opaque-provider-payload"));

        int byteLength = envelope.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(service.isEncryptedData("ABC::DADP_ENC:v3:" + byteLength + ":" + envelope));
    }

    @Test
    void rejectsLegacyAndMalformedCiphertexts() {
        assertFalse(service.isEncryptedData("hub:550e8400-e29b-41d4-a716-446655440000:cGF5bG9hZA=="));
        assertFalse(service.isEncryptedData("ABC::ENC::hub:v1:NXSF2345:ZWRr:payload"));
        assertFalse(service.isEncryptedData("hub:v1:INVALID1:ZWRr:payload"));
        assertFalse(service.isEncryptedData("hub:v1:NXSF2345:not-base64:payload"));
        assertFalse(service.isEncryptedData("ABC::DADP_ENC:v3:1:hub:v1:NXSF2345:ZWRr:payload"));
        assertFalse(service.isEncryptedData("ABC::DADP_ENC:v3:1:X::DADP_ENC:v3:1:Y"));
    }
}
