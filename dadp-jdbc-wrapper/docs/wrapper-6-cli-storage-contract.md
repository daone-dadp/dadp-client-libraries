# Wrapper 6.0 CLI Storage And Call Contract

Wrapper is a JDBC driver, not a server. The `dadp` CLI owns schema collection,
schema registration, refresh calls, and JSON file creation.

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

The CLI writes:

```text
<storage-dir>/proxy-config.json
<storage-dir>/policy-mappings.json
```

Optional schema cache:

```text
<storage-dir>/schemas.json
```

Do not create or read `crypto-endpoints.json`.

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

This returns data only. The CLI may keep it in memory or write it to
`schemas.json`.

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

Before calling Hub, read:

```text
<storage-dir>/proxy-config.json
```

If it exists and has `tenantId`, reuse that tenantId:

- include top-level `tenantId` in the request body
- send `X-DADP-Tenant-Id: {tenantId}` header

If it does not exist, omit tenantId and let Hub issue a new one.

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

CLI refresh reads `proxy-config.json`.

If missing:

```text
Wrapper enrollment is missing. Run wrapper schema register first.
```

If `tenantId` is missing:

```text
Wrapper tenantId is missing. Run wrapper schema register first.
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
| `304` | Do not modify files. |
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

## Wrapper Runtime

At startup, wrapper runtime:

1. validates JDBC URL contains DB parameters only
2. resolves `<wrapper-lib-dir>/dadp/wrapper`
3. finds exactly one valid `proxy-config.json`
4. loads `proxy-config.json`
5. loads `policy-mappings.json`
6. starts automatic refresh only if `policySyncAutoEnabled=true` and
   `runtime.hubUrl` is present

Wrapper runtime does not:

- collect DB schema
- call schema register
- issue tenantId
- read `hubUrl` or `alias` from JDBC URL
- read storage path from env/system/CLI config
- create runtime JSON files
