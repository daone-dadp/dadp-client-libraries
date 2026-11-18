import com.sqream.jdbc.connector.*;
import com.sqream.jdbc.connector.heartbeat.*;
import com.sqream.jdbc.connector.messenger.*;
import com.sqream.jdbc.connector.socket.*;
import java.nio.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public class FullDuplexLivenessTest {
 static int checks;
 static void ok(boolean b,String n){if(!b)throw new AssertionError(n);checks++;System.out.println("PASS "+n);}
 static class Socket extends SQSocketConnector {
  volatile boolean closed,blockPing;volatile int generation=1;
  volatile String response="";
  volatile CountDownLatch readEntered=new CountDownLatch(1),readRelease=new CountDownLatch(1);
  final CountDownLatch pingEntered=new CountDownLatch(1),pingRelease=new CountDownLatch(1);
  final AtomicInteger pings=new AtomicInteger();
  final List<String> events=Collections.synchronizedList(new ArrayList<>());
  Socket()throws Exception{super(null);}
  void releaseRead(){readRelease.countDown();}
  void resetRead(){readEntered=new CountDownLatch(1);readRelease=new CountDownLatch(1);}
  void waitGate(CountDownLatch gate)throws ConnException{try{if(!gate.await(3,TimeUnit.SECONDS))throw new ConnException("synthetic read gate timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ConnException(e);}}
  public boolean isOpen(){return !closed;}
  public void close(){closed=true;releaseRead();}
  public void reconnect(String ip,int port,boolean ssl){events.add("reconnect:"+generation);generation++;closed=false;}
  public String sendData(ByteBuffer b,boolean read)throws ConnException{
   if(b!=null){
    boolean ping=Thread.currentThread().getName().equals("sqream-compat-idle-heartbeat");
    int enteredGeneration=generation;
    events.add((ping?"ping-enter:":"write:")+enteredGeneration);
    if(ping){pingEntered.countDown();if(blockPing)waitGate(pingRelease);pings.incrementAndGet();events.add("ping-exit:"+generation+":"+Thread.currentThread().isInterrupted());}
   }
   if(read){readEntered.countDown();waitGate(readRelease);return response;}
   return null;
  }
  public int readData(ByteBuffer target,int n)throws ConnException{readEntered.countDown();waitGate(readRelease);for(int i=0;i<n;i++)target.put((byte)0);return n;}
 }
 static class Fixture {
  final Socket socket;final MessengerImpl messenger;final HeartBeatServiceImpl hb;
  final AtomicReference<Throwable> failed=new AtomicReference<>();
  Fixture()throws Exception{
   socket=new Socket();messenger=(MessengerImpl)MessengerImpl.getInstance(socket,new JsonParser(){public int toStatementId(String s){return 1;}});
   hb=new HeartBeatServiceImpl(messenger,20);
   Field f=messenger.getClass().getDeclaredField("pingService");f.setAccessible(true);f.set(messenger,hb);
  }
  Thread task(Checked action){Thread t=new Thread(()->{try{action.run();}catch(Throwable e){failed.compareAndSet(null,e);}},"synthetic-foreground");t.start();return t;}
  void finish(Thread t)throws Exception{t.join(3000);if(t.isAlive())throw new AssertionError("foreground stuck");if(failed.get()!=null)throw new AssertionError("foreground failed",failed.get());}
  void shutdown()throws Exception{hb.stop();Field f=hb.getClass().getDeclaredField("executorService");f.setAccessible(true);ExecutorService ex=(ExecutorService)f.get(hb);if(ex!=null&&!ex.awaitTermination(2,TimeUnit.SECONDS))throw new AssertionError("heartbeat thread leak");}
 }
 interface Checked{void run()throws Exception;}
 static void pings(Socket s,int target)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(s.pings.get()<target&&System.nanoTime()<end)Thread.sleep(5);if(s.pings.get()<target)throw new AssertionError("no heartbeat during blocking read");}
 public static void main(String[] args)throws Exception{
  Fixture a=new Fixture();Thread reader=a.task(()->a.messenger.openStatement());
  ok(a.socket.readEntered.await(1,TimeUnit.SECONDS),"foreground entered blocking response read");
  pings(a.socket,2);ok(reader.isAlive(),"JSON response read permits idle heartbeat writes");
  a.socket.releaseRead();a.finish(reader);a.shutdown();
  Fixture b=new Fixture();b.hb.start();pings(b.socket,1);b.messenger.put(1);
  int before=b.socket.pings.get();b.messenger.sendBinaryHeader(4);Thread.sleep(60);b.messenger.sendBinaryData(ByteBuffer.allocate(4).putInt(7));Thread.sleep(60);
  ok(b.socket.pings.get()==before,"heartbeat cannot interleave atomic binary header and body");
  b.socket.response="{\"putted\":\"putted\"}";Thread ack=b.task(()->b.messenger.isPutted());
  ok(b.socket.readEntered.await(1,TimeUnit.SECONDS),"binary frame completed before ACK read");
  pings(b.socket,before+2);ok(ack.isAlive(),"PUT ACK wait keeps heartbeat live");
  b.socket.releaseRead();b.finish(ack);b.shutdown();
  Fixture c=new Fixture();c.socket.response="{\"statementClosed\":\"statementClosed\"}";
  Thread close=c.task(()->c.messenger.closeStatement());
  ok(c.socket.readEntered.await(1,TimeUnit.SECONDS),"statement close entered response wait");
  pings(c.socket,2);ok(close.isAlive(),"statement close response wait keeps heartbeat live");
  c.socket.releaseRead();c.finish(close);c.shutdown();
  Fixture d=new Fixture();Thread binaryRead=d.task(()->d.messenger.fetchBinaryData(ByteBuffer.allocate(8),8));
  ok(d.socket.readEntered.await(1,TimeUnit.SECONDS),"incoming binary data read entered");
  pings(d.socket,2);ok(binaryRead.isAlive(),"incoming binary data read permits full-duplex heartbeat");
  d.socket.releaseRead();d.finish(binaryRead);d.shutdown();
  Fixture e=new Fixture();e.socket.blockPing=true;e.hb.start();
  ok(e.socket.pingEntered.await(1,TimeUnit.SECONDS),"old heartbeat acquired write generation");
  e.socket.releaseRead();Thread reconnect=e.task(()->e.messenger.reconnect("127.0.0.1",1234,false,"db","user","password","service",1,1));
  Thread.sleep(100);
  ok(e.socket.generation==1 && reconnect.isAlive(),"reconnect waits for old heartbeat write without replacing channel");
  e.socket.pingRelease.countDown();e.finish(reconnect);
  int oldExit=e.socket.events.indexOf("ping-exit:1:false"),newOpen=e.socket.events.indexOf("reconnect:1");
  ok(oldExit>=0 && newOpen>oldExit && e.socket.generation==2,"old heartbeat finishes un-interrupted before new generation");
  int count=e.socket.pings.get();Thread.sleep(80);
  ok(e.socket.pings.get()==count,"heartbeat stays paused until statement reconstruction succeeds");
  e.socket.response="{\"statementReconstructed\":\"statementReconstructed\"}";e.messenger.isStatementReconstructed(1);
  pings(e.socket,count+1);ok(e.socket.events.contains("ping-exit:2:false"),"idle heartbeat resumes only on reconstructed new channel");
  e.shutdown();
  System.out.println("RESULT "+checks+" PASS");
 }
}
