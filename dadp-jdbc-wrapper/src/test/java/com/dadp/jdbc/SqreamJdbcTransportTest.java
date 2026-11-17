package com.dadp.jdbc;

import java.sql.SQLException;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqreamJdbcTransportTest {
    final String url="jdbc:Sqream://127.0.0.1:5501/master;cluster=false;ssl=false;service=rea817";
    @Test void defaultsBothInitialAliasModesToSupportedEightMiBUrlOption() throws Exception {
        for(String mode:new String[]{"engine","plugin"}){
            ManagedDatasourceRuntime.Snapshot snapshot=ManagedDatasourceRuntime.Snapshot.parse(
                    new SqreamManagedSqlTest().snapshot(mode),"sq","agent");
            assertEquals(url+";insertBuffer=8388608",SqreamJdbcTransport.nativeUrl(url,new Properties(),snapshot.vendor));
        }
    }
    @Test void otherVendorsAndUnmanagedNativeVendorMismatchesRemainUnchanged() throws Exception {
        for(String vendor:new String[]{"postgresql","mysql","oracle","custom"})
            assertEquals(url,SqreamJdbcTransport.nativeUrl(url,new Properties(),vendor));
        String pg="jdbc:postgresql://localhost/db";
        assertEquals(pg,SqreamJdbcTransport.nativeUrl(pg,new Properties(),"sqream"));
    }
    @Test void explicitNativeUrlWinsAndPreservesDuplicateOptionOrder() throws Exception {
        Properties props=new Properties();props.setProperty("insertBuffer","16777216");
        String custom=url+";insertBuffer=33554432";
        assertEquals(custom,SqreamJdbcTransport.nativeUrl(custom,props,"sqream"));
        String duplicate=custom+";insertBuffer=67108864";
        assertEquals(duplicate,SqreamJdbcTransport.nativeUrl(duplicate,props,"sqream"));
        assertEquals("16777216",props.getProperty("insertBuffer"));
    }
    @Test void explicitWrapperPropertyIsNormalizedOnlyWhenUrlOptionIsAbsent() throws Exception {
        Properties props=new Properties();props.setProperty("insertBuffer","16777216");props.setProperty("password","unprinted");
        assertEquals(url+";insertBuffer=16777216",SqreamJdbcTransport.nativeUrl(url,props,"sqream"));
        assertEquals(2,props.size());assertEquals("16777216",props.getProperty("insertBuffer"));
    }
    @Test void ignoredWrongCaseOptionCannotSuppressSafeDefault() throws Exception {
        String wrong=url+";InsertBuffer=33554432";
        assertEquals(wrong+";insertBuffer=8388608",SqreamJdbcTransport.nativeUrl(wrong,null,"sqream"));
        assertEquals(url+";insertBuffer=8388608",SqreamJdbcTransport.nativeUrl(url+";",null,"sqream"));
        String once=SqreamJdbcTransport.nativeUrl(url,null,"sqream");
        assertEquals(once,SqreamJdbcTransport.nativeUrl(once,null,"sqream"));
    }
    @Test void malformedBufferValueCannotInjectAdditionalUrlOptionsOrDisableBudget() {
        for(String value:new String[]{"0","-1","2147483648","8;service=other","","8 MiB"}){
            Properties props=new Properties();props.setProperty("insertBuffer",value);
            SQLException e=assertThrows(SQLException.class,()->SqreamJdbcTransport.nativeUrl(url,props,"sqream"));
            assertEquals("08001",e.getSQLState());assertFalse(e.getMessage().contains(value+";"));
            if(!value.contains(";"))assertThrows(SQLException.class,()->SqreamJdbcTransport.nativeUrl(url+";insertBuffer="+value,new Properties(),"sqream"));
        }
    }
}
