import com.sqream.jdbc.connector.heartbeat.*;
import com.sqream.jdbc.connector.messenger.*;
import java.lang.reflect.*;
import java.security.Permission;
import java.util.concurrent.*;
public class CompatStartupFailureTest {
 public static void main(String[] a)throws Exception{
  java.util.concurrent.atomic.AtomicInteger pings=new java.util.concurrent.atomic.AtomicInteger();
  Messenger m=(Messenger)Proxy.newProxyInstance(CompatStartupFailureTest.class.getClassLoader(),new Class[]{Messenger.class},(p,method,args)->{if(method.getName().equals("ping"))pings.incrementAndGet();return null;});
  HeartBeatServiceImpl hb=new HeartBeatServiceImpl(m,5);
  SecurityManager original=System.getSecurityManager();
  System.setSecurityManager(new SecurityManager(){
   public void checkPermission(Permission p){}
   public void checkAccess(Thread t){if(t.getName().equals("sqream-compat-heartbeat-count"))throw new SecurityException("injected-second-executor-start");}
  });
  boolean threw=false;try{hb.start();}catch(SecurityException expected){threw=true;}finally{System.setSecurityManager(original);}
  Field running=hb.getClass().getDeclaredField("running");running.setAccessible(true);
  Field ex=hb.getClass().getDeclaredField("executorService");ex.setAccessible(true);
  ExecutorService first=(ExecutorService)ex.get(hb);
  if(!threw||running.getBoolean(hb)||!first.isShutdown()||!first.awaitTermination(2,TimeUnit.SECONDS))throw new AssertionError("startup rollback");
  System.out.println("PASS partial startup failure shuts down first executor and resets running");
  int prior=pings.get();hb.start();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
  while(pings.get()==prior && System.nanoTime()<deadline)Thread.sleep(5);
  if(pings.get()<=prior)throw new AssertionError("restart after rollback");
  hb.stop();if(!((ExecutorService)ex.get(hb)).awaitTermination(2,TimeUnit.SECONDS))throw new AssertionError("cleanup");
  System.out.println("PASS heartbeat recovers after failed startup and terminates");
  System.out.println("RESULT 2 PASS");
 }
}
