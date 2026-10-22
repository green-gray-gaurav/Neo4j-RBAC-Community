# Neo4j RBAC Community — parser proof

The first implementation milestone for the read-only gateway proposed in
[overview.md](overview.md) and [RBAC](RBAC).

Uses **Neo4j's official Cypher 5 parser from Neo4j 5.23.0**:
`org.neo4j:cypher-v5-ast-factory:5.23.0`. There is no custom grammar, regex
parser, database connection, HTTP gateway, policy store, or query execution.

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

Only milestone 1 is implemented. No claim of safe query execution is made.

## Contributors

- **OpenAI Codex (AI coding assistant)** — assisted with the Neo4j 5.23.0
  parser proof, AST inspection, tests, build setup, and documentation under
  the repository owner's direction.
