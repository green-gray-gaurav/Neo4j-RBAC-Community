# Neo4j RBAC Community

Linux API on **127.0.0.1:4444**, with Neo4j login, signed JWTs, administrator-owned
read/full permissions, bounded execution, and a read-only Redash adapter.

Uses **Neo4j's official Cypher 5 parser from Neo4j 5.23.0**:
`org.neo4j:cypher-v5-ast-factory:5.23.0`. There is no custom grammar, regex
parser or separate UI. The standalone classifier sends `EXPLAIN` only; the API
executes authorized data queries after classification in the same transaction.

## Service

For a complete local Redash environment, see [the disposable test stack](redash/test-stack/README.md).

```text
Redash → local SSH tunnel :14444 → Linux API 127.0.0.1:4444 → Neo4j 5.23.0
```

The API supports **readOnly and full** accounts across **all labels**. The Redash
adapter always requests read-only execution, even if its account was accidentally
given full permission. Full means data reads/writes, not schema changes,
administration, procedures, or custom functions. There is no per-label filtering
in this release. See [SERVICE.md](SERVICE.md) for installation, API examples,
limits, Linux services, tunnel setup, Redash registration, and known boundaries.

Permissions live outside Neo4j, in `/etc/neo4j-rbac/permissions.json`:

```json
{"users":{"redash_reader":"readOnly","data_writer":"full"}}
```

The service checks this file at login, on every query, and again before commit.
Missing users are denied; malformed/unavailable configuration fails closed.
Remove an account to revoke existing tokens immediately. Update the file with
an atomic replacement and preserve owner `root:neo4j-rbac`, mode `0640`.

### Administrator-run user setup

Log into Neo4j Browser as an administrator and select `:use system`. Supply
`$password` through the client's parameter binding, then run each command with
the appropriate password. These are **native Neo4j users**, not nodes labeled User.

```cypher
CREATE USER redash_reader SET PASSWORD $password CHANGE NOT REQUIRED;
CREATE USER data_writer SET PASSWORD $password CHANGE NOT REQUIRED;
SHOW USERS;
```

Password rotation/removal, also run directly by the administrator:

```cypher
ALTER USER redash_reader SET PASSWORD $password CHANGE NOT REQUIRED;
DROP USER redash_reader IF EXISTS;
```

Then add the corresponding usernames/modes to the permissions file. No Cypher
`GRANT ROLE` command is needed: Community users do not have database-enforced
read-only permissions. Restrict direct Bolt/HTTP database access to trusted hosts
so clients cannot bypass the API. JWTs issued before a native password change or
user deletion remain valid for at most five minutes; remove the file entry for
immediate API revocation.

## Run

Install JDK 21 or newer and set `JAVA_HOME`. The Maven wrapper downloads Maven
3.9.11 and resolves the pinned libraries from Maven Central on first use.

PowerShell:

```powershell
.\mvnw.cmd clean verify
Get-Content -Raw examples/read.cypher | .\mvnw.cmd -q exec:java
```

Linux/macOS:

```sh
./mvnw clean verify
./mvnw -q exec:java < examples/read.cypher
```

The CLI reads UTF-8 Cypher from stdin and prints AST node types, tree paths,
source positions, and decoded static label names. Invalid syntax or multiple
statements produce a nonzero exit code. Native diagnostics can include input
text; this is a local development tool, not a public error response handler.

## What this proves

| Capability | Implementation |
| --- | --- |
| Native AST | `CypherParserProof.parseSingle` returns Neo4j's `Statement` directly. |
| Complete input | Official `statements()` entry point parses through EOF; wrapper requires exactly one statement. One trailing semicolon and comments are accepted. |
| Nested inspection | `AstInspection` walks AST case-class fields, optional children, and Scala collections, including subqueries, UNION branches, and expression subqueries. |
| Source positions | Each diagnostic AST entry includes the native offset, line, and column. Some synthetic/type nodes have no source position. |
| Serialization proof | `render` uses Neo4j's own `Prettifier`; fixtures are parsed, printed, and reparsed, checking printer stability and AST type/label preservation. |

Tests cover parameters, escaped identifiers, comments, semicolons in literals,
aggregation, ordering, pagination, nested reads, writes, procedures, custom
functions, administrative statements, malformed input, and trailing garbage.

**Parsing is not authorization or semantic validation.** Valid writes and
procedure calls intentionally parse successfully. Undefined variables can also
parse. Label names in this diagnostic report are not a complete access inventory:
unlabeled scans and runtime node labels require later policy enforcement. The
inspector's paths and type names expose an internal AST, not a stable public API.

The direct parser entry point accepts statement bodies. It intentionally rejects
`CYPHER`, `EXPLAIN`, and `PROFILE` prefixes; support for server pre-parser options
is outside this proof. It does not prove bounded CPU/memory use for hostile input
or full semantic equivalence of every possible printer round trip.

## Query classification

`QueryClassifier` uses the official Java driver **5.23.0**, with one explicitly
selected database and a positive transaction timeout. Reuse the application's
`Driver`; the classifier opens and closes a session for each request.

```java
var classifier = new QueryClassifier(driver, "neo4j", Duration.ofSeconds(5));
var result = classifier.classify(
    "MATCH (c:Customer) WHERE c.id = $id RETURN c.name",
    Map.of("id", 42L));
boolean eligible = result.eligibleFor(QueryClassifier.Mode.READ_ONLY);
```

