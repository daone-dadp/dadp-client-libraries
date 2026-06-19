# Wrapper 6.0 CLI Storage And Call Contract

Wrapper is a JDBC driver, not a server. The wrapper JAR owns runtime storage,
runtime DTO serialization, tenant reuse checks, and version checks through
CLI-callable helper entrypoints. The `dadp` CLI only triggers those helpers,
collects operator input, and sends Hub API requests with values returned by the
wrapper helper.

## Fixed Storage

Storage is fixed by the wrapper library directory:

```text
<wrapper-lib-dir>/dadp/wrapper/<alias>
```

Example:

```text
/app/lib/dadp/wrapper/dadp-test-app-standalone-mysql
```

The CLI and wrapper runtime must use the same directory. Do not use
`--storage-dir`, environment variables, system properties, or CLI config to move
runtime storage.

Docker Compose must persist `<wrapper-lib-dir>/dadp`.

```yaml
services:
  app:
    volumes:
      - ./dadp:/app/lib/dadp
```

## Runtime Files

The wrapper helper writes:

```text
<storage-dir>/proxy-config.json
<storage-dir>/policy-mappings.json
```

Optional schema cache:

```text
<storage-dir>/schemas.json
```

The Go CLI must not create, rewrite, normalize, or directly parse these runtime
DTOs except for displaying helper-returned summaries. Do not create or read
`crypto-endpoints.json`.

## JDBC URL

JDBC URL contains DB connection values only:

```text
jdbc:dadp:mysql://127.0.0.1:3306/soe?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Seoul&characterEncoding=UTF-8
```

The wrapper rejects `hubUrl`, `alias`, `tenantId`, `cryptoMode`, `failOpen`,
`enabled`, `policySyncAutoEnabled`, runtime auth values, and datasource values
in the JDBC URL.

## Schema Collect Helper

Preferred Java helper:

```java
Map<String, Object> schemaCache =
    WrapperSchemaCollectSupport.collectSchemaCache(
        alias,
        dadpJdbcUrl,
        connection,
        appName,
        wrapperVersion,
        clientInstanceId);
```

This returns data only. The CLI may keep it in memory or write it to an operator
chosen schema JSON file for review. Schema JSON is not wrapper runtime state.

Command entrypoint:

```bash
java -cp "dadp-jdbc-wrapper.jar:${DB_DRIVER}" com.dadp.jdbc.SchemaCollectCommand \
  --alias "{alias}" \
  --jdbc-url "jdbc:dadp:<db-url>" \
  --db-user-env <USER_ENV_NAME> \
  --db-password-env <PASSWORD_ENV_NAME>
```

Default output is stdout. `--output` is optional and controlled by CLI.

## Schema Register

The CLI calls Hub schema registration using its login/JWT session.

Before calling Hub, the CLI must ask the wrapper helper to load an existing
tenantId from the wrapper-managed directory:

```bash
java -cp "dadp-jdbc-wrapper.jar:${DB_DRIVER}" com.dadp.jdbc.WrapperCliStorageCommand load-tenant-id \
  --storage-dir "{storageDir}" \
  --alias "{alias}"
```

If the helper returns `tenantId`, reuse that tenantId:

- include top-level `tenantId` in the request body
- send `X-DADP-Tenant-Id: {tenantId}` header

If it does not return tenantId, omit tenantId and let Hub issue a new one. Alias
mismatch and malformed runtime files are wrapper helper errors and must stop the
Hub call.

Build the request body from schema cache:

```java
Map<String, Object> payload =
    SchemaRegistrationPayloadBuilder.buildRegistrationPayload(
        schemaCache,
        existingTenantId,
        appName,
        wrapperVersion,
        clientInstanceId);
```

After successful registration, write `proxy-config.json`:

```java
WrapperCliStorageSupport.saveEnrollment(
    storageDir,
    tenantIdFromHub,
    alias,
    runtimeVersionFromHub,
    runtimeHubUrlFromHub,
    null,
    null,
    null);
```

Stable JAR entrypoint:

```bash
java -cp "dadp-jdbc-wrapper.jar" com.dadp.jdbc.WrapperCliStorageCommand save-enrollment \
  --storage-dir "{storageDir}" \
  --tenant-id "{tenantId}" \
  --alias "{alias}" \
  --runtime-version "{runtimeVersion}" \
  --runtime-hub-url "{hubUrl}"
```

The Go CLI must call the JAR entrypoint or `WrapperCliStorageSupport`; it must
not rebuild `proxy-config.json` DTOs itself.

