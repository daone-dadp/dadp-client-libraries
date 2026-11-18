import com.sqream.jdbc.connector.*;
import com.sqream.jdbc.connector.enums.*;
import com.sqream.jdbc.connector.messenger.*;
import com.sqream.jdbc.connector.serverAPI.*;
import com.sqream.jdbc.connector.serverAPI.Statement.*;
import com.sqream.jdbc.connector.serverAPI.enums.*;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
public class PutFailurePreservationTest {
 static int checks;
 static void ok(boolean value,String name){if(!value)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
 static class Fixture {
  final List<String> calls=new ArrayList<>();
  final ConnException first=new ConnException("synthetic-primary");
  final ConnException cleanup=new ConnException("synthetic-cleanup");
  final SqreamConnectionContext context;
  final SqreamExecutedStatementImpl statement;
  boolean failPrimary,failCleanup,fatalCleanup;
  Fixture() {
   Messenger messenger=(Messenger)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Messenger.class},(p,m,a)->{
    calls.add(m.getName());
    if(m.getName().equals("isPutted")&&failPrimary)throw first;
    if(m.getName().equals("closeStatement")&&failCleanup)throw cleanup;
    if(m.getName().equals("closeStatement")&&fatalCleanup)throw new AssertionError("synthetic-cleanup-error");
    return null;
   });
   context=new SqreamConnectionContext(null,null,100,messenger);
   context.setColumnsMetadata(Arrays.asList(new ColumnMetadataDto(false,"value",false,"ftInt","",4,0,0,0)),Collections.emptyList());
   statement=new SqreamExecutedStatementImpl(context,StatementType.PARAMED_INSERT,null);
  }
  void put() {
   BlockDto block=new BlockDto(new ByteBuffer[]{ByteBuffer.allocate(4).putInt(7)},new ByteBuffer[1],new ByteBuffer[1],1,new ByteBuffer[1],Collections.emptyList());
   block.setFillSize(1);statement.put(block);
  }
  RuntimeException failure(){try{put();}catch(RuntimeException e){return e;}throw new AssertionError("missing failure");}
 }
 public static void main(String[] args) {
  Fixture f=new Fixture();f.failPrimary=true;f.failCleanup=true;RuntimeException e=f.failure();
  ok(e.getCause()==f.first,"first protocol error remains primary cause");
  ok(f.first.getSuppressed().length==1 && f.first.getSuppressed()[0].getCause()==f.cleanup,"cleanup error is suppressed under primary");
  ok(f.context.getStatementPhase()==StatementPhase.CLOSED,"failed cleanup still marks statement CLOSED");
  ok(f.calls.equals(Arrays.asList("put","sendBinaryHeader","sendBinaryData","isPutted","closeStatement")),"error path retains one put and one cleanup without replay");
  f=new Fixture();f.failPrimary=true;e=f.failure();
  ok(e.getCause()==f.first && f.first.getSuppressed().length==0,"successful cleanup retains first error without synthetic suppression");
  ok(f.context.getStatementPhase()==StatementPhase.CLOSED,"successful error cleanup closes statement");
  f=new Fixture();f.put();
  ok(f.calls.equals(Arrays.asList("put","sendBinaryHeader","sendBinaryData","isPutted")),"normal PUT header data ACK order unchanged");
  ok(f.context.getStatementPhase()==StatementPhase.EXECUTED,"successful put leaves statement open until normal close");
  f.statement.close();
  ok(f.calls.get(f.calls.size()-1).equals("closeStatement") && f.context.getStatementPhase()==StatementPhase.CLOSED,"normal close still sends statement close");
  f=new Fixture();f.failPrimary=true;f.fatalCleanup=true;e=f.failure();
  ok(e.getCause()==f.first && f.first.getSuppressed().length==1 && f.first.getSuppressed()[0] instanceof AssertionError,"cleanup Error cannot erase original protocol failure");
  System.out.println("RESULT "+checks+" PASS");
 }
}
