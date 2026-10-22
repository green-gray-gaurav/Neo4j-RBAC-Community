package io.github.greengraygaurav.rbac;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.junit.jupiter.api.Test;

class ApiServerTest {
    @Test void rejectsExpiredWrongAudienceAlgorithmAndKeyTokens() {
        var tokens = new Tokens(new byte[32]);
        var now = Instant.now();
        var expired = JWT.create().withIssuer("neo4j-rbac").withAudience("neo4j-rbac-api")
                .withSubject("reader").withJWTId("id").withIssuedAt(now.minusSeconds(400))
                .withExpiresAt(now.minusSeconds(100)).sign(Algorithm.HMAC256(new byte[32]));
        assertEquals(401, assertThrows(ApiException.class, () -> tokens.subject(expired)).status);
        assertThrows(ApiException.class, () -> tokens.subject(JWT.create().withSubject("writer").sign(Algorithm.none())));
        assertThrows(ApiException.class, () -> tokens.subject(new Tokens("different-key-with-at-least-32-bytes".getBytes()).issue("writer")));
        assertThrows(ApiException.class, () -> tokens.subject(JWT.create().withIssuer("neo4j-rbac").withAudience("wrong")
                .withSubject("reader").withJWTId("id").withIssuedAt(now).withExpiresAt(now.plusSeconds(100))
                .sign(Algorithm.HMAC256(new byte[32]))));
        assertThrows(IllegalArgumentException.class, () -> new Tokens(new byte[31]));
    }

    static class Backend implements GatewayBackend {
        volatile CountDownLatch release;
        final CountDownLatch entered = new CountDownLatch(8);
        public boolean authenticate(String u, String p) { return true; }
        public QueryClassifier.Mode permission(String u) { return QueryClassifier.Mode.READ_ONLY; }
        public Execution execute(String u, String q, Map<String,Object> p, String id, long d, boolean r) {
            entered.countDown();
            try { if (release != null) release.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            Neo4jGateway.checkDeadline(d);
            return new Execution("{}".getBytes(),"READ_ONLY",0);
        }
        public void close() {}
    }

    @Test void sendsDeadlineErrorAndReleasesResources() throws Exception {
        var backend = new Backend();
        backend.release = new CountDownLatch(1);
        var tokens = new Tokens(new byte[32]);
        try (var api = new ApiServer(0, backend, tokens, Duration.ofMillis(200))) {
            api.start();
            var client = HttpClient.newHttpClient();
            var response = client.send(request(api, tokens.issue("reader")), HttpResponse.BodyHandlers.ofString());
            assertEquals(504, response.statusCode());
            backend.release.countDown();
            var health = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + "/health"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
        }
    }

    @Test void validatesHttpRoutesBodyAndContentType() throws Exception {
        var tokens = new Tokens(new byte[32]);
        try (var api = new ApiServer(0, new Backend(), tokens, Duration.ofSeconds(15))) {
            api.start();
            var client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + api.port();
            assertEquals(404, client.send(HttpRequest.newBuilder(URI.create(base+"/missing")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(405, client.send(HttpRequest.newBuilder(URI.create(base+"/v1/query")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            var noType = HttpRequest.newBuilder(URI.create(base+"/v1/auth/login"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            assertEquals(415,client.send(noType,HttpResponse.BodyHandlers.ofString()).statusCode());
            var tooBig = HttpRequest.newBuilder(URI.create(base+"/v1/auth/login")).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("x".repeat(ApiServer.MAX_BODY+1))).build();
            assertEquals(413,client.send(tooBig,HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test void saturationReturns429RatherThanExecutingExtraWork() throws Exception {
        var backend = new Backend();
        backend.release = new CountDownLatch(1);
        var tokens = new Tokens(new byte[32]);
        try (var api = new ApiServer(0, backend, tokens, Duration.ofSeconds(15))) {
            api.start();
            var client = HttpClient.newHttpClient();
            var requests = new ArrayList<CompletableFuture<HttpResponse<String>>>();
            for (int i=0; i<8; i++) requests.add(client.sendAsync(request(api,tokens.issue("reader")), HttpResponse.BodyHandlers.ofString()));
            try {
                assertTrue(backend.entered.await(10,TimeUnit.SECONDS));
                var rejected = client.send(request(api,tokens.issue("reader")), HttpResponse.BodyHandlers.ofString());
                assertEquals(429,rejected.statusCode());
            } finally { backend.release.countDown(); }
            for (var request : requests) assertEquals(200, request.get(10,TimeUnit.SECONDS).statusCode());
        }
    }

    private static HttpRequest request(ApiServer api, String token) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+api.port()+"/v1/query"))
                .timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+token)
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"RETURN 1\"}")).build();
    }
}
