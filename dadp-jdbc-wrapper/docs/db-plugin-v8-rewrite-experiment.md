# V8 SQL rewrite: bounded AST experiment

Status: implemented and unit-tested experiment, NOT a supported JDBC execution
mode, approved SQL ABI, DB support declaration, or release. G1-G3 remain open.
Date: 2026-09-18. Branch: `codex/db-plugin-v8`.

## Findings and scope

The required parent architecture/design/work-order/workspace documents were read.
No AGENTS.md was found in this repository, the v8 parent tree, or ancestor paths.
Only this independent Wrapper repository was edited.

Existing `policy/SqlParser.java` uses regex and manual lineage traversal, not an
AST. `DadpProxyPreparedStatement` builds parameter mappings and transforms bound
values. `DadpProxyResultSet` resolves labels/metadata and decrypts retrieved values.
The active shared `PolicyResolver` uses schema/table/column lookups, including a
table-only fallback. Vendor resolution supplies schema defaults and label lookup;
it is not a SQL rewrite dialect. None of those runtime paths is changed here.

Because there was no existing AST to extend, this isolated component adds
JSqlParser 4.9. Upstream identifies it as the last Java 8-compatible release:
https://github.com/JSQLParser/JSqlParser#java-version
The dependency has a Wrapper shade relocation to avoid application conflicts.
Parser upgrades and packaged-runtime behavior still need separate review.

## Explicit fixture contract

`new ExperimentalSqlRewriter()` is disabled and returns the exact input. It is
not a security enforcement point while disabled. Only `forFixture(scope,
functions)` enables rewriting. No property, JDBC URL, connection, statement or
ResultSet activates it. Callers must NOT feed its output into the legacy value
encryption/decryption wrappers. Production integration is a separate gate.

The immutable scope binds one exact schema/table to an explicit column map.
Every referenced column must occur in the map; a null policy explicitly denotes
an unprotected column. Missing columns/tables/schemas are errors, not policy
misses or plaintext passthrough. There is no Hub lookup or default-schema fallback.
Identifiers and policy tokens are restricted to lowercase ASCII fixture tokens;
quoted identifiers and dialect case folding are deliberately not implemented.
Fixtures use the existing PostgreSQL-style schema-qualified SQL vocabulary, not
a claim that PostgreSQL has been selected or approved.

Function names are mandatory injected fixture values. The trial shape is
`function(value, 'policy_token')`, not a final API. No key/revision/authority wire
contract is implied. Input expressions are AST nodes; there is no SQL substring
replacement. Policy literals are AST StringValue nodes with restricted tokens.

Only allowlisted AST statement shapes are serialized. Reconstructing the allowed
shape and comparing AST serializations rejects additional clauses before output
is returned. Parser errors become a generic SQLState `0A000` exception without
SQL text or parser causes. Input is capped at 16,384 characters and parsing uses
a 1,000 ms parser timeout; this is not a hard real-time resource guarantee.

## Fixture coverage

| Shape | Behavior |
| --- | --- |
| INSERT with explicit columns and one VALUES row | Wrap protected scalar values |
| UPDATE with simple scalar assignments, no WHERE | Wrap protected assignments |
| SELECT column projections from one table | Wrap protected columns; retain explicit aliases or add original column label |
| Anonymous positional `?` | Keep order/count and original uncast AST parameter |
| String, integer, decimal literals | Retain scalar node; no Java value conversion |
| Literal NULL | Leave NULL unchanged |
| Unprotected known columns | Leave expression unchanged |
| Second rewrite of function-bearing output | Reject; never nest another crypto call |
| Functions, casts, column-valued assignments | Reject, including user-supplied crypto functions |
| WHERE, joins, grouping, ordering, aggregates, star, CTE, union | Reject |
| Multi-row/INSERT SELECT/upsert/RETURNING/bulk/procedures | Reject |
| Multiple statements, named/numbered binds, unknown/ambiguous identifiers | Reject |

The no-WHERE UPDATE is a structural fixture, not advice to update all rows.
All unsupported statements fail closed in enabled fixture mode, even if a caller
believes they are unprotected. No remote fallback or direct execution occurs.

Literal NULL preservation is verified. Bound NULL and decrypted NULL require the
future DB functions to be NULL-strict. No statement setters are invoked by this
component: unit tests prove parameter nodes/positions and no inserted casts, NOT
JDBC parameter type inference, binary encoding or driver metadata equivalence.

## Actual verification

Environment: Ubuntu WSL2 x86_64, OpenJDK 17.0.20, Maven 3.8.7. Wrapper compiles
with `--release 8`; execution on a Java 8 VM has not been tested.

Commands run from the independent repository root:

```sh
mvn -B -q -pl dadp-jdbc-wrapper -am test -DskipTests
mvn -B -q -pl dadp-jdbc-wrapper -am test -Dtest=ExperimentalSqlRewriterTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -q -pl dadp-jdbc-wrapper -am test
git diff --check
```

- Compile: PASS.
- New suite: 68 tests, 0 failures/errors/skips. Golden SQL, bind index/column
  order, aliases, NULL, malformed/unsupported SQL, duplicate transformations,
  immutable policy snapshot, injectable names and disabled passthrough covered.
- Full Wrapper suite: 201 tests, 0 failures/errors/skips, Maven exit 0.
  Reactor output includes an upstream module with zero discovered tests; this
  is not a claim that every upstream module has full test coverage.
- Existing negative startup/HTTP tests print missing-storage and HTTP 500 warnings.
  No enrollment or live service was needed for these tests.
- Initial targeted runs failed 6, then 2 cases due to list constructor overload
  and single-column VALUES AST representation. Both were fixed; the final runs
  above passed. Existing tests were not changed.

## Unverified and next gates

G1 must choose DB/version/OS and driver before adding real dialect normalization
or declaring DB support. G2 must approve function names, SQL input/output types,
NULL/error behavior, binary/text encoding, authority/policy references, and common
vectors. The fixture names and policy argument are proposals only.

G3 must approve authentication, authorization and protected key import/lease.
This component contains no JNI, key cache, actual key path, native crypto,
network crypto fallback, or HA. Partial encryption is not implemented here:
the selected range denotes plaintext retention and belongs at the front of the
stored string; selection units/names and reconstruction metadata await G2.

After contracts are approved, the next bounded change should add an explicit JDBC
execution mode that bypasses BOTH legacy parameter encryption and ResultSet
decryption. It must fail closed on policy snapshot uncertainty/refresh races,
and verify setters (including setNull/setObject/streams), batch/transactions,
getters/wasNull, aliases and ResultSet/ParameterMetaData with the chosen driver.
Direct-UDF interoperability, policy/key rotation, native failures, DB privileges,
packaged jar smoke tests, install/TLS tests and production activation remain undone.
No encrypted DB data was written, and no G1-G5 approval is asserted.
