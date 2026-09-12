package com.mamtrex.hospital.admission;

import com.mamtrex.hospital.audit.AuditService;
import com.mamtrex.hospital.auth.ActingContext;
import com.mamtrex.hospital.bed.Bed;
import com.mamtrex.hospital.bed.BedRepository;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
import com.mamtrex.hospital.patient.Patient;
import com.mamtrex.hospital.patient.PatientRepository;
import com.mamtrex.hospital.shared.BaseEntity;
import com.mamtrex.hospital.shared.InvalidStateTransitionException;
import com.mamtrex.hospital.shared.NotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Admission workflow rules (docs/plan2.md Task 2, extended by docs/plan3.md
 * Tasks 7 and 7A). Creation resolves the patient reference through the
 * patient repository and requires patient — and the optional bed — to live
 * inside the acting branch; a hidden or cross-branch reference answers the
 * same generic 404 as a nonexistent one. Only an AVAILABLE bed may be
 * occupied, and occupancy is a transactional consequence of the admission
 * lifecycle: one {@link AdmissionBedAssignment} row exists exactly while an
 * admission holds a bed (inserted at the first assignment, its bed reference
 * updated in place by a transfer, deleted on release), so the row's two DB
 * unique constraints enforce at most one active assignment per admission and
 * per bed, and every mutation of admission, assignment, and bed rows commits
 * or rolls back as one transaction.
 *
 * <p>Branch isolation (docs/plan3.md Task 7A): every new admission is owned
 * by the authenticated acting branch — ownership is server-stamped, never
 * client input — and every admission read and command resolves its row
 * through the branch-scoped repository methods, so a cross-branch id and a
 * legacy null-ownership row answer the same generic 404 as a nonexistent
 * one, with no mutation and no audit event. No branch-scoped path ever
 * touches whole-table {@code findAll()}, unscoped {@code findById()}, or
 * {@code existsById()}.</p>
 *
 * <p>The server owns the lifecycle: a new admission is ADMITTED with
 * dischargedAt unset, and only the server stamps the discharge time —
 * through the narrow {@link Admission#dischargeAt(String)} mutation and
 * ordinary dirty checking (no bulk JPQL, no EntityManager). Discharge is
 * legal only from ADMITTED; repeating it, repeating a bed assignment,
 * targeting an unavailable bed, or commanding any other transition is an
 * {@link InvalidStateTransitionException} (shared 409). Every successful
 * admission command records exactly one existing-format audit event; every
 * failure rolls back fully and records nothing. Queries are read-only.</p>
 */
@Service
@Transactional
public class AdmissionService {

    /** The only lifecycle source state the admission transition map admits. */
    static final String STATUS_ADMITTED = Admission.STATUS_ADMITTED;

    /** Terminal lifecycle state; the discharge time is stamped by the server. */
    static final String STATUS_DISCHARGED = Admission.STATUS_DISCHARGED;

    private final AdmissionRepository admissions;
    private final PatientRepository patients;
    private final BedRepository beds;
    private final AdmissionBedAssignmentRepository assignments;
    private final BranchRepository branches;
    private final AuditService audit;

    public AdmissionService(AdmissionRepository admissions, PatientRepository patients,
                            BedRepository beds, AdmissionBedAssignmentRepository assignments,
                            BranchRepository branches, AuditService audit) {
        this.admissions = admissions;
        this.patients = patients;
        this.beds = beds;
        this.assignments = assignments;
        this.branches = branches;
        this.audit = audit;
    }

    public AdmissionDtos.AdmissionResponse create(AdmissionDtos.CreateAdmissionRequest r) {
        Branch acting = actingBranch();
        Patient patient = patients.findById(r.patientId())
                .orElseThrow(() -> new NotFoundException("Patient not found: " + r.patientId()));
        requireActingBranch(patient.getBranch(), "Patient not found: " + r.patientId());
        Bed bed = null;
        if (r.bedId() != null) {
            bed = availableBedInBranch(r.bedId(), acting.getId());
        }
        // Ownership is server-stamped from the acting branch; the create
        // request carries no branch field, so client input can never choose it.
        Admission saved = admissions.save(new Admission(acting.getId(), patient.getId().toString(),
                r.admittedAt().toString(), r.reason().trim()));
        if (bed != null) {
            occupyAndAssign(saved.getId(), bed, null);
        }
        audit.record("CREATE", "Admission", saved.getId().toString(), "created");
        return AdmissionDtos.AdmissionResponse.from(saved, saved.getBranchId(),
                bed == null ? null : AdmissionDtos.CurrentBed.from(bed));
    }

    /**
     * Initial bed assignment or atomic transfer (docs/plan3.md Task 7). The
     * admission and the target bed must both resolve inside the acting
     * branch (otherwise the generic 404), the admission must be ADMITTED,
     * and the target must be AVAILABLE and different from the held bed.
     * Transfer releases the source bed and occupies the target inside this
     * one transaction; the assignment row's unique constraints plus the bed
     * rows' optimistic locks backstop every race as the shared 409.
     */
    public AdmissionDtos.AdmissionResponse assignBed(UUID id, AdmissionDtos.AssignBedRequest r) {
        Branch acting = actingBranch();
        Admission admission = admissions.findByIdAndBranchId(id, acting.getId())
                .orElseThrow(() -> new NotFoundException("Admission not found: " + id));
        if (!STATUS_ADMITTED.equals(admission.getStatus())) {
            throw new InvalidStateTransitionException(
                    "Admission " + id + " cannot hold a bed: it is " + admission.getStatus());
        }
        Bed target = availableBedInBranch(r.bedId(), acting.getId());
        AdmissionBedAssignment current = assignments.findByAdmissionId(id).orElse(null);
        if (current != null && current.getBedId().equals(target.getId())) {
            throw new InvalidStateTransitionException(
                    "Admission " + id + " already holds bed " + target.getId());
        }
        if (current != null) {
            // Atomic transfer, same transaction: the source bed is released
            // before the target is occupied, and any later failure rolls both back.
            beds.findById(current.getBedId()).ifPresent(Bed::releaseByAdmission);
        }
        occupyAndAssign(id, target, current);
        audit.record("UPDATE", "Admission", id.toString(), "bed: " + target.getId());
        return AdmissionDtos.AdmissionResponse.from(admission, admission.getBranchId(),
                AdmissionDtos.CurrentBed.from(target));
    }

    /** Branch-scoped list (docs/plan3.md Task 7A): only the acting branch's own admissions. */
    @Transactional(readOnly = true)
    public List<AdmissionDtos.AdmissionResponse> list() {
        return responsesOf(admissions.findByBranchId(currentContext().branchId()));
    }

    /** Branch-scoped detail (docs/plan3.md Task 7A): the generic 404 for any other branch's row. */
    @Transactional(readOnly = true)
    public AdmissionDtos.AdmissionResponse get(UUID id) {
        return admissions.findByIdAndBranchId(id, currentContext().branchId())
                .map(this::responseOf)
                .orElseThrow(() -> new NotFoundException("Admission not found: " + id));
    }

    /**
     * Server-stamped discharge of an ADMITTED admission inside the acting
     * branch. The admission is resolved through the branch-scoped lookup,
     * and the state change applies through the narrow
     * {@link Admission#dischargeAt(String)} lifecycle mutation with the
     * server-supplied time — ordinary dirty checking flushes it in the
     * caller's transaction, so no bulk JPQL, EntityManager, flush/clear, or
     * re-read is involved. The same transaction closes the active
     * assignment and releases its bed, so occupancy ends exactly when the
     * admission ends.
     */
    public AdmissionDtos.AdmissionResponse discharge(UUID id, AdmissionDtos.UpdateAdmissionStatusRequest r) {
        Admission admission = admissions.findByIdAndBranchId(id, currentContext().branchId())
                .orElseThrow(() -> new NotFoundException("Admission not found: " + id));
        if (!STATUS_DISCHARGED.equals(r.status())) {
            throw new InvalidStateTransitionException(
                    "Admission " + id + " has no " + r.status() + " transition: only DISCHARGED is defined");
        }
        admission.dischargeAt(LocalDateTime.now().toString());
        releaseAssignment(id);
        audit.record("UPDATE", "Admission", id.toString(), "status: " + STATUS_DISCHARGED);
        return responseOf(admission);
    }

    /**
     * Branch-scoped deletion (docs/plan3.md Task 7A) that also releases an
     * active assignment, mirroring the discharge transaction.
     */
    public void delete(UUID id) {
        if (!admissions.existsByIdAndBranchId(id, currentContext().branchId())) {
            throw new NotFoundException("Admission not found: " + id);
        }
        releaseAssignment(id);
        admissions.deleteById(id);
        audit.record("DELETE", "Admission", id.toString(), "deleted");
    }

    /**
     * The single occupancy mutation: occupies the target bed and inserts or
     * moves the live assignment row, then flushes so any constraint or lock
     * loss surfaces before the audit write — a failure here leaves no
     * assignment, an occupied bed, or an audit event behind.
     */
    private void occupyAndAssign(UUID admissionId, Bed target, AdmissionBedAssignment current) {
        target.markOccupiedByAdmission();
        if (current == null) {
            assignments.saveAndFlush(new AdmissionBedAssignment(admissionId, target.getId()));
        } else {
            current.moveBed(target.getId());
            assignments.saveAndFlush(current);
        }
    }

    /** Closes the live assignment and releases its bed inside the caller's transaction. */
    private void releaseAssignment(UUID admissionId) {
        assignments.findByAdmissionId(admissionId).ifPresent(assignment -> {
            beds.findById(assignment.getBedId()).ifPresent(Bed::releaseByAdmission);
            assignments.delete(assignment);
        });
    }

    /** Resolves the target bed inside one branch and refuses every non-AVAILABLE state. */
    private Bed availableBedInBranch(UUID bedId, UUID branchId) {
        Bed bed = beds.findByIdAndBranchId(bedId, branchId)
                .orElseThrow(() -> new NotFoundException("Bed not found: " + bedId));
        if (!Bed.STATUS_AVAILABLE.equals(bed.getOccupancyStatus())) {
            throw new InvalidStateTransitionException(
                    "Bed " + bedId + " is not AVAILABLE: it is " + bed.getOccupancyStatus());
        }
        return bed;
    }

    /** Hidden and cross-branch references share the generic safe 404 (docs/plan3.md Task 7). */
    private void requireActingBranch(Branch owned, String safeNotFoundMessage) {
        if (owned == null || !owned.getId().equals(currentContext().branchId())) {
            throw new NotFoundException(safeNotFoundMessage);
        }
    }

    /** One response: the row's own branch ownership plus the live assignment's bed summary. */
    private AdmissionDtos.AdmissionResponse responseOf(Admission admission) {
        return responsesOf(List.of(admission)).get(0);
    }

    /** Batch assembly for list/detail: one assignment/bed sweep over branch-scoped rows. */
    private List<AdmissionDtos.AdmissionResponse> responsesOf(List<Admission> rows) {
        Map<UUID, AdmissionBedAssignment> assignmentByAdmission = assignments.findByAdmissionIdIn(
                        rows.stream().map(BaseEntity::getId).toList()).stream()
                .collect(Collectors.toMap(AdmissionBedAssignment::getAdmissionId, Function.identity()));
        Map<UUID, Bed> bedById = beds.findAllById(
                        assignmentByAdmission.values().stream().map(AdmissionBedAssignment::getBedId).toList())
                .stream().collect(Collectors.toMap(BaseEntity::getId, Function.identity()));
        List<AdmissionDtos.AdmissionResponse> responses = new ArrayList<>(rows.size());
        for (Admission admission : rows) {
            AdmissionBedAssignment assignment = assignmentByAdmission.get(admission.getId());
            Bed bed = assignment == null ? null : bedById.get(assignment.getBedId());
            responses.add(AdmissionDtos.AdmissionResponse.from(admission, admission.getBranchId(),
                    bed == null ? null : AdmissionDtos.CurrentBed.from(bed)));
        }
        return responses;
    }

    private Branch actingBranch() {
        return branches.findById(currentContext().branchId())
                .orElseThrow(() -> new AccessDeniedException("The acting branch is not available"));
    }

    /* Fail-closed seam: the JWT filter guarantees an ActingContext principal
     * and an existing active selected branch for every authorized request;
     * anything else is refused, never guessed. */
    private static ActingContext currentContext() {
        var a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof ActingContext c) {
            return c;
        }
        throw new AccessDeniedException("No acting context is available");
    }
}
