package com.mamtrex.hospital.emergency;

import com.mamtrex.hospital.audit.AuditService;
import com.mamtrex.hospital.auth.ActingContext;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
import com.mamtrex.hospital.patient.Patient;
import com.mamtrex.hospital.patient.PatientRepository;
import com.mamtrex.hospital.shared.InvalidStateTransitionException;
import com.mamtrex.hospital.shared.NotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Emergency-visit workflow rules (docs/plan2.md Task 3, branch scope added
 * by docs/plan3.md Task 8): creation derives ownership from the acting
 * context (never client input) and resolves the patient reference through
 * the patient repository scoped to that branch — a cross-branch or unknown
 * patient answers the same generic 404, so no existence information leaks —
 * and persists only after it resolves, storing the validated UUID and the
 * typed arrivalAt as canonical strings because the legacy emergency_visits
 * columns remain String-typed (no destructive column migration).
 *
 * <p>Branch isolation (docs/plan3.md Task 8): every new visit is owned by
 * the authenticated acting branch, and every visit read and command
 * resolves its row through the branch-scoped repository methods, so a
 * cross-branch id and a legacy null-ownership row answer the same generic
 * 404 as a nonexistent one, with no mutation and no audit event. No
 * branch-scoped path ever touches whole-table {@code findAll()}, unscoped
 * {@code findById()}, or {@code existsById()}.</p>
 *
 * <p>The server owns the lifecycle: a new visit is WAITING, and the only
 * legal transitions are WAITING -> IN_TREATMENT | CLOSED and
 * IN_TREATMENT -> CLOSED, with CLOSED terminal — repeating a transition,
 * moving backward, or requesting any target outside the map is an
 * {@link InvalidStateTransitionException} (shared 409). The triageLevel
 * stays the neutral 1–5 demo label verified by the DTO @Pattern — it has NO
 * clinical meaning and is never a triage protocol. The state change applies
 * through the narrow {@link EmergencyVisit#changeStatus(String)} mutation
 * with ordinary dirty checking (no bulk JPQL, no EntityManager). Create,
 * every transition, and delete own exactly one audit event each; failed
 * operations record nothing. Queries are read-only.</p>
 */
@Service
@Transactional
public class EmergencyVisitService {

    /** Initial lifecycle state, set by the server alone at creation. */
    static final String STATUS_WAITING = "WAITING";

    /** Active-treatment lifecycle state. */
    static final String STATUS_IN_TREATMENT = "IN_TREATMENT";

    /** Terminal lifecycle state; no further transitions exist. */
    static final String STATUS_CLOSED = "CLOSED";

    /** Every legal transition target — the map below narrows it per state. */
    private static final Set<String> TRANSITION_TARGETS = Set.of(STATUS_IN_TREATMENT, STATUS_CLOSED);

    private final EmergencyVisitRepository visits;
    private final PatientRepository patients;
    private final BranchRepository branches;
    private final AuditService audit;

    public EmergencyVisitService(EmergencyVisitRepository visits, PatientRepository patients,
                                 BranchRepository branches, AuditService audit) {
        this.visits = visits;
        this.patients = patients;
        this.branches = branches;
        this.audit = audit;
    }

    public EmergencyVisitDtos.EmergencyVisitResponse create(EmergencyVisitDtos.CreateEmergencyVisitRequest r) {
        Branch acting = actingBranch();
        Patient patient = patients.findByIdAndBranchId(r.patientId(), acting.getId())
                .orElseThrow(() -> new NotFoundException("Patient not found: " + r.patientId()));
        // Ownership is server-stamped from the acting branch; the create
        // request carries no branch field, so client input can never choose it.
        EmergencyVisit saved = visits.save(new EmergencyVisit(acting.getId(), patient.getId().toString(),
                r.arrivalAt().toString(), r.triageLevel(), r.chiefComplaint().trim(), STATUS_WAITING));
        audit.record("CREATE", "EmergencyVisit", saved.getId().toString(), "created");
        return EmergencyVisitDtos.EmergencyVisitResponse.from(saved);
    }

    /** Branch-scoped list (docs/plan3.md Task 8): only the acting branch's own visits. */
    @Transactional(readOnly = true)
    public List<EmergencyVisitDtos.EmergencyVisitResponse> list() {
        return visits.findByBranchId(currentContext().branchId()).stream()
                .map(EmergencyVisitDtos.EmergencyVisitResponse::from).toList();
    }

    /** Branch-scoped detail (docs/plan3.md Task 8): the generic 404 for any other branch's row. */
    @Transactional(readOnly = true)
    public EmergencyVisitDtos.EmergencyVisitResponse get(UUID id) {
        return visits.findByIdAndBranchId(id, currentContext().branchId())
                .map(EmergencyVisitDtos.EmergencyVisitResponse::from)
                .orElseThrow(() -> new NotFoundException("EmergencyVisit not found: " + id));
    }

    /**
     * Server-applied lifecycle transition inside the acting branch. The
     * visit is resolved through the branch-scoped lookup, WAITING admits
     * IN_TREATMENT and CLOSED, IN_TREATMENT admits only CLOSED, CLOSED is
     * terminal, and the change persists through the narrow
     * {@link EmergencyVisit#changeStatus(String)} with ordinary dirty
     * checking in the caller's transaction.
     */
    public EmergencyVisitDtos.EmergencyVisitResponse updateStatus(UUID id, EmergencyVisitDtos.UpdateEmergencyVisitStatusRequest r) {
        EmergencyVisit visit = visits.findByIdAndBranchId(id, currentContext().branchId())
                .orElseThrow(() -> new NotFoundException("EmergencyVisit not found: " + id));
        String target = r.status();
        if (!TRANSITION_TARGETS.contains(target)) {
            throw new InvalidStateTransitionException(
                    "Emergency visit " + id + " has no " + target
                            + " transition: only IN_TREATMENT or CLOSED are defined");
        }
        String current = visit.getStatus();
        if (STATUS_CLOSED.equals(current)) {
            throw new InvalidStateTransitionException(
                    "Emergency visit " + id + " is CLOSED: no further transitions are defined");
        }
        if (STATUS_IN_TREATMENT.equals(target) && STATUS_IN_TREATMENT.equals(current)) {
            throw new InvalidStateTransitionException(
                    "Emergency visit " + id + " cannot transition to IN_TREATMENT: it is already IN_TREATMENT");
        }
        visit.changeStatus(target);
        EmergencyVisit saved = visits.save(visit);
        audit.record("UPDATE", "EmergencyVisit", id.toString(), "status: " + target);
        return EmergencyVisitDtos.EmergencyVisitResponse.from(saved);
    }

    /** Branch-scoped delete (docs/plan3.md Task 8): 404-safe for any other branch's row. */
    public void delete(UUID id) {
        EmergencyVisit visit = visits.findByIdAndBranchId(id, currentContext().branchId())
                .orElseThrow(() -> new NotFoundException("EmergencyVisit not found: " + id));
        visits.delete(visit);
        audit.record("DELETE", "EmergencyVisit", id.toString(), "deleted");
    }

    private Branch actingBranch() {
        return branches.findById(currentContext().branchId())
                .orElseThrow(() -> new AccessDeniedException("The acting branch is not available"));
    }

    /**
     * Fail-closed seam: the JWT filter guarantees an {@link ActingContext}
     * principal with a verified assignment and an existing active selected
     * branch for every authorized request; anything else is refused, never
     * guessed.
     */
    private static ActingContext currentContext() {
        var a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof ActingContext c) {
            return c;
        }
        throw new AccessDeniedException("No acting context is available");
    }
}
