package com.dadp.wrapper.crypto;

/**
 * Listener invoked when wrapper local crypto resolves and caches Hub policy material.
 */
public interface PolicyMaterialCacheListener {
    void onPolicyMaterialCached(PolicyMaterial policy);
}
