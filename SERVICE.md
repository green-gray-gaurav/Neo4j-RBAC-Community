# Linux API and Redash deployment

Requires Java 21 and Neo4j **5.23.0** with native authentication enabled.
There is no UI, CORS setup, cookie session, or additional database.
Runtime additions are the official Neo4j driver, Jackson JSON, and Auth0 java-jwt.
Neo4j harness is test-only. No database credentials or signing keys are shipped.

## Build and install

```sh
./mvnw --batch-mode clean verify
python3 -m unittest discover -s redash -p 'test_*.py'
sudo sh deploy/install.sh target/neo4j-rbac-parser-proof-0.1.0-SNAPSHOT-service.jar
```

The installer creates a non-login OS user, installs the JAR/unit, generates a
32-byte signing secret with OpenSSL, and creates an **empty permission map**.
It preserves existing config/key files on subsequent installs. It does not start
the service or change Neo4j. Java 21, OpenSSL and systemd must already be installed.
`/usr/bin/java` must select Java 21; otherwise override ExecStart with `systemctl edit`.

Edit `/etc/neo4j-rbac/service.env` as root to set the actual Neo4j URI, database,
backend username and password. The backend credential is used for execution;
login credentials are used only to verify the API account's identity with Neo4j.
The API does not impersonate native Neo4j users during execution.
Keep config and key files readable only by root and the service group (0640),
and their directory root-owned (0750). They must not be writable by the service.

Add approved native usernames to `/etc/neo4j-rbac/permissions.json`, following
`deploy/permissions.example.json`. Admin setup Cypher is in README.md.

```sh
sudo systemctl enable --now neo4j-rbac
curl --fail http://127.0.0.1:4444/health
sudo systemctl status neo4j-rbac
sudo journalctl -u neo4j-rbac
ss -ltn 'sport = :4444'
```

The listener is fixed to IPv4 loopback, not 0.0.0.0. The service starts on boot,
restarts on failure, and drains active requests on shutdown. Health is process
liveness only. Startup verifies the backend Neo4j connection; subsequent errors
are reported per request. Permissions are reloaded without restart; signing-key
rotation requires replacement of `jwt.key` and a restart, invalidating all JWTs.

## API

`POST /v1/auth/login` with `Content-Type: application/json`:

```json
{"username":"redash_reader","password":"<supply securely>"}
```

Response:

```json
{"access_token":"<signed JWT>","token_type":"Bearer","expires_in":300}
```

The API verifies credentials against Neo4j and requires an entry in the
permissions file. Tokens contain subject, token ID, issuer, audience and timestamps;
they contain neither passwords nor permissions. Signature algorithm is fixed to
HS256 with a server-only random secret. No refresh endpoint or token cache is needed.

`POST /v1/query`, with `Authorization: Bearer <token>` and JSON content type:

```json
{"query":"MATCH(n:Customer) WHERE n.id=$id RETURN n.name AS name","parameters":{"id":42},"readOnly":true}
```

`parameters` defaults to `{}` and `readOnly` defaults to `false`. A true flag can
only narrow access. False cannot elevate a readOnly account. Unknown fields,
duplicate JSON keys, trailing JSON, non-object parameters, and multiple Cypher
statements are rejected. Clients cannot supply a database, username, role or mode.

```json
{"requestId":"generated-id","classification":"READ_ONLY","columns":["name"],"rows":[["Example"]],"rowCount":1}
```

All labels, unlabeled reads, traversals, OPTIONAL MATCH, WITH, UNION, aggregation,
UNWIND, read subqueries, pattern comprehensions and whole graph values are supported.
Full accounts also support CREATE/INSERT, MERGE, SET, DELETE, REMOVE, FOREACH and
data-write subqueries. Registered built-in functions are allowed; custom/unresolved
functions, procedures, LOAD CSV, USE, schema/admin commands and IN TRANSACTIONS
subqueries are blocked in both modes. User-supplied CYPHER/EXPLAIN/PROFILE prefixes
are rejected. The classifier's FULL helper is not a blanket service authorization.

EXPLAIN and execution use the same backend identity, database, parameters, original
query text, and explicit transaction. No automatic retry is performed. Permissions
are checked again before commit. Result limits or detected permission revocation
roll back writes. A caller can still lose the response after commit: clients must
not automatically retry modifying requests following a timeout or network error.

Scalars retain JSON types. Integers outside JavaScript's safe range, non-finite
numbers, temporal/duration/spatial values become strings. Bytes become base64.
Nodes contain elementId/labels/properties; relationships contain elementId/type/
startElementId/endElementId/properties; paths contain node and relationship arrays.

