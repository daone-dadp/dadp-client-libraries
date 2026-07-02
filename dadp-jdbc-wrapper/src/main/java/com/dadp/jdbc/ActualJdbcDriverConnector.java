package com.dadp.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

final class ActualJdbcDriverConnector {

    private ActualJdbcDriverConnector() {
    }

    static Connection connect(String actualUrl, Properties info) throws SQLException {
        Properties connectionInfo = info != null ? info : new Properties();
        SQLException explicitDriverFailure = null;

        for (String driverClassName : candidateDriverClassNames(actualUrl)) {
            try {
                Driver driver = instantiateDriver(driverClassName);
                if (driver == null || !driver.acceptsURL(actualUrl)) {
                    continue;
                }
                Connection connection = driver.connect(actualUrl, connectionInfo);
                if (connection != null) {
                    return connection;
                }
            } catch (ClassNotFoundException e) {
                continue;
            } catch (SQLException e) {
                explicitDriverFailure = e;
                break;
            } catch (ReflectiveOperationException e) {
                explicitDriverFailure = new SQLException(
                        "failed to instantiate JDBC driver " + driverClassName + ": " + e.getMessage(), e);
                break;
            }
        }

        try {
            return DriverManager.getConnection(actualUrl, connectionInfo);
        } catch (SQLException e) {
            if (explicitDriverFailure != null) {
                explicitDriverFailure.addSuppressed(e);
                throw explicitDriverFailure;
            }
            throw e;
        }
    }

    static String[] candidateDriverClassNames(String actualUrl) {
        String lower = actualUrl != null ? actualUrl.toLowerCase() : "";
        if (lower.startsWith("jdbc:postgresql:")) {
            return new String[]{"org.postgresql.Driver"};
        }
        if (lower.startsWith("jdbc:mysql:")) {
            return new String[]{"com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver"};
        }
        if (lower.startsWith("jdbc:mariadb:")) {
            return new String[]{"org.mariadb.jdbc.Driver"};
        }
        if (lower.startsWith("jdbc:sqlserver:")) {
            return new String[]{"com.microsoft.sqlserver.jdbc.SQLServerDriver"};
        }
        if (lower.startsWith("jdbc:oracle:")) {
            return new String[]{"oracle.jdbc.OracleDriver", "oracle.jdbc.driver.OracleDriver"};
        }
        if (lower.startsWith("jdbc:tibero:")) {
            return new String[]{"com.tmax.tibero.jdbc.TbDriver"};
        }
        if (lower.startsWith("jdbc:db2:")) {
            return new String[]{"com.ibm.db2.jcc.DB2Driver"};
        }
        if (lower.startsWith("jdbc:h2:")) {
            return new String[]{"org.h2.Driver"};
        }
        if (lower.startsWith("jdbc:sqream:")) {
            return new String[]{"com.sqream.jdbc.SQDriver"};
        }
        return new String[0];
    }

    private static Driver instantiateDriver(String driverClassName)
            throws ReflectiveOperationException, SQLException {
        Class<?> driverClass = loadClass(driverClassName);
        Object instance = driverClass.getDeclaredConstructor().newInstance();
        if (!(instance instanceof Driver)) {
            throw new SQLException(driverClassName + " is not a java.sql.Driver");
        }
        return (Driver) instance;
    }

    private static Class<?> loadClass(String driverClassName) throws ClassNotFoundException {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null) {
            try {
                return Class.forName(driverClassName, true, contextLoader);
            } catch (ClassNotFoundException ignored) {
                // Continue with the wrapper class loader below.
            }
        }
        return Class.forName(driverClassName, true, ActualJdbcDriverConnector.class.getClassLoader());
    }
}
