package com.dadp.jdbc.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dadp.jdbc.policy.SqlParser.SourceColumn;
import com.dadp.jdbc.policy.SqlParser.SqlParseResult;
import org.junit.jupiter.api.Test;

class SqlParserLineageTest {

    private final SqlParser parser = new SqlParser();

    @Test
    void tracesDirectColumnsThroughDerivedTableOutputAliases() {
        String sql = "SELECT merged.cusemail, merged.empemail "
                + "FROM (SELECT c.email AS cusemail, e.email AS empemail "
                + "FROM customer c JOIN employee e ON c.supportrepid = e.employeeid) merged";

        SqlParseResult result = parser.parse(sql);

        assertSource(result, 1, "customer", "email");
        assertSource(result, 2, "employee", "email");
    }

    @Test
    void tracesDerivedTableOutputAliasesWithoutAsKeyword() {
        String sql = "SELECT merged.cusemail FROM "
                + "(SELECT c.email cusemail FROM customer c) merged";

        SqlParseResult result = parser.parse(sql);

        assertSource(result, 1, "customer", "email");
    }

    @Test
    void tracesOnlyExactCteOutputColumnsAndLeavesAggregatesUnresolved() {
        String sql = "WITH cust_invoice AS ("
                + "SELECT inv.customerid, cus.supportrepid, SUM(inv.total) AS cusSum "
                + "FROM invoice inv JOIN customer cus ON inv.customerid = cus.customerid "
                + "GROUP BY inv.customerid, cus.supportrepid), "
                + "emp_total AS ("
                + "SELECT supportrepid, SUM(cusSum) AS empSum "
                + "FROM cust_invoice GROUP BY supportrepid) "
                + "SELECT cus.email AS customer, ci.cusSum, emp.email AS employee, et.empSum "
                + "FROM customer cus "
                + "LEFT JOIN cust_invoice ci ON cus.customerid = ci.customerid "
                + "LEFT JOIN employee emp ON cus.supportrepid = emp.employeeid "
                + "LEFT JOIN emp_total et ON cus.supportrepid = et.supportrepid";

        SqlParseResult result = parser.parse(sql);

        assertSource(result, 1, "customer", "email");
        assertNull(result.getSourceColumn(2));
        assertSource(result, 3, "employee", "email");
        assertNull(result.getSourceColumn(4));
    }

    @Test
    void tracesDirectColumnsThroughMultipleCteLevels() {
        String sql = "WITH first_level AS (SELECT c.email AS source_email FROM customer c), "
                + "second_level AS (SELECT f.source_email AS inherited_email FROM first_level f) "
                + "SELECT s.inherited_email FROM second_level s";

        SqlParseResult result = parser.parse(sql);

        assertSource(result, 1, "customer", "email");
    }

    @Test
    void tracesCteColumnsRenamedByExplicitColumnList() {
        String sql = "WITH customer_contact(contact_email) AS (SELECT c.email FROM customer c) "
                + "SELECT cc.contact_email FROM customer_contact cc";

        SqlParseResult result = parser.parse(sql);

        assertSource(result, 1, "customer", "email");
    }

    @Test
    void leavesDuplicateDerivedOutputAliasesUnresolved() {
        String sql = "SELECT merged.email FROM ("
                + "SELECT c.email AS email, e.email AS email "
                + "FROM customer c JOIN employee e ON c.supportrepid = e.employeeid) merged";

        SqlParseResult result = parser.parse(sql);

        assertNotNull(result);
        assertNull(result.getSourceColumn(1));
        assertTrue(result.isLineageTracked(1));
    }

    @Test
    void leavesQualifiedWildcardColumnsAvailableForMetadataResolution() {
        SqlParseResult result = parser.parse("SELECT c.* FROM customer c");

        assertNotNull(result);
        assertFalse(result.isLineageTracked(1));
    }

    private static void assertSource(SqlParseResult result, int index, String table, String column) {
        assertNotNull(result);
        SourceColumn source = result.getSourceColumn(index);
        assertNotNull(source, "column " + index + " must have exact source lineage");
        assertEquals(table, source.getTableName());
        assertEquals(column, source.getColumnName());
    }
}
