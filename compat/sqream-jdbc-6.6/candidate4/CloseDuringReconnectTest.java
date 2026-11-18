import com.sqream.jdbc.connector.*;
import com.sqream.jdbc.connector.socket.*;
import com.sqream.jdbc.connector.messenger.*;
import java.net.*;
import java.nio.channels.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public class CloseDuringReconnectTest {
 static int checks;
 static void ok(boolean b,String n){if(!b)throw new AssertionError(n);checks++;System.out.println("PASS "+n);}
 static class PausedOpenSocket extends SQSocket {
  final AtomicBoolean hold=new AtomicBoolean();final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  PausedOpenSocket()throws ConnException{super();}
  public void open(String ip,int port,boolean tls){
   if(hold.compareAndSet(true,false)){entered.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError("release timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}
   super.open(ip,port,tls);
  }
 }
 public static void main(String[] args)throws Exception{
  try(ServerSocket listener=new ServerSocket(0,2,InetAddress.getLoopbackAddress())){
   CountDownLatch accepted=new CountDownLatch(1),eof=new CountDownLatch(1);AtomicReference<Throwable> peerError=new AtomicReference<>();
   Thread peer=new Thread(()->{try(java.net.Socket socket=listener.accept()){accepted.countDown();while(socket.getInputStream().read()!=-1){};}catch(Throwable e){peerError.set(e);}finally{eof.countDown();}},"synthetic-reconnect-peer");peer.start();
   PausedOpenSocket raw=new PausedOpenSocket();raw.open("127.0.0.1",listener.getLocalPort(),false);accepted.await(1,TimeUnit.SECONDS);
   MessengerImpl messenger=(MessengerImpl)MessengerImpl.getInstance(new SQSocketConnector(raw),new JsonParser());
   raw.hold.set(true);AtomicReference<Throwable> reconnectFailure=new AtomicReference<>();
   Thread reconnect=new Thread(()->{try{messenger.reconnect("127.0.0.1",listener.getLocalPort(),false,"db","user","synthetic","service",1,1);}catch(Throwable e){reconnectFailure.set(e);}},"synthetic-reconnect");reconnect.start();
   ok(raw.entered.await(1,TimeUnit.SECONDS),"reconnect paused after old close and before new open");
   long start=System.nanoTime();messenger.closeConnection();
   ok(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(1),"terminal abort never waits for blocked reconnect");
   raw.release.countDown();reconnect.join(1500);
   ok(!reconnect.isAlive() && reconnectFailure.get()!=null,"cancelled reconnect cannot complete or publish a new channel");
   Field channel=SQSocket.class.getDeclaredField("socketChannel");channel.setAccessible(true);
   ok(!((SocketChannel)channel.get(raw)).isOpen() && !messenger.compatHeartbeatPermitted(),"old transport is closed and heartbeat cannot restart");
   boolean reopenRejected=false;try{raw.open("127.0.0.1",listener.getLocalPort(),false);}catch(RuntimeException expected){reopenRejected=true;}
   ok(reopenRejected,"terminally cancelled socket cannot be reopened");
   listener.setSoTimeout(150);boolean newConnection=false;
   try(java.net.Socket unexpected=listener.accept()){newConnection=true;}catch(SocketTimeoutException expected){}
   ok(!newConnection,"no new server TCP connection is created after terminal cancellation");
   ok(eof.await(1,TimeUnit.SECONDS),"original peer observes EOF");
   peer.join(1000);if(peerError.get()!=null)throw new AssertionError(peerError.get());
   boolean sqlRejected=false;try{messenger.openStatement();}catch(ConnException expected){sqlRejected=true;}
   ok(sqlRejected,"closed Messenger rejects new SQL without transmission");
  }
  System.out.println("RESULT "+checks+" PASS");
 }
}
