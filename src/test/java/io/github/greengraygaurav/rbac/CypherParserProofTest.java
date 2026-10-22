package io.github.greengraygaurav.rbac;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CypherParserProofTest {
    private final CypherParserProof parser = new CypherParserProof();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "// comment only", "/* only comment */", ";", "MATCH (c:Customer RETURN c",
            "RETURN 1 garbage", "RETURN 1; garbage", "RETURN 'unterminated", "RETURN 1 /* unclosed",
            "RETURN 1; RETURN 2", "RETURN 1;;", "MATCH (c:Customer) RETURN c; CREATE (:Order)",
            "CYPHER 5 MATCH (c:Customer) RETURN c", "EXPLAIN RETURN 1", "PROFILE RETURN 1"})
    void rejectsEmptyMalformedMultipleOrPrefixedInput(String query) {
        assertThrows(RuntimeException.class, () -> parser.parseSingle(query));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "MATCH (c:Customer) WHERE c.id = $id RETURN c.name AS name",
            "MATCH (c:Customer) WITH c.city AS city, count(*) AS n RETURN city, n ORDER BY n DESC SKIP 1 LIMIT 10",
            "MATCH (c:Customer) RETURN c.name; // trailing comment",
            "RETURN '; CREATE (:Secret)' AS text /* not a second statement */",
            "MATCH (`a``b`:`Customer data`) RETURN `a``b`.name",
            "MATCH (c:Customer) RETURN c.name AS name UNION ALL MATCH (s:Secret) RETURN s.name AS name",
            "CALL { MATCH (s:Secret) RETURN s.name AS name } RETURN name",
            "MATCH (c:Customer) WHERE EXISTS { MATCH (s:Secret) RETURN s } RETURN c.name",
            "MATCH (c:Customer) RETURN [(c)-->(s:Secret) | s.name] AS names",
            "MATCH (c:Customer) RETURN COUNT { MATCH (s:Secret) RETURN s } AS n",
            "MATCH (c:Customer) RETURN COLLECT { MATCH (s:Secret) RETURN s.name } AS names",
            "CREATE (:Customer)", "MATCH (c:Customer) SET c.name = 'changed' RETURN c",
            "CALL db.labels() YIELD label RETURN label", "SHOW USERS",
            "LOAD CSV FROM 'https://example.invalid/data.csv' AS row RETURN row",
            "RETURN custom.function(1)", "RETURN missingVariable"
    })
    void parsesAndRoundTripsSyntaxWithoutApprovingIt(String query) {
        var statement = parser.parseSingle(query);
        String rendered = parser.render(statement);
        var reparsed = parser.parseSingle(rendered);
        assertEquals(rendered, parser.render(reparsed), "Printer must reach a stable representation");
        assertEquals(labels(statement), labels(reparsed), "Labels survive printing");
        assertEquals(types(statement), types(reparsed), "AST node structure survives printing");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "MATCH (c:Customer) RETURN c.name UNION MATCH (s:Secret) RETURN s.name",
            "MATCH (c:Customer) CALL { MATCH (s:Secret) RETURN s.name AS name } RETURN name",
            "MATCH (c:Customer) WHERE EXISTS { MATCH (s:Secret) } RETURN c.name",
            "MATCH (c:Customer) RETURN [(c)-->(s:Secret) | s.name]",
            "MATCH (c:Customer) RETURN COUNT { MATCH (s:Secret) }",
            "MATCH (c:Customer) RETURN COLLECT { MATCH (s:Secret) RETURN s.name }"
    })
    void visitsLabelsInsideEveryBranch(String query) {
        assertEquals(List.of("Customer", "Secret"), labels(parser.parseSingle(query)));
    }

    @Test
    void exposesDecodedLabelsAndSourcePosition() {
        var entries = AstInspection.inspect(parser.parseSingle("MATCH (c:`Customer data`)\nRETURN c.name"));
        var label = entries.stream().filter(e -> e.label() != null).findFirst().orElseThrow();
        assertEquals("Customer data", label.label());
        assertEquals(9, label.offset());
        assertEquals(1, label.line());
        assertEquals(10, label.column());
        assertTrue(entries.stream().anyMatch(e -> e.type().equals("Return") && e.line() == 2));
    }

    @Test
    void keywordsInCommentsAndLiteralsDoNotBecomeClauses() {
        var types = types(parser.parseSingle("RETURN 'CREATE (:Secret);' AS text // DELETE MATCH"));
        assertFalse(types.contains("Create"));
        assertFalse(types.contains("Match"));
        assertEquals(List.of(), labels(parser.parseSingle("RETURN ':Secret'")));
    }

    @Test
    void cliReturnsNonzeroForRejectedInputAndMakesNoAuthorizationClaim() {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        assertEquals(2, ParserProofCli.run("RETURN 1; RETURN 2", new PrintStream(out), new PrintStream(err)));
        assertTrue(err.toString().contains("Parse rejected"));
        assertEquals(0, ParserProofCli.run("CREATE (:Customer)", new PrintStream(out), new PrintStream(err)));
        assertTrue(out.toString().contains("not authorized"));
    }

    private static List<String> labels(org.neo4j.cypher.internal.ast.Statement statement) {
        return AstInspection.inspect(statement).stream().map(AstInspection.Entry::label)
                .filter(java.util.Objects::nonNull).toList();
    }

    private static List<String> types(org.neo4j.cypher.internal.ast.Statement statement) {
        return AstInspection.inspect(statement).stream().map(AstInspection.Entry::type).toList();
    }
}
