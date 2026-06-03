# Wrapper 6.0 Runtime Variable Control

This document defines where wrapper runtime variables may come from, whether they are persisted, and how refresh changes are applied.

## Control Component

All wrapper runtime values are controlled by `WrapperRuntimeConfigManager`.

Allowed sources, in priority order:

1. Hub `/refresh` values for mutable runtime options.
2. Startup JDBC URL values for immutable bootstrap inputs.
3. Persistent wrapper storage for previously refreshed mutable options and the Hub-issued tenantId.

`hubUrl` and `alias` are not mutable runtime options. They must be read from the JDBC URL on every startup and must not be overwritten by refresh, exported config, system properties, environment variables, or stored files.

## Variable Rules

| Variable | Source | Persisted | Refresh handling | Startup handling |
| --- | --- | --- | --- | --- |
| `hubUrl` | JDBC URL `hubUrl` only | No | Ignored if present elsewhere | Required. Missing value disables wrapper functionality and logs startup failure. |
| `alias` | JDBC URL `alias` only | No | Ignored if present elsewhere | Required. Missing value disables wrapper functionality and logs startup failure. |
| `tenantId` | CLI schema-register/exported enrollment | Yes | Must not change once stored | Loaded from persistent storage. Different new value is ignored and logged. |
| `refreshUrl` | None | No | Not accepted as stored/configured state | Derived canonically as `/hub/api/v1/runtime/wrappers/{tenantId}/refresh`. |
| `schemaSyncUrl` | None | No | Not accepted as stored/configured state | Derived canonically as `/hub/api/v1/runtime/wrappers/{tenantId}/schema-sync` by collector/schema-sync helper only. |
| `enabled` | Hub `/refresh` only | No | Applied to current JVM memory only | Defaults to `true` on every startup. |
| `cryptoMode` | Hub `/refresh`; persistent storage after refresh | Yes | Updated and persisted | Defaults to `remote` when no stored value exists; otherwise stored value is used. |
| `failOpen` | Hub `/refresh`; persistent storage after refresh | Yes | Updated and persisted | Defaults to `false` when no stored value exists; otherwise stored value is used. |
| `policySyncAutoEnabled` | Hub `/refresh`; persistent storage after refresh | Yes | Updated and persisted | Defaults to `false` when no stored value exists; otherwise stored value is used. |
| `storageDir` | Environment variable `DADP_STORAGE_DIR` only | No | Not controlled by refresh | Defaults to `{user.dir}/dadp/wrapper/{alias}` when env is missing. |
| CA cert path | Not a wrapper runtime variable | No | Not controlled by refresh | Use the JVM/default trust store unless the crypto HTTP layer adds an explicit CA option later. |

## Runtime Flow

Wrapper startup:

1. Parse `hubUrl` and `alias` from the JDBC URL.
2. Resolve storage directory from `DADP_STORAGE_DIR` or default path.
3. Load persistent `tenantId`, `cryptoMode`, `failOpen`, `policySyncAutoEnabled`, and `runtimeVersion`.
4. Initialize crypto/runtime services from the loaded state.
5. Do not collect DB schemas.
6. Start automatic refresh only when `policySyncAutoEnabled=true`.

Manual and automatic refresh:

1. Call the same refresh path.
2. Load policy bindings, engine endpoint, logging, and wrapper runtime options from Hub.
3. Persist only `cryptoMode`, `failOpen`, `policySyncAutoEnabled`, and `runtimeVersion`.
4. Apply `enabled` in memory only.
5. Apply crypto mode and fail-open immediately to the active crypto adapter.

Schema collection:

- Runtime bootstrap must not collect or upload DB schemas.
- Schema collection belongs to the collector/CLI schema-register flow.
- The schema-sync helper may still send a prepared schema document, but only when explicitly invoked outside runtime bootstrap.

## Removed Runtime Identity

`datasourceId` is not a wrapper runtime variable in DADP 6. Schema snapshots are
scoped by `alias`; policy bindings are scoped by `alias + schema.table.column`;
wrapper registrations are scoped by `tenantId + alias`. The CLI/schema-register
response must provide only `tenantId`, `alias`, runtime URLs, and version.
