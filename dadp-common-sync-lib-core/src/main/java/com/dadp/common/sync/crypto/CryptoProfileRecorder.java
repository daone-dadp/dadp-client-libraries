package com.dadp.common.sync.crypto;

import java.util.Map;

/**
 * Optional recorder for wrapper-side crypto profiling.
 *
 * <p>Implementations are expected to be activated only when profiling is
 * explicitly enabled so the default wrapper path remains unaffected.</p>
 */
public interface CryptoProfileRecorder {

    void record(Map<String, Object> event);
}
