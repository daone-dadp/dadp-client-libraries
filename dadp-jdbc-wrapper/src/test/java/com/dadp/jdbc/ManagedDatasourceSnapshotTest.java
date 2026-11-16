package com.dadp.jdbc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ManagedDatasourceSnapshotTest {
 private ObjectNode snapshot(String mode,String vendor)throws Exception{
  ObjectNode root=ManagedDatasourceRuntime.JSON.createObjectNode();
  root.put("runtimeVersion",7);
  root.putObject("wrapper").put("alias","orders").put("tenantId","agent-one");
  root.putObject("runtime").put("executionMode",mode).put("enabled",true).put("failOpen",false);
  root.putObject("datasource").put("alias","orders").put("vendor",vendor).put("schemaName","public");
  root.put("schemaJson","{\"tables\":[{\"schemaName\":\"public\",\"tableName\":\"users\",\"columns\":[{\"columnName\":\"id\"},{\"columnName\":\"name\"},{\"columnName\":\"phone\"}]}]}");
  root.putArray("policyBindings").addObject().put("schemaName","public").put("tableName","users").put("columnName","name").put("policyCode","DAONE234").put("status","ACTIVE");
  return root;
 }
 private ManagedDatasourceRuntime.Snapshot parse(JsonNode n)throws SQLException{return ManagedDatasourceRuntime.Snapshot.parse(n,"orders","agent-one");}
 @Test void hubMappingsProduceSqlWithoutLocalColumnProperties()throws Exception{
  ManagedDatasourceRuntime.Snapshot s=parse(snapshot("plugin","postgresql"));
  String sql="insert into public.users(id,name,phone) values(?,?,?)";
  String rewritten=s.rewriter(sql).rewrite(sql);
  assertTrue(rewritten.contains("dadp.dadp_encrypt(?, 'DAONE234')"));
  assertFalse(rewritten.contains("dadp.dadp_encrypt(?, 'phone')"));
  assertThrows(SQLException.class,()->s.rewriter("insert into other(id,name) values(?,?)"));
 }
 @Test void unqualifiedSqlIsBoundToTheHubSchemaAndUnsupportedSqlCannotEscape()throws Exception{
  ManagedDatasourceRuntime.Snapshot s=parse(snapshot("plugin","postgresql"));
  String sql="insert into users(id,name,phone) values(?,?,?)";
  assertTrue(s.rewriter(sql).rewrite(sql).contains("public.users"));
  assertThrows(SQLException.class,()->s.rewriter("select * from users"));
  assertThrows(SQLException.class,()->s.rewriter("select name from users union select name from other"));
 }
 @Test void mysqlHubMappingsUseMysqlPublicFunctionsAndRemainQualified()throws Exception{
  ManagedDatasourceRuntime.Snapshot s=parse(snapshot("plugin","mysql"));
  String sql="insert into public.users(id,name,phone) values(?,?,?)";
  assertTrue(s.rewriter(sql).rewrite(sql).contains("dadp.dadp_encrypt(?, 'DAONE234')"));
  assertTrue(s.rewriter("select name from users where id=?").rewrite("select name from users where id=?").contains("dadp.dadp_decrypt"));
  assertThrows(SQLException.class,()->parse(snapshot("plugin","mariadb")));
 }
 @Test void staleRefreshCannotOverwriteNewerModeOrPolicies()throws Exception{
  java.nio.file.Path dir=java.nio.file.Files.createTempDirectory("managed-runtime-contract-");
  try{
   ObjectNode current=snapshot("engine","postgresql");ManagedDatasourceRuntime.persist(dir.toString(),current,"orders","agent-one");
   ObjectNode stale=snapshot("plugin","postgresql");stale.put("runtimeVersion",6);
   assertThrows(java.io.IOException.class,()->ManagedDatasourceRuntime.validateRefresh(dir.toString(),stale,"orders","agent-one"));
   assertEquals(7,ManagedDatasourceRuntime.JSON.readTree(dir.resolve("managed-runtime.json").toFile()).path("runtimeVersion").asInt());
  }finally{java.nio.file.Files.deleteIfExists(dir.resolve("managed-runtime.json"));java.nio.file.Files.deleteIfExists(dir);}
 }
 @Test void unsupportedVendorsCannotUsePluginOrUnknownMode()throws Exception{
  assertThrows(SQLException.class,()->parse(snapshot("plugin","sqream")));
  assertThrows(SQLException.class,()->parse(snapshot("plugin","mariadb")));
  assertThrows(SQLException.class,()->parse(snapshot("auto","postgresql")));
  assertEquals("engine",parse(snapshot("engine","sqream")).mode);
 }
 @Test void crossAliasTenantAndVersionConfusionAreRejected()throws Exception{
  ObjectNode n=snapshot("engine","postgresql");((ObjectNode)n.path("datasource")).put("alias","another");
  final ObjectNode different=n;assertThrows(SQLException.class,()->parse(different));
  n=snapshot("engine","postgresql");((ObjectNode)n.path("wrapper")).put("tenantId","another");
  final ObjectNode other=n;assertThrows(SQLException.class,()->parse(other));
  n=snapshot("engine","postgresql");n.put("runtimeVersion",1.5);
  final ObjectNode fractional=n;assertThrows(SQLException.class,()->parse(fractional));
 }
 @Test void missingSchemaOrMappingsAndFailOpenAreRejected()throws Exception{
  ObjectNode n=snapshot("plugin","postgresql");n.remove("schemaJson");
  assertThrows(SQLException.class,()->parse(n));
  ObjectNode noMappings=snapshot("plugin","postgresql");noMappings.putArray("policyBindings");
  assertThrows(SQLException.class,()->parse(noMappings));
  ObjectNode failOpen=snapshot("engine","postgresql");((ObjectNode)failOpen.path("runtime")).put("failOpen",true);
  assertThrows(SQLException.class,()->parse(failOpen));
 }
}
