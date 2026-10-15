package com.dadp.jdbc.integration;

import com.dadp.hub.crypto.exception.HubConnectionException;
import com.dadp.hub.crypto.exception.HubCryptoException;
import org.junit.jupiter.api.Test;
import javax.net.ssl.SSLHandshakeException;
import static org.junit.jupiter.api.Assertions.*;

class WorkerProcessProbeTest {
    @Test void validatesPolicyCodesAndArgumentsBeforeNetworkUse() {
        assertEquals("ABCD2345", WorkerProcessProbe.policyCode("ABCD2345"));
        assertThrows(IllegalArgumentException.class, () -> WorkerProcessProbe.policyCode("policy-name"));
        assertThrows(IllegalArgumentException.class, () -> WorkerProcessProbe.policyCode("ABCD23I0"));
        assertThrows(IllegalArgumentException.class, () -> WorkerProcessProbe.run(new String[0]));
        assertThrows(IllegalArgumentException.class, () -> WorkerProcessProbe.run(
                new String[]{"https://localhost", "ABCD2345", "ABCD2345"}));
    }

    @Test void doesNotMistakeTransportOrServerErrorsForDeniedPolicy() {
        assertFalse(WorkerProcessProbe.isPolicyRejection(
                new HubConnectionException("Engine API call failed: 403 denied"), false));
        assertFalse(WorkerProcessProbe.isPolicyRejection(
                new HubCryptoException("Engine API call failed: 403 denied", new SSLHandshakeException("TLS")), false));
        assertFalse(WorkerProcessProbe.isPolicyRejection(new HubCryptoException("Engine API call failed: 500 error"), false));
        assertFalse(WorkerProcessProbe.isPolicyRejection(new HubCryptoException("Batch encryption failed: 503"), true));
        assertFalse(WorkerProcessProbe.isPolicyRejection(new HubCryptoException("Batch encryption failed: invalid results array"), true));
    }

    @Test void acceptsWorkerPolicyRejectionContracts() {
        assertTrue(WorkerProcessProbe.isPolicyRejection(new HubCryptoException("Engine API call failed: 400 denied"), false));
        assertTrue(WorkerProcessProbe.isPolicyRejection(new HubCryptoException("Engine API call failed: 403 denied"), false));
        assertTrue(WorkerProcessProbe.isPolicyRejection(new HubCryptoException("Batch encryption failed: Batch encryption failed"), true));
        assertTrue(WorkerProcessProbe.isPolicyRejection(new HubCryptoException("Batch encryption failed: 403"), true));
    }
}
