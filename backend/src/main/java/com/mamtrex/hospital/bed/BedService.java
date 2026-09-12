package com.mamtrex.hospital.bed;

import com.mamtrex.hospital.audit.AuditService;
import com.mamtrex.hospital.auth.ActingContext;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
import com.mamtrex.hospital.shared.InvalidStateTransitionException;
import com.mamtrex.hospital.shared.NotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Bed workflow rules (docs/plan3.md Task 6). Branch ownership derives from
 * the acting context, never client input; list/detail resolve only inside
 * the acting branch and a cross-branch id 404s like a nonexistent one.
 * Creation trims the three location fields, refuses a duplicate
 * (branch, ward, room, bedNumber) with the shared 409 — the entity's DB
 * unique constraint is the concurrency backstop for the pre-check — and
 * starts every bed AVAILABLE. The lifecycle mutation delegates to the
 * entity's legal-transition map (OCCUPIED is admission-owned for Task 7),
 * and deletion is refused while the bed is occupied. Create, transition,
 * and delete own exactly one existing-format audit event each; every failed
 * operation records nothing. Queries are read-only.
 */
@Service
@Transactional
public class BedService {

    private static final String DUPLICATE_MESSAGE =
            "Bed already exists in this branch with the same ward, room, and bed number";

    private final BedRepository beds;
    private final BranchRepository branches;
    private final AuditService audit;

    public BedService(BedRepository beds, BranchRepository branches, AuditService audit) {
        this.beds = beds;
        this.branches = branches;
        this.audit = audit;
    }

    public Bed create(String ward, String room, String bedNumber) {
        String trimmedWard = ward.trim();
        String trimmedRoom = room.trim();
        String trimmedBedNumber = bedNumber.trim();
        Branch branch = actingBranch();
        beds.findByBranchIdAndWardAndRoomAndBedNumber(
                        branch.getId(), trimmedWard, trimmedRoom, trimmedBedNumber)
                .ifPresent(existing -> {
                    throw new DuplicateKeyException(DUPLICATE_MESSAGE);
                });
        Bed saved = beds.save(new Bed(branch, trimmedWard, trimmedRoom, trimmedBedNumber));
        audit.record("CREATE", "Bed", saved.getId().toString(), "created");
        return saved;
    }

    @Transactional(readOnly = true)
    public Bed get(UUID id) {
        return beds.findByIdAndBranchId(id, actingBranchId())
                .orElseThrow(() -> new NotFoundException("Bed not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<Bed> list() {
        return beds.findByBranchId(actingBranchId());
    }

    public Bed transitionStatus(UUID id, String targetStatus) {
        Bed bed = get(id);
        bed.changeOperationalStatus(targetStatus);
        audit.record("UPDATE", "Bed", id.toString(), "status: " + targetStatus);
        return bed;
    }

    public void delete(UUID id) {
        Bed bed = get(id);
        if (bed.isOccupied()) {
            throw new InvalidStateTransitionException(
                    "Bed is OCCUPIED: it cannot be deleted while an admission holds it");
        }
        beds.delete(bed);
        audit.record("DELETE", "Bed", id.toString(), "deleted");
    }

    private UUID actingBranchId() {
        return actingBranch().getId();
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
