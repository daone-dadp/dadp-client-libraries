package com.dadp.jdbc;
import com.dadp.jdbc.config.ProxyConfig;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.lang.reflect.Constructor;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ManagedTransactionBoundaryTest {
 Path dir;HttpServer server;ManagedDatasourceRuntime runtime;Connection raw,app;
 @BeforeEach void setup()throws Exception{
  dir=Files.createTempDirectory("dadp-managed-tx-");
  server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",x->{x.sendResponseHeaders(304,-1);x.close();});server.start();
  ProxyConfig cfg=mock(ProxyConfig.class);
  when(cfg.getAlias()).thenReturn("orders");when(cfg.getTenantId()).thenReturn("agent");
  when(cfg.getStorageDir()).thenReturn(dir.toString());
  when(cfg.getHubUrl()).thenReturn("http://127.0.0.1:"+server.getAddress().getPort());
  ObjectNode n=ManagedDatasourceRuntime.JSON.createObjectNode();n.put("runtimeVersion",1);
  n.putObject("wrapper").put("alias","orders").put("tenantId","agent");
  n.putObject("datasource").put("alias","orders").put("vendor","postgresql");
  n.putObject("runtime").put("executionMode","engine").put("enabled",true).put("failOpen",false);
  Files.write(dir.resolve("managed-runtime.json"),ManagedDatasourceRuntime.JSON.writeValueAsBytes(n));
  Constructor<ManagedDatasourceRuntime> c=ManagedDatasourceRuntime.class.getDeclaredConstructor(ProxyConfig.class);c.setAccessible(true);
  runtime=c.newInstance(cfg);
  raw=mock(Connection.class);when(raw.getAutoCommit()).thenReturn(false);
  app=ManagedJdbcConnection.wrap(raw,"jdbc:dadp:postgresql://localhost/test",Collections.emptyMap(),new Properties(),runtime);
  // Begin a managed transaction without touching the DB; SQL delegates stay lazy.
  app.prepareStatement("insert into users(id,name) values(?,?)");
 }
 void change(){
  ManagedDatasourceRuntime.Snapshot s=runtime.current;
  runtime.current=new ManagedDatasourceRuntime.Snapshot(2,s.mode,s.vendor,s.enabled,s.rewriters,s.root);
 }
 @AfterEach void cleanup()throws Exception{
  if(app!=null)app.close();if(server!=null)server.stop(0);
  if(dir!=null){Files.deleteIfExists(dir.resolve("managed-runtime.json"));Files.deleteIfExists(dir);}
 }
 @Test void changedTransactionCannotCommitEvenWithoutAnotherQuery()throws Exception{
  change();assertThrows(SQLTransactionRollbackException.class,()->app.commit());verify(raw,never()).commit();
 }
 @Test void rejectionCannotBeIgnoredThenCommitted()throws Exception{
  change();assertThrows(SQLTransactionRollbackException.class,()->app.prepareStatement("select id from users"));
  assertThrows(SQLTransactionRollbackException.class,()->app.commit());verify(raw,never()).commit();
 }
 @Test void enablingAutoCommitCannotImplicitlyCommitStaleWork()throws Exception{
  change();assertThrows(SQLTransactionRollbackException.class,()->app.setAutoCommit(true));verify(raw,never()).setAutoCommit(true);
 }
 @Test void savepointRollbackDoesNotResetTheTransactionFence()throws Exception{
  Savepoint sp=mock(Savepoint.class);when(raw.setSavepoint()).thenReturn(sp);assertSame(sp,app.setSavepoint());
  change();app.rollback(sp);
  assertThrows(SQLTransactionRollbackException.class,()->app.prepareStatement("select id from users"));
  verify(raw).rollback(sp);
 }
 @Test void fullRollbackStartsANewValidTransaction()throws Exception{
  change();assertThrows(SQLTransactionRollbackException.class,()->app.prepareStatement("select id from users"));
  app.rollback();assertDoesNotThrow(()->app.prepareStatement("select id from users"));app.commit();
  verify(raw).rollback();verify(raw).commit();
 }
 @Test void internalRecoverySharesPhysicalConnectionWithoutPublicUnwrap()throws Exception{
  assertSame(raw,ManagedJdbcConnection.recoveryDelegate(app));assertSame(app,app.unwrap(Connection.class));
  assertThrows(SQLException.class,()->app.unwrap(Runnable.class));
 }
 @Test void permanentRuntimeDenialIsNotARetryableTransportFailure()throws Exception{
  ManagedDatasourceRuntime.Snapshot s=runtime.current;
  runtime.current=new ManagedDatasourceRuntime.Snapshot(s.version,s.mode,s.vendor,false,s.rewriters,s.root);
  SQLException e=assertThrows(SQLException.class,()->app.prepareStatement("select id from users"));
  assertFalse(RecoverablePostgresqlWrite.retryable(e));
 }
 @Test void unchangedTransactionCommitsNormally()throws Exception{app.commit();verify(raw).commit();}
}
