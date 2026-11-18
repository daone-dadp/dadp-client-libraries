import com.sqream.jdbc.connector.*;
import com.sqream.jdbc.connector.messenger.*;
import com.sqream.jdbc.connector.socket.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public class TerminalCloseTest {
 static int checks;
 static void ok(boolean b,String n){if(!b)throw new AssertionError(n);checks++;System.out.println("PASS "+n);}
 static String frame(InputStream in)throws Exception{
  byte[] header=in.readNBytes(10);if(header.length==0)return null;if(header.length!=10)throw new AssertionError("partial synthetic header");
  ByteBuffer b=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);if(b.get()!=8||b.get()!=1)throw new AssertionError("bad control header");long n=b.getLong();if(n<0||n>1024)throw new AssertionError("unsafe synthetic frame length");
  byte[] body=in.readNBytes((int)n);if(body.length!=n)throw new AssertionError("partial synthetic frame");return new String(body,StandardCharsets.UTF_8);
 }
 static void response(OutputStream out,String s)throws Exception{byte[] b=s.getBytes(StandardCharsets.UTF_8);out.write(ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).put((byte)8).put((byte)1).putLong(b.length).array());out.write(b);out.flush();}
 static class Peer implements AutoCloseable{
  final ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
  final List<String> commands=Collections.synchronizedList(new ArrayList<>());final CountDownLatch eof=new CountDownLatch(1);final AtomicReference<Throwable> error=new AtomicReference<>();
  final Thread thread;
  Peer()throws Exception{thread=new Thread(()->{try(java.net.Socket socket=server.accept()){socket.setSoTimeout(3000);String s;while((s=frame(socket.getInputStream()))!=null){commands.add(s);if(s.equals("{\"getStatementId\":\"getStatementId\"}"))response(socket.getOutputStream(),"{\"statementId\":7}");else if(s.equals("{\"closeStatement\":\"closeStatement\"}"))response(socket.getOutputStream(),"{\"statementClosed\":\"statementClosed\"}");else if(!s.equals("{\"closeConnection\":\"closeConnection\"}"))throw new AssertionError("unexpected synthetic control");}}catch(Throwable e){error.set(e);}finally{eof.countDown();}},"synthetic-peer");thread.start();}
  public void close()throws Exception{server.close();thread.join(4000);if(thread.isAlive())throw new AssertionError("peer thread leaked");if(error.get()!=null)throw new AssertionError("peer error",error.get());}
 }
 static class MockConnector extends SQSocketConnector{
  boolean detached;int hintResult=47,terminations,readCalls;String response="{\"statementClosed\":\"statementClosed\"}";ConnException detachFailure;
  MockConnector()throws Exception{super(null);}
  public boolean isOpen(){return !detached;}
  public String sendData(ByteBuffer b,boolean read){if(read)readCalls++;return response;}
  public int compatTerminateLocal(ByteBuffer hint,boolean quiesced)throws ConnException{terminations++;detached=true;if(detachFailure!=null)throw detachFailure;return hint==null?-3:hintResult;}
 }
 static MessengerImpl mock(MockConnector socket){return (MessengerImpl)MessengerImpl.getInstance(socket,new JsonParser(){public int toStatementId(String s){return 7;}});}
 public static void main(String[] args)throws Exception{
  try(Peer peer=new Peer()){
   SQSocket raw=new SQSocket();raw.open("127.0.0.1",peer.server.getLocalPort(),false);MessengerImpl m=(MessengerImpl)MessengerImpl.getInstance(new SQSocketConnector(raw),new JsonParser());
   m.openStatement();m.closeStatement();long start=System.nanoTime();m.closeConnection();
   ok((System.nanoTime()-start)<TimeUnit.SECONDS.toNanos(1),"confirmed statement terminal close does not wait for remote ACK");
   ok(peer.eof.await(1,TimeUnit.SECONDS),"terminal peer observes local EOF without sending close ACK");
   ok(peer.commands.equals(Arrays.asList("{\"getStatementId\":\"getStatementId\"}","{\"closeStatement\":\"closeStatement\"}","{\"closeConnection\":\"closeConnection\"}")),"one complete terminal control hint and no duplicate request");
   ok(m.compatTerminalCloseStatus()==1 && m.compatTerminalHintBytes()==47,"complete optional hint byte count is observable");
  }
  try(Peer peer=new Peer()){
   SQSocket raw=new SQSocket();raw.open("127.0.0.1",peer.server.getLocalPort(),false);MessengerImpl m=(MessengerImpl)MessengerImpl.getInstance(new SQSocketConnector(raw),new JsonParser());
   m.openStatement();m.closeConnection();
   ok(peer.eof.await(1,TimeUnit.SECONDS) && peer.commands.size()==1,"active statement cancellation sends no pending SQL or PUT");
   ok(m.compatTerminalCloseStatus()==5 && m.compatTerminalHintBytes()==-3,"active cancellation is distinct from confirmed completion");
  }
  MockConnector s=new MockConnector();s.hintResult=0;MessengerImpl m=mock(s);m.closeConnection();
  ok(s.detached && m.compatTerminalCloseStatus()==2 && m.compatTerminalHintBytes()==0,"partial hint remains observable while local release completes");
  s=new MockConnector();s.hintResult=-1;m=mock(s);m.closeConnection();
  ok(s.detached && m.compatTerminalCloseStatus()==3,"TLS hint omission is explicit");
  s=new MockConnector();s.hintResult=-2;m=mock(s);m.closeConnection();
  ok(s.detached && m.compatTerminalCloseStatus()==4,"hint failure is recorded rather than reported as complete");
  s=new MockConnector();m=mock(s);m.put(1);int reads=s.readCalls;m.closeConnection();
  ok(s.readCalls==reads && m.compatTerminalCloseStatus()==5,"unknown write cancellation does not read or retry a write ACK");
  s=new MockConnector();s.response="{\"putted\":\"putted\"}";m=mock(s);m.openStatement();boolean unconfirmed=false;
  try{m.closeStatement();}catch(ConnException expected){unconfirmed=true;}m.closeConnection();
  ok(unconfirmed && m.compatTerminalCloseStatus()==5,"unrelated JSON is not accepted as a statement-close ACK");
  s=new MockConnector();m=mock(s);s.detachFailure=new ConnException("synthetic-local-release");ConnException first=null;
  try{m.closeConnection();}catch(ConnException expected){first=expected;}
  ok(first==s.detachFailure && s.detached && m.compatTerminalCloseStatus()==8,"local release failure remains an explicit primary error");
  m.closeConnection();ok(s.terminations==2,"repeated close does not resend terminal hint after failed local cleanup");
  try(Peer peer=new Peer()){
   SQSocket raw=new SQSocket();raw.open("127.0.0.1",peer.server.getLocalPort(),false);
   Field tls=SQSocket.class.getDeclaredField("useSsl");tls.setAccessible(true);tls.setBoolean(raw,true);
   MessengerImpl tlsClose=(MessengerImpl)MessengerImpl.getInstance(new SQSocketConnector(raw),new JsonParser());tlsClose.closeConnection();
   ok(peer.eof.await(1,TimeUnit.SECONDS) && peer.commands.isEmpty(),"TLS-marked transport never receives a plaintext close hint");
   ok(tlsClose.compatTerminalCloseStatus()==3,"TLS-marked local release records hint omission");
  }
  System.out.println("RESULT "+checks+" PASS");
 }
}
