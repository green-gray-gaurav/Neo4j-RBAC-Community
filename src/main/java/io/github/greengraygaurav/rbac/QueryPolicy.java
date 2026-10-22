package io.github.greengraygaurav.rbac;

import java.util.Set;
import org.neo4j.cypher.internal.ast.*;
import org.neo4j.cypher.internal.expressions.FunctionInvocation;

/** Operation policy. There is deliberately no label filtering in this all-label release. */
final class QueryPolicy {
    private static final Set<String> CLAUSES = Set.of("Match", "With", "Return", "Unwind", "Finish",
            "ImportingWithSubqueryCall", "ScopeClauseSubqueryCall", "Create", "Insert", "Merge",
            "SetClause", "Delete", "Remove", "Foreach");

    static Statement validate(String text) {
        final Statement statement;
        try { statement = new CypherParserProof().parseSingle(text); }
        catch (IllegalArgumentException | org.neo4j.exceptions.SyntaxException e) {
            throw new ApiException(400, "INVALID_QUERY", "Expected exactly one valid Cypher statement body");
        }
        if (!(statement instanceof Query)) deny("Schema and administrative statements are not available through this API");
        for (var node : AstInspection.nodes(statement)) {
            if (node instanceof Clause && !CLAUSES.contains(node.getClass().getSimpleName())) {
                deny("Unsupported clause: " + node.getClass().getSimpleName());
            }
            if (node instanceof SubqueryCall call && call.inTransactionsParameters().isDefined()) {
                deny("Subqueries IN TRANSACTIONS are not supported");
            }
            if (node instanceof FunctionInvocation function && (function.needsToBeResolved()
                    || Set.of("file", "linenumber").contains(function.name().toLowerCase(java.util.Locale.ROOT)))) {
                deny("Custom or unsupported functions are not permitted");
            }
        }
        return statement;
    }

    private static void deny(String message) { throw new ApiException(403, "QUERY_NOT_ALLOWED", message); }
}