Source-of-truth `proxy-config.json`:

```json
{
  "tenantId": "wtenant_xxx",
  "alias": "dadp-test-app-standalone-mysql",
  "runtimeVersion": "7",
  "runtime": {
    "hubUrl": "http://dadp-hub:9004",
    "engineUrl": "http://dadp-engine:9003",
    "cryptoMode": "local",
    "failOpen": false,
    "policySyncAutoEnabled": false
  }
}
```

`refreshUrl`, `schemaSyncUrl`, and `engineEndpointUrl` are derived from
`runtime.hubUrl` and `tenantId`; they are not persisted source-of-truth values.

## Refresh

CLI refresh resolves runtime context through the wrapper helper. It does not
scan or interpret runtime JSON by itself.

```bash
java -cp "dadp-jdbc-wrapper.jar:${DB_DRIVER}" com.dadp.jdbc.WrapperCliStorageCommand resolve-runtime-context \
  --wrapper-lib-dir "{wrapperLibDir}"
```

The helper returns `storageDir`, `proxyConfigPath`, `tenantId`, `alias`, and
`runtimeVersion` for exactly one enrolled wrapper under
`<wrapper-lib-dir>/dadp/wrapper`.

If missing:

```text
Wrapper enrollment is missing. Run wrapper schema register first.
```

If `tenantId` is missing:

```text
Wrapper tenantId is missing. Run wrapper schema register first.
```

Before calling Hub, the CLI asks the wrapper helper for the stored policy mapping
version:

```bash
java -cp "dadp-jdbc-wrapper.jar:${DB_DRIVER}" com.dadp.jdbc.WrapperCliStorageCommand load-policy-version \
  --storage-dir "{storageDir}"
```

Hub call:

```http
GET /hub/api/v1/runtime/wrappers/{tenantId}/refresh?version={runtimeVersion}
X-DADP-Tenant-Id: {tenantId}
Accept: application/json
```

Do not use JWT for runtime refresh.

Status handling:

| Status | CLI action |
| --- | --- |
| `304` | Do not modify files. Print `wrapper refresh: no changes`. |
| `200` | Call `WrapperCliStorageSupport.applyRefreshResponse(storageDir, body)`. |
| `401` | Do not retry with JWT. Report tenant runtime auth rejection. |
| `404` | Report missing wrapper enrollment. |
| `409` | Report schema registration missing/stale. |
| Other | Do not modify files. Report Hub error. |

Refresh response values persisted in `proxy-config.json`:

- `runtime.hubUrl`
- `runtime.engineUrl` from Hub response `runtime.engineUrl` or `wrapper.engineUrl`
- `runtime.cryptoMode`
- `runtime.failOpen`
- `runtime.policySyncAutoEnabled`
- `runtimeVersion`

Refresh response values not persisted:

- `enabled`
- `debugEnabled`
- `debugLevel`
- `refreshUrl`
- `schemaSyncUrl`
- `engineEndpointUrl`

Active policy bindings are stored in `policy-mappings.json` using
`schema.table.column -> policyCode`. Local crypto policy attributes are stored
there too.

Stable JAR entrypoint:

```bash
java -cp "dadp-jdbc-wrapper.jar" com.dadp.jdbc.WrapperCliStorageCommand apply-refresh-response \
  --storage-dir "{storageDir}" \
  --response-file "{refresh-response.json}"
```

This command writes both `proxy-config.json` and `policy-mappings.json` through
wrapper storage code and returns:

```json
{
  "runtimeVersion": 8,
  "policyBindingCount": 3,
  "mappingCount": 2,
  "engineUrl": "http://dadp-engine:9003",
  "cryptoMode": "local"
}
```

## Wrapper Runtime

At startup, wrapper runtime:

1. validates JDBC URL contains DB parameters only
2. resolves `<wrapper-lib-dir>/dadp/wrapper`
3. finds exactly one valid `proxy-config.json`
4. loads `proxy-config.json`
5. loads `policy-mappings.json`
6. starts automatic refresh only if `policySyncAutoEnabled=true` and
   `runtime.hubUrl` is present

The long-running wrapper runtime does not:

- collect DB schema
- call schema register
- issue tenantId
- read `hubUrl` or `alias` from JDBC URL
- read storage path from env/system/CLI config
- create runtime JSON files directly during SQL execution

The CLI-callable wrapper helper does create and update runtime JSON files. This
keeps CLI-triggered behavior and wrapper runtime behavior on the same DTO and
storage code.
