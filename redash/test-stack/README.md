# Disposable local Redash test stack

Requires Docker Desktop/Engine with Compose and the built gateway JAR in `target/`.
Allow at least 5 GB of free space on the drive holding Docker's disk image before
starting, including space for image extraction and database volumes.
This starts actual Redash **26.9.0**, Neo4j Community **5.23.0**, PostgreSQL 16,
Redis 7, the gateway, and a Redash worker/scheduler. It is separate from production.
The official Neo4j UBI9 and PostgreSQL Debian image variants are used. The adapter
returns structured results for Redash 26.9.0; older Redash versions are not covered
by this setup.

```sh
python redash/test-stack/local_test.py start
python redash/test-stack/local_test.py status
python redash/test-stack/local_test.py verify
```

Open **http://localhost:5000** and log in as `admin@rbac.test`. The generated
password is the `admin_password` field in `.local/secrets.json`. Do not commit or
share that file: it also contains the disposable database credentials. Local
state is ignored by Git and excluded from Docker build contexts; the startup
script restricts its filesystem permissions. Reuse it with the existing volumes.

The setup creates the **Neo4j RBAC Test (read-only)** data source and three saved
queries: all labels, a customer/order traversal, and a write that should fail.
Sample data is synthetic. Neo4j native ports, PostgreSQL and Redis are not
published. The only host port is IPv4 loopback **5000** for Redash.

Redash server/worker/scheduler share the gateway container's network namespace,
so `127.0.0.1:4444` reaches its loopback listener. No SSH tunnel is needed for this
single-machine test arrangement; the production cross-host tunnel remains separate.
Redash always sends `readOnly:true`. The standalone gateway still supports full
accounts according to `.local/permissions.json`.

`verify` runs through Redash's real API and RQ worker, checks all-label reads,
relationship queries and rejected writes, confirms no rejected-write nodes exist,
then enables a 60-second schedule and waits for an actual scheduler refresh.
It restores the query's schedule to disabled afterward. The sanitized report is
written to `.local/verification.json`. This may take up to three minutes.

```sh
python redash/test-stack/local_test.py stop
```

Stopping retains the dedicated named volumes and local credentials for another
test session. It does not stop other Docker projects or delete their data. Do
not delete `.local` while retaining these volumes: passwords and Redash encryption
keys must stay paired with the persisted test metadata database.

If Docker reports `metadata.db: input/output error`, check the host drive's free
space as well as the space reported inside Docker's Linux VM. A large virtual
disk can still fail when its Windows host drive is full. Free space on the host
and restart Docker before retrying `start`; keep the test volumes and `.local`
directory together. Do not reset Docker or prune unrelated projects.
