package com.dadp.wrapper.crypto;

import java.net.HttpURLConnection;

interface HubAuthHeaderProvider {
    void applyAuthHeaders(HttpURLConnection connection, String method, String path, String query, byte[] body);
}