| Boundary | Default |
| --- | --- |
| Body / query text | 256 KiB / 64 KiB |
| Concurrent work / admission slots | 8 / 40; contention beyond 100 ms returns 429 |
| Connection / acquisition timeout | 5 seconds each |
| Server transaction / HTTP request deadline | 10 / 15 seconds |
| Result rows / serialized result bytes | 1,000 / 4 MiB |
| JSON input nesting | 50 |

`RBAC_MAX_ROWS` and `RBAC_MAX_RESULT_BYTES` may override result limits at startup.
There is no silent truncation. The HTTP deadline interrupts the handler and closes
the exchange after attempting a 504. Neo4j's transaction timeout bounds work still
waiting in the driver. Disconnects detected during response writing are logged;
an earlier client disconnect cannot reliably cancel database work already underway.

Errors contain requestId/code/message and no raw query, password or stack trace.
401 = invalid credentials/token; 403 = no grant or disallowed operation; 400 = invalid
input/planning; 413 = input limit; 415 = content type; 422 = result limit/encoding;
429 = busy; 503 = policy/database unavailable; 504 = timeout. Audit logs contain
account, request ID, outcome, classification/row count on success, and duration.

## SSH tunnel from the Redash host

The API stays on loopback. Install `deploy/neo4j-rbac-tunnel.service` on the
**Redash host**. Create a dedicated local `redash-tunnel` OS account and the
root-owned directory `/etc/neo4j-rbac-tunnel`. Supply these files, readable only
by root and that account's group:

- `id_ed25519`: dedicated SSH private key, mode 0640.
- `known_hosts`: API host key verified through a trusted channel; do not disable checking.
- `tunnel.env`: `RBAC_SSH_TARGET=rbac-tunnel@your-api-host`.

On the API host, authorize the public key for a dedicated forwarding account.
Restrict that SSH account to local forwarding to `127.0.0.1:4444` (sshd Match block:
`AllowTcpForwarding local`, `PermitOpen 127.0.0.1:4444`, `PermitTTY no`,
`MaxSessions 0`). Validate with `sshd -t` before applying SSH configuration changes.

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now neo4j-rbac-tunnel
curl --fail http://127.0.0.1:14444/health
```

The tunnel's loopback listener must be in the Redash worker's network namespace.
For Linux Docker workers, use host networking for the worker using this host-level
tunnel, or run an equivalent tunnel sidecar sharing the worker's network namespace.
Browser traffic never calls port 4444 directly. Native Neo4j ports must remain
restricted to trusted hosts; Community credentials can bypass API restrictions
if clients can connect directly to Neo4j.

## Redash query runner

Copy `redash/neo4j_rbac.py` into your Redash image at
`/app/redash/query_runner/neo4j_rbac.py`. `redash/Dockerfile` extends your existing
image; supply its exact current tag as the `REDASH_IMAGE` build argument.
Set `REDASH_ADDITIONAL_QUERY_RUNNERS=redash.query_runner.neo4j_rbac` (append to any
existing runners), and deploy the same image/config to Redash web and workers.
The adapter follows Redash 10.1.0's BaseQueryRunner contract and uses only Python's
standard library; verify it on your deployed Redash release before rollout.

Create a **Neo4j RBAC (read-only)** data source with:

- API URL: `http://127.0.0.1:14444`.
- Username/password: the native Neo4j account also listed as readOnly in permissions.json.

Password is marked secret in the Redash schema. Use Redash groups to control which
people can use the source. They share its account identity; the Redash user argument
is not trusted as an API identity. Query execution and scheduled refresh follow
the same path: obtain a fresh JWT, submit the rendered Cypher with `readOnly:true`,
and map results into Redash columns/rows. HTTP timeout is 20 seconds; there are no
POST retries, redirects, environment proxies, or persisted JWTs. This adapter only
accepts loopback HTTP URLs intended for the tunnel. Rendered Redash parameters
remain query text; use the HTTP/Java API for typed Cypher parameter binding.

Nested graph objects are JSON strings in Redash cells. Duplicate column names are
disambiguated. No schema-browser endpoint is exposed. Test the data-source
connection, run `MATCH(n) RETURN labels(n), count(*)`, and verify a scheduled refresh.
Attempting CREATE must fail without changing data. A real Redash installation,
remote SSH tunnel and target Linux host are deployment acceptance checks, not
things the local automated tests claim to have exercised.

## Verification

`./mvnw clean verify` runs parser, classifier, JWT, HTTP capacity/deadline and
authenticated Neo4j 5.23.0 integration tests. They verify read/full separation,
revocation, AST rejection, parameters, large integers and rollback on result limits.
Adapter tests exercise actual local HTTP with a stub of Redash's documented base
class; they cover interactive/scheduled calls, conversion and refusal/no retries.
CI also checks the install script syntax. The systemd unit may be checked with
`systemd-analyze verify deploy/*.service` on Linux before installation.
