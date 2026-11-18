import com.sqream.jdbc.*;
import com.sqream.jdbc.connector.*;
import com.sqream.jdbc.connector.enums.*;
import com.sqream.jdbc.connector.serverAPI.*;
import com.sqream.jdbc.connector.serverAPI.Statement.*;
import com.sqream.jdbc.connector.storage.fetchStorage.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import sun.misc.Unsafe;
public class AbortWithoutFlushTest {
 static int checks;
 static void ok(boolean b,String n){if(!b)throw new AssertionError(n);checks++;System.out.println("PASS "+n);}
 static void set(Object o,String n,Object value)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,value);}
 static void field(Class<?> c,Object o,String n,Object value)throws Exception{Field f=c.getDeclaredField(n);f.setAccessible(true);f.set(o,value);}
 static Object read(Class<?> c,Object o,String n)throws Exception{Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 static class Probe extends ConnectorImpl {
  int implicitClose,flushInitial,flushAndClose,terminal,bound,nextCalls;ConnException terminalFailure;RuntimeException dataFailure,statementCloseFailure;boolean statementOpen=true,abortDuringFlush,suppressAck,wrongAckId;long ackSequence;int ackStatementId;Runnable afterAck;
  Probe()throws Exception{super(ConnectionParams.builder(true).build());}
  public boolean isOpen(){return true;}
  public boolean isOpenStatement(){return statementOpen;}
  public void close(){implicitClose++;if(statementCloseFailure!=null)throw statementCloseFailure;if(statementOpen&&!suppressAck){statementOpen=false;ackSequence++;ackStatementId=wrongAckId?99:0;if(afterAck!=null)afterAck.run();}}
  public long compatStatementAckSequence(){return ackSequence;}
  public void compatRequireStatementCloseAck(int expected,long prior)throws ConnException{if(ackSequence<=prior||ackStatementId!=expected)throw new ConnException("synthetic ACK not confirmed for execution");}
  public boolean next(){nextCalls++;return true;}
  public String getQueryType(){return StatementType.PARAMED_INSERT.getValue();}
  public void flushInitial(){flushInitial++;}
  public void flushAndClose(){flushAndClose++;if(dataFailure!=null)throw dataFailure;if(abortDuringFlush)statementOpen=false;}
  public boolean closeConnection()throws ConnException{terminal++;if(terminalFailure!=null)throw terminalFailure;return true;}
  public boolean setNvarchar(int n,String value){bound++;return true;}
  public boolean setLong(int n,Long value){bound++;return true;}
 }
 static SQPreparedStatement statement(Probe client)throws Exception{
  Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);Unsafe unsafe=(Unsafe)f.get(null);
  // Test-only constructor bypass: the production constructor opens a real server.
  SQPreparedStatement ps=(SQPreparedStatement)unsafe.allocateInstance(SQPreparedStatement.class);
  field(SQPreparedStatement.class,ps,"client",client);field(SQPreparedStatement.class,ps,"isClosed",false);
  field(SQPreparedStatement.class,ps,"setsPerBatch",new ArrayList<Integer>());
  return ps;
 }
 static class ActualConnector extends ConnectorImpl{
  int wouldFlush;ActualConnector()throws Exception{super(ConnectionParams.builder(true).build());}
  public void close(){wouldFlush++;}
 }
 static class LocalConnection extends SqreamConnection{
  int closes;boolean open=true;LocalConnection(){super(null);}
  public boolean isOpen(){return open;}
  public void close(){closes++;open=false;}
 }
 public static void main(String[] args)throws Exception{
  Probe pending=new Probe();SQPreparedStatement ps=statement(pending);ps.setLong(1,7L);ps.close();
  ok(pending.bound==1 && pending.flushInitial==0 && pending.flushAndClose==0 && pending.implicitClose==0,"closing bound unused PreparedStatement never flushes pending data");
  ok(pending.terminal==1 && ps.isClosed(),"unused PreparedStatement releases resources and is locally closed");
  ps.close();ok(pending.terminal==1,"PreparedStatement close is idempotent");
  Probe explicit=new Probe();ps=statement(explicit);ps.setLong(1,7L);int count=ps.executeUpdate();
  ok(count==1 && explicit.flushInitial==1 && explicit.flushAndClose==1 && explicit.implicitClose==1,"explicit executeUpdate preserves one flush and statement close");
  ps.close();ok(explicit.terminal==1 && explicit.flushAndClose==1 && explicit.implicitClose==1,"terminal close does not repeat successful execute flush");
  Probe unknown=new Probe();unknown.dataFailure=new RuntimeException(new ConnException("synthetic write outcome unknown"));unknown.terminalFailure=new ConnException("synthetic cleanup failure");
  SQLException primary=null;try(SQPreparedStatement failed=statement(unknown)){failed.setLong(1,7L);failed.executeUpdate();}catch(SQLException e){primary=e;}
  ok(primary!=null && primary.getCause()==unknown.dataFailure,"original unknown-write error stays primary");
  ok(primary.getSuppressed().length==1 && primary.getSuppressed()[0].getCause()==unknown.terminalFailure,"later PreparedStatement cleanup error is suppressed");
  ok(unknown.flushAndClose==1 && unknown.implicitClose==0 && unknown.terminal==1,"unknown write close never resends pending data");
  for(int rows:new int[]{1,3,128}){
   Probe batch=new Probe();SQPreparedStatement batchPs=statement(batch);
   for(int i=0;i<rows;i++){batchPs.setLong(1,i);batchPs.addBatch();}
   int[] result=batchPs.executeBatch();
   ok(result.length==rows && Arrays.stream(result).allMatch(v->v==1),"batch "+rows+" returns only after explicit completion");
   ok(batch.nextCalls==rows && batch.flushInitial==0 && batch.flushAndClose==1 && batch.implicitClose==1,"batch "+rows+" does not append registered rows twice");
   ok(((List<?>)read(SQPreparedStatement.class,batchPs,"setsPerBatch")).isEmpty(),"batch "+rows+" clears registered rows after ACK");
   batchPs.close();ok(batch.flushAndClose==1,"batch "+rows+" terminal close does not flush again");
  }
  Probe consumed=new Probe();SQPreparedStatement consumedPs=statement(consumed);consumedPs.setLong(1,1);consumedPs.executeUpdate();
  boolean staleRejected=false;try{consumedPs.executeBatch();}catch(SQLException expected){staleRejected=true;}
  ok(staleRejected && consumed.flushAndClose==1,"batch cannot report stale executeUpdate registrations as a new success");
  boolean lateAddRejected=false;try{consumedPs.addBatch();}catch(SQLException expected){lateAddRejected=true;}
  ok(lateAddRejected && consumed.nextCalls==0,"addBatch cannot mutate a completed native statement");
  boolean lateExecuteRejected=false;try{consumedPs.execute();}catch(SQLException expected){lateExecuteRejected=true;}
  ok(lateExecuteRejected && consumed.flushAndClose==1,"execute cannot replay a completed native statement");
  boolean lateUpdateRejected=false;try{consumedPs.executeUpdate();}catch(SQLException expected){lateUpdateRejected=true;}
  ok(lateUpdateRejected && consumed.flushAndClose==1,"executeUpdate rejects closed native server state without extra flush");
  consumedPs.close();
  Probe noBatch=new Probe();SQPreparedStatement noBatchPs=statement(noBatch);noBatchPs.setLong(1,9);
  ok(noBatchPs.executeBatch().length==0 && noBatch.flushAndClose==0,"empty registered batch does not execute a current unadded bind");
  noBatchPs.close();ok(noBatch.flushAndClose==0 && noBatch.implicitClose==0,"empty batch close drops pending bind without executing it");
  Probe mixed=new Probe();SQPreparedStatement mixedPs=statement(mixed);mixedPs.setLong(1,1);mixedPs.addBatch();mixedPs.setLong(1,2);
  boolean mixedRejected=false;try{mixedPs.executeBatch();}catch(SQLException expected){mixedRejected=true;}
  ok(mixedRejected && mixed.flushAndClose==0 && ((List<?>)read(SQPreparedStatement.class,mixedPs,"setsPerBatch")).size()==1,"mixed unadded current row is rejected without silent extra SQL");
  mixedPs.addBatch();ok(mixedPs.executeBatch().length==2 && mixed.nextCalls==2,"explicitly adding current row completes each registered batch row once");mixedPs.close();
  Probe batchFailure=new Probe();batchFailure.dataFailure=new RuntimeException(new ConnException("synthetic batch write"));
  SQPreparedStatement batchFailurePs=statement(batchFailure);for(int i=0;i<3;i++){batchFailurePs.setLong(1,i);batchFailurePs.addBatch();}
  boolean batchFailed=false;try{batchFailurePs.executeBatch();}catch(SQLException expected){batchFailed=expected.getCause()==batchFailure.dataFailure;}
  ok(batchFailed && ((List<?>)read(SQPreparedStatement.class,batchFailurePs,"setsPerBatch")).size()==3,"failed batch preserves its first error and registration evidence");
  batchFailurePs.close();ok(batchFailure.flushAndClose==1 && batchFailure.implicitClose==0,"failed batch close never retries data");
  Probe batchCloseFailure=new Probe();batchCloseFailure.statementCloseFailure=new RuntimeException(new ConnException("synthetic batch close ACK"));
  SQPreparedStatement batchClosePs=statement(batchCloseFailure);batchClosePs.setLong(1,1);batchClosePs.addBatch();
  boolean batchAckFailed=false;try{batchClosePs.executeBatch();}catch(SQLException expected){batchAckFailed=expected.getCause()==batchCloseFailure.statementCloseFailure;}
  ok(batchAckFailed && ((List<?>)read(SQPreparedStatement.class,batchClosePs,"setsPerBatch")).size()==1,"batch does not clear evidence before statement close ACK");
  batchClosePs.close();ok(batchCloseFailure.flushAndClose==1 && batchCloseFailure.implicitClose==1,"missing batch ACK does not trigger close replay");
  Probe execute=new Probe();SQPreparedStatement executePs=statement(execute);executePs.setLong(1,7);
  ok(!executePs.execute() && execute.flushInitial==1 && execute.flushAndClose==1 && execute.implicitClose==1,"execute DML returns false only after normal flush and ACK");
  executePs.close();ok(execute.flushAndClose==1 && ((List<?>)read(SQPreparedStatement.class,executePs,"setsPerBatch")).isEmpty(),"execute DML close cannot flush a second time");
  Probe addedAndCurrent=new Probe();SQPreparedStatement addedPs=statement(addedAndCurrent);
  addedPs.setLong(1,1);addedPs.addBatch();addedPs.setLong(1,2);addedPs.addBatch();addedPs.setLong(1,3);addedPs.execute();
  ok(addedAndCurrent.nextCalls==2 && addedAndCurrent.flushInitial==1 && addedAndCurrent.flushAndClose==1,"execute appends only the current unadded row once");
  addedPs.close();
  Probe abortedBeforeAck=new Probe();abortedBeforeAck.abortDuringFlush=true;SQPreparedStatement beforeAckPs=statement(abortedBeforeAck);beforeAckPs.setLong(1,1);
  boolean missingAckRejected=false;try{beforeAckPs.executeUpdate();}catch(SQLException expected){missingAckRejected=true;}
  ok(missingAckRejected && abortedBeforeAck.ackSequence==0,"local cancellation cannot turn missing statement ACK into update success");beforeAckPs.close();
  Probe wrongAck=new Probe();wrongAck.wrongAckId=true;SQPreparedStatement wrongAckPs=statement(wrongAck);wrongAckPs.setLong(1,1);wrongAckPs.addBatch();
  boolean wrongAckRejected=false;try{wrongAckPs.executeBatch();}catch(SQLException expected){wrongAckRejected=true;}
  ok(wrongAckRejected && ((List<?>)read(SQPreparedStatement.class,wrongAckPs,"setsPerBatch")).size()==1,"ACK from another statement cannot confirm this batch");wrongAckPs.close();
  Probe staleAck=new Probe();SQPreparedStatement staleAckPs=statement(staleAck);staleAckPs.setLong(1,1);staleAckPs.executeUpdate();staleAck.statementOpen=true;staleAck.suppressAck=true;staleAckPs.setLong(1,2);
  boolean staleAckRejected=false;try{staleAckPs.executeUpdate();}catch(SQLException expected){staleAckRejected=true;}
  ok(staleAckRejected && staleAck.ackSequence==1,"old ACK sequence cannot confirm another explicit execution");staleAckPs.close();
  Probe acknowledgedThenClosed=new Probe();SQPreparedStatement afterAckPs=statement(acknowledgedThenClosed);
  acknowledgedThenClosed.afterAck=()->{try{afterAckPs.close();}catch(SQLException e){throw new RuntimeException(e);}};
  afterAckPs.setLong(1,1);int confirmed=afterAckPs.executeUpdate();
  ok(confirmed==1 && afterAckPs.isClosed() && acknowledgedThenClosed.ackSequence==1,"same execution ACK remains valid after resource close");
  Probe registeredOnly=new Probe();SQPreparedStatement registeredPs=statement(registeredOnly);registeredPs.setLong(1,1);registeredPs.addBatch();registeredPs.close();
  ok(registeredOnly.flushAndClose==0 && registeredOnly.implicitClose==0,"addBatch without an execution is cancelled without a close flush");
  ActualConnector connector=new ActualConnector();LocalConnection connection=new LocalConnection();
  field(ConnectorImpl.class,connector,"sqreamConnection",connection);
  SqreamExecutedStatement active=(SqreamExecutedStatement)Proxy.newProxyInstance(AbortWithoutFlushTest.class.getClassLoader(),new Class[]{SqreamExecutedStatement.class},(o,m,a)->m.getName().equals("isOpen")?true:null);
  field(ConnectorImpl.class,connector,"sqreamExecutedStatement",active);
  connector.closeConnection();
  ok(connector.wouldFlush==0 && connection.closes==1,"Connector terminal close bypasses implicit active statement flush");
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger puts=new AtomicInteger();AtomicBoolean interrupted=new AtomicBoolean();
  SqreamExecutedStatement writer=(SqreamExecutedStatement)Proxy.newProxyInstance(AbortWithoutFlushTest.class.getClassLoader(),new Class[]{SqreamExecutedStatement.class},(o,m,a)->{
   if(m.getName().equals("put")){puts.incrementAndGet();entered.countDown();try{release.await();}catch(InterruptedException e){interrupted.set(true);throw e;}interrupted.set(Thread.currentThread().isInterrupted());}return null;});
  Constructor<TableMetadata> constructor=TableMetadata.class.getDeclaredConstructor(int.class);constructor.setAccessible(true);TableMetadata metadata=constructor.newInstance(0);
  ByteBufferPool pool=new ByteBufferPool(1,1,metadata);BlockDto block=pool.getBlock();FlushService service=FlushService.getInstance(writer,0);
  service.process(block,pool);ok(entered.await(1,TimeUnit.SECONDS),"synthetic active flush entered");
  service.process(block,pool);service.process(block,pool);long start=System.nanoTime();service.compatAbortWithoutFlush();
  ok(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(1),"flush abort returns without waiting for writer");
  boolean rejected=false;try{service.process(block,pool);}catch(IllegalStateException expected){rejected=true;}
  ok(rejected,"aborted FlushService cannot create a new flush");
  release.countDown();ExecutorService executor=(ExecutorService)read(FlushService.class,service,"executorService");
  ok(executor.awaitTermination(2,TimeUnit.SECONDS) && puts.get()==1,"queued flushes are discarded and executor terminates");
  ok(!interrupted.get(),"abort never interrupts old active writer");
  System.out.println("RESULT "+checks+" PASS");
 }
}
