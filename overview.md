a Cypher parser plus a strict validation policy is a practical way to build the proxy. It can let users write flexible read queries instead of choosing predefined reports.
The flow would be:
Redash → authenticate → parse Cypher → validate entire syntax tree
       → EXPLAIN check → execute approved query → return results
The parser converts the query into a structured tree. Our validator would then:
- Accept exactly one complete statement. Reject parsing errors, unsupported syntax, and unconsumed input.
- Allow only approved read constructs, such as MATCH, WHERE, WITH, aggregation, and RETURN.
- Inspect every nested branch, including subqueries and UNION branches.
- Initially reject all procedure calls and custom functions, plus LOAD CSV, administrative commands, and write operations. Allow reviewed built-in functions.
- Run EXPLAIN as an additional check and require read-only query classification.
- Apply execution timeouts and result limits.