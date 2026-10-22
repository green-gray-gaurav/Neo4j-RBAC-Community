import importlib.util
import json
import pathlib
import sys
import threading
import types
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

# Only the small documented Redash extension contract is stubbed; HTTP is real.
query_runner = types.ModuleType("redash.query_runner")


class BaseQueryRunner:
    def __init__(self, configuration):
        self.configuration = configuration

    def fetch_columns(self, values):
        result, names = [], set()
        for name, kind in values:
            unique, i = name, 1
            while unique in names:
                unique, i = name + str(i), i + 1
            names.add(unique)
            result.append({"name": unique, "friendly_name": unique, "type": kind})
        return result


query_runner.BaseQueryRunner = BaseQueryRunner
query_runner.register = lambda cls: None
with patch.dict(sys.modules, {"redash.query_runner": query_runner}):
    spec = importlib.util.spec_from_file_location("neo4j_rbac", pathlib.Path(__file__).with_name("neo4j_rbac.py"))
    adapter = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(adapter)


class AdapterTest(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.status = 200
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                owner.calls.append((self.path, body, self.headers.get("Authorization")))
                if self.path.endswith("login"):
                    data = {"access_token": "test-token"}
                    code = 200
                else:
                    code = owner.status
                    data = {"classification": "READ_ONLY", "columns": ["n", "obj", "nothing", "n"],
                            "rows": [[1, {"labels": ["Customer"]}, None, "9007199254740993"]]}
                    if code != 200:
                        data = {"code": "QUERY_NOT_ALLOWED", "requestId": "id"}
                self.send_response(code)
                self.end_headers()
                self.wfile.write(json.dumps(data).encode())

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.runner = adapter.Neo4jRBAC({"api_url": "http://127.0.0.1:" + str(self.server.server_port),
                                         "username": "reader", "password": "test-only-password"})

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def test_auth_and_query_with_read_restriction_for_interactive_and_scheduled(self):
        for user in (object(), None):
            result, error = self.runner.run_query("MATCH(n) RETURN n", user)
            self.assertIsNone(error)
            result = json.loads(result)
            self.assertEqual(["n", "obj", "nothing", "n1"], [c["name"] for c in result["columns"]])
            self.assertEqual("integer", result["columns"][0]["type"])
            self.assertEqual("string", result["columns"][3]["type"])
            self.assertEqual({"labels": ["Customer"]}, json.loads(result["rows"][0]["obj"]))
            self.assertTrue(self.calls[-1][1]["readOnly"])
            self.assertEqual("Bearer test-token", self.calls[-1][2])
        self.assertEqual(4, len(self.calls))

    def test_error_is_not_retried_and_does_not_expose_credentials(self):
        self.status = 403
        result, error = self.runner.run_query("CREATE ()", None)
        self.assertIsNone(result)
        self.assertIn("403", error)
        self.assertNotIn("test-only-password", error)
        self.assertEqual(2, len(self.calls))

    def test_only_loopback_urls_and_no_redirects(self):
        for url in ("http://example.com", "http://secret@localhost:4444", "http://localhost:4444/path"):
            self.runner.configuration["api_url"] = url
            result, error = self.runner.run_query("RETURN 1", None)
            self.assertIsNone(result)
            self.assertIsNotNone(error)
        self.assertIsNone(adapter.NoRedirect().redirect_request(None, None, 302, "", {}, "http://example.com"))

    def test_mixed_columns_keep_boolean_and_large_integer_strings_distinct(self):
        self.assertEqual("string", adapter.Neo4jRBAC._column_type(["9007199254740993", None]))
        self.assertEqual("boolean", adapter.Neo4jRBAC._column_type([True, None]))
        self.assertEqual("float", adapter.Neo4jRBAC._column_type([1, 1.5]))
        self.assertEqual("string", adapter.Neo4jRBAC._column_type([1, True]))


if __name__ == "__main__":
    unittest.main()
