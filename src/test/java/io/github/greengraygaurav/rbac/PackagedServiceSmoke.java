package io.github.greengraygaurav.rbac;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.harness.Neo4jBuilders;

/** Run after package to exercise the actual executable JAR, not test-classpath service classes. */
public final class PackagedServiceSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Supply the service JAR path");
        var jar = Path.of(args[0]).toAbsolutePath();
        var directory = Files.createTempDirectory("neo4j-rbac-package-smoke-");
        try (var database = Neo4jBuilders.newInProcessBuilder().withDisabledServer()
                .withConfig(GraphDatabaseSettings.auth_enabled, true).build()) {
            try (var tx = database.databaseManagementService().database("system").beginTx()) {
                tx.execute("ALTER USER neo4j SET PASSWORD 'smoke-backend-password' CHANGE NOT REQUIRED");
                tx.execute("CREATE USER reader SET PASSWORD 'smoke-reader-password' CHANGE NOT REQUIRED");
                tx.execute("CREATE USER writer SET PASSWORD 'smoke-writer-password' CHANGE NOT REQUIRED");
                tx.commit();
            }
            var policy = directory.resolve("permissions.json");
            Files.writeString(policy, "{\"users\":{\"reader\":\"readOnly\",\"writer\":\"full\"}}");
            byte[] key = new byte[32];
            new java.security.SecureRandom().nextBytes(key);
            var keyFile = directory.resolve("jwt.key");
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(key));
            var log = directory.resolve("service.log");
            var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                    "-XX:ActiveProcessorCount=4", "-Xmx512m", "-jar", jar.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().putAll(Map.of("NEO4J_URI",database.boltURI().toString(),"NEO4J_DATABASE","neo4j",
                    "NEO4J_USERNAME","neo4j","NEO4J_PASSWORD","smoke-backend-password",
                    "RBAC_PERMISSIONS_FILE",policy.toString(),"RBAC_JWT_KEY_FILE",keyFile.toString()));
            var process = builder.start();
            try {
                var client = HttpClient.newHttpClient();
                boolean ready = false;
                for (int attempt=0; attempt<100; attempt++) {
                    if (!process.isAlive()) fail("Service exited: " + Files.readString(log));
                    try {
                        var health = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:4444/health"))
                                .timeout(Duration.ofSeconds(1)).build(),HttpResponse.BodyHandlers.ofString());
                        if (health.statusCode()==200) { ready=true; break; }
                    } catch (java.io.IOException ignored) { /* Wait for service startup. */ }
                    Thread.sleep(100);
                }
                assertTrue(ready,"Executable JAR did not start");
                for (String user : List.of("reader","writer")) {
                    var login = post(client,"/v1/auth/login",Map.of("username",user,"password","smoke-"+user+"-password"),null);
                    assertEquals(200,login.statusCode(),login.body());
                    String token = Json.MAPPER.readTree(login.body()).path("access_token").asText();
                    assertEquals(200,post(client,"/v1/query",Map.of("query","MATCH(n) RETURN count(*)"),token).statusCode());
                    assertEquals(user.equals("writer")?200:403,
                            post(client,"/v1/query",Map.of("query","CREATE (:Smoke)"),token).statusCode());
                    assertEquals(403,post(client,"/v1/query",Map.of("query","CREATE (:Smoke)","readOnly",true),token).statusCode());
                }
                System.out.println("Packaged JAR: login, read/full separation and Redash read restriction passed on port 4444");
            } finally {
                process.destroy();
                if (!process.waitFor(35,TimeUnit.SECONDS)) process.destroyForcibly().waitFor();
                Files.deleteIfExists(keyFile);
            }
        }
    }
    private static HttpResponse<String> post(HttpClient client,String path,Object body,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:4444"+path))
                .timeout(Duration.ofSeconds(20)).header("Content-Type","application/json");
        if(token!=null) request.header("Authorization","Bearer "+token);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(Json.MAPPER.writeValueAsBytes(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
