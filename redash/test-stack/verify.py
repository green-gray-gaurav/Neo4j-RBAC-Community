"""Live Redash API/worker checks. Runs in the dedicated server container, not against mocks."""
import json
from pathlib import Path
import time
from urllib.request import Request, urlopen

from .bootstrap import cypher

HERE = Path(__file__).resolve().parent
state = json.loads((HERE / ".local/state.json").read_text())
values = json.loads((HERE / ".local/secrets.json").read_text())


def api(path, data=None):
    headers = {"Authorization": "Key " + state["admin_api_key"], "Content-Type": "application/json"}
    request = Request("http://127.0.0.1:5000" + path,
                      None if data is None else json.dumps(data).encode(), headers)
    with urlopen(request, timeout=30) as response:
        return json.load(response)


def execute(name):
    query_id = state["queries"][name]
    definition = api("/api/queries/" + str(query_id))
    result = api("/api/query_results", {"query": definition["query"], "data_source_id": state["data_source_id"],
                 "query_id": query_id, "max_age": 0, "parameters": {}})
    for attempt in range(60):
        if "query_result" in result:
            return result["query_result"], None
        job = result["job"]
        if job["status"] == 3:
            return api("/api/query_results/" + str(job["query_result_id"]) + ".json")["query_result"], None
        if job["status"] in (4, 5):
            return None, job.get("error", "failed")
        time.sleep(1)
        result = api("/api/jobs/" + job["id"])
    raise RuntimeError("Redash worker did not finish within 60 seconds")


def main():
    connected = api("/api/data_sources/" + str(state["data_source_id"]) + "/test", {})
    assert connected["ok"], "Redash data-source connection test failed"
    print("Redash data-source connection: passed", flush=True)
    labels, error = execute("RBAC - All labels")
    assert error is None, error
    present = {row["label"] for row in labels["data"]["rows"]}
    assert {"Customer", "Order", "RBACTest"} <= present, present
    print("All-label read through the Redash worker: passed", flush=True)
    joined, error = execute("RBAC - Customers and orders")
    assert error is None, error
    assert len(joined["data"]["rows"]) == 4
    print("Relationship query through the Redash worker: passed", flush=True)
    result, error = execute("RBAC - Write rejection")
    assert result is None and "QUERY_NOT_ALLOWED" in str(error), "A write was not rejected by the live Redash worker"
    count = cypher("MATCH(n:ShouldNotExist) RETURN count(n)")["results"][0]["data"][0]["row"][0]
    assert count == 0, "Denied Redash query changed the database"
    print("Write rejected; database unchanged: passed", flush=True)

    # Exercise an actual scheduler refresh, not a manually invoked query-runner function.
    query_id = state["queries"]["RBAC - Customers and orders"]
    definition = api("/api/queries/" + str(query_id))
    previous = definition["latest_query_data_id"]
    schedule = {"interval": 60, "until": None, "time": None, "day_of_week": None}
    api("/api/queries/" + str(query_id), {"schedule": schedule, "version": definition["version"]})
    print("Waiting for an actual scheduled refresh...", flush=True)
    try:
        for attempt in range(150):
            time.sleep(1)
            current = api("/api/queries/" + str(query_id))
            if current["latest_query_data_id"] and current["latest_query_data_id"] != previous:
                break
        else:
            raise RuntimeError("Scheduled refresh did not produce a new result within 150 seconds")
    finally:
        current = api("/api/queries/" + str(query_id))
        api("/api/queries/" + str(query_id), {"schedule": None, "version": current["version"]})

    report = {"redash_version": "26.9.0", "neo4j_version": "5.23.0", "connection_test": "passed",
              "all_labels_read": "passed", "relationship_query": "passed", "write_rejection": "passed",
              "denied_write_node_count": count, "scheduled_refresh": "passed", "schedule_restored": True}
    (HERE / ".local/verification.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2), flush=True)


if __name__ == "__main__":
    main()
