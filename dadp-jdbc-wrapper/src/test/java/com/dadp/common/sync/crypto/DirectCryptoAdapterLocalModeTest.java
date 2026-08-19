package com.dadp.common.sync.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class DirectCryptoAdapterLocalModeTest {

    @Test
    void localModeRequestIsForcedToRemote() {
        DirectCryptoAdapter adapter = new DirectCryptoAdapter(false);

        adapter.setCryptoMode(
                "local",
                "http://dadp-hub:9004",
                false,
                1000,
                "wtenant_local",
                true,
                "1day");

        assertFalse(adapter.isLocalCryptoMode());
    }
}