The flow is: parse exactly one statement body → recognize schema/admin ASTs →
`EXPLAIN` ordinary queries on Neo4j → inspect `ResultSummary.queryType()`.
The original body and typed parameters are passed unchanged, prefixed with
`EXPLAIN` and a newline. User-supplied `CYPHER`, `EXPLAIN`, and `PROFILE` prefixes
are rejected before any server request. There is no query retry or classification cache.

| Classification | Meaning | READ_ONLY eligibility | FULL eligibility |
| --- | --- | --- | --- |
| `READ_ONLY` | Server reports `READ_ONLY`, with no conflicting AST update flag | Yes | Yes |
| `MODIFY` | Server reports `READ_WRITE` or `WRITE_ONLY` | No | Yes |
| `SCHEMA` | Native schema-command AST or server `SCHEMA_WRITE` | No | Yes |
| `ADMINISTRATIVE` | Native administrative-command AST, including `SHOW USERS` | No | Yes |
| `UNKNOWN` | Invalid input, planning/auth/connection failure, missing plan/type, or AST/server disagreement | No | No |

Schema/admin categories are recognized locally and do not require a server
request; their `serverType` is null. They are not validated against server permissions.
For data queries, Neo4j resolves installed procedures during EXPLAIN, so a
registered WRITE procedure is classified as `MODIFY` even when the standalone
AST's `containsUpdates()` says false. Unavailable procedures become `UNKNOWN`.

**Eligibility is not authorization or execution.** FULL does not elevate database
permissions. READ_ONLY describes database modification behavior, not absence of
external side effects: read procedures/functions and `LOAD CSV` can access files
or networks. The later gateway policy must reject unreviewed procedures/functions
and `LOAD CSV`, and enforce label access. Classification trusts Neo4j's registered
procedure/function metadata and is scoped to the database, identity, and installed
extensions used for EXPLAIN; it must not be reused across changes to that context.
The timeout bounds the server transaction, not all parsing or wall-clock work.

### Classifier CLI

Set `NEO4J_URI`, `NEO4J_USERNAME`, `NEO4J_PASSWORD`, and `NEO4J_DATABASE` in the
process environment using your secret manager or hidden prompt. Do not pass
credentials as CLI arguments or commit them to files. The CLI uses five-second
connection, acquisition, and EXPLAIN timeouts. Its simple stdin interface uses
no parameters; use the Java API above for parameterized queries.

```powershell
Get-Content -Raw examples/read.cypher | .\mvnw.cmd -q exec:java '-Dexec.mainClass=io.github.greengraygaurav.rbac.ClassifyCli'
# Optional: append '-Dexec.args=FULL' to report FULL eligibility.
```

```sh
./mvnw -q exec:java -Dexec.mainClass=io.github.greengraygaurav.rbac.ClassifyCli < examples/read.cypher
```

Output includes kind, native server type (when available), selected mode, and
eligibility. Exit code is 0 for eligible, 2 for rejected/unknown, and nonzero for
configuration errors. Even FULL mode sends only EXPLAIN, never the original query
for execution. The existing parser-inspection CLI remains the default main class.

`./mvnw clean verify` also starts a disposable **Neo4j 5.23.0** server over Bolt
through the test-only Neo4j harness. No Docker or external database is needed.
It checks reads, writes, nested writes, parameters, procedure modes, semantic
failures, and missing databases, and verifies no nodes, indexes, or procedure
side effects were produced by classification. The harness is not a runtime dependency.

## Sources and version boundary

All Neo4j artifacts are pinned to 5.23.0. These are Neo4j internal APIs; changing
the version requires recompiling and rerunning the corpus before adoption.
The adapter is isolated in `CypherParserProof` to keep that dependency visible.

- [Official 5.23.0 parser implementation](https://github.com/neo4j/neo4j/blob/5.23.0/community/cypher/front-end/parser/v5/ast-factory/src/main/scala/org/neo4j/cypher/internal/parser/v5/ast/factory/Cypher5AstParser.scala)
- [Official 5.23.0 parser artifact dependencies](https://github.com/neo4j/neo4j/blob/5.23.0/community/cypher/front-end/parser/v5/ast-factory/pom.xml)
- [Official 5.23.0 AST printer](https://github.com/neo4j/neo4j/blob/5.23.0/community/cypher/front-end/ast/src/main/scala/org/neo4j/cypher/internal/ast/prettifier/Prettifier.scala)

## Next milestones

1. **Parser proof (this module).** Native syntax tree, inspection, and corpus.
2. **Policy store.** Trusted identity lookup, role grants, and revocation behavior.
3. **Authorization compiler.** Fail-closed AST validation, scope tracking, and
   runtime label filters before aggregation. Reject unknown constructs.
4. **Bounded executor.** EXPLAIN classification, deadlines, cancellation, and limits.
5. **Redash adapter.** Verified identity contract for interactive and scheduled queries.
6. **Adversarial testing.** End-to-end authorization and bypass tests against 5.23.0.

The parser, classifier, all-label API, permissions file and Redash adapter are
implemented. Graph-based per-label authorization remains future work.

## Contributors

- **OpenAI Codex (AI coding assistant)** — assisted with the Neo4j 5.23.0
  parser proof, AST inspection, tests, build setup, and documentation under
  the repository owner's direction.
