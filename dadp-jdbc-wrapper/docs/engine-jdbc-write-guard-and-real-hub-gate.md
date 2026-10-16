# Engine JDBC Write Guard and Real Hub Gate

## Product Boundary

The same Agent JAR supports Engine and Plugin execution modes. These are not
two independent product artifacts. Engine mode uses the actual HubCryptoService
and Worker HTTPS mTLS client. No business keys or local business encryption are
introduced by these tests. The existing `artifacts/239350b` JAR is unchanged.

## Engine SQL Support

While any column policy mapping is active, PreparedStatement writes accept only
single-row INSERT with an explicit column list and direct `?` values, or simple
single-table UPDATE with direct `?` assignments. All supplied value/assignment
expressions must be binds, including assignments to unprotected columns. JDBC
batching of these supported prepared statements is supported.

Identifiers must be simple unquoted identifiers, optionally schema-qualified.
The AST must agree with the existing positional bind planner. Literal values,
expressions, INSERT SELECT, multi-row SQL VALUES, upserts, RETURNING, ambiguous or
unclassified syntax, and comment-prefixed writes are rejected with SQLState
`0A000` before native prepare. SELECT without WITH or INTO remains supported.
DELETE, DDL and stored procedure calls are not in the active-policy write allowlist.
All three prepareCall overloads are unsupported in Engine Agent, even before
mappings arrive. A raw CallableStatement must not escape
and later execute an untransformed INSERT through the vendor call parser.
All createStatement/prepareStatement concurrency overloads accept only
CONCUR_READ_ONLY, regardless of whether mappings exist. Updatable ResultSets are
unsupported: updateRow/insertRow would bypass protected parameter binding.
This is deliberately conservative, not a claim to support every vendor SQL form.

Protected parameters accept string setters, String-valued setObject and SQL
NULL. Numeric, byte, stream, LOB and other unsupported protected setters fail
closed before native binding. Unprotected parameters retain native setter/type
and invalid-index validation. Database defaults, triggers and writes outside
this Agent are not transformed by this client-side bind guard; deployment must
not use plaintext-producing defaults/triggers for protected columns.

All prepareStatement overloads validate before delegation. Statement SQL overloads
cannot bypass the guard. Ordinary Statement batch execution is rejected whenever
policies are active, including batches queued before policy arrival.

A prepared statement whose column mapping snapshot changes must be prepared and
bound again (`HY010`). Only successful native protected binds are recorded.
clearParameters and rejected/failed rebinds invalidate those recorded bindings;
execute, executeUpdate, executeQuery, executeLargeUpdate and addBatch require
valid protected bindings. Previously queued encrypted batches remain usable after
clearParameters, provided the policy mapping snapshot has not changed.
setObject trace logging no longer prints input values or their prefixes.
Enabled Engine runtime startup/initialization failures always reject SQL, even
with a stale stored failOpen=true. Only explicit enabled=false is passthrough;
CallableStatement and updatable ResultSets remain unsupported in this client.

## Tests

- EnginePreparedSqlGuardTest: ten unit tests, including all six connection
  prepare overloads, late policy arrival, failed delegate rebind, clearParameters,
  setNull followed by setInt, invalid parameter index, and large update.
- EngineStatementGuardTest: two tests, including policy arrival after addBatch.
- Real PG17 and MySQL8.4 Engine JDBC gates: unsupported SQL and setters rejected,
  no rejected rows stored, late policy arrival rejects queued prepared/Statement
  batches, normal prepared/batch/NULL/empty/Unicode/partial operations and denial
  recovery still pass. Worker and native KMS are actual processes.
- Full Java 8 reactor test/build passes. These are client/product integration
  tests, not KCMVP validation evidence.

## Real Hub Consumer

Build a separate probe against the product fat JAR:

```bash
bash scripts/build-hub-sync-jdbc-probe.sh
```

The runner is `scripts/hub-sync-jdbc-consumer.py`. It reuses the parent's actual
`deploy/systemd/tests/process_gate.py` lifecycle without editing parent files.
Its child-only subclass extends the private manifest with `workerUrl`,
`workerClientCAFile`, `workerClientCertFile`, and `workerClientKeyFile`. These are
Agent TLS identity paths only, not KMS root/recipient/business keys.

```bash
export DADP_TEST_LICENSE_KEY PG_JDBC_JAR
export HUB_JDBC_RESULT_FILE="$PWD/dadp-jdbc-wrapper/target/real-hub-jdbc-result.json"
python3 scripts/hub-sync-jdbc-consumer.py \
  --hub /absolute/current-hub --kms /absolute/current-kms \
  --worker /absolute/current-worker --crypto-lib /absolute/native-library-dir \
  --keep-running 300 --keep-failed
```

Optional `WRAPPER_JAR` and `HUB_JDBC_PROBE_JAR` select artifacts. Java 17, keytool,
OpenSSL, Docker, and the local `dadp-pg17-kms-test:local` image are required.
Only disposable loopback endpoints are accepted. The test DB uses loopback
password authentication; production DB transport requirements are not waived.

For an already provisioned parent fixture with those four fields, invoke
`python3 scripts/hub-sync-jdbc-consumer.py --consume` with
`DADP_BROWSER_FIXTURE_FILE` set. The consumer provisions and deletes its own PG17
container, registers the actual table/column and policy through Hub APIs, and
enrolls the Agent through the real Hub. The Java probe starts with empty local
mapping storage, invokes the product MappingSyncService, checks the returned
binding and Worker endpoint, then uses DadpJdbcDriver/DadpProxyConnection.
Mappings are not manually injected into the Java probe.

Pass markers: `REAL_HUB_AGENT_SYNC_PASS` and `REAL_HUB_ENGINE_JDBC_PASS`.
Evidence includes prepared/batch, NULL/empty/Unicode, repeated getters and raw
`kms:{policyCode}:...` DB storage. Parent consumer logs stay private; the optional
receipt contains only sanitized pass markers and the tested Agent JAR hash.
The fixture license is an AIR_GAPPED development test license through the normal
Hub registration API, not proof of commercial license validation.
