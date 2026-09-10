package com.mamtrex.hospital.admission;

import com.mamtrex.hospital.audit.AuditService;
import com.mamtrex.hospital.patient.Patient;
import com.mamtrex.hospital.patient.PatientRepository;
import com.mamtrex.hospital.shared.InvalidStateTransitionException;
import com.mamtrex.hospital.shared.NotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Admission workflow rules (docs/plan2.md Task 2): creation resolves the
 * patient reference through the patient repository and persists only when it
 * exists, storing the validated UUID and the typed admittedAt as canonical
 * strings because the legacy admissions columns remain String-typed (no
 * destructive column migration). The server owns the lifecycle: a new
 * admission is ADMITTED with dischargedAt unset, and only the server stamps
 * the discharge time. Discharge is legal only from ADMITTED; repeating it or
 * requesting any other target is an {@link InvalidStateTransitionException}
 * (shared 409). Create, discharge, and delete own exactly one audit event
 * each; failed operations record nothing. Queries are read-only.
 */
@Service
@Transactional
public class AdmissionService {

    /** The only lifecycle source state the admission transition map admits. */
    static final String STATUS_ADMITTED = "ADMITTED";

    /** Terminal lifecycle state; the discharge time is stamped by the server. */
    static final String STATUS_DISCHARGED = "DISCHARGED";

    private final AdmissionRepository admissions;
    private final PatientRepository patients;
    private final AuditService audit;

    /**
     * JPA-standard injection: the shared EntityManager is not a plain bean
     * and backs the server-stamped discharge below (the Admission entity is
     * immutable by design and outside this task's allowed paths).
     */
    @PersistenceContext
    private EntityManager entityManager;

    public AdmissionService(AdmissionRepository admissions, PatientRepository patients, AuditService audit) {
        this.admissions = admissions;
        this.patients = patients;
        this.audit = audit;
    }

    public AdmissionDtos.AdmissionResponse create(AdmissionDtos.CreateAdmissionRequest r) {
        Patient patient = patients.findById(r.patientId())
                .orElseThrow(() -> new NotFoundException("Patient not found: " + r.patientId()));
        Admission saved = admissions.save(new Admission(patient.getId().toString(),
                r.admittedAt().toString(), null, r.reason().trim(), STATUS_ADMITTED));
        audit.record("CREATE", "Admission", saved.getId().toString(), "created");
        return AdmissionDtos.AdmissionResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<AdmissionDtos.AdmissionResponse> list() {
        return admissions.findAll().stream().map(AdmissionDtos.AdmissionResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public AdmissionDtos.AdmissionResponse get(UUID id) {
        return admissions.findById(id).map(AdmissionDtos.AdmissionResponse::from)
                .orElseThrow(() -> new NotFoundException("Admission not found: " + id));
    }

    /**
     * Server-stamped discharge of an ADMITTED admission. The Admission
     * entity deliberately exposes no mutation methods and is outside this
     * task's allowed paths, so the state change applies as one parameterized
     * bulk update; the persistence context is flushed and cleared and the
     * row re-read so the response reports the true persisted state (bulk
     * updates bypass dirty checking and leave stale snapshots behind).
     */
    public AdmissionDtos.AdmissionResponse discharge(UUID id, AdmissionDtos.UpdateAdmissionStatusRequest r) {
        Admission admission = admissions.findById(id)
                .orElseThrow(() -> new NotFoundException("Admission not found: " + id));
        if (!STATUS_DISCHARGED.equals(r.status())) {
            throw new InvalidStateTransitionException(
                    "Admission " + id + " has no " + r.status() + " transition: only DISCHARGED is defined");
        }
        if (!STATUS_ADMITTED.equals(admission.getStatus())) {
            throw new InvalidStateTransitionException(
                    "Admission " + id + " cannot be discharged: it is already " + admission.getStatus());
        }
        entityManager.createQuery(
                        "update Admission a set a.status = :status, a.dischargedAt = :dischargedAt where a.id = :id")
                .setParameter("status", STATUS_DISCHARGED)
                .setParameter("dischargedAt", LocalDateTime.now().toString())
                .setParameter("id", id)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
        Admission discharged = admissions.findById(id)
                .orElseThrow(() -> new NotFoundException("Admission not found: " + id));
        audit.record("UPDATE", "Admission", id.toString(), "discharged");
        return AdmissionDtos.AdmissionResponse.from(discharged);
    }

    public void delete(UUID id) {
        if (!admissions.existsById(id)) {
            throw new NotFoundException("Admission not found: " + id);
        }
        admissions.deleteById(id);
        audit.record("DELETE", "Admission", id.toString(), "deleted");
    }
}
