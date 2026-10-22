package io.github.greengraygaurav.rbac;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.neo4j.cypher.internal.ast.AdministrationCommand;
import org.neo4j.cypher.internal.ast.SchemaCommand;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.Driver;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.exceptions.Neo4jException;
import org.neo4j.driver.summary.QueryType;

/** Classifies database access, not label permissions or external side effects. Never executes the input. */
public final class QueryClassifier {
    public enum Kind { READ_ONLY, MODIFY, SCHEMA, ADMINISTRATIVE, UNKNOWN }
    public enum Mode { READ_ONLY, FULL }

    public record Classification(Kind kind, QueryType serverType, String reason) {
        /** Eligibility only; the caller must still apply RBAC and the supported-syntax policy. */
        public boolean eligibleFor(Mode mode) {
            Objects.requireNonNull(mode);
            return kind != Kind.UNKNOWN && (mode == Mode.FULL || kind == Kind.READ_ONLY);
        }
    }

    @FunctionalInterface
    interface Explain {
        QueryType type(String query, Map<String, Object> parameters);
    }

    private final Explain explain;

    public QueryClassifier(Driver driver, String database, Duration timeout) {
        Objects.requireNonNull(driver);
        if (database == null || database.isBlank()) throw new IllegalArgumentException("Database is required");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("A positive EXPLAIN timeout is required");
        }
        var config = SessionConfig.builder().withDatabase(database).withDefaultAccessMode(AccessMode.READ).build();
        var transaction = TransactionConfig.builder().withTimeout(timeout).build();
        explain = (query, parameters) -> {
            try (var session = driver.session(config)) {
                // READ routing is not access control. EXPLAIN prevents execution of the input.
                var summary = session.run("EXPLAIN\n" + query, parameters, transaction).consume();
                return summary.hasPlan() && !summary.hasProfile() ? summary.queryType() : null;
            }
        };
    }

    QueryClassifier(Explain explain) {
        this.explain = Objects.requireNonNull(explain);
    }

    public Classification classify(String query, Map<String, Object> parameters) {
        Objects.requireNonNull(parameters);
        final org.neo4j.cypher.internal.ast.Statement statement;
        try {
            statement = new CypherParserProof().parseSingle(query);
        } catch (IllegalArgumentException | org.neo4j.exceptions.SyntaxException error) {
            return new Classification(Kind.UNKNOWN, null, "Invalid syntax or not exactly one statement body");
        }
        // Do not confuse SHOW/administrative statements with ordinary data reads.
        // These can be classified locally without requesting privileges on the system database.
        if (statement instanceof AdministrationCommand) {
            return new Classification(Kind.ADMINISTRATIVE, null, "Administrative AST statement");
        }
        if (statement instanceof SchemaCommand) {
            return new Classification(Kind.SCHEMA, null, "Schema AST statement");
        }
        try {
            var type = explain.type(query, parameters);
            if (type == null) return new Classification(Kind.UNKNOWN, null, "Missing EXPLAIN plan or query type");
            return switch (type) {
                case READ_ONLY -> new Classification(
                        statement.containsUpdates() ? Kind.UNKNOWN : Kind.READ_ONLY, type,
                        statement.containsUpdates() ? "AST and server classification disagree" : "Server classified read-only");
                case READ_WRITE, WRITE_ONLY -> new Classification(Kind.MODIFY, type, "Server classified data modification");
                case SCHEMA_WRITE -> new Classification(Kind.SCHEMA, type, "Server classified schema modification");
            };
        } catch (Neo4jException error) {
            // Auth, planning, unavailable procedures and network failures must never become READ_ONLY.
            return new Classification(Kind.UNKNOWN, null, "EXPLAIN failed (" + error.code() + ")");
        }
    }
}
