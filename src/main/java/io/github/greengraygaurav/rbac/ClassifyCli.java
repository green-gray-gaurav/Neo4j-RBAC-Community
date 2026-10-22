package io.github.greengraygaurav.rbac;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.GraphDatabase;

/** Classifies stdin only; does not execute user queries. Credentials are read from the environment. */
public final class ClassifyCli {
    private ClassifyCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("Usage: ClassifyCli [READ_ONLY|FULL]");
        var mode = args.length == 0 ? QueryClassifier.Mode.READ_ONLY : QueryClassifier.Mode.valueOf(args[0]);
        String query = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        var config = Config.builder().withConnectionTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .withConnectionAcquisitionTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build();
        boolean eligible;
        try (var driver = GraphDatabase.driver(required("NEO4J_URI"),
                AuthTokens.basic(required("NEO4J_USERNAME"), required("NEO4J_PASSWORD")), config)) {
            var classifier = new QueryClassifier(driver, required("NEO4J_DATABASE"), Duration.ofSeconds(5));
            var result = classifier.classify(query, Map.of());
            System.out.printf("kind=%s serverType=%s mode=%s eligible=%s%nreason=%s%n",
                    result.kind(), result.serverType(), mode, result.eligibleFor(mode), result.reason());
            eligible = result.eligibleFor(mode);
        }
        if (!eligible) System.exit(2);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing environment variable " + name);
        return value;
    }
}
