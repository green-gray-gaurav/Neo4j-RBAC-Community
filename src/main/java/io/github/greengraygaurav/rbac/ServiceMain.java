package io.github.greengraygaurav.rbac;

import java.nio.file.*;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import org.neo4j.driver.*;

public final class ServiceMain {
    private ServiceMain() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("Configure the service through its environment");
        var permissions = new FilePermissions(Path.of(env("RBAC_PERMISSIONS_FILE", "/etc/neo4j-rbac/permissions.json")));
        var keyPath = Path.of(env("RBAC_JWT_KEY_FILE", "/etc/neo4j-rbac/jwt.key"));
        var tokens = new Tokens(Base64.getDecoder().decode(Files.readString(keyPath).strip()));
        var config = Config.builder().withMaxConnectionPoolSize(8)
                .withConnectionTimeout(5, TimeUnit.SECONDS).withConnectionAcquisitionTimeout(5, TimeUnit.SECONDS)
                .withMaxTransactionRetryTime(0, TimeUnit.SECONDS).withTelemetryDisabled(true).build();
        var driver = GraphDatabase.driver(required("NEO4J_URI"),
                AuthTokens.basic(required("NEO4J_USERNAME"), required("NEO4J_PASSWORD")), config);
        try {
            driver.verifyConnectivity();
            var limits = new Neo4jGateway.Limits(Integer.parseInt(env("RBAC_MAX_ROWS", "1000")),
                    Integer.parseInt(env("RBAC_MAX_RESULT_BYTES", "4194304")), Duration.ofSeconds(10));
            var backend = new Neo4jGateway(driver, required("NEO4J_DATABASE"), permissions, limits, required("NEO4J_URI"));
            var server = new ApiServer(4444, backend, tokens, Duration.ofSeconds(15));
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
            server.start();
            System.out.println("{\"event\":\"started\",\"bind\":\"127.0.0.1:4444\"}");
        } catch (Exception e) { driver.close(); throw e; }
    }
    private static String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }
    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing environment variable " + name);
        return value;
    }
}
