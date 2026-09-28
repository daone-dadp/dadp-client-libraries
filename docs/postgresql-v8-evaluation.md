# PostgreSQL v8 JDBC Evaluation

This opt-in development path rewrites SQL to DB plugin functions. It is not a
production release, crypto implementation, policy authorization service, or
enforcement boundary against applications using a separate native connection.

## Entry Point And Settings

Use `com.dadp.jdbc.DadpJdbcDriver` (also registered by JDBC ServiceLoader), the
shaded `dadp-jdbc-wrapper-8.0.0-SNAPSHOT-all.jar`, and a PostgreSQL JDBC driver.
Use the normal `jdbc:dadp:postgresql://host:port/database` URL. Supply these
settings as JDBC `Properties`, never as URL parameters:

```properties
dadp.v8.mode=pg-evaluation
dadp.v8.schema=app
dadp.v8.table=users
dadp.v8.column.id=plain
dadp.v8.column.secret=text:TEST0001
```

```java
Properties p = new Properties();
p.setProperty("dadp.v8.mode", "pg-evaluation");
p.setProperty("dadp.v8.schema", "app");
p.setProperty("dadp.v8.table", "users");
p.setProperty("dadp.v8.column.id", "plain");
p.setProperty("dadp.v8.column.secret", "text:TEST0001");
p.setProperty("user", "evaluation_user");
p.setProperty("password", passwordFromSecretStore);
p.setProperty("sslmode", "verify-full");
p.setProperty("sslrootcert", trustedRootCertificatePath);
Connection c = DriverManager.getConnection(
        "jdbc:dadp:postgresql://db.example:5432/evaluation", p);
```

The explicit mapping is a trusted, immutable, connection-lifetime evaluation
snapshot supplied by the caller. It is not downloaded from the old policy
service and does not prove server authorization. Every referenced column must
be listed. `plain` means confirmed unprotected, not unknown. Protected columns
must be PostgreSQL text/UTF8; `text:` labels must match `[A-Za-z0-9]{8}` exactly.
Actual database schema/type validation is the evaluator's responsibility.
Schema/table/column identifiers are exact lowercase ASCII identifiers; quoted
identifiers and schema/search-path inference are unsupported. One table per
connection is supported. Reconnect to change mappings; there is no live refresh.

Presence of any `dadp.v8.*` property selects validation of this isolated path;
missing/invalid mode, schema, table, empty mapping, unknown v8 settings or invalid
column descriptors fail before connecting. A referenced unmapped column fails
before preparing/executing SQL. Without v8 properties the legacy path remains
unchanged. v8 URL parameters are rejected even without v8 Properties, preventing
accidental legacy activation. Native user/password/TLS properties are preserved;
v8 properties are stripped before handing them to PG. No TLS verification is
disabled. The caller must configure and verify the appropriate TLS protection.

`DadpJdbcDriver.connect` dispatches to `V8PostgresqlConnection` before legacy
configuration, enrollment, policy sync, telemetry or `DadpProxyConnection`
construction. That path has no Hub/Engine crypto calls or ResultSet decryption.
There is exactly one native driver connection attempt and no remote fallback.

## SQL Support

| SQL | Status / Restriction |
| --- | --- |
| INSERT | Supported: explicit column list, one VALUES row, scalar text/number/NULL/bind inputs; protected values text/NULL/bind only |
| UPDATE | Supported: scalar SET assignments; optional single `known_plain_column = scalar` WHERE |
| SELECT | Supported: explicit column projection, one mapped table; optional single known-plain equality WHERE |
| Aliases | SELECT table aliases and explicit column aliases retained; a rewritten bare protected column gets its original label |
| Explicit UDF | Supported only exact matching `dadp.dadp_encrypt(value, 'POLICY01')` write or `dadp.dadp_decrypt(protected_column)` projection; no second wrapping |
| Explicit UDF label | Without alias, native PG `dadp_decrypt` label retained; with alias, alias retained |
| Bind parameters | JDBC `?` count/order unchanged; no policy binds or casts added; setter arguments are delegated unchanged |
| NULL | Literal NULL unchanged; bound NULL evaluated by strict PG function |
| Unsupported SQL | Rejected before sending: stars, protected predicates, joins, ordering/grouping/aggregates, CTE, set operations, INSERT SELECT, multiple rows, upsert, RETURNING, COPY, CALL, DDL, DELETE, multiple statements, prefixed literals, arbitrary expressions |

The actual fixed AST-generated function contract is:

```sql
dadp.dadp_encrypt(plaintext text, policy_code text)
dadp.dadp_decrypt(ciphertext text)
```

