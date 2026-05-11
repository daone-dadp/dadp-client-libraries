package com.dadp.jdbc.resolution;

import com.dadp.jdbc.policy.SqlParser;

class StandardJdbcVendorResolutionStrategy implements JdbcVendorResolutionStrategy {

    private final String dbVendor;

    StandardJdbcVendorResolutionStrategy(String dbVendor) {
        this.dbVendor = dbVendor != null ? dbVendor.toLowerCase() : "";
    }

    @Override
    public String resolveLookupSchema(String explicitSchemaName, String currentSchemaName, String currentDatabaseName) {
        if (hasText(explicitSchemaName)) {
            return explicitSchemaName;
        }

        String schema = trimToNull(currentSchemaName);
        String database = trimToNull(currentDatabaseName);

        if (dbVendor.contains("postgresql")) {
            return hasText(schema) ? schema : "public";
        }
        if (dbVendor.contains("microsoft sql server") || dbVendor.contains("sql server") || dbVendor.contains("mssql")) {
            if (hasText(schema) && !equalsIgnoreCase(schema, database)) {
                return schema;
            }
            return "dbo";
        }
        if (dbVendor.contains("oracle") || dbVendor.contains("tibero")) {
            if (hasText(schema)) {
                return schema;
            }
            return hasText(database) ? database : null;
        }
        if (dbVendor.contains("mysql") || dbVendor.contains("mariadb")) {
            if (hasText(database)) {
                return database;
            }
            return hasText(schema) ? schema : null;
        }

        if (hasText(schema)) {
            return schema;
        }
        return hasText(database) ? database : null;
    }

    @Override
    public String resolveParsedColumnName(SqlParser.SqlParseResult sqlParseResult, String rawColumnName, String columnLabel) {
        if (rawColumnName == null) {
            return null;
        }

        String columnName = stripQualifier(rawColumnName);
        if (columnLabel != null) {
            String originalColumnName = sqlParseResult.getOriginalColumnName(columnLabel);
            if (!originalColumnName.equals(columnLabel)) {
                return originalColumnName;
            }
            if (!columnName.equalsIgnoreCase(columnLabel)) {
                String mappedName = sqlParseResult.getOriginalColumnName(columnName);
                if (!mappedName.equals(columnName)) {
                    return mappedName;
                }
            }
        }

        return columnName;
    }

    static String stripQualifier(String columnName) {
        if (columnName == null) {
            return null;
        }
        if (columnName.contains(".")) {
            return columnName.substring(columnName.lastIndexOf('.') + 1);
        }
        return columnName;
    }

    static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.equalsIgnoreCase(right);
    }
}
