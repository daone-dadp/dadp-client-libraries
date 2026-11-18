import com.sqream.jdbc.connector.*;
import com.sqream.jdbc.connector.heartbeat.*;
import com.sqream.jdbc.connector.messenger.*;
import com.sqream.jdbc.connector.socket.*;
import java.nio.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
public class CompatOwnershipTest {
 static int checks;
 static void ok(boolean b,String n){if(!b)throw new AssertionError(n);checks++;System.out.println("PASS "+n);}
 static class Socket extends SQSocketConnector {
  boolean closed;int written,read;String response="";
  Socket()throws Exception{super(null);}
  public boolean isOpen(){return !closed;}
  public void close(){closed=true;}
  public String sendData(ByteBuffer b,boolean r){if(b!=null)written+=b.position();return response;}
  public int readData(ByteBuffer b,int n){for(int i=0;i<n;i++)b.put((byte)0);read+=n;return n;}
  public int parseHeader(){return 0;}
 }
 static class Parser extends JsonParser {
  public int toStatementId(String s){return 1;}
  public FetchMetadataDto toFetchMetadata(String s){return new FetchMetadataDto(2,new int[]{2,8,16});}
 }
 static class HB implements HeartBeatService {
  int starts,stops;boolean failStart,failStop;
  public void start(){starts++;if(failStart)throw new AssertionError("injected-start");}
  public void stop(){stops++;if(failStop)throw new AssertionError("injected-stop");}
 }
 static void set(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
 static boolean getBool(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.getBoolean(o);}
 static MessengerImpl create(Socket s,HB h)throws Exception{MessengerImpl m=(MessengerImpl)MessengerImpl.getInstance(s,new Parser());set(m,"pingService",h);return m;}
 public static void main(String[] a)throws Exception {
  Socket s=new Socket();HB h=new HB();MessengerImpl m=create(s,h);h.failStart=true;
  boolean startThrew=false;try{m.openStatement();}catch(AssertionError expected){startThrew=true;}
  ok(startThrew && !m.compatTransportOwner().isLocked(),"resume Error unlocks owner");
  h.failStart=false;s.response="{\"putted\":\"putted\"}";m.put(2);
  int before=h.starts;
  Thread producer=new Thread(()->{try{m.sendBinaryHeader(16);m.sendBinaryData(ByteBuffer.allocate(16));}catch(Exception e){throw new RuntimeException(e);}});producer.start();producer.join();
  ok(h.starts==before,"binary producer handoff holds heartbeat paused");
  m.isPutted();ok(h.starts==before+1,"binary ACK resumes heartbeat");
  s.response="fetch";m.fetch();before=h.starts;
  Thread reader=new Thread(()->{try{m.parseHeader();m.fetchBinaryData(ByteBuffer.allocate(2),2);m.fetchBinaryData(ByteBuffer.allocate(8),8);}catch(Exception e){throw new RuntimeException(e);}});reader.start();reader.join();
  ok(h.starts==before,"fetch intermediate physical columns pause heartbeat");
  m.fetchBinaryData(ByteBuffer.allocate(16),16);
  ok(h.starts==before+1 && s.read==26,"fetch complete physical block resumes heartbeat");
  m.fetch();m.parseHeader();m.fetchBinaryData(ByteBuffer.allocate(26),26);
  ok(s.read==52,"second fetch block remains readable");
  h.failStop=true;boolean stopThrew=false;try{m.closeConnection();}catch(AssertionError expected){stopThrew=true;}
  ok(stopThrew && s.closed && !m.compatTransportOwner().isLocked(),"stop Error still closes socket and unlocks");
  final CountDownLatch pinged=new CountDownLatch(1);
  Messenger broken=(Messenger)Proxy.newProxyInstance(CompatOwnershipTest.class.getClassLoader(),new Class[]{Messenger.class},(p,met,arg)->{if(met.getName().equals("ping")){pinged.countDown();throw new AssertionError("injected-ping");}return null;});
  HeartBeatServiceImpl real=new HeartBeatServiceImpl(broken,1);
  real.start();ok(pinged.await(2,TimeUnit.SECONDS),"heartbeat task exercised");
  Thread.sleep(100);
  Field running=real.getClass().getDeclaredField("running");running.setAccessible(true);
  ok(!running.getBoolean(real),"heartbeat Error invalidates running generation");
  Field ex=real.getClass().getDeclaredField("executorService");ex.setAccessible(true);
  ok(((ExecutorService)ex.get(real)).isShutdown(),"heartbeat Error shuts executor down");
  Socket timeoutSocket=new Socket();HB timeoutHB=new HB();timeoutHB.failStop=true;
  MessengerImpl timeoutMessenger=create(timeoutSocket,timeoutHB);
  CountDownLatch locked=new CountDownLatch(1), release=new CountDownLatch(1);
  Thread holder=new Thread(()->{timeoutMessenger.compatTransportOwner().lock();try{locked.countDown();release.await();}catch(Exception e){throw new RuntimeException(e);}finally{timeoutMessenger.compatTransportOwner().unlock();}});
  holder.start();locked.await();long started=System.nanoTime();boolean timeoutThrew=false;
  try{timeoutMessenger.openStatement();}catch(Throwable expected){timeoutThrew=true;}
  double seconds=(System.nanoTime()-started)/1e9;release.countDown();holder.join();
  ok(timeoutThrew && timeoutSocket.closed && seconds>=9.5 && seconds<13,"ownership timeout closes OLD transport even if stop fails");
  long cleanupStarted=System.nanoTime();boolean unusable=false;
  try{timeoutMessenger.closeConnection();}catch(ConnException expected){unusable=true;}
  ok(unusable && (System.nanoTime()-cleanupStarted)<1000000000L,"poisoned cleanup fails promptly without protocol IO");
  System.out.println("RESULT "+checks+" PASS");
 }
}
