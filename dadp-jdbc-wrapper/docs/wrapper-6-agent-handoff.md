# Wrapper 6.0.0 Agent Handoff

This document is the handoff guide for the next wrapper agent.

## Scope

Work only in:

```text
/home/au212/projects/dadp/dadp-client-libraries
```

Do not modify the parent repo unless explicitly instructed for documentation.
Do not start a local DB container. Use the existing test app and existing test DB.

## Runtime Storage Contract

`proxy-config.json` must use this source-of-truth shape:

```json
{
  "tenantId": "wtenant_xxx",
  "alias": "dadp-test-app-standalone-mysql",
  "runtime": {
    "hubUrl": "http://dadp-hub:9004",
    "engineUrl": "http://dadp-engine:9003",
    "cryptoMode": "local",
    "failOpen": false,
    "policySyncAutoEnabled": false
  }
}
```

Meaning:

- `tenantId`: Hub-issued wrapper runtime identity. Must not change once stored.
- `alias`: DB shared group name from CLI registration.
- `runtime.hubUrl`: Hub base URL used for refresh and execution-key resolution.
- `runtime.engineUrl`: Engine base URL used for remote mode and local fallback.
- `runtime.cryptoMode`: `remote` or `local`.
- `runtime.failOpen`: default false.
- `runtime.policySyncAutoEnabled`: default false.

The following are derived values, not source-of-truth configuration:

```text
/hub/api/v1/runtime/wrappers/{tenantId}/refresh
/hub/api/v1/runtime/wrappers/{tenantId}/schema-sync
/hub/api/v1/runtime/engine-endpoint
```

Generate them from `runtime.hubUrl + tenantId` when needed.

## Naming Cleanup

The current/legacy name below is confusing and should be removed for 6.0.0 cleanup:

```json
"engine": {
  "wrapperEngineUrl": "http://dadp-engine:9003"
}
```

Use this instead:

```json
"runtime": {
  "engineUrl": "http://dadp-engine:9003"
}
```

Wrapper remote/fallback engine URL must come from `runtime.engineUrl`.

## Crypto Mode Contract

The app and Hikari do not choose local or remote. Wrapper does.

```text
cryptoMode=remote
 -> call Engine through runtime.engineUrl

cryptoMode=local
 -> try WrapperLocalCryptoService
 -> on success, do not call Engine
 -> on local failure or unsupported provider/algorithm, fallback to Engine through runtime.engineUrl
```

Important:

- Engine endpoint must remain available even in local mode.
- Fallback is required for unsupported providers/algorithms.
- Fallback must log at WARN or higher.

## CLI And Refresh Contract

CLI owns schema collection/registration and persistent JSON updates.
Wrapper runtime reads CLI-created storage.

Flow:

1. CLI schema collect.
2. CLI schema register.
   - If `proxy-config.json` already has `tenantId`, send it as `X-DADP-Tenant-Id`.
   - Do not create duplicate tenants for the same alias.
3. CLI refresh.
   - `GET /hub/api/v1/runtime/wrappers/{tenantId}/refresh?version={runtimeVersion}`
   - Header: `X-DADP-Tenant-Id: {tenantId}`
   - `304`: do not update files.
   - `200`: update `proxy-config.json` and `policy-mappings.json`.

Wrapper automatic refresh must use the same API and response contract as CLI refresh.
Only the caller differs.

## Verification Rules

Use the existing MySQL test app and existing test DB.

Active wrapper jar:

```text
/home/au212/projects/dadp-test-app-standalone/docker/lib/dadp-jdbc-wrapper.jar
```

Required verification:

1. Build and test:

```bash
cd /home/au212/projects/dadp/dadp-client-libraries
../mvnw -pl dadp-jdbc-wrapper -am test -DskipITs -Dmaven.javadoc.skip=true -Dsurefire.failIfNoSpecifiedTests=false
../mvnw -pl dadp-jdbc-wrapper -am package -DskipTests -DskipITs -Dmaven.javadoc.skip=true
```

2. Remote mode:

- Set Hub wrapper `cryptoMode=remote`.
- Run CLI refresh.
- Restart the MySQL test app or otherwise guarantee wrapper connection recreation.
- Run `benchmarkInsert count=20`.
- Run `benchmarkWrapperSearch count=20`.
- DB raw values must be encrypted.
- App response must be plaintext.
- `dadp-engine` encrypt/decrypt logs must appear.

3. Local mode:

- Set Hub wrapper `cryptoMode=local`.
- Run CLI refresh.
- Restart the MySQL test app or otherwise guarantee wrapper connection recreation.
- Run `benchmarkInsert count=20`.
- Run `benchmarkWrapperSearch count=20`.
- DB raw values must be encrypted.
- App response must be plaintext.
- `dadp-engine` encrypt/decrypt logs must not appear.
- Local fallback WARN must not appear.

4. Fallback mode:

- Unsupported provider/algorithm should fallback to Engine.
- Fallback must produce WARN log.
- Engine crypto logs are expected in this case.

5. Auto refresh:

- Enable `policySyncAutoEnabled=true`.
- Change Hub runtime option.
- Verify wrapper automatic refresh applies the same response contract as CLI refresh.

## Current Risk

Recent commits before this handoff:

```text
1ccae17 fix: derive wrapper runtime urls from hub url
7521817 fix: warn on local crypto remote fallback
```

Do not blindly trust them. Re-check whether they match this document.
Pay special attention to:

- `runtime.hubUrl`
- `runtime.engineUrl`
- legacy `engine.wrapperEngineUrl`
- `refreshUrl`
- `schemaSyncUrl`
- `engineEndpointUrl`
- local/remote/fallback logging

## S3 Deploy

Deploy from the wrapper repo:

```bash
cd /home/au212/projects/dadp/dadp-client-libraries
python3 scripts/deploy_wrapper_s3_via_iam_host.py \
  --skip-build \
  --key-path /home/au212/projects/dadp-prod.pem \
  --skip-hub-verify
```

IAM host:

```text
43.202.206.43
```

Verify public artifact:

```bash
curl -fsSL https://dadp-artifacts.s3.ap-northeast-2.amazonaws.com/wrapper/v6.0.0/dadp-jdbc-wrapper-6.0.0-all.jar \
  -o /tmp/dadp-jdbc-wrapper-6.0.0-all.jar
sha256sum /tmp/dadp-jdbc-wrapper-6.0.0-all.jar
```

## Git

Commit and push only in:

```text
/home/au212/projects/dadp/dadp-client-libraries
```

Remote:

```text
https://github.com/daone-dadp/dadp-client-libraries.git
```

Branch:

```text
main
```
