package com.mamtrex.hospital.appointment;

import com.mamtrex.hospital.audit.AuditService;
import com.mamtrex.hospital.auth.ActingContext;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Appointment workflow rules (docs/plan1.md Task 4, docs/plan3.md Tasks 4
 * and 9). Creation derives the owning branch from the acting context (never
 * client input), then resolves both references scoped to that branch — a
 * cross-branch reference answers the same 404 as a nonexistent one, so no
 * existence information leaks across branches.
 *
 * <p>Since Task 9 every create additionally computes the half-open window
 * {@code [scheduledAt, scheduledAt + durationMinutes)} and enforces the §4.6
 * conflict contract inside one transaction: the containing availability
 * interval for that professional is taken under a pessimistic row lock (the
 * database-backed serialization grain — a concurrent same-time create for
 * the same interval blocks here until the first transaction commits, so its
 * overlap check runs against committed state), an empty containment answer
 * and any overlapping non-cancelled appointment are the shared 409, and only
 * then is the row persisted and audited once. A lost lock race surfaces as
 * the same 409 rather than a server error. This is a bounded JPA/H2 defense,
 * never a distributed-locking or production-capacity claim (§8.7). Every
 * failure path above persists nothing and records no domain-success event;
 * {@code durationMinutes} stays a bounded engineering validation range
 * (5-480) for the synthetic demo, never clinical policy.</p>
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

        LocalDateTime start = r.scheduledAt();
        LocalDateTime end = start.plusMinutes(r.durationMinutes());

        List<StaffAvailability> containing;
        try {
            containing = availability.lockContainingIntervals(
                    branch.getId(), professional.getId(), start, end);
        } catch (PessimisticLockingFailureException lostRace) {
            throw new InvalidStateTransitionException(
                    "Scheduling conflict: another request is booking this availability right now");
        }
        if (containing.isEmpty()) {
            throw new InvalidStateTransitionException(
                    "Scheduling conflict: the appointment time is outside the professional's modeled availability");
        }
        // Overlap is defined between two non-cancelled appointments
        // (docs/plan3.md §4.6): a create already carrying the explicit
        // cancelled status does not take or create a time claim, and the
        // query itself excludes persisted cancelled rows.
        if (!"cancelled".equals(r.status())
                && appointments.countConflicting(branch.getId(), professional.getId().toString(),
                        start.toString(), end.toString()) > 0) {
            throw new InvalidStateTransitionException(
                    "Scheduling conflict: the appointment overlaps an existing appointment for this professional");
        }

        Appointment saved = appointments.save(new Appointment(branch, patient.getId().toString(),
                professional.getId().toString(), start.toString(), r.durationMinutes(), end.toString(),
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
