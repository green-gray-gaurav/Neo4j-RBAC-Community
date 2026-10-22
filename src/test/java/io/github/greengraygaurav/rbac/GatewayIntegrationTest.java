package io.github.greengraygaurav.rbac;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.driver.*;
import org.neo4j.harness.*;

class GatewayIntegrationTest {
    @TempDir static Path directory;
    static Neo4j database;
    static Driver driver;
    static ApiServer api;
    static Neo4jGateway gateway;
    static Path permissions;
    static String base;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll static void start() throws Exception {
        database = Neo4jBuilders.newInProcessBuilder().withDisabledServer()
                .withConfig(GraphDatabaseSettings.auth_enabled, true).build();
        try (var tx = database.databaseManagementService().database("system").beginTx()) {
            tx.execute("ALTER USER neo4j SET PASSWORD 'backend-test-password' CHANGE NOT REQUIRED");
            tx.execute("CREATE USER reader SET PASSWORD 'reader-test-password' CHANGE NOT REQUIRED");
            tx.execute("CREATE USER writer SET PASSWORD 'writer-test-password' CHANGE NOT REQUIRED");
            tx.commit();
        }
        driver = GraphDatabase.driver(database.boltURI(), AuthTokens.basic("neo4j", "backend-test-password"),
                Config.builder().withMaxConnectionPoolSize(8).withConnectionTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .withConnectionAcquisitionTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build());
        permissions = directory.resolve("permissions.json");
        gateway = new Neo4jGateway(driver, "neo4j", new FilePermissions(permissions), Neo4jGateway.Limits.defaults(), database.boltURI().toString());
        api = new ApiServer(0, gateway, new Tokens(new byte[32]), Duration.ofSeconds(15));
        api.start();
        base = "http://127.0.0.1:" + api.port();
    }
    @BeforeEach void reset() throws Exception {
        Files.writeString(permissions, "{\"users\":{\"reader\":\"readOnly\",\"writer\":\"full\"}}");
        try (var session = driver.session()) {
            session.run("MATCH(n) DETACH DELETE n").consume();
            session.run("CREATE (:Customer {id: 1, name:'A'}), (:Order {id:2})").consume();
        }
    }
    @AfterAll static void stop() {
        try { if (api != null) api.close(); }
        finally { if (database != null) database.close(); }
    }

