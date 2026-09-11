package com.mamtrex.hospital.auth;

import com.mamtrex.hospital.department.Department;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.HospitalOrganization;
import com.mamtrex.hospital.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.Objects;

/**
 * One acting assignment (docs/plan3.md Task 3): the single server-verified
 * link between an enabled {@link UserAccount} (the credential identity), the
 * hospital organization, exactly one {@link Role}, and a scope with its
 * optional branch/department narrowing. Legacy global roles on the account
 * stay stored only for bootstrap/migration compatibility — HTTP authority is
 * derived exclusively from this row.
 *
 * <p>Instances are created only through the scope-specific factories
 * ({@code organization}, {@code branch}, {@code department}), which make each
 * valid scope shape inexpressible in the wrong form. Cross-aggregate
 * consistency (a branch or department actually belonging to the
 * organization) is a persisted-state invariant that no in-memory factory can
 * verify; {@code ActingContextService} and the bootstrap re-check it against
 * current database state on every use and fail closed: an ORGANIZATION
 * assignment carries no branch and no department; a BRANCH assignment
 * carries an active branch of its organization and no department; a
 * DEPARTMENT assignment carries a department whose active branch belongs to
 * the organization.</p>
 *
 * <p>Associations are eager: the JWT filter reloads an assignment on every
 * request outside a transaction, and the whole graph is three small rows.
 * The {@code (account_id, role, branch_id, department_id)} unique constraint
 * is the concurrency backstop behind the bootstrap pre-check; SQL null
 * semantics keep null-column tuples mutually distinct, so organization-scope
 * duplicates (both columns null) are prevented by the pre-check alone.</p>
 */
@Entity
@Table(name = "acting_assignments", uniqueConstraints = @UniqueConstraint(
        name = "uk_acting_assignments_logical", columnNames = {"account_id", "role", "branch_id", "department_id"}))
public class ActingAssignment extends BaseEntity {

    @ManyToOne(optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private UserAccount account;

    @ManyToOne(optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private HospitalOrganization organization;

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
                             AssignmentScope scope, Branch branch, Department department) {
        this.account = Objects.requireNonNull(account, "account");
        this.organization = Objects.requireNonNull(organization, "organization");
        this.role = Objects.requireNonNull(role, "role");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.branch = branch;
        this.department = department;
    }

    /** Organization scope: the account acts on the whole organization — no branch, no department. */
    public static ActingAssignment organization(UserAccount account, HospitalOrganization organization, Role role) {
        return new ActingAssignment(account, organization, role, AssignmentScope.ORGANIZATION, null, null);
    }

    /** Branch scope: the account acts on exactly one branch of the organization — never a department. */
    public static ActingAssignment branch(UserAccount account, HospitalOrganization organization,
                                          Role role, Branch branch) {
        return new ActingAssignment(account, organization, role, AssignmentScope.BRANCH,
                Objects.requireNonNull(branch, "branch"), null);
    }

    /** Department scope: the account acts on one department; its active branch is derived at use time. */
    public static ActingAssignment department(UserAccount account, HospitalOrganization organization,
                                              Role role, Department department) {
        return new ActingAssignment(account, organization, role, AssignmentScope.DEPARTMENT,
                null, Objects.requireNonNull(department, "department"));
    }

    public UserAccount getAccount() {
        return account;
    }

    public HospitalOrganization getOrganization() {
        return organization;
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
