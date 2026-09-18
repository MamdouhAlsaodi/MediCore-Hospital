package com.mamtrex.hospital.auth;

import com.mamtrex.hospital.department.Department;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.HospitalFacility;
import com.mamtrex.hospital.organization.HospitalOrganization;
import com.mamtrex.hospital.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Objects;
import java.util.UUID;

/**
 * One acting assignment (docs/plan3.md Task 3; Phase 5 data-model.md): the
 * single server-verified link between an enabled {@link UserAccount} (the
 * credential identity), the hospital organization, at most one
 * {@link HospitalFacility}, exactly one {@link Role}, and a scope with its
 * optional hospital/branch/department narrowing. Legacy global roles on the
 * account stay stored only for bootstrap/migration compatibility — HTTP
 * authority is derived exclusively from this row.
 *
 * <p>Instances are created only through the scope-specific factories
 * ({@code organization}, {@code hospital}, {@code branch}, {@code
 * department}), which make each valid scope shape inexpressible in the
 * wrong form. An ORGANIZATION assignment carries no hospital, branch, or
 * department (the acting hospital and branch are resolved per request); a
 * HOSPITAL assignment carries its facility and nothing narrower; a BRANCH
 * assignment derives its hospital from the branch itself; a DEPARTMENT
 * assignment derives its hospital through the department's branch and
 * refuses a branchless department. Cross-aggregate consistency (a branch or
 * hospital really belonging to the organization) is additionally a
 * persisted-state invariant that no in-memory factory can fully verify;
 * {@code ActingContextService} and the bootstrap re-check it against
 * current database state on every use and fail closed.</p>
 *
 * <p>Associations are eager: the JWT filter reloads an assignment on every
 * request outside a transaction, and the whole graph is a handful of small
 * rows. Table-level uniqueness is intentionally NOT declared here: the
 * V5 migration replaced the null-sensitive
 * {@code uk_acting_assignments_logical} constraint (SQL NULLs are distinct)
 * with the PostgreSQL-safe null-collapsing expression unique index
 * {@code uq_acting_assignments_scope_logical}, which no annotation can
 * express. Hibernate's validator does not check indexes or unique keys, so
 * validation stays green while the database remains the backstop.</p>
 */
@Entity
@Table(name = "acting_assignments")
public class ActingAssignment extends BaseEntity {

    @ManyToOne(optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private UserAccount account;

    @ManyToOne(optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private HospitalOrganization organization;

    /** The fixed acting hospital facility; null only for ORGANIZATION scope. */
    @ManyToOne
    @JoinColumn(name = "hospital_id")
    private HospitalFacility hospital;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssignmentScope scope;

    @ManyToOne
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @ManyToOne
    @JoinColumn(name = "department_id")
    private Department department;

    @Column(nullable = false)
    private boolean enabled = true;

    protected ActingAssignment() {
    }

    private ActingAssignment(UserAccount account, HospitalOrganization organization, Role role,
                             AssignmentScope scope, HospitalFacility hospital, Branch branch,
                             Department department) {
        this.account = Objects.requireNonNull(account, "account");
        this.organization = Objects.requireNonNull(organization, "organization");
        this.role = Objects.requireNonNull(role, "role");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.hospital = hospital;
        this.branch = branch;
        this.department = department;
    }

    /** Organization (network) scope: acts on the whole network — no hospital, branch, or department. */
    public static ActingAssignment organization(UserAccount account, HospitalOrganization organization, Role role) {
        return new ActingAssignment(account, organization, role, AssignmentScope.ORGANIZATION, null, null, null);
    }

    /**
     * Hospital scope: the account acts on exactly one hospital facility —
     * never a branch or department; the selected branch must belong to the
     * hospital and is resolved per request. The facility must belong to the
     * assignment's organization (validated here, never trusted from a
     * caller-supplied pair).
     */
    public static ActingAssignment hospital(UserAccount account, HospitalOrganization organization,
                                            Role role, HospitalFacility hospital) {
        Objects.requireNonNull(hospital, "hospital");
        requireSameOrganization(organization, hospital.getOrganization());
        return new ActingAssignment(account, organization, role, AssignmentScope.HOSPITAL, hospital, null, null);
    }

    /** Branch scope: the account acts on exactly one branch — its hospital is derived from the branch. */
    public static ActingAssignment branch(UserAccount account, HospitalOrganization organization,
                                          Role role, Branch branch) {
        Objects.requireNonNull(branch, "branch");
        requireSameOrganization(organization, branch.getOrganization());
        return new ActingAssignment(account, organization, role, AssignmentScope.BRANCH,
                branch.getHospital(), branch, null);
    }

    /**
     * Department scope: the account acts on one department; its active
     * branch and hospital are derived at use time. A department without a
     * branch cannot derive a hospital and is refused here — the invalid
     * assignment shape is inexpressible.
     */
    public static ActingAssignment department(UserAccount account, HospitalOrganization organization,
                                              Role role, Department department) {
        Objects.requireNonNull(department, "department");
        if (department.getBranch() == null || department.getBranch().getHospital() == null) {
            throw new IllegalArgumentException(
                    "A department assignment requires a department whose branch and hospital are resolvable.");
        }
        requireSameOrganization(organization, department.getBranch().getOrganization());
        return new ActingAssignment(account, organization, role, AssignmentScope.DEPARTMENT,
                department.getBranch().getHospital(), null, department);
    }

    /**
     * The validated-consistency guard shared by the hospital/branch/department
     * factories. Persisted rows are compared by id; transient fixtures (ids
     * not yet assigned) fall back to reference identity. This is an
     * in-memory shape guard only — the current database state is re-verified
     * fail-closed on every use.
     */
    private static void requireSameOrganization(HospitalOrganization expected, HospitalOrganization actual) {
        UUID expectedId = expected.getId();
        UUID actualId = actual.getId();
        boolean mismatch = (expectedId != null && actualId != null)
                ? !expectedId.equals(actualId)
                : actual != expected;
        if (mismatch) {
            throw new IllegalArgumentException(
                    "The assignment target does not belong to the assignment's organization.");
        }
    }

    public UserAccount getAccount() {
        return account;
    }

    public HospitalOrganization getOrganization() {
        return organization;
    }

    /** The fixed acting hospital; null only on the ORGANIZATION scope. */
    public HospitalFacility getHospital() {
        return hospital;
    }

    public Role getRole() {
        return role;
    }

    public AssignmentScope getScope() {
        return scope;
    }

    public Branch getBranch() {
        return branch;
    }

    public Department getDepartment() {
        return department;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Package-private lifecycle seam (docs/plan3.md Task 3): assignments are
     * only ever disabled through server-side lifecycle features, and this
     * task ships no public endpoint for it. The fail-closed tests are the
     * only callers; widen visibility only when a real assignment-lifecycle
     * feature calls for it.
     */
    void disable() {
        this.enabled = false;
    }
}
