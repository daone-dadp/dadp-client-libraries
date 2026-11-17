package com.dadp.jdbc;

import com.dadp.jdbc.rewrite.ExperimentalSqlRewriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.SQLException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqreamManagedSqlTest {
    ExperimentalSqlRewriter rewrite() throws Exception {
        Map<String,String> columns=new LinkedHashMap<>();
        columns.put("id",null);columns.put("name","DAONE234");columns.put("email","DAONE234");columns.put("phone","PART0001");
        return ExperimentalSqlRewriter.forManagedSqream(new ExperimentalSqlRewriter.FixtureScope("public","users",columns));
    }
    ObjectNode snapshot(String mode) throws Exception {
        ObjectNode n=ManagedDatasourceRuntime.JSON.createObjectNode();
        n.put("runtimeVersion",18);
        n.putObject("wrapper").put("alias","sq").put("tenantId","agent");
        n.putObject("runtime").put("executionMode",mode).put("enabled",true).put("failOpen",false);
        n.putObject("datasource").put("alias","sq").put("vendor","sqream").put("schemaName","public");
        n.put("schemaJson","{\"tables\":[{\"schemaName\":\"public\",\"tableName\":\"users\",\"columns\":[{\"columnName\":\"id\"},{\"columnName\":\"name\"},{\"columnName\":\"email\"},{\"columnName\":\"phone\"}]}]}");
        for(String c:new String[]{"name","email","phone"})
            n.withArray("policyBindings").addObject().put("schemaName","public").put("tableName","users")
             .put("columnName",c).put("policyCode",c.equals("phone")?"PART0001":"DAONE234").put("status","ACTIVE");
        return n;
    }
    @Test void writesCallExternalModuleAndKeepPlainBinds() throws Exception {
        String sql=rewrite().rewrite("insert into users(id,name,email,phone) values(?,?,?,?)");
        assertTrue(sql.contains("public.users"));
        assertTrue(sql.contains("dadp_crypto.dadp_encrypt(?, 'DAONE234')"));
        assertTrue(sql.contains("dadp_crypto.dadp_encrypt(?, 'PART0001')"));
        assertFalse(sql.contains("dadp.dadp_encrypt"));
        assertTrue(rewrite().rewrite("update users set name=? where id=?").contains("dadp_crypto.dadp_encrypt"));
    }
    @Test void readsDecryptInsideSqreamAndOrderOnlyAnUnprotectedColumn() throws Exception {
        String sql=rewrite().rewrite("select u.id,u.name,u.phone from users u where u.id=? order by u.id desc");
        assertTrue(sql.contains("dadp_crypto.dadp_decrypt(u.name) AS name"));
        assertTrue(sql.contains("ORDER BY u.id DESC"));
        assertThrows(SQLException.class,()->rewrite().rewrite("select name from users order by name"));
        assertThrows(SQLException.class,()->rewrite().rewrite("select name from users order by id nulls first"));
        assertThrows(SQLException.class,()->rewrite().rewrite("select name from users order by id,name"));
    }
    @Test void rejectCrossSchemaUnknownSqlAndPolicyOverrides() throws Exception {
        assertThrows(SQLException.class,()->rewrite().rewrite("select name from other.users where id=?"));
        assertThrows(SQLException.class,()->rewrite().rewrite("select * from users"));
        assertThrows(SQLException.class,()->rewrite().rewrite("select name from users where name=?"));
        assertThrows(SQLException.class,()->rewrite().rewrite("insert into users(id,name) values(?,dadp_crypto.dadp_encrypt(?,'OTHER234'))"));
        assertThrows(SQLException.class,()->rewrite().rewrite("select name from users union select name from other"));
    }
    @Test void nullPolicyValuesAndExistingPublicFunctionsPreserveContract() throws Exception {
        String sql=rewrite().rewrite("insert into users(id,name) values(1,NULL)");
        assertFalse(sql.contains("dadp_encrypt"));
        assertTrue(rewrite().rewrite("insert into users(id,name) values(?,dadp_crypto.dadp_encrypt(?,'DAONE234'))")
                .contains("dadp_crypto.dadp_encrypt"));
    }
    @Test void aliasSnapshotControlsTheOneJarPluginRoute() throws Exception {
        ManagedDatasourceRuntime.Snapshot n=ManagedDatasourceRuntime.Snapshot.parse(snapshot("plugin"),"sq","agent");
        String sql="select id,name from users order by id";
        assertEquals("sqream",n.vendor);assertEquals("plugin",n.mode);
        assertTrue(n.rewriter(sql).rewrite(sql).contains("dadp_crypto.dadp_decrypt"));
        ObjectNode engine=snapshot("engine");engine.remove("schemaJson");engine.putArray("policyBindings");
        assertEquals("engine",ManagedDatasourceRuntime.Snapshot.parse(engine,"sq","agent").mode);
        ObjectNode invalid=snapshot("auto");
        assertThrows(SQLException.class,()->ManagedDatasourceRuntime.Snapshot.parse(invalid,"sq","agent"));
    }
    @Test void sqreamOrderSupportDoesNotBroadenOtherPluginDialects() throws Exception {
        Map<String,String> columns=new LinkedHashMap<>();columns.put("id",null);columns.put("name","DAONE234");
        ExperimentalSqlRewriter pg=ExperimentalSqlRewriter.forManagedPostgresql(
            new ExperimentalSqlRewriter.FixtureScope("public","users",columns));
        assertThrows(SQLException.class,()->pg.rewrite("select name from users order by id"));
    }
}