Rewrites are bounded to 16,384 input characters and a 1-second parser timeout.
Additional AST clauses are rejected by comparing against an allowlisted AST.
Unknown/unprotected mappings never cause a pass-through on unsupported syntax.
Numeric protected bind types are not cast to text: the PG function resolver
rejects incompatible setter types. Use `setString`, compatible `setObject`, or
`setNull(..., Types.VARCHAR)` for protected text. There is no binary protection.

## JDBC Surface

All `prepareStatement` and `createStatement` overloads either wrap a read-only
statement or reject unsupported options before delegation. All Statement SQL
`execute`, `executeQuery`, `executeUpdate`, `executeLargeUpdate`, and `addBatch`
overloads rewrite. Prepared SQL is rewritten before native preparation; reuse,
binds, `clearParameters`, batches, large batches, commit, rollback, savepoints,
cancellation and closing otherwise delegate to JDBC without value transformation.

Generated key requests (`RETURN_GENERATED_KEYS`, column-name/index arrays) and
`getGeneratedKeys` are rejected: PG can append `RETURNING *`. `NO_GENERATED_KEYS`
is allowed. Updatable result sets and ResultSet mutations are rejected.
`prepareCall`, `nativeSQL`, vendor unwrap, type maps, Array/Struct/LOB/SQLXML
factories and corresponding ResultSet getters are rejected. `getObject` first
checks metadata for standard scalar JDBC types; ARRAY, REF_CURSOR, OTHER,
STRUCT and similar types are rejected before invoking a getter that might run
additional SQL. This also rejects `getObject` on PG UUID (Types.OTHER); use a
supported scalar getter such as `getString`. Driver-specific complex Java
results are not exposed. All prefixed SQL literals, including E/N/B/X, are
outside this evaluation allowlist.

Statement/metadata connection back-references and ResultSet statement
back-references stay wrapped. Metadata ResultSets report a null statement.
ResultSetMetaData/ParameterMetaData are guarded against vendor unwrap. Normal
scalar result getters return the DB's value unchanged. Function projections
can change native column provenance and expression metadata; labels, not full
base-table metadata equivalence, are preserved. Vendor interfaces, ORM SQL
generators, arbitrary metadata workflows and pools relying on vendor unwrap
are not certified by this evaluation.

DB SQLExceptions are replaced with a fixed message, retaining SQLState/vendor
code, but no original message, cause, nextException or suppressed exceptions.
BatchUpdateException retains large/int update counts. Cancellation state 57014
is preserved. SQLWarnings retain state/code with a fixed message and no chain.
No SQL or bind values are logged by the new path. Native-driver/application/DB
logging remains the deployer's responsibility. Failed operations are not
retried with original SQL and do not change transaction state in the wrapper.

## Verification

```bash
mvn -B -q -pl dadp-jdbc-wrapper -am test
mvn -B -q -pl dadp-jdbc-wrapper -am package -DskipTests
mvn -B -q -pl dadp-jdbc-wrapper -am test \
  -Dtest=ExperimentalSqlRewriterTest,V8PostgresqlConnectionTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Unit tests exercise AST output, PG policy validation, bind slots, aliases,
single UDF application, missing mappings, unsupported syntax, every JDBC SQL
overload, unchanged setters, prepared reuse/batches, transaction delegation,
exception/warning redaction, generated keys rejection and JDBC escape guards.

2026-09-28 verification of this implementation:

- Full reactor test command above: exit 0; wrapper 230 tests, zero failures,
  errors or skips (84 AST cases, 13 JDBC boundary tests included).
- Shaded package command above: exit 0. PG driver remains external.
- Independent parent-owned `tests/wrapper-contract/run.sh` execution against the
  shaded artifact: reported exit 0, 382 assertions, real private loopback PG16,
  cluster cleanup confirmed. Tested cross-connection direct-UDF/Wrapper SQL
  interoperability, UTF8/NULL/TOAST, binds/aliases, batches/rollback, ACL denial
  and recovery, sanitized batch exceptions with counts, unsupported-write
  rejection, generated keys, Array escape blocking, and a calibrated refcursor
  probe showing `getObject` was rejected without implicit FETCH side effects.
- Focused read-only peer review of those guards: no remaining actionable
  findings after correction. This is not a comprehensive security audit.

The parent repository owns the independent real PostgreSQL SQL-contract harness
and its run evidence. SQL test-double success is not evidence of cryptography,
INISAFE execution, envelope compatibility, key handling or production security.
Native licensing does not block AST/JDBC development. Live policy delivery,
partial encryption, server authorization, protected key supply, native crypto
verification, TLS deployment verification and production release remain outside
this bounded implementation.
