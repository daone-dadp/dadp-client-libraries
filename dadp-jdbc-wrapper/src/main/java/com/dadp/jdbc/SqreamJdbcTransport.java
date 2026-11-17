package com.dadp.jdbc;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Properties;

/** Bounded SQream transport buffers for either initial mode of a Hub-managed Alias. */
final class SqreamJdbcTransport {
    static final int DEFAULT_INSERT_BUFFER_BYTES=8*1024*1024;
    private SqreamJdbcTransport() {}
    static String nativeUrl(String url,Properties properties,String vendor)throws SQLException {
        if(!"sqream".equals(vendor)||url==null||!url.toLowerCase(Locale.ROOT).startsWith("jdbc:sqream:"))return url;
        boolean explicitUrl=false;
        for(String option:url.split(";",-1)){
            int equal=option.indexOf('=');
            // SQream JDBC 6.6 parameter names are case sensitive.
            if(equal>=0&&"insertBuffer".equals(option.substring(0,equal))){
                positiveBytes(option.substring(equal+1));explicitUrl=true;
            }
        }
        // Preserve native URL ordering and precedence, including duplicate valid options.
        if(explicitUrl)return url;
        String configured=properties==null?null:properties.getProperty("insertBuffer");
        int bytes=configured==null?DEFAULT_INSERT_BUFFER_BYTES:positiveBytes(configured);
        // JDBC 6.6 ignores this option in Properties. Normalize a Wrapper caller's
        // explicit property into the supported native URL form, without changing info.
        return url+(url.endsWith(";")?"":";")+"insertBuffer="+bytes;
    }
    private static int positiveBytes(String value)throws SQLException {
        try{
            if(value==null||!value.matches("[+]?[0-9]+"))throw new NumberFormatException();
            int bytes=Integer.parseInt(value);
            if(bytes<=0)throw new NumberFormatException();
            return bytes;
        }catch(NumberFormatException invalid){
            throw new SQLException("SQream insertBuffer must be a positive integer byte count","08001");
        }
    }
}
