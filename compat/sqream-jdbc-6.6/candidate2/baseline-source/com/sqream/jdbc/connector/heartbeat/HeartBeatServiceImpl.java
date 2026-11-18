/*
 * Decompiled with CFR 0.152.
 */
package com.sqream.jdbc.connector.heartbeat;

import com.sqream.jdbc.connector.heartbeat.HeartBeatService;
import com.sqream.jdbc.connector.messenger.Messenger;
import java.text.MessageFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HeartBeatServiceImpl
implements HeartBeatService {
    private static final Logger LOGGER = Logger.getLogger(HeartBeatServiceImpl.class.getName());
    private final Messenger messenger;
    private final long interval;
    private ScheduledExecutorService executorService;
    private ScheduledExecutorService executorServicePingCount;
    private static final int PING_TIMER = 60;

    public HeartBeatServiceImpl(Messenger messenger, long intervalMs) {
        this.messenger = messenger;
        this.interval = intervalMs;
    }

    @Override
    public void start() {
        if (this.isRunning()) {
            return;
        }
        AtomicInteger counterPing = new AtomicInteger();
        this.executorServicePingCount = Executors.newSingleThreadScheduledExecutor();
        this.executorService = Executors.newSingleThreadScheduledExecutor();
        this.executorService.scheduleWithFixedDelay(() -> {
            try {
                this.messenger.ping();
                counterPing.getAndIncrement();
            }
            catch (Exception e) {
                this.stop();
                LOGGER.log(Level.WARNING, "Failed to ping server");
            }
        }, this.interval, this.interval, TimeUnit.MILLISECONDS);
        this.executorServicePingCount.scheduleWithFixedDelay(() -> LOGGER.log(Level.FINE, MessageFormat.format("Ping count on {0} seconds is {1}", 60, counterPing.get())), 60L, 60L, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        if (this.isRunning()) {
            this.executorService.shutdownNow();
            this.executorServicePingCount.shutdownNow();
        }
    }

    private boolean isRunning() {
        return this.executorService != null && !this.executorService.isShutdown();
    }
}
