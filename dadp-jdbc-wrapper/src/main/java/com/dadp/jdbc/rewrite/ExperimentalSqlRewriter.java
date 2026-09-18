package com.dadp.jdbc.rewrite;

import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;

/**
 * Internal, opt-in G2 experiment, deliberately disconnected from JDBC execution.
 * No SQL function ABI, DB support, policy authorization or key path is approved here.
 */
public final class ExperimentalSqlRewriter {
    private final FixtureScope scope;
    private final FixtureFunctions functions;

    /** Disabled by default: does not parse, resolve policies or change SQL. */
    public ExperimentalSqlRewriter() {
        this.scope = null;
        this.functions = null;
    }

    private ExperimentalSqlRewriter(FixtureScope scope, FixtureFunctions functions) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.functions = Objects.requireNonNull(functions, "functions");
    }

    public static ExperimentalSqlRewriter forFixture(FixtureScope scope, FixtureFunctions functions) {
        return new ExperimentalSqlRewriter(scope, functions);
    }

    public String rewrite(String sql) throws SQLException {
        if (scope == null) {
            return sql;
        }
        require(sql != null && sql.length() <= 16384);
        try {
            Statement statement = CCJSqlParserUtil.parse(sql, parser -> parser.withTimeOut(1000));
            if (statement instanceof Insert) {
                return insert((Insert) statement).toString();
            }
            if (statement instanceof Update) {
                return update((Update) statement).toString();
            }
            if (statement instanceof PlainSelect) {
                return select((PlainSelect) statement).toString();
            }
            throw unsupported();
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            // Parser exceptions can include SQL literals. Never attach them as causes.
            throw unsupported();
        }
    }

    private Insert insert(Insert source) throws SQLException {
        Table table = table(source.getTable(), false);
        require(source.getColumns() != null && !source.getColumns().isEmpty());
        require(source.getSelect() instanceof Values);
        ExpressionList<?> values = ((Values) source.getSelect()).getExpressions();
        // JSqlParser represents a one-column row as a Parenthesis in a plain list.
        if (values != null && values.size() == 1 && values.get(0) instanceof Parenthesis) {
            values = new ParenthesedExpressionList<>(Collections.singletonList(
                    ((Parenthesis) values.get(0)).getExpression()));
        }
        require(values instanceof ParenthesedExpressionList);
        require(source.getColumns().size() == values.size());
        Set<String> seen = new HashSet<>();
        List<Expression> rewritten = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            String name = column(source.getColumns().get(i), table, false);
            require(seen.add(name));
            Expression value = values.get(i);
            scalar(value);
            rewritten.add(wrap(value, name, false));
        }
        Insert allowed = new Insert();
        allowed.setTable(table);
        allowed.setColumns(source.getColumns());
        allowed.setSelect(new Values(new ParenthesedExpressionList<>(new ArrayList<Expression>(values))));
        sameShape(source, allowed);
        allowed.setSelect(new Values(new ParenthesedExpressionList<>(rewritten)));
        return allowed;
    }

    private Update update(Update source) throws SQLException {
        Table table = table(source.getTable(), false);
        require(source.getUpdateSets() != null && !source.getUpdateSets().isEmpty());
        Set<String> seen = new HashSet<>();
        List<UpdateSet> original = new ArrayList<>();
        List<UpdateSet> rewritten = new ArrayList<>();
        for (UpdateSet assignment : source.getUpdateSets()) {
            require(assignment.getColumns().size() == 1 && assignment.getValues().size() == 1);
            Column target = assignment.getColumn(0);
            String name = column(target, table, false);
            require(seen.add(name));
            Expression value = assignment.getValue(0);
            scalar(value);
            original.add(new UpdateSet(target, value));
            rewritten.add(new UpdateSet(target, wrap(value, name, false)));
        }
        Update allowed = new Update();
        allowed.setTable(table);
        allowed.setUpdateSets(original);
        sameShape(source, allowed);
        allowed.setUpdateSets(rewritten);
        return allowed;
    }

    private PlainSelect select(PlainSelect source) throws SQLException {
        require(source.getFromItem() instanceof Table);
        Table table = table((Table) source.getFromItem(), true);
        require(source.getSelectItems() != null && !source.getSelectItems().isEmpty());
        List<SelectItem<?>> rewritten = new ArrayList<>();
        for (SelectItem<?> item : source.getSelectItems()) {
            require(item.getExpression() instanceof Column);
            Column target = (Column) item.getExpression();
            String name = column(target, table, true);
            Expression value = wrap(target, name, true);
            Alias alias = item.getAlias();
            if (alias != null) {
                identifier(alias.getName());
                sameShape(alias, new Alias(alias.getName(), alias.isUseAs()));
            } else if (value != target) {
                alias = new Alias(name, true);
            }
            rewritten.add(new SelectItem<>(value, alias));
        }
        PlainSelect allowed = new PlainSelect();
        allowed.setFromItem(table);
        allowed.setSelectItems(source.getSelectItems());
        sameShape(source, allowed);
        allowed.setSelectItems(rewritten);
        return allowed;
    }

    private Table table(Table source, boolean allowAlias) throws SQLException {
        require(source != null);
        require(Objects.equals(scope.schema, source.getSchemaName()) && scope.table.equals(source.getName()));
        Table allowed = new Table(scope.table);
        allowed.setSchemaName(scope.schema);
        if (source.getAlias() != null) {
            require(allowAlias);
            identifier(source.getAlias().getName());
            allowed.setAlias(new Alias(source.getAlias().getName(), source.getAlias().isUseAs()));
        }
        sameShape(source, allowed);
        return allowed;
    }

    private String column(Column column, Table table, boolean qualified) throws SQLException {
        String name = column.getColumnName();
        require(scope.columns.containsKey(name));
        if (column.getTable() != null && column.getTable().getName() != null) {
            require(qualified);
            String qualifier = column.getTable().getFullyQualifiedName();
            require(qualifier.equals(table.getAlias() == null
                    ? table.getFullyQualifiedName() : table.getAlias().getName()));
        }
        sameShape(column, new Column(column.getTable(), name));
        return name;
    }

    private Expression wrap(Expression value, String column, boolean decrypt) {
        String policy = scope.columns.get(column);
        if (policy == null || value instanceof NullValue) {
            return value;
        }
        Function function = new Function();
        function.setName(decrypt ? functions.decrypt : functions.encrypt);
        function.setParameters(new ExpressionList<>(value, new StringValue(policy)));
        return function;
    }

    private static void scalar(Expression value) throws SQLException {
        require(value instanceof StringValue || value instanceof LongValue || value instanceof DoubleValue
                || value instanceof NullValue || value instanceof JdbcParameter);
        if (value instanceof JdbcParameter) {
            require("?".equals(value.toString()));
        }
    }

    private static void sameShape(Object source, Object allowed) throws SQLException {
        // Compare AST serializations, never substitute SQL text. Extra clauses fail closed.
        require(source.toString().equals(allowed.toString()));
    }

    private static void identifier(String value) throws SQLException {
        require(value != null && value.matches("[a-z_][a-z0-9_]*"));
    }

    private static void require(boolean condition) throws SQLException {
        if (!condition) {
            throw unsupported();
        }
    }

    private static SQLFeatureNotSupportedException unsupported() {
        return new SQLFeatureNotSupportedException("SQL outside v8 fixture rewrite allowlist", "0A000");
    }

    /** Exact lowercase fixture identifiers, not production dialect normalization. */
    public static final class FixtureScope {
        private final String schema;
        private final String table;
        private final Map<String, String> columns;

        /** Every known column must be explicit; null policy means confirmed unprotected. */
        public FixtureScope(String schema, String table, Map<String, String> columns) throws SQLException {
            if (schema != null) {
                identifier(schema);
            }
            identifier(table);
            require(columns != null && !columns.isEmpty());
            for (Map.Entry<String, String> entry : columns.entrySet()) {
                identifier(entry.getKey());
                // Restrict fixture policy tokens; wire representation awaits G2.
                if (entry.getValue() != null) {
                    identifier(entry.getValue());
                }
            }
            this.schema = schema;
            this.table = table;
            this.columns = Collections.unmodifiableMap(new HashMap<>(columns));
        }
    }

    /** Injected trial ABI: function(value, policy-token), NULL-strict, no casts or extra binds. */
    public static final class FixtureFunctions {
        private final String encrypt;
        private final String decrypt;

        public FixtureFunctions(String encrypt, String decrypt) throws SQLException {
            identifier(encrypt);
            identifier(decrypt);
            require(!encrypt.equals(decrypt));
            this.encrypt = encrypt;
            this.decrypt = decrypt;
        }
    }
}
