package com.mamtrex.hospital.patient;

import com.mamtrex.hospital.organization.HospitalFacility;
import com.mamtrex.hospital.organization.HospitalOrganization;
import com.mamtrex.hospital.shared.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One hospital's access grant to one network patient (Phase 5 US3;
 * specs/005 data-model.md, T066). Patient existence alone never grants
 * visibility: a hospital discovers a patient only while it holds an
 * {@link PatientAccessStatus#ACTIVE} grant for it, and every grant is
 * pinned to one organization — the grant's patient and hospital must both
 * belong to the row's organization, enforced by the V6 composite foreign
 * keys ({@code fk_access_patient_organization},
 * {@code fk_access_hospital_organization}) as the concurrency backstop
 * behind the service checks. At most one ACTIVE grant exists per
 * (patient, hospital); the partial unique index
 * {@code uq_patient_hospital_access_active} keeps REVOKED history legal.
 */
@Entity
@Table(name = "patient_hospital_access")
public class PatientHospitalAccess extends BaseEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false)
    private HospitalFacility hospital;

    /** The network owner; both the patient and the hospital belong to it (FK-pinned). */
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private HospitalOrganization organization;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PatientAccessStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PatientAccessSource source;

    /** The transfer that produced a TRANSFER_ACCEPTED grant; null otherwise. */
    @Column(name = "source_transfer_id")
    private UUID sourceTransferId;

    /** Set exactly when the grant is revoked; never on an ACTIVE row (check-pinned). */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected PatientHospitalAccess() {
    }

    /**
     * A new ACTIVE grant. The organization is derived from the patient and
     * validated against the hospital's own — no divergent caller-supplied
     * organization exists.
     */
    public PatientHospitalAccess(Patient patient, HospitalFacility hospital, PatientAccessSource source,
                                 UUID sourceTransferId) {
        if (!patient.getOrganization().getId().equals(hospital.getOrganization().getId())) {
            throw new IllegalArgumentException("A hospital access grant must stay inside one organization");
        }
        this.patient = Objects.requireNonNull(patient, "patient");
        this.hospital = Objects.requireNonNull(hospital, "hospital");
        this.organization = patient.getOrganization();
        this.status = PatientAccessStatus.ACTIVE;
        this.source = Objects.requireNonNull(source, "source");
        this.sourceTransferId = sourceTransferId;
        this.revokedAt = null;
    }

    public Patient getPatient() {
        return patient;
    }

    public HospitalFacility getHospital() {
        return hospital;
    }

    public HospitalOrganization getOrganization() {
        return organization;
    }

    public PatientAccessStatus getStatus() {
        return status;
    }

    public PatientAccessSource getSource() {
        return source;
    }

    public UUID getSourceTransferId() {
        return sourceTransferId;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    /** Terminal transition (lifecycle seam): ACTIVE -> REVOKED, stamped once. */
    public void revoke() {
        if (status != PatientAccessStatus.ACTIVE) {
            throw new IllegalStateException("Only an ACTIVE grant can be revoked");
        }
        this.status = PatientAccessStatus.REVOKED;
        this.revokedAt = Instant.now();
    }
}
