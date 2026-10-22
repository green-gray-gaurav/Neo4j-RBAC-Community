package io.github.greengraygaurav.rbac;

import java.util.*;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;
import org.neo4j.driver.types.Path;

final class ResultValues {
    static Object json(Value value) { return convert(value.asObject()); }

    private static Object convert(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Long number) {
            return number > 9007199254740991L || number < -9007199254740991L ? number.toString() : number;
        }
        if (value instanceof Double number) return Double.isFinite(number) ? number : number.toString();
        if (value instanceof Node node) {
            return Map.of("elementId", node.elementId(), "labels", node.labels(), "properties", convert(node.asMap()));
        }
        if (value instanceof Relationship rel) {
            return Map.of("elementId", rel.elementId(), "type", rel.type(), "startElementId", rel.startNodeElementId(),
                    "endElementId", rel.endNodeElementId(), "properties", convert(rel.asMap()));
        }
        if (value instanceof Path path) {
            var nodes = new ArrayList<>();
            var relationships = new ArrayList<>();
            path.nodes().forEach(n -> nodes.add(convert(n)));
            path.relationships().forEach(r -> relationships.add(convert(r)));
            return Map.of("nodes", nodes, "relationships", relationships);
        }
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((k, v) -> result.put(k.toString(), convert(v)));
            return result;
        }
        if (value instanceof Iterable<?> iterable) {
            var result = new ArrayList<>();
            iterable.forEach(v -> result.add(convert(v)));
            return result;
        }
        if (value instanceof byte[] bytes) return Base64.getEncoder().encodeToString(bytes);
        return value.toString(); // Temporal, duration and spatial values.
    }
}
