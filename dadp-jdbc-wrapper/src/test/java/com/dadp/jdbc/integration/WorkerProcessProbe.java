package com.dadp.jdbc.integration;

import com.dadp.hub.crypto.HubCryptoService;
import com.dadp.hub.crypto.exception.HubConnectionException;
import com.dadp.hub.crypto.exception.HubCryptoException;
import java.io.IOException;
import java.util.*;

/** External real-Worker probe; no server fixture or local business crypto. */
public final class WorkerProcessProbe {
    private WorkerProcessProbe() {}

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception | AssertionError e) {
            System.err.println("WORKER_PROBE_FAIL: " + e.getClass().getSimpleName());
            if (e instanceof AssertionError) System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    static void run(String[] args) {
        if (args.length != 3) throw new IllegalArgumentException("Expected WORKER_URL, allowed policy code, denied policy code");
        String policy = policyCode(args[1]);
        String denied = policyCode(args[2]);
        if (policy.equals(denied)) throw new IllegalArgumentException("Allowed and denied policies must differ");
        String tls = System.getProperty("dadp.worker.tls.config");
        if (tls == null || tls.isEmpty()) throw new IllegalArgumentException("dadp.worker.tls.config is required");
        HubCryptoService client = HubCryptoService.createInstance(args[0], "/api", 10000, false, tls);

        List<String> values = Arrays.asList("", "ASCII-123",
                "\uD55C\uAE00 \uD83D\uDE80 caf\u00E9",
                "quote \" slash \\ newline\n tab\t nul\u0000");
        for (int i = 0; i < values.size(); i++) {
            String encrypted = client.encrypt(values.get(i), policy);
            require(client.isEncryptedData(encrypted), "single ciphertext not recognized");
            require(values.get(i).equals(client.decrypt(encrypted)), "single round-trip mismatch");
            System.out.println("WORKER_PROBE_SINGLE_PASS index=" + i);
        }
        List<String> encrypted = client.batchEncrypt(values, Collections.nCopies(values.size(), policy));
        require(encrypted.size() == values.size(), "batch encryption count mismatch");
        for (String value : encrypted) require(client.isEncryptedData(value), "batch ciphertext not recognized");
        require(values.equals(client.batchDecrypt(encrypted, null, null, false)), "batch round-trip mismatch");
        System.out.println("WORKER_PROBE_BATCH_PASS count=" + values.size());

        boolean singleDenied = false;
        try { client.encrypt("denied-probe", denied); }
        catch (HubCryptoException e) { singleDenied = isPolicyRejection(e, false); }
        require(singleDenied, "denied policy single encryption was not rejected");

        boolean batchDenied = false;
        try { client.batchEncrypt(Arrays.asList("allowed", "denied"), Arrays.asList(policy, denied)); }
        catch (HubCryptoException e) { batchDenied = isPolicyRejection(e, true); }
        require(batchDenied, "denied policy batch encryption was not rejected");

        // A service outage must not masquerade as a successful authorization test.
        require("after-denial".equals(client.decrypt(client.encrypt("after-denial", policy))), "post-denial health round-trip mismatch");
        System.out.println("WORKER_PROBE_DENIED_PASS single=true batch=true");
        System.out.println("WORKER_PROBE_PASS singles=4 batchItems=4 deniedChecks=2 postDenial=1");
    }

    static String policyCode(String value) {
        if (value == null || !value.matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}"))
            throw new IllegalArgumentException("Expected an eight-character policy code");
        return value;
    }

    static boolean isPolicyRejection(HubCryptoException failure, boolean batch) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HubConnectionException || cause instanceof IOException) return false;
        }
        String message = failure.getMessage();
        if (message == null) return false;
        if (!batch) return message.startsWith("Engine API call failed: 400 ")
                || message.startsWith("Engine API call failed: 403 ");
        return message.equals("Batch encryption failed: Batch encryption failed")
                || message.equals("Batch encryption failed: 400")
                || message.equals("Batch encryption failed: 403")
                || message.equals("Worker batch item failed or omitted result");
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
