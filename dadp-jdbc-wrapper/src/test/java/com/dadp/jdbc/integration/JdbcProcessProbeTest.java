package com.dadp.jdbc.integration;

import java.sql.SQLException;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JdbcProcessProbeTest {
    @Test void mysqlDenialAndTargetVersionGatesAreStrict() {
        JdbcProcessProbe.requireDenial(new SQLException("", "45000"), true);
        for (String state : new String[]{"HY000", "42000", "08S01", "38000"}) {
            assertThrows(AssertionError.class, () -> JdbcProcessProbe.requireDenial(new SQLException("", state), true));
        }
        JdbcProcessProbe.requireVersion(true, 8, 4);
        JdbcProcessProbe.requireVersion(false, 17, 1);
        assertThrows(AssertionError.class, () -> JdbcProcessProbe.requireVersion(true, 8, 0));
        assertThrows(AssertionError.class, () -> JdbcProcessProbe.requireVersion(true, 9, 4));
        assertThrows(AssertionError.class, () -> JdbcProcessProbe.requireVersion(false, 16, 0));
    }
    @Test void validatesMappingAndLeavesOriginalPropertiesUnchanged() {
        Properties p = new Properties(); p.setProperty("user", "fixture");
        Properties mapped = JdbcProcessProbe.mapping(p, "probe", "ABCD2345", "PREFIX01", "DENIED01");
        assertEquals("pg-evaluation", mapped.getProperty("dadp.v8.mode"));
        assertEquals("plain", mapped.getProperty("dadp.v8.column.id"));
        assertEquals("text:PREFIX01", mapped.getProperty("dadp.v8.column.partial_value"));
        assertEquals("fixture", mapped.getProperty("user"));
        assertEquals(1, p.size());
        assertThrows(AssertionError.class, () -> JdbcProcessProbe.policy("bad';--"));
    }
    @Test void failureMustNotMistakeTransportSyntaxOrConfigurationErrorForDenial() {
        JdbcProcessProbe.requireDenial(new SQLException("", "38000"));
        JdbcProcessProbe.requireDenial(new SQLException("", "42501"));
        for (String state : new String[]{null, "08006", "42601", "42883", "55000", "25P02"}) {
            assertThrows(AssertionError.class, () -> JdbcProcessProbe.requireDenial(new SQLException("", state)));
        }
    }
    @Test void refusesMissingArgumentsBeforeAnyDatabaseAccess() {
        assertThrows(AssertionError.class, () -> JdbcProcessProbe.run(new String[0]));
    }
}
