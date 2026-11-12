package com.dadp.jdbc;

import com.dadp.jdbc.config.ProxyConfig;
import com.dadp.jdbc.rewrite.ExperimentalSqlRewriter;
import com.dadp.common.sync.http.HttpClientAdapter;
import com.dadp.common.sync.http.Java8HttpClientAdapterFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** One authoritative Hub mode+schema+policy snapshot per enrolled datasource. */
final class ManagedDatasourceRuntime {
    static final ObjectMapper JSON = new ObjectMapper();
    private static final ConcurrentMap<String,ManagedDatasourceRuntime> INSTANCES = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService POLLER = Executors.newScheduledThreadPool(2, r -> {
        Thread t=new Thread(r,"dadp-datasource-refresh");t.setDaemon(true);return t;
    });
    final ReentrantReadWriteLock gate = new ReentrantReadWriteLock(true);
    final ProxyConfig config;
    final Path file;
    private final HttpClientAdapter http = Java8HttpClientAdapterFactory.create(3000,5000);
    volatile Snapshot current;
    private volatile SQLException blocked;
    private ManagedDatasourceRuntime(ProxyConfig cfg)throws SQLException {
        config=cfg;file=Paths.get(cfg.getStorageDir(),"managed-runtime.json");
        try { if(Files.exists(file)) current=Snapshot.parse(JSON.readTree(file.toFile()),cfg.getAlias(),cfg.getTenantId()); }
        catch(Exception e){throw invalid("Invalid cached Hub datasource snapshot");}
        try{refresh();}catch(SQLException e){if(current==null||blocked!=null)throw e;}
        if(current==null) throw invalid("Hub datasource snapshot is required; run wrapper refresh or check Hub availability");
        POLLER.scheduleWithFixedDelay(() -> {try{refresh();}catch(SQLException ignored){}},1,5,TimeUnit.SECONDS);
    }
    static ManagedDatasourceRuntime get(ProxyConfig cfg)throws SQLException {
        ManagedDatasourceRuntime existing=INSTANCES.get(cfg.getStorageDir());
        if(existing!=null)return existing;
        synchronized(INSTANCES){
            existing=INSTANCES.get(cfg.getStorageDir());
            if(existing==null){existing=new ManagedDatasourceRuntime(cfg);INSTANCES.put(cfg.getStorageDir(),existing);}
            return existing;
        }
    }
    Snapshot snapshot()throws SQLException {
        if(blocked!=null)throw blocked;
        Snapshot value=current;
        if(value==null||!value.enabled)throw invalid("Hub Agent is disabled or datasource runtime is unavailable");
        return value;
    }
    void refresh()throws SQLException {
        String base=config.getHubUrl();
        if(base==null)throw invalid("Hub runtime URL is missing");
        String url=base.replaceAll("/+$","")+"/hub/api/v1/runtime/wrappers/"+config.getTenantId()+"/refresh";
        Snapshot prev=current;
        if(prev!=null)url+="?version="+prev.version;
        try {
            Map<String,String> headers=new HashMap<>();headers.put("X-DADP-Tenant-Id",config.getTenantId());headers.put("Accept","application/json");
            HttpClientAdapter.HttpResponse response=http.get(URI.create(url),headers);
            int status=response.getStatusCode();
            if(status==304){blocked=null;return;}
            if(status==401||status==403||status==404||status==409){
                blocked=invalid("Hub rejected datasource runtime refresh (HTTP "+status+")");throw blocked;
            }
            if(status!=200)throw invalid("Hub datasource refresh is temporarily unavailable");
            JsonNode root=JSON.readTree(response.getBody());
            Snapshot next;
            try{next=Snapshot.parse(root,config.getAlias(),config.getTenantId());}
            catch(SQLException e){blocked=e;throw e;}
            gate.writeLock().lock();
            try {
                if(current!=null&&next.version<current.version)throw invalid("Hub runtime version rollback rejected");
                if(current==null||next.version>current.version){
                    WrapperCliStorageSupport.applyRefreshResponse(config.getStorageDir(),response.getBody(),config.getHubUrl());
                    current=next;
                }
                blocked=null;
            }finally{gate.writeLock().unlock();}
        }catch(SQLException e){throw e;}
        catch(Exception e){throw invalid("Hub datasource refresh failed; last valid snapshot retained");}
    }
    static void validateRefresh(String directory,JsonNode root,String alias,String tenant)throws java.io.IOException {
        if(!root.path("runtime").has("executionMode"))return;
        try {
            Snapshot next=Snapshot.parse(root,alias,tenant);
            Path cached=Paths.get(directory,"managed-runtime.json");
            if(Files.exists(cached)&&JSON.readTree(cached.toFile()).path("runtimeVersion").asLong()>next.version)
                throw invalid("Stale Hub runtime snapshot rejected");
        }catch(SQLException e){throw new java.io.IOException(e.getMessage());}
    }
    /** CLI and the live poller persist the exact same atomic, identity-bound response. */
    static void persist(String directory,JsonNode root,String alias,String tenant)throws java.io.IOException {
        if(!root.path("runtime").has("executionMode"))return; // pre-8.0.14 Hub compatibility
        try{Snapshot.parse(root,alias,tenant);}catch(SQLException e){throw new java.io.IOException(e.getMessage());}
        com.dadp.common.sync.config.AtomicJsonFile.write(Paths.get(directory,"managed-runtime.json"),JSON,root);
    }
    static SQLException invalid(String message){return new SQLException(message,"08001");}
    static final class Snapshot {
        final long version;final String mode,vendor;final boolean enabled;
        final List<ExperimentalSqlRewriter> rewriters;
        final JsonNode root;
        Snapshot(long version,String mode,String vendor,boolean enabled,List<ExperimentalSqlRewriter> rs,JsonNode root){
            this.version=version;this.mode=mode;this.vendor=vendor;this.enabled=enabled;this.rewriters=Collections.unmodifiableList(rs);this.root=root.deepCopy();
        }
        static Snapshot parse(JsonNode root,String alias,String tenant)throws SQLException {
            if(!alias.equals(root.path("datasource").path("alias").asText())||!alias.equals(root.path("wrapper").path("alias").asText())||!tenant.equals(root.path("wrapper").path("tenantId").asText()))
                throw invalid("Hub datasource identity mismatch");
            if(!root.path("runtimeVersion").isIntegralNumber()||!root.path("runtimeVersion").canConvertToLong()||root.path("runtimeVersion").asLong()<0)throw invalid("Invalid Hub runtime version");
            JsonNode runtime=root.path("runtime");
            String mode=runtime.path("executionMode").asText("engine");
            if(!"engine".equals(mode)&&!"plugin".equals(mode))throw invalid("Invalid Hub datasource execution mode");
            if(runtime.path("failOpen").asBoolean(false))throw invalid("Managed datasource requires failOpen=false");
            String vendor=root.path("datasource").path("vendor").asText().toLowerCase(Locale.ROOT);
            if("postgres".equals(vendor))vendor="postgresql";
            if("plugin".equals(mode)&&!"postgresql".equals(vendor))throw invalid("This DB supports Engine mode only");
            List<ExperimentalSqlRewriter> rewriters=new ArrayList<>();
            if("plugin".equals(mode)){
                JsonNode schema=root.path("schemaJson");
                try{
                    JsonNode tables=schema.isTextual()?JSON.readTree(schema.asText()).path("tables"):root.path("schemaSnapshot").path("tables");
                    if(!tables.isArray())throw invalid("Hub schema snapshot is required for Plugin mode");
                    Map<String,String> bindings=new LinkedHashMap<>();
                    for(JsonNode b:root.path("policyBindings")){
                        if(!"ACTIVE".equals(b.path("status").asText("ACTIVE")))continue;
                        String code=b.path("policyCode").asText();
                        if(!code.matches("[A-Z0-9]{8}"))throw invalid("Invalid Hub policy code");
                        String key=b.path("schemaName").asText()+"."+b.path("tableName").asText()+"."+b.path("columnName").asText();
                        if(bindings.put(key,code)!=null)throw invalid("Duplicate Hub column policy binding");
                    }
                    for(JsonNode table:tables){
                        String sc=table.path("schemaName").asText(root.path("datasource").path("schemaName").asText());
                        String name=table.path("tableName").asText();
                        Map<String,String> columns=new LinkedHashMap<>();boolean protectedColumn=false;
                        for(JsonNode col:table.path("columns")){
                            String column=col.path("columnName").asText(),code=bindings.get(sc+"."+name+"."+column);
                            columns.put(column,code);protectedColumn|=code!=null;
                        }
                        if(!protectedColumn)continue;
                        ExperimentalSqlRewriter.FixtureScope scope=new ExperimentalSqlRewriter.FixtureScope(sc,name,columns);
                        rewriters.add("mysql".equals(vendor)?ExperimentalSqlRewriter.forMysql(scope):ExperimentalSqlRewriter.forManagedPostgresql(scope));
                    }
                    if(rewriters.isEmpty())throw invalid("Plugin mode requires a schema and active column policy mappings in Hub");
                }catch(SQLException e){throw e;}catch(Exception e){throw invalid("Invalid Hub schema snapshot");}
            }
            return new Snapshot(root.path("runtimeVersion").asLong(),mode,vendor,runtime.path("enabled").asBoolean(true),rewriters,root);
        }
        ExperimentalSqlRewriter rewriter(String sql)throws SQLException {
            ExperimentalSqlRewriter found=null;
            for(ExperimentalSqlRewriter r:rewriters){
                try{r.rewrite(sql);}catch(SQLException unsupported){continue;}
                if(found!=null)throw invalid("Ambiguous Plugin table; qualify the schema in SQL");
                found=r;
            }
            if(found==null)throw new SQLFeatureNotSupportedException("SQL is outside the Hub-managed Plugin table/column scope","0A000");
            return found;
        }
    }
}
