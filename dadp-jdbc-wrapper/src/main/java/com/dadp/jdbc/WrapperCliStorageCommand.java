package com.dadp.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stable CLI-callable entrypoint for wrapper runtime storage.
 */
public final class WrapperCliStorageCommand {

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private WrapperCliStorageCommand() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args == null || args.length == 0 || "help".equals(args[0]) || "--help".equals(args[0])) {
                printUsage(out);
                return 0;
            }
            String command = args[0];
            Map<String, String> options = parseOptions(args);
            if ("resolve-storage-dir".equals(command)) {
                String storageDir = WrapperCliStorageSupport.resolveStorageDir(
                        required(options, "wrapper-lib-dir"),
                        required(options, "alias"));
                writeJson(out, result("storageDir", storageDir));
                return 0;
            }
            if ("build-schema-register-payload".equals(command)) {
                Map<String, Object> payload = WrapperCliStorageSupport.buildSchemaRegisterPayload(
                        new File(required(options, "schemas-json")),
                        required(options, "storage-dir"),
                        required(options, "app-name"),
                        required(options, "wrapper-version"),
                        required(options, "client-instance-id"));
                writeJsonOrFile(out, options.get("output"), payload);
                return 0;
            }
            if ("save-enrollment".equals(command)) {
                boolean saved = WrapperCliStorageSupport.saveEnrollment(
                        required(options, "storage-dir"),
                        required(options, "tenant-id"),
                        options.get("alias"),
                        options.get("runtime-version"),
                        options.get("runtime-hub-url"),
                        null,
                        null,
                        null);
                writeJson(out, result("saved", Boolean.valueOf(saved)));
                return saved ? 0 : 2;
            }
            if ("apply-refresh-response".equals(command)) {
                String responseBody = readFile(required(options, "response-file"));
                WrapperCliStorageSupport.RefreshApplyResult result =
                        WrapperCliStorageSupport.applyRefreshResponse(required(options, "storage-dir"), responseBody);
                Map<String, Object> output = new LinkedHashMap<String, Object>();
                output.put("runtimeVersion", result.getRuntimeVersion());
                output.put("mappingCount", result.getMappingCount());
                output.put("engineUrl", result.getEngineUrl());
                writeJson(out, output);
                return 0;
            }
            if ("load-tenant-id".equals(command)) {
                writeJson(out, result("tenantId", WrapperCliStorageSupport.loadTenantId(required(options, "storage-dir"))));
                return 0;
            }
            if ("load-runtime-version".equals(command)) {
                writeJson(out, result("runtimeVersion", WrapperCliStorageSupport.loadRuntimeVersion(required(options, "storage-dir"))));
                return 0;
            }
            if ("load-policy-version".equals(command)) {
                writeJson(out, result("policyVersion", WrapperCliStorageSupport.loadPolicyVersion(required(options, "storage-dir"))));
                return 0;
            }
            err.println("Unknown command: " + command);
            printUsage(err);
            return 2;
        } catch (Exception e) {
            err.println(e.getMessage());
            return 1;
        }
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new LinkedHashMap<String, String>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + arg);
            }
            String key = arg.substring(2);
            if (key.length() == 0) {
                throw new IllegalArgumentException("Empty option name");
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for --" + key);
            }
            options.put(key, args[++i]);
        }
        return options;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("--" + name + " is required");
        }
        return value;
    }

    private static String readFile(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static void writeJsonOrFile(PrintStream out, String outputPath, Object value) throws Exception {
        String json = OBJECT_MAPPER.writeValueAsString(value);
        if (outputPath == null || outputPath.trim().isEmpty() || "-".equals(outputPath.trim())) {
            out.println(json);
            return;
        }
        Files.write(Paths.get(outputPath), json.getBytes(StandardCharsets.UTF_8));
        writeJson(out, result("output", outputPath));
    }

    private static void writeJson(PrintStream out, Object value) throws Exception {
        out.println(OBJECT_MAPPER.writeValueAsString(value));
    }

    private static Map<String, Object> result(String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put(key, value);
        return result;
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage: java -cp dadp-jdbc-wrapper.jar com.dadp.jdbc.WrapperCliStorageCommand <command> [options]");
        out.println("Commands:");
        out.println("  resolve-storage-dir --wrapper-lib-dir <dir> --alias <alias>");
        out.println("  build-schema-register-payload --schemas-json <file> --storage-dir <dir> --app-name <name> --wrapper-version <version> --client-instance-id <id> [--output <file>]");
        out.println("  save-enrollment --storage-dir <dir> --tenant-id <tenantId> [--alias <alias>] [--runtime-version <version>] [--runtime-hub-url <url>]");
        out.println("  apply-refresh-response --storage-dir <dir> --response-file <file>");
        out.println("  load-tenant-id --storage-dir <dir>");
        out.println("  load-runtime-version --storage-dir <dir>");
        out.println("  load-policy-version --storage-dir <dir>");
    }
}
