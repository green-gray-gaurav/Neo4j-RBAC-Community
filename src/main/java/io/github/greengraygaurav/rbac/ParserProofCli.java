package io.github.greengraygaurav.rbac;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Reads Cypher from stdin; never connects to a database or executes it. */
public final class ParserProofCli {
    private ParserProofCli() {}

    public static void main(String[] args) throws Exception {
        String query = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        int exit = run(query, System.out, System.err);
        if (exit != 0) System.exit(exit);
    }

    static int run(String query, PrintStream out, PrintStream err) {
        try {
            var statement = new CypherParserProof().parseSingle(query);
            out.println("Parsed one statement with Neo4j 5.23.0 (syntax only; not authorized)");
            for (var entry : AstInspection.inspect(statement)) {
                out.printf("%s  %s  @%d:%d offset=%d%s%n", entry.path(), entry.type(),
                        entry.line(), entry.column(), entry.offset(),
                        entry.label() == null ? "" : " label=" + entry.label());
            }
            return 0;
        } catch (IllegalArgumentException | org.neo4j.exceptions.SyntaxException error) {
            err.println("Parse rejected: " + error.getMessage());
            return 2;
        }
    }
}
