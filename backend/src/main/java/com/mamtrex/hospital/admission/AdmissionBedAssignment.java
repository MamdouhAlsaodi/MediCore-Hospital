package com.mamtrex.hospital.admission;

import com.mamtrex.hospital.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * The one live bed assignment of an admission (docs/plan3.md Task 7). Row
 * existence IS the active assignment: the row is inserted when an admission
 * first occupies a bed, its bed reference is the only mutation a transfer
 * makes, and the row is deleted when discharge (or admission deletion)
 * releases the bed. Because a released assignment leaves no row, the two
 * DB unique constraints below enforce exactly the Task 7 safeguard — at
 * most one active assignment per admission and at most one per bed —
 * through the existing schema-management path, with no migration resource
 * and no soft-delete state to keep consistent.
 *
 * <p>The reference columns follow the admission {@code patientId} precedent:
 * app-owned UUID references resolved through repositories, never JPA
 * relations, so no entity graph can leak outward. No HTTP route ever
 * exposes this entity; responses carry only the allowlisted current-bed
 * summary built by {@link AdmissionService}.</p>
 */
@Entity
@Table(name = "admission_bed_assignments", uniqueConstraints = {
        @UniqueConstraint(name = "uq_assignment_active_admission", columnNames = {"admissionId"}),
        @UniqueConstraint(name = "uq_assignment_active_bed", columnNames = {"bedId"})})
public class AdmissionBedAssignment extends BaseEntity {

    @Column(nullable = false)
    private UUID admissionId;

    @Column(nullable = false)
    private UUID bedId;

    protected AdmissionBedAssignment() {}

    public AdmissionBedAssignment(UUID admissionId, UUID bedId) {
        this.admissionId = admissionId;
        this.bedId = bedId;
    }

    public UUID getAdmissionId() {
        return admissionId;
    }

    public UUID getBedId() {
        return bedId;
    }

    /** The single transfer mutation; applied only inside the admission transaction. */
    void moveBed(UUID targetBedId) {
        this.bedId = targetBedId;
    }
}
