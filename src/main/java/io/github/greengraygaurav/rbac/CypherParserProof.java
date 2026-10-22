package io.github.greengraygaurav.rbac;

import org.neo4j.cypher.internal.ast.Statement;
import org.neo4j.cypher.internal.parser.v5.ast.factory.Cypher5AstParser;
import org.neo4j.cypher.internal.util.Neo4jCypherExceptionFactory;
import scala.Option;
import org.neo4j.cypher.internal.ast.prettifier.ExpressionStringifier$;
import org.neo4j.cypher.internal.ast.prettifier.Prettifier;

/** Syntax parsing only. A returned statement is NOT approved for execution. */
public final class CypherParserProof {
    /** Diagnostic serialization using Neo4j's own printer, not an authorization compiler. */
    public String render(Statement statement) {
        var defaults = ExpressionStringifier$.MODULE$;
        var expressions = defaults.apply(defaults.apply$default$1(), defaults.apply$default$2(),
                defaults.apply$default$3(), defaults.apply$default$4(), defaults.apply$default$5());
        return new Prettifier(expressions, Prettifier.apply$default$2(), Prettifier.apply$default$3())
                .asString(statement);
    }

    public Statement parseSingle(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Expected one non-empty Cypher statement");
        }
        var factory = new Neo4jCypherExceptionFactory(query, Option.empty());
        var statements = new Cypher5AstParser(query, factory, Option.empty()).statements();
        if (statements.size() != 1) {
            throw new IllegalArgumentException("Expected exactly one Cypher statement; found " + statements.size());
        }
        return statements.get(0);
    }
}
