package com.mamtrex.hospital.emergency;

import com.mamtrex.hospital.audit.AuditService;
import com.mamtrex.hospital.patient.Patient;
import com.mamtrex.hospital.patient.PatientRepository;
import com.mamtrex.hospital.shared.InvalidStateTransitionException;
import com.mamtrex.hospital.shared.NotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Emergency-visit workflow rules (docs/plan2.md Task 3): creation resolves
 * the patient reference through the patient repository and persists only
 * when it exists, storing the validated UUID and the typed arrivalAt as
 * canonical strings because the legacy emergency_visits columns remain
 * String-typed (no destructive column migration). The server owns the
 * lifecycle: a new visit is WAITING, and the only legal transitions are
 * WAITING -> IN_TREATMENT | CLOSED and IN_TREATMENT -> CLOSED, with CLOSED
 * terminal — repeating a transition, moving backward, or requesting any
 * target outside the map is an {@link InvalidStateTransitionException}
 * (shared 409). The triageLevel stays the neutral 1–5 demo label verified by
 * the DTO @Pattern — it has NO clinical meaning and is never a triage
 * protocol. Create, every transition, and delete own exactly one audit event
 * each; failed operations record nothing. Queries are read-only.
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
    private final AuditService audit;

    /**
     * JPA-standard injection: the shared EntityManager is not a plain bean
     * and backs the server-applied transitions below (the EmergencyVisit
     * entity is immutable by design and outside this task's allowed paths).
     */
    @PersistenceContext
    private EntityManager entityManager;

    public EmergencyVisitService(EmergencyVisitRepository visits, PatientRepository patients, AuditService audit) {
        this.visits = visits;
        this.patients = patients;
        this.audit = audit;
    }

    public EmergencyVisitDtos.EmergencyVisitResponse create(EmergencyVisitDtos.CreateEmergencyVisitRequest r) {
        Patient patient = patients.findById(r.patientId())
                .orElseThrow(() -> new NotFoundException("Patient not found: " + r.patientId()));
        EmergencyVisit saved = visits.save(new EmergencyVisit(patient.getId().toString(),
                r.arrivalAt().toString(), r.triageLevel(), r.chiefComplaint().trim(), STATUS_WAITING));
        audit.record("CREATE", "EmergencyVisit", saved.getId().toString(), "created");
        return EmergencyVisitDtos.EmergencyVisitResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<EmergencyVisitDtos.EmergencyVisitResponse> list() {
        return visits.findAll().stream().map(EmergencyVisitDtos.EmergencyVisitResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public EmergencyVisitDtos.EmergencyVisitResponse get(UUID id) {
        return visits.findById(id).map(EmergencyVisitDtos.EmergencyVisitResponse::from)
                .orElseThrow(() -> new NotFoundException("EmergencyVisit not found: " + id));
    }

    /**
     * Server-applied lifecycle transition. WAITING admits IN_TREATMENT and
     * CLOSED; IN_TREATMENT admits only CLOSED; CLOSED is terminal. The
     * EmergencyVisit entity deliberately exposes no mutation methods and is
     * outside this task's allowed paths, so the state change applies as one
     * parameterized bulk update; the persistence context is flushed and
     * cleared and the row re-read so the response reports the true persisted
     * state (bulk updates bypass dirty checking and leave stale snapshots
     * behind).
     */
    public EmergencyVisitDtos.EmergencyVisitResponse updateStatus(UUID id, EmergencyVisitDtos.UpdateEmergencyVisitStatusRequest r) {
        EmergencyVisit visit = visits.findById(id)
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
        entityManager.createQuery(
                        "update EmergencyVisit v set v.status = :status where v.id = :id")
                .setParameter("status", target)
                .setParameter("id", id)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
        EmergencyVisit updated = visits.findById(id)
                .orElseThrow(() -> new NotFoundException("EmergencyVisit not found: " + id));
        audit.record("UPDATE", "EmergencyVisit", id.toString(),
                STATUS_CLOSED.equals(target) ? "closed" : "in treatment");
        return EmergencyVisitDtos.EmergencyVisitResponse.from(updated);
    }

    public void delete(UUID id) {
        if (!visits.existsById(id)) {
            throw new NotFoundException("EmergencyVisit not found: " + id);
        }
        visits.deleteById(id);
        audit.record("DELETE", "EmergencyVisit", id.toString(), "deleted");
    }
}
