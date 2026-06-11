package com.dadp.common.sync.crypto;

/**
 * Receives wrapper local crypto failures without coupling common sync code to JDBC wrapper classes.
 */
public interface LocalCryptoFailureListener {

    void onLocalCryptoFailure(String operation,
                              String policyIdentifier,
                              String failureType,
                              String errorMessage,
                              boolean fallbackRemote,
                              boolean failOpen);
}
