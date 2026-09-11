package com.mamtrex.hospital.auth;

import java.security.Principal;
import java.util.UUID;

/**
 * The immutable allowlisted acting principal/context value carried in the
 * security session and inside the JWT (docs/plan3.md Task 3): the username,
 * the acting assignment pointer, the acting role, the scope, and the
 * selected organization/branch/department. It contains no password, no
 * hash, no token, and no JPA entity, and it is re-derived from server state
 * on every request — the JWT claims only point at the assignment row, so a
 * tampered claim can never widen authority. Implementing {@link Principal}
 * keeps the authenticated username (not a record dump) as the audit actor.
 */
public record ActingContext(
        String username,
        UUID assignmentId,
        Role role,
        AssignmentScope scope,
        UUID organizationId,
        UUID branchId,
        UUID departmentId) implements Principal {

    @Override
    public String getName() {
        return username;
    }
}
