package com.dadp.wrapper.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Best-effort aggregated local crypto statistics sender for wrapper local mode.
 */
public class WrapperCryptoStatsSender implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(WrapperCryptoStatsSender.class.getName());
    private static final String ENDPOINT_PATH = "/api/v1/stats/wrapper/crypto";

    private final String hubBaseUrl;
    private final int timeoutMillis;
    private final HubInternalKeyClient.AuthHeaderProvider authHeaderProvider;
    private final String aggregationLevel;
    private final TimeProvider timeProvider;
    private final Transport transport;
    private final LinkedBlockingQueue<WindowSnapshot> completedWindows = new LinkedBlockingQueue<WindowSnapshot>();
    private final ScheduledExecutorService scheduler;
    private volatile WindowCounters currentWindow;

    public WrapperCryptoStatsSender(String hubBaseUrl, int timeoutMillis,
                                    String hubAuthId, String hubAuthSecret,
                                    String aggregationLevel) {
        this(hubBaseUrl, timeoutMillis,
                createAuthHeaderProvider(hubAuthId, hubAuthSecret),
                normalizeAggregationLevel(aggregationLevel),
                new SystemTimeProvider(),
                new HttpTransport(),
                true);
    }

    WrapperCryptoStatsSender(String hubBaseUrl, int timeoutMillis,
                             HubInternalKeyClient.AuthHeaderProvider authHeaderProvider,
                             String aggregationLevel,
                             TimeProvider timeProvider,
                             Transport transport,
                             boolean startScheduler) {
        this.hubBaseUrl = normalizeBaseUrl(hubBaseUrl);
        this.timeoutMillis = timeoutMillis > 0 ? timeoutMillis : 30000;
        this.authHeaderProvider = authHeaderProvider;
        this.aggregationLevel = normalizeAggregationLevel(aggregationLevel);
        this.timeProvider = timeProvider != null ? timeProvider : new SystemTimeProvider();
        this.transport = transport != null ? transport : new HttpTransport();
        this.currentWindow = createWindow(this.timeProvider.now(), this.aggregationLevel);
        if (startScheduler) {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "wrapper-crypto-stats");
                t.setDaemon(true);
                return t;
            });
            long intervalSeconds = "1day".equals(this.aggregationLevel) ? 300L : 60L;
            this.scheduler.scheduleAtFixedRate(this::tick, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        } else {
            this.scheduler = null;
        }
    }

    public void recordEncryptSuccess() {
        WindowCounters counters = currentCounters();
        counters.encryptCount.increment();
        counters.successCount.increment();
    }

    public void recordEncryptFailure() {
        WindowCounters counters = currentCounters();
        counters.encryptCount.increment();
        counters.failureCount.increment();
    }

    public void recordDecryptSuccess() {
        WindowCounters counters = currentCounters();
        counters.decryptCount.increment();
        counters.successCount.increment();
    }

    public void recordDecryptFailure() {
        WindowCounters counters = currentCounters();
        counters.decryptCount.increment();
        counters.failureCount.increment();
    }

    void flushForTests() {
        tick();
    }

    private WindowCounters currentCounters() {
        LocalDateTime now = timeProvider.now();
        WindowCounters snapshot = currentWindow;
        if (snapshot.isWithin(now)) {
            return snapshot;
        }
        return rotateWindow(now);
    }

    private synchronized WindowCounters rotateWindow(LocalDateTime now) {
        WindowCounters snapshot = currentWindow;
        if (snapshot.isWithin(now)) {
            return snapshot;
        }
        if (snapshot.hasTraffic()) {
            completedWindows.offer(snapshot.toSnapshot(timeProvider.now()));
        }
        currentWindow = createWindow(now, aggregationLevel);
        return currentWindow;
    }

    private void tick() {
        try {
            rotateWindow(timeProvider.now());
            drainCompletedWindows();
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Wrapper crypto stats tick failed", e);
        }
    }

    private void drainCompletedWindows() {
        WindowSnapshot snapshot;
        while ((snapshot = completedWindows.poll()) != null) {
            try {
                transport.send(hubBaseUrl, timeoutMillis, authHeaderProvider, snapshot);
            } catch (IOException e) {
                LOG.log(Level.FINE, "Wrapper crypto stats send failed", e);
            }
        }
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private static WindowCounters createWindow(LocalDateTime now, String aggregationLevel) {
        LocalDateTime start;
        LocalDateTime nextStart;
        if ("1day".equals(aggregationLevel)) {
            start = now.truncatedTo(ChronoUnit.DAYS);
            nextStart = start.plusDays(1);
        } else {
            start = now.truncatedTo(ChronoUnit.HOURS);
            nextStart = start.plusHours(1);
        }
        return new WindowCounters(aggregationLevel, start, nextStart.minusNanos(1L));
    }

    private static String normalizeAggregationLevel(String aggregationLevel) {
        String normalized = aggregationLevel != null ? aggregationLevel.trim().toLowerCase() : "1hour";
        if ("1day".equals(normalized)) {
            return "1day";
        }
        return "1hour";
    }

    private static String normalizeBaseUrl(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "http://localhost:9004";
        }
        String baseUrl = value.trim();
        try {
            URI uri = URI.create(baseUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (scheme != null && host != null) {
                return port >= 0 ? scheme + "://" + host + ":" + port : scheme + "://" + host;
            }
        } catch (Exception ignored) {
            // Fall back to trimmed string below.
        }
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private static HubInternalKeyClient.AuthHeaderProvider createAuthHeaderProvider(String hubAuthId, String hubAuthSecret) {
        if (hubAuthId == null || hubAuthId.trim().isEmpty()
                || hubAuthSecret == null || hubAuthSecret.trim().isEmpty()) {
            return null;
        }
        return new HubInternalAuthHeaderProvider(hubAuthId, hubAuthSecret);
    }

    interface TimeProvider {
        LocalDateTime now();
    }

    interface Transport {
        void send(String hubBaseUrl, int timeoutMillis,
                  HubInternalKeyClient.AuthHeaderProvider authHeaderProvider,
                  WindowSnapshot snapshot) throws IOException;
    }

    static final class WindowSnapshot {
        private final String aggregationLevel;
        private final LocalDateTime collectedAt;
        private final LocalDateTime windowStart;
        private final LocalDateTime windowEnd;
        private final long encryptCount;
        private final long decryptCount;
        private final long successCount;
        private final long failureCount;

        WindowSnapshot(String aggregationLevel, LocalDateTime collectedAt,
                       LocalDateTime windowStart, LocalDateTime windowEnd,
                       long encryptCount, long decryptCount,
                       long successCount, long failureCount) {
            this.aggregationLevel = aggregationLevel;
            this.collectedAt = collectedAt;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.encryptCount = encryptCount;
            this.decryptCount = decryptCount;
            this.successCount = successCount;
            this.failureCount = failureCount;
        }

        Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("aggregationLevel", aggregationLevel);
            payload.put("collectedAt", collectedAt.toString());
            payload.put("windowStart", windowStart.toString());
            payload.put("windowEnd", windowEnd.toString());
            payload.put("encryptCount", encryptCount);
            payload.put("decryptCount", decryptCount);
            payload.put("successCount", successCount);
            payload.put("failureCount", failureCount);
            return payload;
        }
    }

    private static final class WindowCounters {
        private final String aggregationLevel;
        private final LocalDateTime windowStart;
        private final LocalDateTime windowEnd;
        private final LongAdder encryptCount = new LongAdder();
        private final LongAdder decryptCount = new LongAdder();
        private final LongAdder successCount = new LongAdder();
        private final LongAdder failureCount = new LongAdder();

        private WindowCounters(String aggregationLevel, LocalDateTime windowStart, LocalDateTime windowEnd) {
            this.aggregationLevel = aggregationLevel;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
        }

        boolean isWithin(LocalDateTime timestamp) {
            return !timestamp.isBefore(windowStart) && !timestamp.isAfter(windowEnd);
        }

        boolean hasTraffic() {
            return encryptCount.sum() > 0L
                    || decryptCount.sum() > 0L
                    || successCount.sum() > 0L
                    || failureCount.sum() > 0L;
        }

        WindowSnapshot toSnapshot(LocalDateTime collectedAt) {
            return new WindowSnapshot(
                    aggregationLevel,
                    collectedAt,
                    windowStart,
                    windowEnd,
                    encryptCount.sum(),
                    decryptCount.sum(),
                    successCount.sum(),
                    failureCount.sum());
        }
    }

    private static final class SystemTimeProvider implements TimeProvider {
        @Override
        public LocalDateTime now() {
            return LocalDateTime.now();
        }
    }

    static final class HttpTransport implements Transport {
        private final ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public void send(String hubBaseUrl, int timeoutMillis,
                         HubInternalKeyClient.AuthHeaderProvider authHeaderProvider,
                         WindowSnapshot snapshot) throws IOException {
            HttpURLConnection connection = null;
            String engineStylePath = ENDPOINT_PATH;
            String url = buildHubApiUrl(hubBaseUrl, engineStylePath);
            byte[] body = objectMapper.writeValueAsBytes(snapshot.toPayload());
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setConnectTimeout(timeoutMillis);
                connection.setReadTimeout(timeoutMillis);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(body.length);
                if (authHeaderProvider != null) {
                    String authHeader = authHeaderProvider.createAuthHeader(engineStylePath);
                    if (authHeader != null && !authHeader.trim().isEmpty()) {
                        connection.setRequestProperty("X-Hub-Auth", authHeader);
                    }
                }
                connection.getOutputStream().write(body);
                connection.getOutputStream().flush();

                int status = connection.getResponseCode();
                byte[] responseBody = readAll(status >= 200 && status < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream());
                if (status < 200 || status >= 300) {
                    throw new IOException("Wrapper crypto stats API failed: status=" + status
                            + ", body=" + new String(responseBody, StandardCharsets.UTF_8));
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        private static String buildHubApiUrl(String hubBaseUrl, String engineStylePath) {
            if (engineStylePath.startsWith("/hub/api/v1")) {
                return hubBaseUrl + engineStylePath;
            }
            if (engineStylePath.startsWith("/api/v1")) {
                return hubBaseUrl + "/hub" + engineStylePath;
            }
            return hubBaseUrl + "/hub/api/v1" + (engineStylePath.startsWith("/") ? "" : "/") + engineStylePath;
        }

        private static byte[] readAll(InputStream input) throws IOException {
            if (input == null) {
                return new byte[0];
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }
}
