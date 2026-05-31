package com.dadp.wrapper.crypto;

import java.net.HttpURLConnection;

final class HubTenantHeaderProvider implements HubAuthHeaderProvider {
    private final String tenantId;

    HubTenantHeaderProvider(String tenantId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        this.tenantId = tenantId.trim();
    }

    @Override
    public void applyAuthHeaders(HttpURLConnection connection, String method, String path, String query, byte[] body) {
        connection.setRequestProperty("X-DADP-Tenant-Id", tenantId);
    }
}
