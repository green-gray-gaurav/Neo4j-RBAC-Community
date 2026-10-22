package io.github.greengraygaurav.rbac;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import java.time.Instant;
import java.util.UUID;

/** Fixed algorithm, issuer and audience. No passwords or permissions inside a token. */
final class Tokens {
    static final long TTL_SECONDS = 300;
    private final Algorithm algorithm;
    private final JWTVerifier verifier;

    Tokens(byte[] key) {
        if (key.length < 32) throw new IllegalArgumentException("JWT key must contain at least 32 random bytes");
        algorithm = Algorithm.HMAC256(key);
        verifier = JWT.require(algorithm).withIssuer("neo4j-rbac").withAudience("neo4j-rbac-api")
                .withClaimPresence("sub").withClaimPresence("exp").withClaimPresence("iat")
                .withClaimPresence("jti").build();
    }

    String issue(String username) {
        Instant now = Instant.now();
        return JWT.create().withIssuer("neo4j-rbac").withAudience("neo4j-rbac-api")
                .withSubject(username).withJWTId(UUID.randomUUID().toString()).withIssuedAt(now)
                .withExpiresAt(now.plusSeconds(TTL_SECONDS)).sign(algorithm);
    }

    String subject(String token) {
        try {
            var jwt = verifier.verify(token);
            if (jwt.getSubject().isBlank() || jwt.getId().isBlank()
                    || jwt.getExpiresAtAsInstant().isAfter(jwt.getIssuedAtAsInstant().plusSeconds(TTL_SECONDS))) {
                throw new IllegalArgumentException();
            }
            return jwt.getSubject();
        } catch (RuntimeException e) {
            throw new ApiException(401, "INVALID_TOKEN", "A valid, unexpired bearer token is required");
        }
    }
}
