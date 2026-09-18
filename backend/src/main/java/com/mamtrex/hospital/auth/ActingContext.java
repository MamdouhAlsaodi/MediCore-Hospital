package com.mamtrex.hospital.auth;

import java.security.Principal;
import java.util.UUID;

/**
 * The immutable allowlisted acting principal/context value carried in the
 * security session and inside the JWT (docs/plan3.md Task 3; Phase 5
 * data-model.md): the username, the acting assignment pointer, the acting
 * role, the scope, and the selected organization/hospital/branch/department
 * chain. The hospital id is always non-null for an operational request:
 * every scope resolves to one concrete branch, and that branch's facility
 * is the acting hospital. It contains no password, no hash, no token, and
 * no JPA entity, and it is re-derived from server state on every request —
 * the JWT claims only point at the assignment row, so a tampered claim can
 * never widen authority. Implementing {@link Principal} keeps the
 * authenticated username (not a record dump) as the audit actor.
 */
public record ActingContext(
        String username,
        UUID assignmentId,
        Role role,
        AssignmentScope scope,
        UUID organizationId,
        UUID hospitalId,
        UUID branchId,
        UUID departmentId) implements Principal {

    public ActingContext {
        java.util.Objects.requireNonNull(hospitalId, "hospitalId");
        java.util.Objects.requireNonNull(branchId, "branchId");
    }

    @Override
    public String getName() {
        return username;
    }
}
