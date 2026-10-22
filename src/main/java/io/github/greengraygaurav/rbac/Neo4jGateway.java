package io.github.greengraygaurav.rbac;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import org.neo4j.driver.*;
import org.neo4j.driver.exceptions.Neo4jException;

final class Neo4jGateway implements GatewayBackend {
    record Limits(int rows, int bytes, Duration transactionTimeout) {
        Limits {
            if (rows < 1 || bytes < 1024 || transactionTimeout.isNegative() || transactionTimeout.isZero()) {
                throw new IllegalArgumentException("Invalid execution limits");
            }
        }
        static Limits defaults() { return new Limits(1000, 4 * 1024 * 1024, Duration.ofSeconds(10)); }
    }
    private final Driver driver;
    private final String database;
    private final FilePermissions permissions;
    private final Limits limits;
    private final String uri;

    Neo4jGateway(Driver driver, String database, FilePermissions permissions, Limits limits, String uri) {
        this.driver = driver;
        this.database = database;
        this.permissions = permissions;
        this.limits = limits;
        this.uri = uri;
    }

    public boolean authenticate(String username, String password) {
        var authConfig = SessionConfig.builder().withDatabase(database).withDefaultAccessMode(AccessMode.READ).build();
        var config = Config.builder().withMaxConnectionPoolSize(1)
                .withConnectionTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .withConnectionAcquisitionTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .withMaxTransactionRetryTime(0, java.util.concurrent.TimeUnit.SECONDS).withTelemetryDisabled(true).build();
        // Isolate login credentials from the pooled execution account. No login driver is cached.
        try (var verifier = GraphDatabase.driver(uri, AuthTokens.basic(username, password), config);
             var session = verifier.session(authConfig)) {
            session.run("RETURN 1", Map.of(), TransactionConfig.builder().withTimeout(Duration.ofSeconds(5)).build()).consume();
            return true;
        }
        catch (Neo4jException e) {
            if (e.code().startsWith("Neo.ClientError.Security.")) return false;
            throw new ApiException(503, "DATABASE_UNAVAILABLE", "Neo4j authentication is unavailable");
        }
    }

    public QueryClassifier.Mode permission(String username) { return permissions.mode(username); }

    public Execution execute(String username, String query, Map<String, Object> parameters, String requestId, long deadline, boolean readOnly) {
        var statement = QueryPolicy.validate(query);
        var granted = permission(username);
        var mode = readOnly ? QueryClassifier.Mode.READ_ONLY : granted;
        if (mode == QueryClassifier.Mode.READ_ONLY && statement.containsUpdates()) denyWrite();
        var sessionConfig = SessionConfig.builder().withDatabase(database).withFetchSize(100)
                .withDefaultAccessMode(mode == QueryClassifier.Mode.READ_ONLY ? AccessMode.READ : AccessMode.WRITE).build();
        checkDeadline(deadline);
        try (var session = driver.session(sessionConfig);
             var tx = session.beginTransaction(TransactionConfig.builder().withTimeout(limits.transactionTimeout()).build())) {
            var plan = tx.run("EXPLAIN\n" + query, parameters).consume();
            var classification = QueryClassifier.classifyParsed(statement,
                    plan.hasPlan() && !plan.hasProfile() ? plan.queryType() : null);
            if (classification.kind() == QueryClassifier.Kind.UNKNOWN) {
                throw new ApiException(400, "CLASSIFICATION_FAILED", "Neo4j could not classify this query");
            }
            if (classification.kind() != QueryClassifier.Kind.READ_ONLY
                    && classification.kind() != QueryClassifier.Kind.MODIFY) denyWrite();
            if (!classification.eligibleFor(mode)) denyWrite();
            checkDeadline(deadline);
            var result = tx.run(query, parameters);
            // Serialize incrementally into a strictly bounded buffer before any write transaction commits.
            var buffer = new BoundedBuffer(limits.bytes());
            int count = 0;
            try (var json = Json.MAPPER.getFactory().createGenerator(buffer)) {
                json.writeStartObject();
                json.writeStringField("requestId", requestId);
                json.writeStringField("classification", classification.kind().name());
                json.writeObjectField("columns", result.keys());
                json.writeArrayFieldStart("rows");
                while (result.hasNext()) {
                    checkDeadline(deadline);
                    if (++count > limits.rows()) throw new ApiException(422, "ROW_LIMIT", "Result exceeds the row limit");
                    json.writeStartArray();
                    for (var value : result.next().values()) json.writeObject(ResultValues.json(value));
                    json.writeEndArray();
                    json.flush();
                }
                result.consume();
                json.writeEndArray();
                json.writeNumberField("rowCount", count);
                json.writeEndObject();
            }
            checkDeadline(deadline);
            // Grants are checked again before commit, including downgrades during execution.
            if (!classification.eligibleFor(permission(username))) denyWrite();
            tx.commit();
            return new Execution(buffer.toByteArray(), classification.kind().name(), count);
        } catch (Neo4jException e) {
            if (e.code().contains("TimedOut") || e.code().contains("Terminated")) {
                throw new ApiException(504, "QUERY_TIMEOUT", "Query exceeded its execution deadline");
            }
            if (e.code().startsWith("Neo.ClientError.Statement.")) {
                throw new ApiException(400, "INVALID_QUERY", "Neo4j rejected the query or parameters");
            }
            throw new ApiException(503, "DATABASE_UNAVAILABLE", "Neo4j request failed");
        } catch (IOException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof ApiException api) throw api;
            }
            throw new ApiException(422, "RESULT_ENCODING", "Result cannot be encoded within the supported limits");
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "INVALID_PARAMETERS", "Parameters contain a value unsupported by Neo4j");
        }
    }

    static void checkDeadline(long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) {
            throw new ApiException(504, "QUERY_TIMEOUT", "Request deadline exceeded");
        }
    }
    private static void denyWrite() { throw new ApiException(403, "QUERY_NOT_ALLOWED", "Query is not permitted for this account"); }
    public void close() { driver.close(); }

    private static final class BoundedBuffer extends java.io.ByteArrayOutputStream {
        private final int limit;
        BoundedBuffer(int limit) { this.limit = limit; }
        @Override public synchronized void write(byte[] b, int off, int len) {
            check(len); super.write(b, off, len);
        }
        @Override public synchronized void write(int b) { check(1); super.write(b); }
        private void check(int size) {
            if (count > limit - size) throw new ApiException(422, "BYTE_LIMIT", "Result exceeds the byte limit");
        }
    }
}
