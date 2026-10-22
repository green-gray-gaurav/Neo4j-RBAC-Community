package io.github.greengraygaurav.rbac;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.greengraygaurav.rbac.QueryClassifier.Kind.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.summary.QueryType;

class QueryClassifierTest {
    @ParameterizedTest
    @ValueSource(strings = {"RETURN 1; CREATE (:Secret)", "EXPLAIN RETURN 1", "PROFILE CREATE ()",
            "CYPHER runtime=slotted RETURN 1", "RETURN '", "// only comment"})
    void invalidInputNeverReachesServer(String query) {
        var classifier = new QueryClassifier((q, p) -> { fail("Must reject before EXPLAIN"); return null; });
        assertEquals(UNKNOWN, classifier.classify(query, Map.of()).kind());
    }

    @Test
    void forwardsParametersAndOriginalBody() {
        String query = "// leading comment\nRETURN $id;";
        var parameters = Map.<String, Object>of("id", 42L);
        var classifier = new QueryClassifier((q, p) -> {
            assertEquals(query, q);
            assertEquals(parameters, p);
            return QueryType.READ_ONLY;
        });
        assertEquals(READ_ONLY, classifier.classify(query, parameters).kind());
    }

    @Test
    void failureAndMissingTypeNeverBecomeReadOnly() {
        var unavailable = new QueryClassifier((q, p) -> { throw new ServiceUnavailableException("private connection detail"); });
        var result = unavailable.classify("RETURN 1", Map.of());
        assertEquals(UNKNOWN, result.kind());
        assertFalse(result.reason().contains("private connection detail"));
        assertFalse(result.eligibleFor(QueryClassifier.Mode.FULL));
        assertEquals(UNKNOWN, new QueryClassifier((q, p) -> null).classify("RETURN 1", Map.of()).kind());
    }

    @Test
    void refusesReadClassificationThatConflictsWithWriteAst() {
        var classifier = new QueryClassifier((q, p) -> QueryType.READ_ONLY);
        assertEquals(UNKNOWN, classifier.classify("CREATE (:Customer)", Map.of()).kind());
    }

    @Test
    void modesDoNotPromoteUnknownToEligible() {
        for (var kind : QueryClassifier.Kind.values()) {
            var result = new QueryClassifier.Classification(kind, null, "test");
            assertEquals(kind == READ_ONLY, result.eligibleFor(QueryClassifier.Mode.READ_ONLY));
            assertEquals(kind != UNKNOWN, result.eligibleFor(QueryClassifier.Mode.FULL));
        }
    }
}
