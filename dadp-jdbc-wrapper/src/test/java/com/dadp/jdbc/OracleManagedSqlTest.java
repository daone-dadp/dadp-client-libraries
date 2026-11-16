package com.dadp.jdbc;
import com.dadp.jdbc.rewrite.ExperimentalSqlRewriter;
import java.sql.SQLException;import java.util.*;import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OracleManagedSqlTest{
 ExperimentalSqlRewriter rewriter()throws Exception{
  Map<String,String>m=new LinkedHashMap<>();m.put("ID",null);m.put("NAME","DAONE234");m.put("PHONE","PART0001");
  return ExperimentalSqlRewriter.forManagedOracle(ExperimentalSqlRewriter.FixtureScope.forOracle("CUSTOMER_APP","USERS",m));
 }
 @Test void unquotedOracleNamesAreCaseInsensitiveAndLiteralsAreUnchanged()throws Exception{
  String sql=rewriter().rewrite("INSERT INTO USERS(ID,NAME,PHONE) VALUES(?, 'MixedCase O''Neil', ?)");
  assertTrue(sql.contains("customer_app.users"));assertTrue(sql.contains("dadp.dadp_encrypt('MixedCase O''Neil', 'DAONE234')"));
  assertTrue(rewriter().rewrite("SELECT U.ID,U.NAME AS DISPLAY_NAME FROM USERS U WHERE U.ID=?").contains("dadp.dadp_decrypt(u.name)"));
 }
 @Test void upperQuotedObjectsMatchOnlyTheirUnquotedEquivalent()throws Exception{
  assertTrue(rewriter().rewrite("select \"NAME\" from \"CUSTOMER_APP\".\"USERS\" where \"ID\"=?").contains("dadp.dadp_decrypt(name)"));
  assertThrows(SQLException.class,()->rewriter().rewrite("select \"name\" from \"users\" where id=?"));
  assertThrows(SQLException.class,()->rewriter().rewrite("select name from OTHER.USERS where id=?"));
 }
 @Test void nativeFunctionPolicyAndPrimaryKeyCannotBeOverridden()throws Exception{
  assertThrows(SQLException.class,()->rewriter().rewrite("insert into users(id,name,phone) values(?,DADP.DADP_ENCRYPT(?,'OTHER234'),?)"));
  rewriter().checkGeneratedKeyColumns(new String[]{"ID"});
  assertThrows(SQLException.class,()->rewriter().checkGeneratedKeyColumns(new String[]{"NAME"}));
  assertThrows(SQLException.class,()->rewriter().rewrite("select name from users where name=?"));
 }
 @Test void mixedCaseCatalogObjectsAreRejectedWithoutChangingOtherDialects()throws Exception{
  Map<String,String>m=new LinkedHashMap<>();m.put("ID",null);m.put("Name","DAONE234");
  assertThrows(SQLException.class,()->ExperimentalSqlRewriter.FixtureScope.forOracle("CUSTOMER_APP","USERS",m));
  assertThrows(SQLException.class,()->new ExperimentalSqlRewriter.FixtureScope("PUBLIC","USERS",m));
 }
 @Test void hubOracleCatalogAndPolicyBindingsBuildTheNativeRoute()throws Exception{
  com.fasterxml.jackson.databind.node.ObjectNode n=ManagedDatasourceRuntime.JSON.createObjectNode();
  n.put("runtimeVersion",8);n.putObject("wrapper").put("alias","ora").put("tenantId","agent");
  n.putObject("runtime").put("executionMode","plugin").put("enabled",true).put("failOpen",false);
  n.putObject("datasource").put("alias","ora").put("vendor","oracle").put("schemaName","CUSTOMER_APP");
  n.put("schemaJson","{\"tables\":[{\"schemaName\":\"CUSTOMER_APP\",\"tableName\":\"USERS\",\"columns\":[{\"columnName\":\"ID\"},{\"columnName\":\"NAME\"}]}]}");
  n.putArray("policyBindings").addObject().put("schemaName","customer_app").put("tableName","users").put("columnName","name").put("policyCode","DAONE234").put("status","ACTIVE");
  ManagedDatasourceRuntime.Snapshot snap=ManagedDatasourceRuntime.Snapshot.parse(n,"ora","agent");
  String sql="INSERT INTO USERS(ID,NAME) VALUES(?,?)";
  assertTrue(snap.rewriter(sql).rewrite(sql).contains("dadp.dadp_encrypt(?, 'DAONE234')"));
 }
 @Test void defaultIsAllowedOnlyForAnOracleUnprotectedInsertColumn()throws Exception{
  assertTrue(rewriter().rewrite("insert into users(id,name,phone) values(default,?,?)").toLowerCase().contains("default"));
  assertThrows(SQLException.class,()->rewriter().rewrite("insert into users(id,name,phone) values(?,default,?)"));
  assertThrows(SQLException.class,()->rewriter().rewrite("update users set id=default where id=?"));
  java.util.Map<String,String>m=new java.util.LinkedHashMap<>();m.put("id",null);m.put("name","DAONE234");
  ExperimentalSqlRewriter pg=ExperimentalSqlRewriter.forManagedPostgresql(new ExperimentalSqlRewriter.FixtureScope("public","users",m));
  assertThrows(SQLException.class,()->pg.rewrite("insert into users(id,name) values(default,?)"));
 }
}
