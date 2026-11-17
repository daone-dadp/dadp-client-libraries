package com.dadp.jdbc;

import com.dadp.jdbc.config.ProxyConfig;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The Alias-managed SQL route invokes the DB module; it never invokes an Engine client. */
class ManagedSqreamConnectionTest {
    Path dir;HttpServer server;ManagedDatasourceRuntime runtime;Connection raw,app;PreparedStatement nativeStatement;
    @BeforeEach void setup() throws Exception {
        dir=Files.createTempDirectory("dadp-sqream-managed-");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",x->{x.sendResponseHeaders(304,-1);x.close();});server.start();
        ProxyConfig cfg=mock(ProxyConfig.class);
        when(cfg.getAlias()).thenReturn("sq");when(cfg.getTenantId()).thenReturn("agent");
        when(cfg.getStorageDir()).thenReturn(dir.toString());
        when(cfg.getHubUrl()).thenReturn("http://127.0.0.1:"+server.getAddress().getPort());
        ObjectNode n=new SqreamManagedSqlTest().snapshot("plugin");
        Files.write(dir.resolve("managed-runtime.json"),ManagedDatasourceRuntime.JSON.writeValueAsBytes(n));
        Constructor<ManagedDatasourceRuntime> c=ManagedDatasourceRuntime.class.getDeclaredConstructor(ProxyConfig.class);
        c.setAccessible(true);runtime=c.newInstance(cfg);
        raw=mock(Connection.class);nativeStatement=mock(PreparedStatement.class);
        when(raw.getAutoCommit()).thenReturn(true);
        when(raw.prepareStatement(anyString())).thenReturn(nativeStatement);
        when(nativeStatement.executeUpdate()).thenReturn(1);
        app=ManagedJdbcConnection.wrap(raw,"jdbc:dadp:sqream://localhost/db",Collections.emptyMap(),new Properties(),runtime);
    }
    @AfterEach void cleanup() throws Exception {
        if(app!=null)app.close();if(server!=null)server.stop(0);
        if(dir!=null){Files.deleteIfExists(dir.resolve("managed-runtime.json"));Files.deleteIfExists(dir);}
    }
    @Test void typedPreparedBindsReachTheModuleWithoutClientEncryption() throws Exception {
        try(PreparedStatement p=app.prepareStatement("insert into users(id,name) values(?,?)")) {
            p.setInt(1,7);p.setString(2,"customer's value");assertEquals(1,p.executeUpdate());
        }
        verify(raw).prepareStatement("INSERT INTO public.users (id, name) VALUES (?, dadp_crypto.dadp_encrypt(?, 'DAONE234'))");
        verify(nativeStatement).setInt(1,7);verify(nativeStatement).setString(2,"customer's value");
        verify(nativeStatement,times(1)).executeUpdate();
    }
    @Test void moduleErrorsPropagateWithoutReplayOrFallback() throws Exception {
        when(nativeStatement.executeUpdate()).thenThrow(new SQLException("private backend detail","38000",17));
        try(PreparedStatement p=app.prepareStatement("insert into users(id,name) values(?,?)")) {
            p.setInt(1,7);p.setString(2,"plain");
            SQLException e=assertThrows(SQLException.class,p::executeUpdate);
            assertEquals("38000",e.getSQLState());assertEquals(17,e.getErrorCode());
            assertFalse(e.toString().contains("private backend detail"));
        }
        verify(nativeStatement,times(1)).executeUpdate();
    }
    @Test void unsupportedGeneratedKeysAndScopeChangesCannotTouchNativeConnection() throws Exception {
        assertThrows(SQLException.class,()->app.setSchema("other"));
        assertThrows(SQLException.class,()->app.setCatalog("other"));
        try(PreparedStatement p=app.prepareStatement("insert into users(id,name) values(?,?)",Statement.RETURN_GENERATED_KEYS)) {
            p.setInt(1,7);p.setString(2,"plain");assertThrows(SQLException.class,p::executeUpdate);
        }
        verify(raw,never()).setSchema(anyString());verify(raw,never()).setCatalog(anyString());
        verify(raw,never()).prepareStatement(anyString(),anyInt());
        verify(raw,never()).getMetaData();
    }
    @Test void disabledAliasRejectsAlreadyPreparedWorkBeforeAnySql() throws Exception {
        try(PreparedStatement p=app.prepareStatement("insert into users(id,name) values(?,?)")) {
            p.setInt(1,7);p.setString(2,"plain");
            ManagedDatasourceRuntime.Snapshot s=runtime.current;
            runtime.current=new ManagedDatasourceRuntime.Snapshot(s.version+1,s.mode,s.vendor,false,s.rewriters,s.root);
            assertThrows(SQLException.class,p::executeUpdate);
        }
        verify(raw,never()).prepareStatement(anyString());verify(nativeStatement,never()).executeUpdate();
    }
}
