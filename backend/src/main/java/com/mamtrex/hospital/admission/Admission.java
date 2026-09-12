package com.mamtrex.hospital.admission;

import com.mamtrex.hospital.shared.BaseEntity;
import com.mamtrex.hospital.shared.InvalidStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A raw admission row (docs/plan2.md Task 2, extended by docs/plan3.md
 * Tasks 7 and 7A). Reference columns follow the app-owned UUID precedent
 * shared with {@link AdmissionBedAssignment}: resolved through
 * repositories, never JPA relations, so no entity graph can leak outward.
 * Timestamps stay canonical ISO strings, mirroring the appointment
 * precedent (no destructive column migration).
 *
 * <p>Branch ownership (docs/plan3.md Task 7A) is stamped by the server from
 * the authenticated acting branch at creation and is never client input;
 * the column is nullable as the deliberate transitional seam for
 * pre-Task-7A rows, which stay invisible and untouchable through every
 * branch-scoped admission read and command.</p>
 *
 * <p>The lifecycle is server-owned: a new admission is ADMITTED with
 * dischargedAt unset, and {@link #dischargeAt(String)} is the single
 * lifecycle mutation — it permits only ADMITTED -> DISCHARGED and stamps
 * the server time supplied by the service, so ordinary dirty checking (not
 * bulk JPQL or an EntityManager) persists the discharge inside the
 * caller's transaction.</p>
 */
@Entity
@Table(name = "admissions")
public class Admission extends BaseEntity {

    /** The only lifecycle source state {@link #dischargeAt(String)} admits. */
    public static final String STATUS_ADMITTED = "ADMITTED";

    /** Terminal lifecycle state; the discharge time is stamped by the server. */
    public static final String STATUS_DISCHARGED = "DISCHARGED";

    private String patientId;

    private String admittedAt;

    private String dischargedAt;

    private String reason;

    private String status;

    /**
     * Nullable transitional ownership (docs/plan3.md Task 7A): set by the
     * server from the acting context at creation, never client input; null
     * only on legacy pre-Task-7A rows, which no branch-scoped endpoint
     * discloses or mutates.
     */
    @Column
    private UUID branchId;

    protected Admission() {}

    /**
     * A new admission is owned by the acting branch (server-stamped, never
     * client input), starts ADMITTED, and carries no discharge time.
     */
    public Admission(UUID branchId, String patientId, String admittedAt, String reason) {
        this.branchId = branchId;
        this.patientId = patientId;
        this.admittedAt = admittedAt;
        this.reason = reason;
        this.status = STATUS_ADMITTED;
    }

    /** Legacy constructor for pre-Task-7A rows: no ownership, hidden from branch-scoped reads. */
    public Admission(String patientId, String admittedAt, String dischargedAt, String reason, String status) {
        this.patientId = patientId;
        this.admittedAt = admittedAt;
        this.dischargedAt = dischargedAt;
        this.reason = reason;
        this.status = status;
    }

    public String getPatientId() {
        return patientId;
    }

    public String getAdmittedAt() {
        return admittedAt;
    }

    public String getDischargedAt() {
        return dischargedAt;
    }

    public String getReason() {
        return reason;
    }

    public String getStatus() {
        return status;
    }

    public UUID getBranchId() {
        return branchId;
    }

    /**
     * The single lifecycle mutation (docs/plan3.md Task 7A): only
     * ADMITTED -> DISCHARGED is legal, the discharge time is the server
     * time supplied by the service (never client input), and any other
     * source state — including a repeated discharge — is refused. A refused
     * mutation leaves the row untouched.
     */
    public void dischargeAt(String serverDischargeTime) {
        if (!STATUS_ADMITTED.equals(status)) {
            throw new InvalidStateTransitionException(
                    "Admission " + getId() + " cannot be discharged: it is already " + status);
        }
        if (serverDischargeTime == null || serverDischargeTime.isBlank()) {
            throw new InvalidStateTransitionException("Discharge requires the server-stamped time");
        }
        this.status = STATUS_DISCHARGED;
        this.dischargedAt = serverDischargeTime;
    }
}
