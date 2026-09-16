package com.mamtrex.hospital.appointment;

import com.mamtrex.hospital.audit.AuditService;
import com.mamtrex.hospital.auth.ActingContext;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
import com.mamtrex.hospital.organization.BranchTimeService;
import com.mamtrex.hospital.patient.Patient;
import com.mamtrex.hospital.patient.PatientRepository;
import com.mamtrex.hospital.shared.InvalidStateTransitionException;
import com.mamtrex.hospital.shared.NotFoundException;
import com.mamtrex.hospital.staff.StaffAvailability;
import com.mamtrex.hospital.staff.StaffAvailabilityRepository;
import com.mamtrex.hospital.staff.StaffMember;
import com.mamtrex.hospital.staff.StaffMemberRepository;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Appointment workflow rules (docs/plan1.md Task 4, docs/plan3.md Tasks 4
 * and 9; typed storage added by Phase 4 Task 4). Creation derives the
 * owning branch from the acting context (never client input), then resolves
 * both references scoped to that branch — a cross-branch reference answers
 * the same 404 as a nonexistent one, so no existence information leaks
 * across branches.
 *
 * <p>Phase 4 time semantics (FR-012/FR-015): the client's branch-local
 * {@code scheduledAt} is converted through the acting branch's IANA zone
 * into the stored unambiguous instants {@code [scheduledAt, endsAt)};
 * nonexistent DST-gap times are the shared 400 and ambiguous overlaps
 * resolve to the documented earlier offset. The availability containment
 * check keeps comparing the professional's branch-local wall clock (the
 * modeled availability is branch-local), while the appointment conflict
 * check compares exact half-open instants. Every failure path below
 * persists nothing and records no domain-success event; a lost lock race
 * surfaces as the same 409. {@code durationMinutes} stays a bounded
 * engineering validation range (5-480) for the synthetic demo, never
 * clinical policy.</p>
 */
@Service
@Transactional
public class AppointmentService {

    private final AppointmentRepository appointments;
    private final PatientRepository patients;
    private final StaffMemberRepository professionals;
    private final StaffAvailabilityRepository availability;
    private final BranchRepository branches;
    private final AuditService audit;

    public AppointmentService(AppointmentRepository appointments, PatientRepository patients,
                              StaffMemberRepository professionals, StaffAvailabilityRepository availability,
                              BranchRepository branches, AuditService audit) {
        this.appointments = appointments;
        this.patients = patients;
        this.professionals = professionals;
        this.availability = availability;
        this.branches = branches;
        this.audit = audit;
    }

    public AppointmentDtos.AppointmentResponse create(AppointmentDtos.CreateAppointmentRequest r) {
        Branch branch = actingBranch();
        Patient patient = patients.findByIdAndBranchId(r.patientId(), branch.getId())
                .orElseThrow(() -> new NotFoundException("Patient not found: " + r.patientId()));
        StaffMember professional = professionals.findByIdAndBranchId(r.professionalId(), branch.getId())
                .orElseThrow(() -> new NotFoundException("Professional not found: " + r.professionalId()));

        // Branch-local input -> unambiguous instants (DST gap = shared 400).
        LocalDateTime startLocal = r.scheduledAt();
        Instant start = BranchTimeService.toInstant(branch, startLocal);
        Instant end = start.plus(Duration.ofMinutes(r.durationMinutes()));
        LocalDateTime endLocal = startLocal.plusMinutes(r.durationMinutes());

        List<StaffAvailability> containing;
        try {
            // The modeled availability is branch-local wall-clock time.
            containing = availability.lockContainingIntervals(
                    branch.getId(), professional.getId(), startLocal, endLocal);
        } catch (PessimisticLockingFailureException lostRace) {
            throw new InvalidStateTransitionException(
                    "Scheduling conflict: another request is booking this availability right now");
        }
        if (containing.isEmpty()) {
            throw new InvalidStateTransitionException(
                    "Scheduling conflict: the appointment time is outside the professional's modeled availability");
        }
        // Overlap is defined between two non-cancelled appointments
        // (docs/plan3.md §4.6), now as exact half-open instants: a create
        // already carrying the explicit cancelled status does not take or
        // create a time claim, and the query itself excludes persisted
        // cancelled rows.
        if (!"cancelled".equals(r.status())
                && appointments.countConflicting(branch.getId(), professional.getId().toString(),
                        start, end) > 0) {
            throw new InvalidStateTransitionException(
                    "Scheduling conflict: the appointment overlaps an existing appointment for this professional");
        }

        Appointment saved = appointments.save(new Appointment(branch, patient.getId().toString(),
                professional.getId().toString(), start, r.durationMinutes(), end,
                r.type(), r.status()));
        audit.record("CREATE", "Appointment", saved.getId().toString(), "created");
        return AppointmentDtos.AppointmentResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<AppointmentDtos.AppointmentResponse> list() {
        return appointments.findByBranchIdOrderByIdAsc(actingBranch().getId()).stream()
                .map(AppointmentDtos.AppointmentResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public AppointmentDtos.AppointmentResponse get(UUID id) {
        return appointments.findByIdAndBranchId(id, actingBranch().getId())
                .map(AppointmentDtos.AppointmentResponse::from)
                .orElseThrow(() -> new NotFoundException("Appointment not found: " + id));
    }

    public void delete(UUID id) {
        Appointment appointment = appointments.findByIdAndBranchId(id, actingBranch().getId())
                .orElseThrow(() -> new NotFoundException("Appointment not found: " + id));
        appointments.delete(appointment);
        audit.record("DELETE", "Appointment", id.toString(), "deleted");
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
