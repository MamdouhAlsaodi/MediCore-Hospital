package com.mamtrex.hospital.staff;

import com.mamtrex.hospital.audit.AuditService;
import com.mamtrex.hospital.auth.ActingContext;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
import com.mamtrex.hospital.shared.InvalidStateTransitionException;
import com.mamtrex.hospital.shared.NotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Availability workflow rules (docs/plan3.md Task 9, §4.6). An interval is
 * created for one verified same-branch professional with the owning branch
 * derived from the acting context (never client input); a cross-branch or
 * unknown professional answers the shared 404. For the same professional,
 * overlapping intervals are the shared 409 while exactly-adjacent half-open
 * intervals are allowed.
 *
 * <p>The overlap check is transactionally defended: the professional row is
 * locked pessimistically before the check, so two concurrent creates for
 * the same professional serialize instead of racing the pre-check — a
 * bounded JPA/H2 defense, never a distributed-locking or
 * production-capacity claim (§8.7). Every successful create records exactly
 * one existing audit event; failed 400/403/404/409 operations record none
 * and persist nothing. No recurring calendar, leave, payroll, credentialing,
 * or profession-based clinical-eligibility concept exists here.</p>
 */
@Service
@Transactional
public class StaffAvailabilityService {

    private final StaffAvailabilityRepository availability;
    private final StaffMemberRepository professionals;
    private final BranchRepository branches;
    private final AuditService audit;
    private final EntityManager entityManager;

    public StaffAvailabilityService(StaffAvailabilityRepository availability,
                                    StaffMemberRepository professionals,
                                    BranchRepository branches,
                                    AuditService audit,
                                    EntityManager entityManager) {
        this.availability = availability;
        this.professionals = professionals;
        this.branches = branches;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    public StaffAvailabilityDtos.AvailabilityResponse create(UUID staffMemberId,
                                                             StaffAvailabilityDtos.CreateAvailabilityRequest r) {
        Branch branch = actingBranch();
        StaffMember professional = professionals.findByIdAndBranchId(staffMemberId, branch.getId())
                .orElseThrow(() -> new NotFoundException("Professional not found: " + staffMemberId));
        try {
            entityManager.lock(professional, LockModeType.PESSIMISTIC_WRITE);
        } catch (PessimisticLockingFailureException lostRace) {
            throw new InvalidStateTransitionException(
                    "Conflict: this professional's availability is being changed by another request");
        }
        if (availability.countOverlapping(branch.getId(), staffMemberId, r.startsAt(), r.endsAt()) > 0) {
            throw new InvalidStateTransitionException(
                    "Conflict: the availability interval overlaps an existing interval for this professional");
        }
        StaffAvailability saved = availability.save(
                new StaffAvailability(branch.getId(), staffMemberId, r.startsAt(), r.endsAt()));
        audit.record("CREATE", "StaffAvailability", saved.getId().toString(), "created");
        return StaffAvailabilityDtos.AvailabilityResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<StaffAvailabilityDtos.AvailabilityResponse> window(UUID staffMemberId,
                                                                   LocalDateTime from, LocalDateTime to) {
        Branch branch = actingBranch();
        professionals.findByIdAndBranchId(staffMemberId, branch.getId())
                .orElseThrow(() -> new NotFoundException("Professional not found: " + staffMemberId));
        return availability.findWindow(branch.getId(), staffMemberId, from, to).stream()
                .map(StaffAvailabilityDtos.AvailabilityResponse::from)
                .toList();
    }

    private Branch actingBranch() {
        return branches.findById(actingContext().branchId())
                .orElseThrow(() -> new AccessDeniedException("The acting branch is not available"));
    }

    /**
     * Fail-closed seam: the JWT filter guarantees an {@link ActingContext}
     * principal and an existing active selected branch for every authorized
     * request; anything else is refused, never guessed.
     */
    private static ActingContext actingContext() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof ActingContext context) {
            return context;
        }
        throw new AccessDeniedException("No acting context is available");
    }
}
