package com.dadp.jdbc;

import com.dadp.common.sync.schema.SchemaMetadata;
import com.dadp.jdbc.config.ProxyConfig;
import com.dadp.jdbc.schema.JdbcSchemaCollector;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class SchemaCollectCommand {

    private SchemaCollectCommand() {
    }

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        String actualJdbcUrl = DadpJdbcUrlSupport.extractActualUrl(arguments.jdbcUrl);
        Map<String, String> proxyParams = DadpJdbcUrlSupport.extractProxyParams(arguments.jdbcUrl);
        String alias = trimToNull(proxyParams.get("alias"));
        if (alias == null) {
            throw new IllegalArgumentException("JDBC URL must include alias");
        }
        if (trimToNull(proxyParams.get("hubUrl")) == null) {
            throw new IllegalArgumentException("JDBC URL must include hubUrl");
        }

        Properties connectionProps = new Properties();
        if (arguments.dbUser != null) {
            connectionProps.setProperty("user", arguments.dbUser);
        }
        if (arguments.dbPassword != null) {
            connectionProps.setProperty("password", arguments.dbPassword);
        }

        try (Connection connection = connectionProps.isEmpty()
                ? DriverManager.getConnection(actualJdbcUrl)
                : DriverManager.getConnection(actualJdbcUrl, connectionProps)) {
            JdbcSchemaCollector collector = new JdbcSchemaCollector(alias, new ProxyConfig(proxyParams));
            List<SchemaMetadata> schemas = collector.collectSchemas(connection);
            Map<String, Object> payload = SchemaRegistrationPayloadBuilder.build(
                    arguments.jdbcUrl,
                    actualJdbcUrl,
                    connection,
                    schemas,
                    arguments.appName,
                    arguments.wrapperVersion,
                    arguments.clientInstanceId
            );

            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            File output = new File(arguments.output);
            File parent = output.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IllegalStateException("failed to create output directory: " + parent);
            }
            mapper.writeValue(output, payload);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final class Arguments {
        private String jdbcUrl;
        private String dbUser;
        private String dbPassword;
        private String output = "schema-register.json";
        private String appName;
        private String wrapperVersion = detectWrapperVersion();
        private String clientInstanceId;
        private boolean dbPasswordSet;
        private boolean dbPasswordEnvSet;

        private static Arguments parse(String[] args) {
            Arguments parsed = new Arguments();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--jdbc-url".equals(arg)) {
                    parsed.jdbcUrl = requireValue(args, ++i, arg);
                } else if ("--db-user".equals(arg)) {
                    parsed.dbUser = requireValue(args, ++i, arg);
                } else if ("--db-password".equals(arg)) {
                    parsed.dbPassword = requireValue(args, ++i, arg);
                    parsed.dbPasswordSet = true;
                } else if ("--db-password-env".equals(arg)) {
                    String envName = requireValue(args, ++i, arg);
                    parsed.dbPassword = trimToNull(System.getenv(envName));
                    parsed.dbPasswordEnvSet = true;
                    if (parsed.dbPassword == null) {
                        throw new IllegalArgumentException("environment variable is empty: " + envName);
                    }
                } else if ("--output".equals(arg)) {
                    parsed.output = requireValue(args, ++i, arg);
                } else if ("--app-name".equals(arg)) {
                    parsed.appName = requireValue(args, ++i, arg);
                } else if ("--wrapper-version".equals(arg)) {
                    parsed.wrapperVersion = requireValue(args, ++i, arg);
                } else if ("--client-instance-id".equals(arg)) {
                    parsed.clientInstanceId = requireValue(args, ++i, arg);
                } else if ("--help".equals(arg) || "-h".equals(arg)) {
                    throw new IllegalArgumentException(usage());
                } else {
                    throw new IllegalArgumentException("unknown option: " + arg + "\n" + usage());
                }
            }
            if (trimToNull(parsed.jdbcUrl) == null) {
                throw new IllegalArgumentException(usage());
            }
            if (parsed.dbPasswordSet && parsed.dbPasswordEnvSet) {
                throw new IllegalArgumentException("--db-password and --db-password-env cannot be used together");
            }
            return parsed;
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("--")) {
                throw new IllegalArgumentException("missing value for " + option);
            }
            return args[index];
        }

        private static String usage() {
            return "usage: java -cp <wrapper.jar:db-driver.jar> com.dadp.jdbc.SchemaCollectCommand "
                    + "--jdbc-url <jdbc:dadp:...> [--output schema-register.json] "
                    + "[--db-user <user>] [--db-password-env <ENV> | --db-password <password>] "
                    + "[--app-name <name>] [--client-instance-id <id>]";
        }

        private static String detectWrapperVersion() {
            Package pkg = SchemaCollectCommand.class.getPackage();
            String version = pkg != null ? pkg.getImplementationVersion() : null;
            return trimToNull(version) != null ? version : "6.0.0";
        }
    }
}
