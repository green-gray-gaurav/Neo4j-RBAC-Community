package io.github.greengraygaurav.rbac;

import java.util.ArrayList;
import java.util.List;
import org.neo4j.cypher.internal.ast.Statement;
import org.neo4j.cypher.internal.expressions.LabelName;
import org.neo4j.cypher.internal.util.ASTNode;
import scala.Option;
import scala.Product;
import scala.collection.Iterable;

/** Diagnostic tree traversal, deliberately not an authorization validator. */
public final class AstInspection {
    private AstInspection() {}

    public record Entry(String path, String type, int offset, int line, int column, String label) {}

    public static List<Entry> inspect(Statement statement) {
        var entries = new ArrayList<Entry>();
        walk(statement, "$", entries);
        return List.copyOf(entries);
    }

    // Scala AST case classes may wrap children in Option, sequences, sets and tuples.
    // Walk those containers as well, so nested branches are not silently skipped.
    private static void walk(Object value, String path, List<Entry> entries) {
        if (value instanceof ASTNode node) {
            var position = node.position();
            entries.add(new Entry(path, node.getClass().getSimpleName(), position.offset(),
                    position.line(), position.column(), node instanceof LabelName label ? label.name() : null));
        }
        if (value instanceof Option<?> option) {
            if (option.isDefined()) walk(option.get(), path + ".value", entries);
        } else if (value instanceof Iterable<?> iterable) {
            var iterator = iterable.iterator();
            int index = 0;
            while (iterator.hasNext()) walk(iterator.next(), path + "[" + index++ + "]", entries);
        } else if (value instanceof Product product) {
            for (int i = 0; i < product.productArity(); i++) {
                walk(product.productElement(i), path + "." + product.productElementName(i), entries);
            }
        }
    }
}
