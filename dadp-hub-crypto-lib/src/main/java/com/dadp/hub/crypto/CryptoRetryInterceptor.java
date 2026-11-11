package com.dadp.hub.crypto;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** Retries only side-effect-free crypto HTTP calls, never JDBC SQL or writes. */
final class CryptoRetryInterceptor implements ClientHttpRequestInterceptor {
    private final long budgetNanos;
    private final int maxAttempts;
    private final long firstDelayMillis;

    CryptoRetryInterceptor() { this(15000, 16, 100); }
    CryptoRetryInterceptor(long budgetMillis, int maxAttempts, long firstDelayMillis) {
        this.budgetNanos = TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        this.maxAttempts = maxAttempts;
        this.firstDelayMillis = firstDelayMillis;
    }
    private static boolean transportFailure(IOException error) {
        for (Throwable t=error; t!=null; t=t.getCause()) {
            if (t instanceof SSLException) return false;
        }
        for (Throwable t=error; t!=null; t=t.getCause()) {
            if (t instanceof SocketException || t instanceof SocketTimeoutException || t instanceof EOFException) return true;
        }
        return false;
    }
    @Override public ClientHttpResponse intercept(HttpRequest request, byte[] body,
            ClientHttpRequestExecution execution) throws IOException {
        String path=request.getURI().getPath();
        boolean crypto=path.matches(".*/api/(encrypt|decrypt)(/batch|/stream)?");
        if (!crypto) return execution.execute(request,body);
        long start=System.nanoTime();
        long delay=firstDelayMillis;
        for (int attempt=1;;attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Crypto request interrupted");
            ClientHttpResponse response=null;
            try {
                response=execution.execute(request,body);
                int status=response.getRawStatusCode();
                boolean retryable=status==502 || status==503 || status==504;
                if (!retryable || attempt>=maxAttempts || System.nanoTime()-start>=budgetNanos) return response;
            } catch (IOException error) {
                if (!transportFailure(error) || attempt>=maxAttempts || System.nanoTime()-start>=budgetNanos) throw error;
            }
            if (response!=null) response.close();
            long remaining=budgetNanos-(System.nanoTime()-start);
            if (remaining<=0) throw new SocketTimeoutException("Crypto recovery budget exhausted");
            long pause=Math.min(delay, Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining)));
            try { Thread.sleep(pause); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Crypto recovery interrupted");
            }
            delay=Math.min(1000,delay*2);
        }
    }
}
