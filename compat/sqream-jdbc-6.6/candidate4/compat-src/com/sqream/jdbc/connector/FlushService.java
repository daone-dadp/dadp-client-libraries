/*
 * Decompiled with CFR 0.152.
 */
package com.sqream.jdbc.connector;

import com.sqream.jdbc.connector.BlockDto;
import com.sqream.jdbc.connector.ByteBufferPool;
import com.sqream.jdbc.connector.ConnException;
import com.sqream.jdbc.connector.serverAPI.Statement.SqreamExecutedStatement;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FlushService {
    private static final Logger LOGGER = Logger.getLogger(FlushService.class.getName());
    private SqreamExecutedStatement sqreamExecutedStatement;
    private long paramedQueryTimeout;
    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    private Future<?> future;
    private volatile boolean compatAborted;

    // Resource cancellation only. No wait, no new flush and no thread interrupt.
    public synchronized void compatAbortWithoutFlush() {
        this.compatAborted = true;
        if (this.future != null) this.future.cancel(false);
        this.executorService.shutdown();
    }


    private FlushService(SqreamExecutedStatement sqreamExecutedStatement, long paramedQueryTimeout) {
        this.sqreamExecutedStatement = sqreamExecutedStatement;
        this.paramedQueryTimeout = paramedQueryTimeout;
    }

    public static FlushService getInstance(SqreamExecutedStatement sqreamExecutedStatement, long paramedQueryTimeout) {
        return new FlushService(sqreamExecutedStatement, paramedQueryTimeout);
    }

    public synchronized void process(BlockDto block, ByteBufferPool byteBufferPool) {
        if (this.compatAborted) throw new IllegalStateException("SQream flush service is closed");
        LOGGER.log(Level.FINE, MessageFormat.format("Process block: block=[{0}]", block));
        if (this.executorService.isTerminated()) {
            this.executorService = Executors.newSingleThreadExecutor();
        }
        this.future = this.executorService.submit(() -> {
            try {
                if (this.compatAborted) return;
                Thread.currentThread().setName("flush-service");
                this.flush(this.sqreamExecutedStatement, block);
                this.resetBlock(block);
                byteBufferPool.releaseBlock(block);
            }
            catch (Exception e) {
                LOGGER.warning(e.getMessage());
                throw new RuntimeException("Exception when flush data", e);
            }
        });
    }

    private void resetBlock(BlockDto block) {
        this.clearBuffers(block);
    }

    private void clearBuffers(BlockDto block) {
        for (ByteBuffer dataBuffer : block.getDataBuffers()) {
            dataBuffer.clear();
        }
        for (ByteBuffer nullBuffer : block.getNullBuffers()) {
            if (nullBuffer == null) continue;
            nullBuffer.clear();
            nullBuffer.put(ByteBuffer.allocate(block.getCapacity()));
            nullBuffer.clear();
        }
        for (ByteBuffer nvarcLenBuffer : block.getNvarcLenBuffers()) {
            if (nvarcLenBuffer == null) continue;
            nvarcLenBuffer.clear();
        }
    }

    public void close() {
        try {
            if (this.future != null) {
                LOGGER.log(Level.FINE, "Attempting to get flush service future..");
                if (this.paramedQueryTimeout == 0L) {
                    this.future.get();
                } else {
                    this.future.get(this.paramedQueryTimeout, TimeUnit.MINUTES);
                }
                LOGGER.log(Level.FINE, "Successfully got flush service future");
            }
        }
        catch (ExecutionException e) {
            throw new RuntimeException("Error during task execution", e.getCause());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Task was interrupted", e);
        }
        catch (TimeoutException e) {
            throw new RuntimeException(e);
        }
        finally {
            LOGGER.log(Level.FINE, "Attempting to shut down flush service executor service..");
            this.executorService.shutdownNow();
            LOGGER.log(Level.FINE, "Successfully shut down executor service");
        }
    }

    private void flush(SqreamExecutedStatement sqreamExecutedStatement, BlockDto block) throws IOException, ConnException {
        LOGGER.log(Level.FINE, MessageFormat.format("Flush data: block=[{0}]", block));
        sqreamExecutedStatement.put(block);
    }
}

