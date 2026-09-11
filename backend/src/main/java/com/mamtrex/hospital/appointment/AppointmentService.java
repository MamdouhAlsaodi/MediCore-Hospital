package com.mamtrex.hospital.appointment;

import com.mamtrex.hospital.audit.AuditService;
import com.mamtrex.hospital.auth.ActingContext;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
import com.mamtrex.hospital.patient.Patient;
import com.mamtrex.hospital.patient.PatientRepository;
import com.mamtrex.hospital.shared.NotFoundException;
import com.mamtrex.hospital.staff.StaffMember;
import com.mamtrex.hospital.staff.StaffMemberRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Appointment workflow rules (docs/plan1.md Task 4, docs/plan3.md Task 4).
 * Creation derives the owning branch from the acting context (never client
 * input), then resolves both references scoped to that branch — a
 * cross-branch reference answers the same 404 as a nonexistent one, so no
 * existence information leaks across branches — and persists only after
 * both resolve, storing the validated UUIDs as canonical strings because
 * the legacy appointments columns remain String-typed (no destructive
 * column migration in this task). Every list/detail/delete is
 * branch-scoped; failed writes record no audit event. StaffMember still
 * carries no field representing availability or eligibility, so
 * professional eligibility checks are honestly deferred, not invented.
 */
@Service
@Transactional
public class AppointmentService {

    private final AppointmentRepository appointments;
    private final PatientRepository patients;
    private final StaffMemberRepository professionals;
    private final BranchRepository branches;
    private final AuditService audit;

    public AppointmentService(AppointmentRepository appointments, PatientRepository patients,
                              StaffMemberRepository professionals, BranchRepository branches, AuditService audit) {
        this.appointments = appointments;
        this.patients = patients;
        this.professionals = professionals;
        this.branches = branches;
        this.audit = audit;
    }

    public AppointmentDtos.AppointmentResponse create(AppointmentDtos.CreateAppointmentRequest r) {
        Branch branch = actingBranch();
        Patient patient = patients.findByIdAndBranchId(r.patientId(), branch.getId())
                .orElseThrow(() -> new NotFoundException("Patient not found: " + r.patientId()));
        StaffMember professional = professionals.findByIdAndBranchId(r.professionalId(), branch.getId())
                .orElseThrow(() -> new NotFoundException("Professional not found: " + r.professionalId()));
        Appointment saved = appointments.save(new Appointment(branch, patient.getId().toString(),
                professional.getId().toString(), r.scheduledAt().toString(), r.type(), r.status()));
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
