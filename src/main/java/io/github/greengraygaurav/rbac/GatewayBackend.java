package io.github.greengraygaurav.rbac;

import java.util.Map;

interface GatewayBackend extends AutoCloseable {
    record Execution(byte[] body, String classification, int rowCount) {}
    boolean authenticate(String username, String password);
    QueryClassifier.Mode permission(String username);
    Execution execute(String username, String query, Map<String, Object> parameters, String requestId, long deadline, boolean readOnly);
    default Execution execute(String username, String query, Map<String, Object> parameters, String requestId, long deadline) {
        return execute(username, query, parameters, requestId, deadline, false);
    }
    @Override void close();
}
