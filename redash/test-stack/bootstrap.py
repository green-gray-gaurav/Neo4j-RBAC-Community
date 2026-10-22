"""Runs inside this dedicated test Redash container. Never prints credentials or API keys."""
import base64
import json
from pathlib import Path
import time
from urllib.error import URLError
from urllib.request import Request, urlopen

HERE = Path(__file__).resolve().parent
values = json.loads((HERE / ".local/secrets.json").read_text())


def cypher(query, parameters=None, database="neo4j"):
    token = base64.b64encode(("neo4j:" + values["neo4j_password"]).encode()).decode()
    request = Request("http://neo4j:7474/db/" + database + "/tx/commit",
                      json.dumps({"statements": [{"statement": query, "parameters": parameters or {}}]}).encode(),
                      {"Content-Type": "application/json", "Authorization": "Basic " + token})
    with urlopen(request, timeout=10) as response:
        result = json.load(response)
    if result["errors"]:
        raise RuntimeError("Test Neo4j setup failed: " + result["errors"][0]["code"])
    return result


if __name__ == "__main__":
    for attempt in range(30):
        try:
            cypher("RETURN 1")
            break
        except (URLError, OSError):
            time.sleep(1)
    else:
        raise SystemExit("Test Neo4j did not become ready")
    for user, password in (("redash_reader", values["reader_password"]), ("data_writer", values["writer_password"])):
        cypher("CREATE USER " + user + " IF NOT EXISTS SET PASSWORD $password CHANGE NOT REQUIRED",
               {"password": password}, "system")
    cypher("""UNWIND [{id:1,name:'Alice'},{id:2,name:'Bob'},{id:3,name:'Cara'}] AS row
        MERGE (c:Customer:RBACTest {id:row.id}) SET c.name=row.name
        WITH collect(c) AS customers
        UNWIND [1,2,3,4] AS id MERGE(o:Order:RBACTest {id:id}) SET o.amount=id*100
        WITH o,id,customers WITH o,customers[(id-1)%3] AS c MERGE(c)-[:PLACED]->(o)""")

    from redash import create_app, models
    from redash.utils.configuration import ConfigurationContainer
    from redash.query_runner.neo4j_rbac import Neo4jRBAC

    app = create_app()
    with app.app_context():
        org = models.Organization.query.filter_by(slug="default").first()
        if org is None:
            org = models.Organization(name="Local RBAC Test", slug="default", settings={})
            admin = models.Group(name="admin", permissions=models.Group.ADMIN_PERMISSIONS,
                                 org=org, type=models.Group.BUILTIN_GROUP)
            default = models.Group(name="default", permissions=models.Group.DEFAULT_PERMISSIONS,
                                   org=org, type=models.Group.BUILTIN_GROUP)
            models.db.session.add_all([org, admin, default])
            models.db.session.commit()
        user = models.User.query.filter_by(email=values["admin_email"], org=org).first()
        if user is None:
            user = models.User(org=org, email=values["admin_email"], name="Local Test Admin",
                               group_ids=[org.admin_group.id, org.default_group.id])
            user.hash_password(values["admin_password"])
            models.db.session.add(user)
            models.db.session.commit()
        source = models.DataSource.query.filter_by(org=org, name="Neo4j RBAC Test (read-only)").first()
        if source is None:
            source = models.DataSource.create_with_group(org=org, name="Neo4j RBAC Test (read-only)", type="neo4j_rbac",
                options=ConfigurationContainer({"api_url": "http://127.0.0.1:4444", "username": "redash_reader",
                    "password": values["reader_password"]}, Neo4jRBAC.configuration_schema()))
            models.db.session.commit()
        examples = {
            "RBAC - All labels": "MATCH(n) UNWIND labels(n) AS label RETURN label, count(*) AS nodes ORDER BY label",
            "RBAC - Customers and orders": "MATCH(c:Customer)-[:PLACED]->(o:Order) RETURN c.name AS customer, o.id AS order_id, o.amount AS amount ORDER BY order_id",
            "RBAC - Write rejection": "CREATE (:ShouldNotExist {source:'redash-test'})",
        }
        query_ids = {}
        for name, text in examples.items():
            query = models.Query.query.filter_by(org=org, name=name).first()
            if query is None:
                query = models.Query.create(org=org, user=user, data_source=source, name=name, query_text=text,
                                            is_draft=False, description="Disposable local RBAC integration test")
                models.db.session.add(query)
                models.db.session.commit()
            query_ids[name] = query.id
        state = {"admin_api_key": user.api_key, "data_source_id": source.id, "queries": query_ids}
        (HERE / ".local/state.json").write_text(json.dumps(state))
        print("Test admin, data source and three saved queries are ready")
