package io.github.greengraygaurav.rbac;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** One loopback HTTP listener, no UI, cookies, CORS or client-supplied permissions. */
final class ApiServer implements AutoCloseable {
    static final int MAX_BODY = 256 * 1024;
    static final int MAX_QUERY = 64 * 1024;
    private final GatewayBackend backend;
    private final Tokens tokens;
    private final HttpServer server;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final Semaphore admitted = new Semaphore(40);
    private final Semaphore executing = new Semaphore(8, true);
    private final long deadlineMillis;

    ApiServer(int port, GatewayBackend backend, Tokens tokens, Duration deadline) throws IOException {
        this.backend = backend;
        this.tokens = tokens;
        this.deadlineMillis = deadline.toMillis();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 40);
        server.setExecutor(workers);
        server.createContext("/", this::handle);
    }
    void start() { server.start(); }
    int port() { return server.getAddress().getPort(); }

    private void handle(HttpExchange exchange) throws IOException {
        String id = UUID.randomUUID().toString();
        String user = null;
        String classification = "";
        int rowCount = 0;
        int status = 500;
        long start = System.nanoTime();
        boolean admittedRequest = admitted.tryAcquire();
        boolean active = false;
        Thread thread = Thread.currentThread();
        var reply = new Reply(exchange);
        var watchdog = timer.schedule(() -> {
            try {
                reply.send(504, Json.MAPPER.writeValueAsBytes(Map.of("requestId", id,
                        "code", "QUERY_TIMEOUT", "message", "Request deadline exceeded")));
            } catch (IOException ignored) { /* Client may already have disconnected. */ }
            thread.interrupt();
            exchange.close();
        }, deadlineMillis, TimeUnit.MILLISECONDS);
        try {
            if (!admittedRequest || !(active = executing.tryAcquire(100, TimeUnit.MILLISECONDS))) {
                throw new ApiException(429, "BUSY", "Request capacity reached; try later");
            }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/health") && exchange.getRequestMethod().equals("GET")) {
                status = 200; reply.send(status, Json.MAPPER.writeValueAsBytes(Map.of("status", "up"))); return;
            }
            if (!Set.of("/v1/auth/login", "/v1/query").contains(path)) {
                throw new ApiException(404, "NOT_FOUND", "Endpoint does not exist");
            }
            if (!exchange.getRequestMethod().equals("POST")) throw new ApiException(405, "METHOD_NOT_ALLOWED", "Use POST");
            if (path.equals("/v1/query")) {
                var authorization = exchange.getRequestHeaders().get("Authorization");
                if (authorization == null || authorization.size() != 1 || !authorization.getFirst().startsWith("Bearer ")) {
                    throw new ApiException(401, "INVALID_TOKEN", "Bearer token required");
                }
                user = tokens.subject(authorization.getFirst().substring(7));
                backend.permission(user);
            }
            var contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
                throw new ApiException(415, "CONTENT_TYPE", "Use application/json");
            }
            byte[] raw = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
            if (raw.length > MAX_BODY) throw new ApiException(413, "BODY_LIMIT", "Request body is too large");
            JsonNode body;
            try { body = Json.MAPPER.readTree(raw); }
            catch (IOException e) { throw new ApiException(400, "INVALID_JSON", "Invalid JSON body"); }
            if (body == null || !body.isObject()) throw new ApiException(400, "INVALID_JSON", "Expected a JSON object");
            long deadline = start + TimeUnit.MILLISECONDS.toNanos(deadlineMillis);
            if (path.equals("/v1/auth/login")) {
                fields(body, Set.of("username", "password"));
                String name = text(body, "username"), password = text(body, "password");
                if (name.length() > 256 || password.length() > 4096 || !backend.authenticate(name, password)) {
                    throw new ApiException(401, "INVALID_CREDENTIALS", "Invalid username or password");
                }
                backend.permission(name);
                user = name;
                Neo4jGateway.checkDeadline(deadline);
                status = 200;
                reply.send(status, Json.MAPPER.writeValueAsBytes(Map.of("access_token", tokens.issue(name),
                        "token_type", "Bearer", "expires_in", Tokens.TTL_SECONDS)));
            } else {
                fields(body, Set.of("query", "parameters", "readOnly"));
                String query = text(body, "query");
                if (query.getBytes(StandardCharsets.UTF_8).length > MAX_QUERY) {
                    throw new ApiException(413, "QUERY_LIMIT", "Query text is too large");
                }
                JsonNode parameters = body.get("parameters");
                if (parameters != null && !parameters.isObject()) throw new ApiException(400, "INVALID_PARAMETERS", "Parameters must be an object");
                Map<String, Object> args = parameters == null ? Map.of() : Json.MAPPER.convertValue(parameters,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                if (body.has("readOnly") && !body.get("readOnly").isBoolean()) {
                    throw new ApiException(400, "INVALID_MODE", "readOnly must be a boolean");
                }
                var payload = backend.execute(user, query, args, id, deadline, body.path("readOnly").asBoolean(false));
                classification = payload.classification();
                rowCount = payload.rowCount();
                status = 200; reply.send(status, payload.body());
            }
        } catch (ApiException e) {
            status = e.status;
            if (status == 401) exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            reply.send(status, Json.MAPPER.writeValueAsBytes(Map.of("requestId", id, "code", e.code, "message", e.getMessage())));
        } catch (InterruptedException e) {
            status = 504;
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            status = 500;
            reply.send(status, Json.MAPPER.writeValueAsBytes(Map.of("requestId", id, "code", "INTERNAL_ERROR", "message", "Request failed")));
        } finally {
            watchdog.cancel(false);
            exchange.close();
            if (active) executing.release();
            if (admittedRequest) admitted.release();
            // JSON encoding prevents user-controlled log injection. Do not log queries, tokens or credentials.
            System.out.println(Json.MAPPER.writeValueAsString(Map.of("requestId", id, "user", user == null ? "" : user,
                    "status", reply.status == 0 ? status : reply.status, "classification", classification, "rowCount", rowCount,
                    "durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))));
        }
    }

    private static void fields(JsonNode body, Set<String> allowed) {
        if (body.properties().stream().anyMatch(e -> !allowed.contains(e.getKey()))) {
            throw new ApiException(400, "UNKNOWN_FIELD", "Request contains an unsupported field");
        }
    }
    private static String text(JsonNode body, String name) {
        var node = body.get(name);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new ApiException(400, "MISSING_FIELD", "Non-empty text field required: " + name);
        }
        return node.asText();
    }
    private static final class Reply {
        private final HttpExchange exchange;
        private final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
        private volatile int status;
        Reply(HttpExchange exchange) { this.exchange = exchange; }
        void send(int status, byte[] body) throws IOException {
            if (!started.compareAndSet(false, true)) return;
            this.status = status;
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }
    public void close() {
        server.stop(15);
        workers.shutdown();
        timer.shutdownNow();
        try { if (!workers.awaitTermination(15, TimeUnit.SECONDS)) workers.shutdownNow(); }
        catch (InterruptedException e) { workers.shutdownNow(); Thread.currentThread().interrupt(); }
        backend.close();
    }
}
