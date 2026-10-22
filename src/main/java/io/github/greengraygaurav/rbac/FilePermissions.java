package io.github.greengraygaurav.rbac;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Reload on every request. An invalid file disables access rather than reusing stale grants. */
final class FilePermissions {
    private final Path path;
    FilePermissions(Path path) { this.path = path; }

    QueryClassifier.Mode mode(String username) {
        try {
            if (Files.size(path) > 65536) throw new IOException();
            var root = Json.MAPPER.readTree(Files.readAllBytes(path));
            if (!root.isObject() || root.size() != 1 || !root.path("users").isObject()) throw new IOException();
            var users = root.path("users");
            for (var user : users.properties()) {
                if (!user.getValue().isTextual() || !Map.of("readOnly", 1, "full", 1).containsKey(user.getValue().asText())) {
                    throw new IOException();
                }
            }
            var mode = users.get(username);
            if (mode == null) throw new ApiException(403, "NO_PERMISSION", "Account has no API permission");
            return mode.asText().equals("full") ? QueryClassifier.Mode.FULL : QueryClassifier.Mode.READ_ONLY;
        } catch (IOException | IllegalArgumentException e) {
            throw new ApiException(503, "POLICY_UNAVAILABLE", "Permissions configuration is unavailable or invalid");
        }
    }
}
