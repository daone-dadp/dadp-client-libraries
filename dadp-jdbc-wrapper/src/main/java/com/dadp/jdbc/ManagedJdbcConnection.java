package com.dadp.jdbc;

import com.dadp.jdbc.config.ProxyConfig;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;

/** Hub is authoritative; mode changes never cause write replay or plaintext fallback. */
final class ManagedJdbcConnection implements InvocationHandler {
    private final Connection raw;
    private final String url;
    private final Map<String,String> params;
    private final Properties properties;
    private final ManagedDatasourceRuntime runtime;
    private Connection self;
    private DadpProxyConnection engine;
    private long engineVersion=-1,transactionVersion=-1;
    private boolean closed,rollbackOnly;
    private volatile boolean autoCommit;
    private ManagedJdbcConnection(Connection raw,String url,Map<String,String> params,Properties properties,ManagedDatasourceRuntime runtime)throws SQLException{
        this.raw=raw;this.url=url;this.params=params;this.properties=properties;this.runtime=runtime;
        try{autoCommit=raw.getAutoCommit();}catch(SQLFeatureNotSupportedException unsupported){autoCommit=true;}
    }
    static Connection wrap(Connection raw,String url,Map<String,String> params,Properties props,ManagedDatasourceRuntime runtime)throws SQLException{
        String vendor=runtime.snapshot().vendor;
        String nativeUrl=DadpJdbcUrlSupport.extractActualUrl(url).toLowerCase(Locale.ROOT);
        if(!"custom".equals(vendor)&&!nativeUrl.startsWith("jdbc:"+vendor+":") && !("postgresql".equals(vendor)&&nativeUrl.startsWith("jdbc:postgresql:")))
            throw ManagedDatasourceRuntime.invalid("Registered Hub DB vendor does not match the JDBC datasource");
        ManagedJdbcConnection h=new ManagedJdbcConnection(raw,url,params,props,runtime);
        h.self=proxy(Connection.class,h);return h.self;
    }
    @SuppressWarnings("unchecked")
    static <T>T proxy(Class<T> type,InvocationHandler h){return (T)Proxy.newProxyInstance(ManagedJdbcConnection.class.getClassLoader(),new Class<?>[]{type},h);}
    static Object invoke(Method m,Object target,Object[] args)throws Throwable{
        try{return m.invoke(target,args);}catch(InvocationTargetException e){throw e.getTargetException();}
    }
    // Internal recovery-ledger access only; public JDBC unwrap remains guarded.
    static Connection recoveryDelegate(Connection wrapped)throws SQLException{
        if(Proxy.isProxyClass(wrapped.getClass())){
            InvocationHandler handler=Proxy.getInvocationHandler(wrapped);
            if(handler instanceof ManagedJdbcConnection)return ((ManagedJdbcConnection)handler).raw;
        }
        return V8PostgresqlConnection.recoveryDelegate(wrapped);
    }
    private Connection route(ManagedDatasourceRuntime.Snapshot s,String sql,boolean autoKeys)throws SQLException{
        if("plugin".equals(s.mode)){
            if("sqream".equals(s.vendor)&&autoKeys)throw unsupported();
            if(sql.matches("(?is)\\s*SELECT\\s+1(?:\\s+AS\\s+[A-Za-z_][A-Za-z0-9_]*)?\\s*;?\\s*"))
                return V8PostgresqlConnection.wrap(raw,new com.dadp.jdbc.rewrite.ExperimentalSqlRewriter(),false,null);
            com.dadp.jdbc.rewrite.ExperimentalSqlRewriter rewriter=s.rewriter(sql);
            String generated=null;
            if(autoKeys){
                List<String> keys=new ArrayList<>();
                try(ResultSet rs=raw.getMetaData().getPrimaryKeys(raw.getCatalog(),"oracle".equals(s.vendor)?rewriter.getSchemaName().toUpperCase(Locale.ROOT):rewriter.getSchemaName(),"oracle".equals(s.vendor)?rewriter.getTableName().toUpperCase(Locale.ROOT):rewriter.getTableName())){
                    while(rs.next())keys.add(rs.getString("COLUMN_NAME"));
                }
                if(keys.size()!=1)throw new SQLFeatureNotSupportedException("Generated keys require one unprotected primary key","0A000");
                rewriter.checkGeneratedKeyColumns(new String[]{keys.get(0)});generated=keys.get(0);
            }
            return V8PostgresqlConnection.wrap(raw,rewriter,!"sqream".equals(s.vendor),generated);
        }
        if(engine==null)engine=new DadpProxyConnection(raw,url,params,properties,true);
        if(engineVersion!=s.version){engine.reloadManagedRuntime(s.version);engineVersion=s.version;}
        return engine;
    }
    private SQLTransactionRollbackException rollbackRequired(){
        return new SQLTransactionRollbackException("Hub datasource configuration changed; rollback the complete transaction before continuing","40001");
    }
    private ManagedDatasourceRuntime.Snapshot active()throws SQLException{
        if(!autoCommit&&rollbackOnly)throw rollbackRequired();
        ManagedDatasourceRuntime.Snapshot s;
        try{s=runtime.snapshot();}
        catch(SQLException denied){
            if(!autoCommit&&transactionVersion>=0)rollbackOnly=true;
            throw denied;
        }
        if(!autoCommit){
            if(transactionVersion<0)transactionVersion=s.version;
            if(transactionVersion!=s.version){rollbackOnly=true;throw rollbackRequired();}
        }
        return s;
    }
    private void checkTransactionBoundary()throws SQLException{
        if(!autoCommit&&(transactionVersion>=0||rollbackOnly))active();
    }
    public Object invoke(Object ignored,Method method,Object[] args)throws Throwable{
        String name=method.getName();
        if(method.getDeclaringClass()==Object.class){
            if(name.equals("toString"))return "Hub-managed DADP JDBC connection";
            if(name.equals("hashCode"))return System.identityHashCode(self);
            if(name.equals("equals"))return self==args[0];
        }
        if(name.equals("unwrap")){if(((Class<?>)args[0]).isInstance(self))return self;throw unsupported();}
        if(name.equals("isWrapperFor"))return ((Class<?>)args[0]).isInstance(self);
        if(name.equals("close")){if(!closed){closed=true;if(engine!=null)engine.close();else raw.close();}return null;}
        if(name.equals("isClosed"))return closed||raw.isClosed();
        if(closed)throw new SQLException("Connection is closed","08003");
        if(name.equals("commit")){
            runtime.gate.readLock().lock();
            try{
                checkTransactionBoundary();
                Object out=invoke(method,raw,args);transactionVersion=-1;rollbackOnly=false;return out;
            }finally{runtime.gate.readLock().unlock();}
        }
        if(name.equals("rollback")){
            Object out=invoke(method,raw,args);
            // A savepoint rollback does not end the surrounding transaction.
            if(args==null||args.length==0){transactionVersion=-1;rollbackOnly=false;}
            return out;
        }
        if(name.equals("setAutoCommit")){
            runtime.gate.readLock().lock();
            try{
                boolean enable=Boolean.TRUE.equals(args[0]);
                if(enable&&!autoCommit)checkTransactionBoundary();
                Object out=invoke(method,raw,args);autoCommit=enable;
                if(autoCommit){transactionVersion=-1;rollbackOnly=false;}return out;
            }finally{runtime.gate.readLock().unlock();}
        }
        if(name.equals("setSavepoint")){
            runtime.gate.readLock().lock();
            try{active();return invoke(method,raw,args);}
            finally{runtime.gate.readLock().unlock();}
        }
        if(name.equals("prepareCall"))throw unsupported();
        if(name.equals("setSchema")||name.equals("setCatalog")){
            ManagedDatasourceRuntime.Snapshot s=active();
            if("sqream".equals(s.vendor)&&"plugin".equals(s.mode))throw unsupported();
            return invoke(method,engine!=null?engine:raw,args);
        }
        if(name.equals("createStatement")||name.equals("prepareStatement")){
            runtime.gate.readLock().lock();
            try{
                active();
                // SQL is prepared only when needed; typed plaintext binds remain memory-only.
                StatementHandler handler=new StatementHandler(method,args);
                return name.equals("prepareStatement")?proxy(PreparedStatement.class,handler):proxy(Statement.class,handler);
            }finally{runtime.gate.readLock().unlock();}
        }
        if(name.equals("getMetaData")){
            DatabaseMetaData metadata=raw.getMetaData();
            return proxy(DatabaseMetaData.class,(o,m,a)->{
                if(m.getName().equals("getConnection"))return self;
                if(m.getName().equals("unwrap"))throw unsupported();
                if(m.getName().equals("isWrapperFor"))return false;
                Object result=invoke(m,metadata,a);
                return result instanceof ResultSet?guardResult((ResultSet)result,null,-1):result;
            });
        }
        return invoke(method,raw,args);
    }
    private ResultSet guardResult(ResultSet delegate,Statement owner,long version){
        return proxy(ResultSet.class,(o,m,a)->{
            String n=m.getName();
            if(n.equals("getStatement"))return owner;
            if(n.equals("unwrap"))throw unsupported();
            if(n.equals("isWrapperFor"))return false;
            if(n.equals("close")||n.equals("isClosed"))return invoke(m,delegate,a);
            runtime.gate.readLock().lock();
            try{
                if(version>=0&&active().version!=version)throw new SQLTransientException("Hub datasource configuration changed during the result; repeat the read","40001");
                Object value=invoke(m,delegate,a);
                if(value instanceof Statement)throw unsupported();
                return value;
            }finally{runtime.gate.readLock().unlock();}
        });
    }
    private final class StatementHandler implements InvocationHandler {
        private final Method prepare;
        private final Object[] prepareArgs;
        private final LinkedHashMap<String,Call> setters=new LinkedHashMap<>();
        private final List<List<Call>> batches=new ArrayList<>();
        private Statement delegate,selfStatement;
        private long version=-1;
        private boolean stopped;
        StatementHandler(Method prepare,Object[] args){this.prepare=prepare;this.prepareArgs=args==null?null:args.clone();}
        private String sql(Object[] args)throws SQLException{
            if(prepare.getName().equals("prepareStatement"))return (String)prepareArgs[0];
            if(args!=null&&args.length>0&&args[0] instanceof String)return (String)args[0];
            throw unsupported();
        }
        private void prepareFor(ManagedDatasourceRuntime.Snapshot s,String sql)throws Throwable{
            // SQream JDBC 6.6 closes its server statement after a prepared execution.
            // Keep the application PS and typed binds; create a native PS for each NEW execution.
            // This never replays an execution that has already started or failed.
            boolean singleUse="sqream".equals(s.vendor)&&"plugin".equals(s.mode)
                &&prepare.getName().equals("prepareStatement");
            if(delegate!=null&&version==s.version&&!singleUse)return;
            if(delegate!=null)delegate.close();
            boolean autoKeys=prepare.getName().equals("prepareStatement")&&prepareArgs.length==2
                &&Integer.valueOf(Statement.RETURN_GENERATED_KEYS).equals(prepareArgs[1]);
            Connection c=route(s,sql,autoKeys);
            delegate=(Statement)ManagedJdbcConnection.invoke(prepare,c,prepareArgs);version=s.version;
        }
        public Object invoke(Object statement,Method method,Object[] args)throws Throwable{
            selfStatement=(Statement)statement;String name=method.getName();
            if(method.getDeclaringClass()==Object.class){
                if(name.equals("toString"))return "Hub-managed DADP statement";
                if(name.equals("hashCode"))return System.identityHashCode(statement);
                if(name.equals("equals"))return statement==args[0];
            }
            if(name.equals("getConnection"))return self;
            if(name.equals("unwrap")){if(((Class<?>)args[0]).isInstance(statement))return statement;throw unsupported();}
            if(name.equals("isWrapperFor"))return ((Class<?>)args[0]).isInstance(statement);
            if(name.equals("close")){stopped=true;setters.clear();batches.clear();if(delegate!=null)delegate.close();return null;}
            if(name.equals("isClosed"))return stopped;
            if(name.equals("cancel")){if(delegate!=null)delegate.cancel();return null;}
            if(stopped)throw new SQLException("Statement is closed","07000");
            if(name.equals("clearParameters")){
                setters.entrySet().removeIf(e->e.getKey().startsWith("parameter:"));
                // Recreate the native statement so removed binds cannot survive.
                // Some JDBC vendors (including SQream) do not implement clearParameters.
                if(delegate!=null){delegate.close();delegate=null;}
                return null;
            }
            if(name.startsWith("set")){
                Object[] copy=copyArgs(args);
                String key=method.getDeclaringClass()==PreparedStatement.class&&args!=null&&args.length>0&&args[0] instanceof Integer?"parameter:"+args[0]:"option:"+name;
                setters.put(key,new Call(method,copy));return null;
            }
            if(name.equals("addBatch")){
                if(args!=null&&args.length>0)throw unsupported();
                batches.add(new ArrayList<>(setters.values()));return null;
            }
            if(name.equals("clearBatch")){batches.clear();if(delegate!=null)delegate.clearBatch();return null;}
            runtime.gate.readLock().lock();
            try{
                ManagedDatasourceRuntime.Snapshot s=active();
                if(name.startsWith("execute")){
                    if(prepare.getName().equals("prepareStatement")&&args!=null&&args.length>0)throw unsupported();
                    String text=sql(args);
                    if(prepare.getName().equals("createStatement")&&delegate!=null){delegate.close();delegate=null;}
                    prepareFor(s,text);
                    // Reapply plaintext binds before SQL, including after an Engine/Plugin switch.
                    if(name.equals("executeBatch")||name.equals("executeLargeBatch")){
                        delegate.clearBatch();
                        for(List<Call> batch:batches){((PreparedStatement)delegate).clearParameters();for(Call call:batch)call.apply(delegate);((PreparedStatement)delegate).addBatch();}
                    }else {
                        // Every retained parameter is reapplied; clearParameters above
                        // disposes the delegate when parameters are actually removed.
                        for(Call call:setters.values())call.apply(delegate);
                    }
                    Object result=ManagedJdbcConnection.invoke(method,delegate,args);
                    if(name.equals("executeBatch")||name.equals("executeLargeBatch"))batches.clear();
                    return result instanceof ResultSet?guardResult((ResultSet)result,selfStatement,version):result;
                }
                if(delegate==null){
                    if(name.equals("getResultSet")||name.equals("getWarnings"))return null;
                    if(name.equals("getUpdateCount"))return -1;
                    if(name.equals("getLargeUpdateCount"))return -1L;
                    if(name.equals("clearWarnings")||name.equals("cancel"))return null;
                    prepareFor(s,sql(args));
                }
                if(version!=s.version)throw new SQLTransientException("Hub datasource configuration changed; execute the statement again","40001");
                Object result=ManagedJdbcConnection.invoke(method,delegate,args);
                return result instanceof ResultSet?guardResult((ResultSet)result,selfStatement,version):result;
            }finally{runtime.gate.readLock().unlock();}
        }
    }
    private static Object[] copyArgs(Object[] args)throws SQLException{
        if(args==null)return null;
        Object[] copy=args.clone();
        for(int i=0;i<copy.length;i++){
            Object value=copy[i];
            if(value instanceof byte[])copy[i]=((byte[])value).clone();
            else if(value instanceof java.sql.Timestamp){java.sql.Timestamp t=(java.sql.Timestamp)value;java.sql.Timestamp d=new java.sql.Timestamp(t.getTime());d.setNanos(t.getNanos());copy[i]=d;}
            else if(value instanceof java.sql.Date)copy[i]=new java.sql.Date(((java.sql.Date)value).getTime());
            else if(value instanceof java.sql.Time)copy[i]=new java.sql.Time(((java.sql.Time)value).getTime());
            else if(value instanceof Calendar)copy[i]=((Calendar)value).clone();
            else if(value instanceof java.io.InputStream||value instanceof java.io.Reader||value instanceof Blob||value instanceof Clob||value instanceof SQLXML||value instanceof java.sql.Array||value instanceof Ref)throw unsupported();
        }
        return copy;
    }
    private static final class Call {
        final Method method;final Object[] args;
        Call(Method method,Object[] args){this.method=method;this.args=args;}
        void apply(Object target)throws Throwable{invoke(method,target,args);}
    }
    private static SQLFeatureNotSupportedException unsupported(){return new SQLFeatureNotSupportedException("Unsupported operation on a Hub-managed DADP datasource","0A000");}
}
