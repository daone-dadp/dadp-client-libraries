package com.dadp.jdbc;

import com.dadp.common.sync.policy.PolicyResolver;
import com.dadp.jdbc.policy.SqlParser;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.*;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;

/** Fail closed where the Engine's positional bind plan cannot prove a safe write. */
final class EnginePreparedSqlGuard {
    private EnginePreparedSqlGuard() { }

    static Set<Integer> validate(String sql, DadpProxyConnection connection) throws SQLException {
        PolicyResolver policies = connection.getPolicyResolver();
        if (policies == null || policies.getAllMappings().isEmpty()) return Collections.emptySet();
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (statement instanceof PlainSelect) {
                PlainSelect select = (PlainSelect) statement;
                require(empty(select.getWithItemsList()) && empty(select.getIntoTables()));
                return Collections.emptySet();
            }
            Table table;
            List<Column> columns = new ArrayList<>();
            List<?> expressions;
            if (statement instanceof Insert) {
                Insert insert = (Insert) statement;
                require(insert.getSelect() instanceof Values && insert.getColumns() != null);
                require(empty(insert.getWithItemsList()) && empty(insert.getDuplicateUpdateSets())
                        && empty(insert.getSetUpdateSets()) && insert.getConflictAction() == null
                        && insert.getConflictTarget() == null && insert.getReturningClause() == null
                        && insert.getOutputClause() == null && !insert.isModifierIgnore());
                table = insert.getTable(); columns.addAll(insert.getColumns());
                expressions = insert.getValues().getExpressions();
                if (columns.size() == 1 && expressions.size() == 1
                        && expressions.get(0) instanceof net.sf.jsqlparser.expression.Parenthesis) {
                    expressions = Collections.singletonList(((net.sf.jsqlparser.expression.Parenthesis) expressions.get(0)).getExpression());
                }
            } else if (statement instanceof Update) {
                Update update = (Update) statement;
                require(empty(update.getWithItemsList()) && empty(update.getJoins()) && empty(update.getStartJoins())
                        && update.getFromItem() == null && update.getReturningClause() == null
                        && update.getOutputClause() == null && update.getLimit() == null
                        && empty(update.getOrderByElements()));
                table = update.getTable();
                List<Object> values = new ArrayList<>();
                for (UpdateSet set : update.getUpdateSets()) {
                    require(set.getColumns().size() == 1 && set.getValues().size() == 1);
                    columns.add(set.getColumn(0)); values.add(set.getValue(0));
                }
                expressions = values;
            } else throw denied();
            require(table != null && table.getAlias() == null && identifier(table.getName())
                    && (table.getSchemaName() == null || identifier(table.getSchemaName())));
            require(!columns.isEmpty() && expressions != null && columns.size() == expressions.size());
            // Cross-check the existing bind planner, not just the independent AST.
            SqlParser.SqlParseResult bind = new SqlParser().parse(sql);
            require(bind != null && table.getName().equalsIgnoreCase(bind.getTableName())
                    && Objects.equals(table.getSchemaName(), bind.getSchemaName())
                    && bind.getColumns() != null && columns.size() == bind.getColumns().length);
            Set<Integer> protectedBinds = new HashSet<>();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < columns.size(); i++) {
                String column = columns.get(i).getColumnName();
                require(identifier(column) && column.equalsIgnoreCase(bind.getColumns()[i]) && seen.add(column.toLowerCase(Locale.ROOT)));
                require(expressions.get(i) instanceof JdbcParameter && "?".equals(expressions.get(i).toString()));
                String suffix = (table.getName() + "." + column).toLowerCase(Locale.ROOT);
                for (String key : policies.getAllMappings().keySet()) {
                    String normalized = key.toLowerCase(Locale.ROOT);
                    if (normalized.equals(suffix) || normalized.endsWith("." + suffix)) protectedBinds.add(i + 1);
                }
            }
            return protectedBinds;
        } catch (Exception failure) {
            throw denied();
        }
    }

    private static boolean identifier(String value) { return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]*"); }
    private static boolean empty(Collection<?> values) { return values == null || values.isEmpty(); }
    private static void require(boolean condition) throws SQLException { if (!condition) throw denied(); }
    static SQLFeatureNotSupportedException denied() {
        return new SQLFeatureNotSupportedException("Engine policy writes require supported single-row INSERT or UPDATE with direct bind parameters and text/NULL protected values", "0A000");
    }
}
