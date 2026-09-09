package com.mamtrex.hospital.appointment;

import com.mamtrex.hospital.audit.AuditService;
import com.mamtrex.hospital.patient.Patient;
import com.mamtrex.hospital.patient.PatientRepository;
import com.mamtrex.hospital.shared.NotFoundException;
import com.mamtrex.hospital.staff.StaffMember;
import com.mamtrex.hospital.staff.StaffMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Appointment workflow rules (docs/plan1.md Task 4): creation resolves both
 * references through their repositories and persists only after both exist,
 * persisting the validated UUIDs as canonical strings because the legacy
 * appointments columns remain String-typed (no destructive column migration
 * in this task). Create/delete own the corresponding audit events; queries
 * are read-only. StaffMember currently carries no field representing
 * availability or eligibility, so professional eligibility checks are
 * honestly deferred, not invented.
 */
@Service
@Transactional
public class AppointmentService {

    private final AppointmentRepository appointments;
    private final PatientRepository patients;
    private final StaffMemberRepository professionals;
    private final AuditService audit;

    public AppointmentService(AppointmentRepository appointments, PatientRepository patients,
                              StaffMemberRepository professionals, AuditService audit) {
        this.appointments = appointments;
        this.patients = patients;
        this.professionals = professionals;
        this.audit = audit;
    }

    public AppointmentDtos.AppointmentResponse create(AppointmentDtos.CreateAppointmentRequest r) {
        Patient patient = patients.findById(r.patientId())
                .orElseThrow(() -> new NotFoundException("Patient not found: " + r.patientId()));
        StaffMember professional = professionals.findById(r.professionalId())
                .orElseThrow(() -> new NotFoundException("Professional not found: " + r.professionalId()));
        Appointment saved = appointments.save(new Appointment(patient.getId().toString(),
                professional.getId().toString(), r.scheduledAt().toString(), r.type(), r.status()));
        audit.record("CREATE", "Appointment", saved.getId().toString(), "created");
        return AppointmentDtos.AppointmentResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<AppointmentDtos.AppointmentResponse> list() {
        return appointments.findAll().stream().map(AppointmentDtos.AppointmentResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public AppointmentDtos.AppointmentResponse get(UUID id) {
        return appointments.findById(id).map(AppointmentDtos.AppointmentResponse::from)
                .orElseThrow(() -> new NotFoundException("Appointment not found: " + id));
    }

    public void delete(UUID id) {
        if (!appointments.existsById(id)) {
            throw new NotFoundException("Appointment not found: " + id);
        }
        appointments.deleteById(id);
        audit.record("DELETE", "Appointment", id.toString(), "deleted");
    }
}
