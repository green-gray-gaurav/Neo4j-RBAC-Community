"""Local test-stack lifecycle. Secrets stay in .local files and out of command arguments/output."""
import base64
import getpass
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
from urllib.error import URLError
from urllib.request import ProxyHandler, build_opener

HERE = Path(__file__).resolve().parent
PRIVATE = HERE / ".local"


def initialize():
    PRIVATE.mkdir(exist_ok=True)
    if os.name == "nt":
        subprocess.run(["icacls", str(PRIVATE), "/inheritance:r", "/grant:r",
                        getpass.getuser() + ":(OI)(CI)F", "SYSTEM:(OI)(CI)F"], check=True, stdout=subprocess.DEVNULL)
    else:
        PRIVATE.chmod(0o700)
    path = PRIVATE / "secrets.json"
    if not path.exists():
        values = {name: secrets.token_hex(24) for name in
                  ("postgres_password", "neo4j_password", "reader_password", "writer_password",
                   "admin_password", "cookie_secret", "redash_secret")}
        values["admin_email"] = "admin@rbac.test"
        path.write_text(json.dumps(values, indent=2), encoding="utf-8")
    values = json.loads(path.read_text(encoding="utf-8"))
    env = {
        "POSTGRES_PASSWORD": values["postgres_password"], "NEO4J_PASSWORD": values["neo4j_password"],
        "REDASH_COOKIE_SECRET": values["cookie_secret"], "REDASH_SECRET_KEY": values["redash_secret"],
    }
    (PRIVATE / "stack.env").write_text("".join(k + "=" + v + "\n" for k, v in env.items()), encoding="utf-8")
    (PRIVATE / "permissions.json").write_text('{"users":{"redash_reader":"readOnly","data_writer":"full"}}', encoding="utf-8")
    key = PRIVATE / "jwt.key"
    if not key.exists():
        key.write_text(base64.b64encode(secrets.token_bytes(32)).decode(), encoding="ascii")
    if os.name != "nt":
        for item in PRIVATE.iterdir():
            if item.is_file():
                item.chmod(0o600)


def compose(*args):
    subprocess.run(["docker", "compose", "--env-file", str(PRIVATE / "stack.env"), "-f", str(HERE / "compose.yaml"),
                    *args], cwd=HERE, check=True)


def wait_for_redash():
    opener = build_opener(ProxyHandler({}))
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            with opener.open("http://127.0.0.1:5000/login", timeout=2) as response:
                if response.status == 200:
                    return
        except (URLError, OSError):
            pass
        time.sleep(2)
    raise SystemExit("Redash did not become ready within 90 seconds; inspect the test-stack server logs")


def main():
    action = sys.argv[1] if len(sys.argv) == 2 else "start"
    if action == "start":
        initialize()
        if not (HERE.parent.parent / "target/neo4j-rbac-parser-proof-0.1.0-SNAPSHOT-service.jar").exists():
            raise SystemExit("Build the service JAR with ./mvnw clean verify first")
        compose("build", "server", "gateway")
        compose("up", "-d", "postgres", "redis", "neo4j", "gateway")
        compose("run", "--rm", "--no-deps", "server", "create_db")
        compose("run", "--rm", "--no-deps", "server", "python", "-m", "rbac_test.bootstrap")
        compose("up", "-d", "server", "worker", "scheduler")
        wait_for_redash()
        print("Redash: http://localhost:5000 | login: admin@rbac.test")
        print("Password is in redash/test-stack/.local/secrets.json (admin_password)")
    elif action == "stop":
        compose("down")
    elif action == "status":
        compose("ps")
    elif action == "verify":
        compose("exec", "-T", "server", "python", "-m", "rbac_test.verify")
    else:
        raise SystemExit("Usage: local_test.py [start|stop|status|verify]")


if __name__ == "__main__":
    main()
