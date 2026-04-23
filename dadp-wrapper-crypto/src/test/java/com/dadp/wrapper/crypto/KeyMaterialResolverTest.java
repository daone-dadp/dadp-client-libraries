package com.dadp.wrapper.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KeyMaterialResolverTest {

    @Test
    void rejectsProvidersThatNeedRemoteEngineFallback() {
        KeyMaterialResolver resolver = new KeyMaterialResolver(new StubClient("DADP_VAULT"));

        UnsupportedCryptoMaterialException error = assertThrows(
                UnsupportedCryptoMaterialException.class,
                () -> resolver.resolve("key", 1));

        assertEquals("Provider requires remote fallback: DADP_VAULT", error.getMessage());
    }

    private static final class StubClient extends HubInternalKeyClient {
        private final String provider;

        private StubClient(String provider) {
            super("http://localhost:9004", 1000, null);
            this.provider = provider;
        }

        @Override
        public KeyMetadata fetchKeyMetadata(String keyAlias, int keyVersion) {
            return new KeyMetadata(keyAlias, keyVersion, provider, null, null, "A256GCM");
        }

        @Override
        public String fetchKeyData(String keyAlias, int keyVersion) {
            return "unused";
        }
    }
}
