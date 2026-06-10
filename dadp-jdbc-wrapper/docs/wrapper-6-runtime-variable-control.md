# Wrapper 6.0 Runtime Variable Control

Wrapper 6.0 runtime reads CLI-owned JSON files only. JDBC URL, environment
variables, and system properties do not control wrapper runtime options.

See also `wrapper-6-cli-storage-contract.md`.

## Source Rules

1. `dadp` CLI creates and refreshes JSON files under
   `<wrapper-lib-dir>/dadp/wrapper/<alias>`.
2. Wrapper runtime discovers `proxy-config.json` under
   `<wrapper-lib-dir>/dadp/wrapper`.
3. Wrapper runtime reads `proxy-config.json` and `policy-mappings.json`.
4. Hub `/refresh` values are applied by CLI refresh or wrapper automatic refresh
   when `policySyncAutoEnabled=true`.
5. JDBC URL contains DB connection values only.

If no valid `proxy-config.json` exists, wrapper stays in passthrough mode and
logs that CLI schema register and refresh are required.

If multiple valid `proxy-config.json` files exist under one wrapper lib dir,
wrapper stays in passthrough mode and logs the ambiguous aliases.

## Variables

| Variable | Source | Persisted | Runtime behavior |
| --- | --- | --- | --- |
| `tenantId` | CLI schema register response | Yes | Immutable once stored. Required for refresh and tenant-auth Hub calls. |
| `alias` | CLI schema register input, stored in `proxy-config.json` | Yes | Storage scope and display identity. Not read from JDBC URL. |
| `runtime.hubUrl` | Hub schema register/refresh response | Yes | Base Hub URL for refresh and local execution-key resolution. |
| `runtime.engineUrl` | Hub refresh response `runtime.engineUrl` or `wrapper.engineUrl` | Yes | Remote engine endpoint for remote mode and local fallback. |
| `enabled` | Hub refresh response | No | Memory-only. Defaults to `true` on JVM startup. |
| `debugEnabled` | Hub refresh response | No | Applied to logging at refresh time. |
| `debugLevel` | Hub refresh response | No | Applied to logging at refresh time. |
| `runtime.cryptoMode` | Hub refresh response | Yes | Defaults to `remote`; persisted after refresh. |
| `runtime.policySyncAutoEnabled` | Hub refresh response | Yes | Defaults to `false`; persisted after refresh. |
| `runtime.failOpen` | Hub refresh response | Yes | Defaults to `false`; persisted after refresh. |
| `storageDir` | Derived from wrapper JAR lib dir and alias | No external override | `<wrapper-lib-dir>/dadp/wrapper/<alias>`. |

## Forbidden Inputs

The wrapper must reject these in JDBC URL:

- `hubUrl`
- `alias`
- `tenantId`
- `datasourceId`
- `failOpen`
- `enabled`
- `cryptoMode`
- `policySyncAutoEnabled`
- `runtimeAuthKey`
- `runtimeAuthSecret`
- `wrapperAuthKey`
- `wrapperAuthSecret`

The wrapper runtime must not read these:

- `DADP_STORAGE_DIR`
- `DADP_WRAPPER_STORAGE_DIR`
- `DADP_PROXY_HUB_URL`
- `DADP_PROXY_ALIAS`
- CLI config file for runtime storage path
- system properties for wrapper runtime options

## Runtime Flow

Startup:

1. Validate that JDBC URL has DB parameters only.
2. Derive storage root from wrapper JAR location:
   `<wrapper-lib-dir>/dadp/wrapper`.
3. Find exactly one valid `proxy-config.json`.
4. Load tenantId, alias, runtimeVersion, cryptoMode, failOpen,
   policySyncAutoEnabled, runtime.hubUrl, and runtime.engineUrl.
5. Load policy bindings from `policy-mappings.json`.
6. Start automatic refresh only when `policySyncAutoEnabled=true` and
   `runtime.hubUrl` is available.

Refresh:

1. Manual CLI refresh and wrapper automatic refresh use the same Hub refresh
   response contract.
2. `304` does not modify local files.
3. `200` updates `proxy-config.json` and `policy-mappings.json`.
4. `enabled` is applied in memory only.
5. `runtime.cryptoMode`, `runtime.failOpen`,
   `runtime.policySyncAutoEnabled`, `runtimeVersion`, `runtime.hubUrl`, and
   `runtime.engineUrl` are persisted.

## Files

- `proxy-config.json`: tenantId, alias, runtimeVersion, runtime Hub URL,
  runtime engine URL, and runtime options.
- `policy-mappings.json`: active policy bindings and local crypto policy
  attributes.
- `schemas.json`: optional CLI schema cache, not required by runtime.

There is no `datasourceId` runtime state, no persisted `refreshUrl`, and no
`crypto-endpoints.json`.
