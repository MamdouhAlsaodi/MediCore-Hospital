package com.mamtrex.hospital.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Signs and parses the stateless bearer tokens (docs/plan3.md Task 3). The
 * token carries the subject plus the acting assignment/context pointer
 * claims; the role claim exists only for display and diagnostics and is
 * never trusted for authority — {@link JwtFilter} re-derives exactly one
 * authority from the server assignment row on every request, so an altered
 * or expanded role claim in a properly signed token cannot widen (or narrow)
 * server authority. Parsing failures throw to the caller, which owns the
 * intentional fail-closed boundary; no claim or provider detail ever reaches
 * the client.
 *
 * <p>Parsing extracts the structural claims — subject, assignment pointer,
 * scope, organization, selected branch, and optional department — exactly
 * once into the immutable {@link StructuralClaims} pointer value. Every
 * structural claim except {@code departmentId} is required: a missing,
 * malformed, or unknown value throws through the same fail-closed boundary
 * as a bad signature. The role claim is deliberately absent from that value:
 * it is never compared, never trusted, and never authoritative.</p>
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long ttl;

    public JwtService(@Value("${hospital.jwt.secret}") String secret,
                      @Value("${hospital.jwt.ttl-minutes:60}") long ttl) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = ttl;
    }

    /** Issues a token bound to one acting context; branch is always selected, department only for its scope. */
    public String issue(ActingContext context) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(context.username())
                .claim("assignmentId", context.assignmentId().toString())
                .claim("role", context.role().name())
                .claim("scope", context.scope().name())
                .claim("organizationId", context.organizationId().toString())
                .claim("branchId", context.branchId().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(ttl))));
        if (context.departmentId() != null) {
            builder.claim("departmentId", context.departmentId().toString());
        }
        return builder.signWith(key).compact();
    }

    /** Verifies the signature and expiry and returns the raw claims; throws on anything invalid. */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    /**
     * Verifies the token and extracts its structural claims exactly once
     * into the immutable pointer value. A missing or malformed required
     * structural claim throws (a runtime exception), so the filter's
     * existing anonymous boundary absorbs it exactly like a bad signature;
     * only {@code departmentId} is optional, and its null semantics are
     * enforced by the structural comparison, not here.
     */
    public StructuralClaims parseStructural(String token) {
        Claims claims = parse(token);
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Missing subject claim");
        }
        return new StructuralClaims(subject, requiredUuid(claims, "assignmentId"),
                requiredScope(claims), requiredUuid(claims, "organizationId"),
                requiredUuid(claims, "branchId"), optionalUuid(claims, "departmentId"));
    }

    private static UUID requiredUuid(Claims claims, String name) {
        Object value = claims.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Missing " + name + " claim");
        }
        return UUID.fromString(value.toString()); // malformed UUIDs throw through the same boundary
    }

    private static AssignmentScope requiredScope(Claims claims) {
        Object value = claims.get("scope");
        if (value == null) {
            throw new IllegalArgumentException("Missing scope claim");
        }
        return AssignmentScope.valueOf(value.toString()); // unknown scopes throw through the same boundary
    }

    private static UUID optionalUuid(Claims claims, String name) {
        Object value = claims.get(name);
        return value == null ? null : UUID.fromString(value.toString());
    }

    /**
     * The immutable structural token pointer: the subject, the assignment
     * pointer, the scope, the organization, the selected branch, and the
     * optional department. It is the only token content the filter forwards —
     * every field is re-verified against rebuilt server state, and the role
     * claim is intentionally not carried because it is never authoritative.
     */
    public record StructuralClaims(String subject, UUID assignmentId, AssignmentScope scope,
                                   UUID organizationId, UUID branchId, UUID departmentId) {
    }
}
