"""Redash BaseQueryRunner adapter. No database driver or extra Python dependencies."""
import json
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, build_opener, HTTPRedirectHandler, ProxyHandler

from redash.query_runner import BaseQueryRunner, register


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Neo4jRBAC(BaseQueryRunner):
    noop_query = "RETURN 1 AS connected"
    should_annotate_query = False

    @classmethod
    def name(cls):
        return "Neo4j RBAC (read-only)"

    @classmethod
    def type(cls):
        return "neo4j_rbac"

    @classmethod
    def configuration_schema(cls):
        return {
            "type": "object",
            "properties": {
                "api_url": {"type": "string", "title": "Local tunnel URL", "default": "http://127.0.0.1:14444"},
                "username": {"type": "string", "title": "Neo4j username"},
                "password": {"type": "string", "title": "Neo4j password"},
            },
            "required": ["api_url", "username", "password"],
            "secret": ["password"],
        }

    def _post(self, path, data, token=None):
        base = self.configuration["api_url"].rstrip("/")
        parsed = urlparse(base)
        if (parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost")
                or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment):
            raise ValueError("API URL must be an HTTP loopback URL for the SSH tunnel")
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        request = Request(base + path, data=json.dumps(data).encode("utf-8"), headers=headers, method="POST")
        # Do not use environment proxies, forward credentials through redirects, or retry POSTs.
        with build_opener(ProxyHandler({}), NoRedirect()).open(request, timeout=20) as response:
            raw = response.read(5 * 1024 * 1024 + 1)
            if len(raw) > 5 * 1024 * 1024:
                raise ValueError("API response exceeds the adapter limit")
            return json.loads(raw)

    def run_query(self, query, user):
        try:
            # Fresh short-lived token per run: no persisted token, refresh scheduler or shared cache.
            login = self._post("/v1/auth/login", {
                "username": self.configuration["username"], "password": self.configuration["password"]})
            result = self._post("/v1/query", {
                "query": query, "parameters": {}, "readOnly": True}, login["access_token"])
            if result["classification"] != "READ_ONLY":
                raise ValueError("API did not return a read-only classification")
            rows = result["rows"]
            columns = self.fetch_columns([(name, self._column_type([row[i] for row in rows]))
                                          for i, name in enumerate(result["columns"])])
            data = {"columns": columns, "rows": [
                {column["name"]: self._cell(value, column["type"])
                 for column, value in zip(columns, row)} for row in rows]}
            return json.dumps(data, allow_nan=False), None
        except HTTPError as error:
            # Do not surface request headers, credentials, tokens, or untrusted upstream bodies.
            try:
                detail = json.loads(error.read(4096))
                code = detail.get("code", "API_ERROR")
                request_id = detail.get("requestId", "")
                if not isinstance(code, str) or not code.replace("_", "").isalnum():
                    code = "API_ERROR"
                return None, "Neo4j RBAC HTTP {}: {} (request {})".format(error.code, code, str(request_id)[:64])
            except (ValueError, TypeError):
                return None, "Neo4j RBAC HTTP {}".format(error.code)
        except (URLError, TimeoutError, OSError):
            return None, "Neo4j RBAC API is unavailable or timed out"
        except (ValueError, KeyError, TypeError, IndexError):
            return None, "Invalid Neo4j RBAC configuration or response"

    @staticmethod
    def _column_type(values):
        kinds = set("boolean" if isinstance(v, bool) else "integer" if isinstance(v, int)
                    else "float" if isinstance(v, float) else "string" for v in values if v is not None)
        if kinds and kinds <= {"integer", "float"}:
            return "float" if "float" in kinds else "integer"
        return next(iter(kinds)) if len(kinds) == 1 else "string"

    @staticmethod
    def _cell(value, kind):
        if value is None:
            return None
        if isinstance(value, (dict, list)):
            return json.dumps(value, ensure_ascii=False, allow_nan=False)
        if kind == "string" and not isinstance(value, str):
            return json.dumps(value, allow_nan=False)
        return value


register(Neo4jRBAC)
