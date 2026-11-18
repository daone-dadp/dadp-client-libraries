/*
 * SQream JDBC 6.6 ABI-compatible heartbeat implementation.
 * DADP compatibility patch, based on the MIT-licensed 6.6 release bytecode.
 * The original upstream 6.6 sources artifact contains no Java source.
 */
package com.sqream.jdbc.connector.heartbeat;
import com.sqream.jdbc.connector.messenger.Messenger;
import com.sqream.jdbc.connector.messenger.MessengerImpl;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HeartBeatServiceImpl implements HeartBeatService {
    private static final Logger LOGGER=Logger.getLogger(HeartBeatServiceImpl.class.getName());
    private final Messenger messenger;
    private final long interval;
    // Retain the 6.6 field names for diagnostics; neither executor interrupts IO.
    private ScheduledExecutorService executorService;
    private ScheduledExecutorService executorServicePingCount;
    private static final int PING_TIMER=60;
    private ScheduledFuture<?> pingFuture;
    private long generation;
    private boolean running;
    private final ReentrantLock fallbackOwner=new ReentrantLock(true);

    public HeartBeatServiceImpl(Messenger messenger,long interval){this.messenger=messenger;this.interval=interval;}
    public synchronized void start(){
        if(isRunning())return;
        running=true;final long token=++generation;final AtomicInteger count=new AtomicInteger();
        try {
        executorService=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"sqream-compat-idle-heartbeat");t.setDaemon(true);return t;});
        executorServicePingCount=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"sqream-compat-heartbeat-count");t.setDaemon(true);return t;});
        pingFuture=executorService.scheduleWithFixedDelay(()->{
            ReentrantLock owner=messenger instanceof MessengerImpl?((MessengerImpl)messenger).compatTransportOwner():fallbackOwner;
            // Only writes/atomic binary frames exclude PING; response reads stay live.
            if(!owner.tryLock())return;
            try{
                synchronized(this){if(!running||generation!=token)return;}
                if(messenger instanceof MessengerImpl && !((MessengerImpl)messenger).compatHeartbeatPermitted())return;
                messenger.ping();count.incrementAndGet();
            }catch(Throwable failure){
                stop();LOGGER.log(Level.WARNING,"Failed to ping server");
                if(failure instanceof Error)throw (Error)failure;
            }finally{owner.unlock();}
        },interval,interval,TimeUnit.MILLISECONDS);
        executorServicePingCount.scheduleWithFixedDelay(()->LOGGER.log(Level.FINE,"Ping count on 60 seconds is {0}",count.get()),PING_TIMER,PING_TIMER,TimeUnit.SECONDS);
        } catch(Throwable failure) {
            running=false;++generation;
            if(pingFuture!=null)pingFuture.cancel(false);
            if(executorService!=null)executorService.shutdown();
            if(executorServicePingCount!=null)executorServicePingCount.shutdown();
            throw failure;
        }
    }
    public synchronized void stop(){
        // Invalidate before cancellation; a task must recheck after obtaining IO ownership.
        running=false;++generation;
        if(pingFuture!=null)pingFuture.cancel(false);
        if(executorService!=null)executorService.shutdown();
        if(executorServicePingCount!=null)executorServicePingCount.shutdown();
    }
    private boolean isRunning(){return running&&executorService!=null&&!executorService.isShutdown();}
}