    static HttpResponse<String> post(String path, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HTTP.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    static String login(String user) throws Exception {
        var response = post("/v1/auth/login", "{\"username\":\"" + user + "\",\"password\":\"" + user + "-test-password\"}", null);
        assertEquals(200, response.statusCode(), response.body());
        return Json.MAPPER.readTree(response.body()).path("access_token").asText();
    }
    static HttpResponse<String> query(String query, String token) throws Exception {
        return post("/v1/query", Json.MAPPER.writeValueAsString(Map.of("query", query)), token);
    }

    @Test void authenticatesNativeUsersAndReturnsDifferentSignedIdentities() throws Exception {
        String reader = login("reader"), writer = login("writer");
        assertNotEquals(reader, writer);
        assertEquals("reader", new Tokens(new byte[32]).subject(reader));
        assertEquals(401, post("/v1/auth/login", "{\"username\":\"reader\",\"password\":\"wrong\"}", null).statusCode());
        assertEquals(401, query("RETURN 1", reader + "x").statusCode());
        assertEquals(401, query("RETURN 1", null).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MATCH(n) RETURN n", "MATCH(n) RETURN labels(n), count(*)",
            "MATCH(c:Customer) OPTIONAL MATCH (c)-[r]->(o:Order) RETURN c,r,o",
            "CALL { MATCH (n:Customer) RETURN n UNION ALL MATCH (n:Order) RETURN n } RETURN n",
            "MATCH(c:Customer) WHERE EXISTS { MATCH(o:Order) WHERE o.id=2 } RETURN c.name",
            "UNWIND [1,2] AS x WITH sum(x) AS total RETURN total",
            "MATCH(c:Customer) RETURN [(c)-->(o) | o.id] AS ids"})
    void readsAllLabelsAndSupportedNestedQueries(String text) throws Exception {
        var result = query(text, login("reader"));
        assertEquals(200, result.statusCode(), result.body());
        assertEquals("READ_ONLY", Json.MAPPER.readTree(result.body()).path("classification").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE (:Added)", "MATCH(n) SET n.flag=true", "MATCH(n) DETACH DELETE n",
            "CALL { CREATE(n:Added) RETURN n } RETURN n", "FOREACH(x IN [1] | CREATE (:Added))"})
    void readerCannotWriteOrEscalate(String text) throws Exception {
        assertEquals(403, query(text, login("reader")).statusCode());
        try (var session = driver.session()) {
            assertEquals(2, session.run("MATCH(n) RETURN count(*) AS n").single().get("n").asInt());
        }
    }

    @Test void fullAccountCanModifyDataButReaderCannotBorrowItsPermission() throws Exception {
        var write = query("MERGE(c:Customer {id:3}) SET c.name='B' RETURN c", login("writer"));
        assertEquals(200, write.statusCode(), write.body());
        assertEquals("MODIFY", Json.MAPPER.readTree(write.body()).path("classification").asText());
        var spoof = post("/v1/query", "{\"query\":\"CREATE ()\",\"mode\":\"FULL\"}", login("reader"));
        assertEquals(400, spoof.statusCode());
        assertEquals(403, post("/v1/query", "{\"query\":\"CREATE ()\",\"readOnly\":true}", login("writer")).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CALL db.labels()", "SHOW USERS", "SHOW INDEXES", "CREATE INDEX x FOR(n:Customer) ON(n.id)",
            "LOAD CSV FROM 'https://example.invalid/x' AS row RETURN row", "USE system RETURN 1",
            "RETURN custom.function(1)", "PROFILE CREATE ()", "RETURN 1; CREATE ()",
            "CALL { CREATE () } IN TRANSACTIONS"})
    void forbiddenOperationsStayBlockedEvenForFullAccounts(String text) throws Exception {
        int status = query(text, login("writer")).statusCode();
        assertTrue(status == 400 || status == 403, "Unexpected status " + status);
    }

    @Test void revocationAndDowngradeApplyToExistingJwt() throws Exception {
        String token = login("writer");
        Files.writeString(permissions, "{\"users\":{\"writer\":\"readOnly\"}}");
        assertEquals(403, query("CREATE ()", token).statusCode());
        Files.writeString(permissions, "{\"users\":{}}");
        assertEquals(403, query("RETURN 1", token).statusCode());
        Files.writeString(permissions, "invalid");
        assertEquals(503, query("RETURN 1", token).statusCode());
    }

    @Test void rowAndByteLimitsRollbackWrites() {
        var small = new Neo4jGateway(driver, "neo4j", new FilePermissions(permissions),
                new Neo4jGateway.Limits(1, 1024, Duration.ofSeconds(10)), database.boltURI().toString());
        var rowError = assertThrows(ApiException.class, () -> small.execute("writer",
                "UNWIND [1,2] AS n CREATE (c:Added) RETURN n", Map.of(), "test", Long.MAX_VALUE));
        assertEquals(422, rowError.status);
        var byteError = assertThrows(ApiException.class, () -> small.execute("writer",
                "CREATE (:Added) RETURN $large AS text", Map.of("large", "a".repeat(2000)), "test", Long.MAX_VALUE));
        assertEquals(422, byteError.status);
        try (var session = driver.session()) {
            assertEquals(0, session.run("MATCH(n:Added) RETURN count(*) AS n").single().get("n").asInt());
        }
    }

    @Test void validatesJsonAndSerializesParametersAndLargeIntegers() throws Exception {
        String token = login("reader");
        assertEquals(400, post("/v1/query", "{\"query\":\"RETURN 1\",\"query\":\"CREATE ()\"}", token).statusCode());
        assertEquals(400, post("/v1/query", "{\"query\":\"RETURN 1\"} {}", token).statusCode());
        var result = post("/v1/query", "{\"query\":\"RETURN $id AS id\",\"parameters\":{\"id\":9007199254740993}}", token);
        assertEquals(200, result.statusCode(), result.body());
        assertTrue(Json.MAPPER.readTree(result.body()).path("rows").get(0).get(0).isTextual());
        assertEquals(413, query("RETURN '" + "a".repeat(65536) + "'", token).statusCode());
        assertEquals(400, query("RETURN nonexistent", token).statusCode());
    }
}
