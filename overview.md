The Linux service exposes an API only, on 127.0.0.1:4444. Redash supplies the UI
and connects from a separate host through an SSH tunnel. Native Neo4j credentials
are verified at login; the API signs a five-minute JWT identifying the account.
An administrator-owned configuration file determines readOnly/full permissions
across all labels. Redash requests read-only execution; full accounts can modify
data through the API. Schema/admin commands and procedures remain unavailable.

A Cypher parser plus a strict validation policy lets users write flexible queries.
The flow would be:
Redash → login/JWT → load account permission → parse Cypher → validate entire syntax tree
       → EXPLAIN check → enforce permission → execute in same transaction → bounded results → commit
The parser converts the query into a structured tree. Our validator would then:
- Accept exactly one complete statement. Reject parsing errors, unsupported syntax, and unconsumed input.
- Allow reviewed data-query constructs; data modifications require full permission.
- Inspect every nested branch, including subqueries and UNION branches.
- Initially reject all procedure calls and custom functions, plus LOAD CSV, administrative commands, and write operations. Allow reviewed built-in functions.
- Run EXPLAIN as an additional check. Read-only requests require READ_ONLY classification;
  full accounts may also execute MODIFY queries. Unknown classifications are rejected.
- Apply execution timeouts and result limits.
