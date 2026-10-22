package io.github.greengraygaurav.rbac;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.greengraygaurav.rbac.QueryClassifier.Kind.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.neo4j.driver.*;
import org.neo4j.harness.*;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Mode;

/** Starts a disposable, actual 5.23.0 server over Bolt. No Docker or external database. */
class QueryClassifierIntegrationTest {
    private static Neo4j server;
    private static Driver driver;
    private static QueryClassifier classifier;

    @BeforeAll
    static void start() {
        server = Neo4jBuilders.newInProcessBuilder().withDisabledServer()
                .withProcedure(Procedures.class).build();
        driver = GraphDatabase.driver(server.boltURI(), AuthTokens.none());
        classifier = new QueryClassifier(driver, "neo4j", Duration.ofSeconds(5));
    }

    @AfterAll
    static void stop() {
        try { if (driver != null) driver.close(); }
        finally { if (server != null) server.close(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"RETURN 1", "// comment\nMATCH (c:Customer) RETURN c.name;",
            "MATCH (c:Customer) RETURN count(c)",
            "CALL { RETURN 1 AS n } RETURN n UNION ALL RETURN 2 AS n",
            "CALL proof.read() YIELD value RETURN value"})
    void classifiesServerReads(String query) {
        assertEquals(READ_ONLY, classifier.classify(query, Map.of()).kind());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE (:Customer)", "MERGE (:Customer {id: 1})",
            "MATCH (c:Customer) SET c.name = 'new' RETURN c",
            "MATCH (c:Customer) DETACH DELETE c", "MATCH (c:Customer) REMOVE c.name",
            "FOREACH (i IN [1] | CREATE (:Customer))",
            "CALL { CREATE (c:Customer) RETURN c } RETURN c",
            "CALL proof.modify() YIELD value RETURN value"})
    void classifiesWritesWithoutExecutingThem(String query) {
        assertEquals(MODIFY, classifier.classify(query, Map.of()).kind());
    }

    @Test
    void classifiesSchemaAndAdminSeparately() {
        assertEquals(SCHEMA, classifier.classify("CREATE INDEX proof_idx FOR (c:Customer) ON (c.id)", Map.of()).kind());
        assertEquals(ADMINISTRATIVE, classifier.classify("SHOW USERS", Map.of()).kind());
    }

    @Test
    void missingProceduresSemanticErrorsAndBadDatabaseAreUnknown() {
        assertEquals(UNKNOWN, classifier.classify("CALL proof.missing()", Map.of()).kind());
        assertEquals(UNKNOWN, classifier.classify("RETURN nonexistent", Map.of()).kind());
        assertEquals(UNKNOWN, new QueryClassifier(driver, "absent", Duration.ofSeconds(5))
                .classify("RETURN 1", Map.of()).kind());
    }

    @Test
    void acceptsTypedParameters() {
        assertEquals(READ_ONLY, classifier.classify("RETURN $id + 1 AS id", Map.of("id", 41L)).kind());
    }

    @Test
    void cliUsesModeAndNeverExecutesEvenInFullMode() throws Exception {
        runCli("RETURN 1", "READ_ONLY", 0, "kind=READ_ONLY");
        runCli("CREATE (:Customer)", "READ_ONLY", 2, "kind=MODIFY");
        runCli("CREATE (:Customer)", "FULL", 0, "kind=MODIFY");
    }

    private static void runCli(String query, String mode, int expectedExit, String expectedOutput) throws Exception {
        var executable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var builder = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
                ClassifyCli.class.getName(), mode).redirectErrorStream(true);
        builder.environment().put("NEO4J_URI", server.boltURI().toString());
        builder.environment().put("NEO4J_USERNAME", "neo4j");
        builder.environment().put("NEO4J_PASSWORD", "unused-test-credential");
        builder.environment().put("NEO4J_DATABASE", "neo4j");
        var process = builder.start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(query.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "CLI must finish");
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(expectedExit, process.exitValue(), output);
            assertTrue(output.contains(expectedOutput), output);
        } finally {
            process.destroyForcibly();
        }
    }

    @AfterEach
    void verifiesNoInputQueryExecuted() {
        assertEquals(0, Procedures.invocations.get(), "EXPLAIN must not invoke procedures");
        try (var session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            assertEquals(0, session.run("MATCH (n) RETURN count(n) AS n").single().get("n").asLong());
            assertFalse(session.run("SHOW INDEXES YIELD name WHERE name = 'proof_idx' RETURN name").hasNext());
        }
    }

    public static class Procedures {
        static final AtomicInteger invocations = new AtomicInteger();
        @Procedure(name = "proof.read", mode = Mode.READ)
        public Stream<Row> read() { invocations.incrementAndGet(); return Stream.of(new Row()); }
        @Procedure(name = "proof.modify", mode = Mode.WRITE)
        public Stream<Row> modify() { invocations.incrementAndGet(); return Stream.of(new Row()); }
    }
    public static class Row { public long value = 1; }
}
