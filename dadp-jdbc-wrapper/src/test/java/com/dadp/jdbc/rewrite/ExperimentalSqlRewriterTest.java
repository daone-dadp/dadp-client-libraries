package com.dadp.jdbc.rewrite;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.update.Update;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ExperimentalSqlRewriterTest {
    private static Map<String, String> columns() {
        Map<String, String> columns = new HashMap<>();
        columns.put("id", null);
        columns.put("note", null);
        columns.put("email", "pii");
        columns.put("phone", "contact");
        return columns;
    }

    private static ExperimentalSqlRewriter fixture() throws SQLException {
        return ExperimentalSqlRewriter.forFixture(
                new ExperimentalSqlRewriter.FixtureScope("app", "users", columns()),
                new ExperimentalSqlRewriter.FixtureFunctions("fixture_encrypt", "fixture_decrypt"));
    }

    static Stream<Arguments> golden() {
        return Stream.of(
                Arguments.of("insert into app.users (id, email, note, phone) values (?, ?, 'why?', ?)",
                        "INSERT INTO app.users (id, email, note, phone) VALUES (?, fixture_encrypt(?, 'pii'), 'why?', fixture_encrypt(?, 'contact'))"),
                Arguments.of("INSERT INTO app.users (email, phone, id) VALUES (NULL, ?, 7)",
                        "INSERT INTO app.users (email, phone, id) VALUES (NULL, fixture_encrypt(?, 'contact'), 7)"),
                Arguments.of("INSERT INTO app.users (email) VALUES ('a''b,?')",
                        "INSERT INTO app.users (email) VALUES (fixture_encrypt('a''b,?', 'pii'))"),
                Arguments.of("UPDATE app.users SET phone = ?, id = ?, email = NULL, note = '?,email'",
                        "UPDATE app.users SET phone = fixture_encrypt(?, 'contact'), id = ?, email = NULL, note = '?,email'"),
                Arguments.of("UPDATE app.users SET email = 'x', phone = ?",
                        "UPDATE app.users SET email = fixture_encrypt('x', 'pii'), phone = fixture_encrypt(?, 'contact')"),
                Arguments.of("SELECT id, email, phone AS mobile FROM app.users",
                        "SELECT id, fixture_decrypt(email, 'pii') AS email, fixture_decrypt(phone, 'contact') AS mobile FROM app.users"),
                Arguments.of("SELECT u.email addr, u.id, u.phone AS mobile FROM app.users u",
                        "SELECT fixture_decrypt(u.email, 'pii') addr, u.id, fixture_decrypt(u.phone, 'contact') AS mobile FROM app.users u"),
                Arguments.of("SELECT app.users.email FROM app.users",
                        "SELECT fixture_decrypt(app.users.email, 'pii') AS email FROM app.users"),
                Arguments.of("INSERT INTO app.users (id, note) VALUES (?, NULL)",
                        "INSERT INTO app.users (id, note) VALUES (?, NULL)"));
    }

    @ParameterizedTest
    @MethodSource("golden")
    void rewritesAstGoldenWithoutChangingBindOrderOrCount(String sql, String expected) throws Exception {
        String result = fixture().rewrite(sql);
        assertEquals(expected, result);
        assertEquals(bindSlots(sql), bindSlots(result));
    }

    private static List<String> bindSlots(String sql) throws Exception {
        Statement statement = CCJSqlParserUtil.parse(sql);
        List<String> slots = new ArrayList<>();
        if (statement instanceof Insert) {
            Insert insert = (Insert) statement;
            ExpressionList<?> values = ((Values) insert.getSelect()).getExpressions();
            for (int i = 0; i < values.size(); i++) {
                bindSlot(slots, insert.getColumns().get(i).getColumnName(), values.get(i));
            }
        } else if (statement instanceof Update) {
            ((Update) statement).getUpdateSets().forEach(set ->
                    bindSlot(slots, set.getColumn(0).getColumnName(), set.getValue(0)));
        }
        return slots;
    }

    private static void bindSlot(List<String> slots, String column, Expression expression) {
        if (expression instanceof Parenthesis) {
            bindSlot(slots, column, ((Parenthesis) expression).getExpression());
        } else if (expression instanceof Function) {
            ((Function) expression).getParameters().forEach(value -> bindSlot(slots, column, value));
        } else if (expression instanceof JdbcParameter) {
            slots.add(column + ":" + ((JdbcParameter) expression).getIndex());
        }
    }

    @Test
    void keepsUntypedPlaceholdersWithoutCastsOrNewParameters() throws Exception {
        String result = fixture().rewrite("INSERT INTO app.users (email, id, phone) VALUES (?, ?, ?)");
        assertEquals(Arrays.asList("email:1", "id:2", "phone:3"), bindSlots(result));
        Insert insert = (Insert) CCJSqlParserUtil.parse(result);
        Expression wrapped = ((Values) insert.getSelect()).getExpressions().get(0);
        assertInstanceOf(JdbcParameter.class, ((Function) wrapped).getParameters().get(0));
        // JDBC setter types and NULL-strict DB evaluation require a later driver test.
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM app.users", "SELECT u.* FROM app.users u",
            "SELECT COUNT(email) FROM app.users", "SELECT email || phone FROM app.users",
            "SELECT DISTINCT email FROM app.users", "SELECT email FROM app.users ORDER BY email",
            "SELECT email FROM app.users GROUP BY email", "SELECT email FROM app.users LIMIT 1",
            "SELECT email FROM app.users WHERE id = ?", "SELECT email FROM app.users WHERE email = ?",
            "SELECT email FROM app.users FOR UPDATE", "SELECT email INTO other FROM app.users",
            "SELECT u.email FROM app.users u JOIN app.users v ON u.id = v.id",
            "SELECT email FROM app.users UNION SELECT email FROM app.users",
            "WITH x AS (SELECT email FROM app.users) SELECT email FROM x",
            "SELECT email FROM (SELECT email FROM app.users) u",
            "SELECT other.email FROM app.users", "SELECT users.email FROM app.users u",
            "SELECT unknown FROM app.users", "SELECT email FROM other.users",
            "SELECT email FROM users", "SELECT \"email\" FROM app.users",
            "SELECT email AS \"Email\" FROM app.users",
            "INSERT INTO app.users VALUES (?)",
            "INSERT INTO app.users (email) SELECT email FROM app.users",
            "INSERT INTO app.users (email) VALUES (?), (?)",
            "INSERT INTO app.users (email) VALUES (?) ON CONFLICT DO NOTHING",
            "INSERT INTO app.users (email) VALUES (?) ON DUPLICATE KEY UPDATE email = ?",
            "INSERT IGNORE INTO app.users (email) VALUES (?)",
            "INSERT INTO app.users (email) VALUES (?) RETURNING email",
            "INSERT INTO app.users (email) VALUES (COALESCE(?, 'x'))",
            "INSERT INTO app.users (email) VALUES (CAST(? AS VARCHAR))",
            "INSERT INTO app.users (email) VALUES (DEFAULT)",
            "INSERT INTO app.users (email) VALUES (:email)",
            "INSERT INTO app.users (email) VALUES (?1)",
            "INSERT INTO app.users (email, email) VALUES (?, ?)",
            "INSERT INTO app.users (email, phone) VALUES (?)",
            "INSERT INTO app.users (missing) VALUES (?)",
            "UPDATE app.users SET email = ? WHERE id = ?",
            "UPDATE app.users SET email = ? RETURNING email",
            "UPDATE app.users SET email = phone", "UPDATE app.users SET email = email || ?",
            "UPDATE app.users SET (email, phone) = (?, ?)",
            "UPDATE app.users SET email = ?, email = ?",
            "UPDATE app.users SET email = (SELECT email FROM app.users)",
            "DELETE FROM app.users", "CALL update_users(?)", "COPY app.users FROM STDIN",
            "SELECT email FROM app.users; DELETE FROM app.users",
            "INSERT INTO app.users (email) VALUES ('private'); SELECT 1",
            "INSERT INTO app.users (email) VALUES (fixture_encrypt(?, 'wrong'))",
            "SELECT fixture_decrypt(email, 'pii') FROM app.users"
    })
    void unsupportedSqlFailsClosed(String sql) throws Exception {
        SQLException error = assertThrows(SQLFeatureNotSupportedException.class, () -> fixture().rewrite(sql));
        assertEquals("0A000", error.getSQLState());
        assertEquals("SQL outside v8 fixture rewrite allowlist", error.getMessage());
        assertNull(error.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO app.users (email) VALUES (?)",
            "UPDATE app.users SET email = ?", "SELECT email FROM app.users"
    })
    void secondPassRejectsInsteadOfDoubleTransforming(String sql) throws Exception {
        ExperimentalSqlRewriter rewriter = fixture();
        String rewritten = rewriter.rewrite(sql);
        assertThrows(SQLFeatureNotSupportedException.class, () -> rewriter.rewrite(rewritten));
        assertEquals(rewritten, rewriter.rewrite(sql));
    }

    @Test
    void disabledIsExactPassthrough() throws Exception {
        String sql = "this is not SQL; 'private'";
        assertSame(sql, new ExperimentalSqlRewriter().rewrite(sql));
        assertNull(new ExperimentalSqlRewriter().rewrite(null));
    }

    @Test
    void errorsDoNotExposeSqlAndInputsAreBounded() throws Exception {
        for (String sql : Arrays.asList(null, "", "INSERT INTO app.users VALUES ('private", new String(new char[16385]))) {
            SQLException error = assertThrows(SQLException.class, () -> fixture().rewrite(sql));
            assertEquals("SQL outside v8 fixture rewrite allowlist", error.getMessage());
            assertNull(error.getCause());
        }
    }

    @Test
    void policySnapshotIsImmutableAndFunctionsAreExplicitlyInjected() throws Exception {
        Map<String, String> mapping = columns();
        ExperimentalSqlRewriter.FixtureScope scope = new ExperimentalSqlRewriter.FixtureScope("app", "users", mapping);
        mapping.put("email", null);
        ExperimentalSqlRewriter rewriter = ExperimentalSqlRewriter.forFixture(scope,
                new ExperimentalSqlRewriter.FixtureFunctions("trial_enc", "trial_dec"));
        assertEquals("SELECT trial_dec(email, 'pii') AS email FROM app.users",
                rewriter.rewrite("SELECT email FROM app.users"));
        assertThrows(SQLException.class, () -> new ExperimentalSqlRewriter.FixtureFunctions("x);DROP", "d"));
        assertThrows(SQLException.class, () -> new ExperimentalSqlRewriter.FixtureFunctions("same", "same"));
        mapping.put("email", "policy' OR 1=1");
        assertThrows(SQLException.class, () -> new ExperimentalSqlRewriter.FixtureScope("app", "users", mapping));
    }
}
