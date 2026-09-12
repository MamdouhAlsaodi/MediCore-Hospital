package com.mamtrex.hospital.bed;

import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.shared.BaseEntity;
import com.mamtrex.hospital.shared.InvalidStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.Map;
import java.util.Set;

/**
 * A branch-owned operational bed (docs/plan3.md Task 6). Ownership derives
 * from the acting context at creation and is never client input; legacy
 * pre-Task-6 rows keep a null branch and stay invisible through the
 * branch-scoped endpoints, mirroring the Department/Patient seam.
 *
 * <p>The operational lifecycle is server-owned: a new bed starts
 * {@link #STATUS_AVAILABLE} and clients may only move it between
 * {@code AVAILABLE}, {@code MAINTENANCE}, and {@code OUT_OF_SERVICE}.
 * {@link #STATUS_OCCUPIED} is admission-owned (docs/plan3.md Task 7): no
 * client transition may target it, and no client transition may leave it.
 * Every other persisted status (including arbitrary legacy free-form
 * strings) admits no client transition at all — fail closed. The strongest
 * uniqueness representable inside the allowed files is the entity-level
 * (branch, ward, room, bedNumber) unique constraint below: it rides the
 * existing schema-management path, needs no migration resource, and is the
 * concurrency backstop behind the service pre-check.</p>
 */
@Entity
@Table(name = "beds", uniqueConstraints = @UniqueConstraint(
        name = "uq_bed_branch_ward_room_number",
        columnNames = {"branch_id", "ward", "room", "bedNumber"}))
public class Bed extends BaseEntity {

    /** Default operational state set by the server alone at creation. */
    public static final String STATUS_AVAILABLE = "AVAILABLE";

    /** Client-manageable operational state: temporarily unusable. */
    public static final String STATUS_MAINTENANCE = "MAINTENANCE";

    /** Client-manageable operational state: removed from service. */
    public static final String STATUS_OUT_OF_SERVICE = "OUT_OF_SERVICE";

    /** Admission-owned state (docs/plan3.md Task 7): never client-writable. */
    public static final String STATUS_OCCUPIED = "OCCUPIED";

    /** The client-manageable transition targets; OCCUPIED is never among them. */
    private static final Set<String> CLIENT_TARGETS =
            Set.of(STATUS_AVAILABLE, STATUS_MAINTENANCE, STATUS_OUT_OF_SERVICE);

    /**
     * The legal client transition map between the three client-manageable
     * states. A bed in any other state (OCCUPIED or a legacy free-form
     * value) admits no client transition.
     */
    private static final Map<String, Set<String>> LEGAL_TRANSITIONS = Map.of(
            STATUS_AVAILABLE, Set.of(STATUS_MAINTENANCE, STATUS_OUT_OF_SERVICE),
            STATUS_MAINTENANCE, Set.of(STATUS_AVAILABLE, STATUS_OUT_OF_SERVICE),
            STATUS_OUT_OF_SERVICE, Set.of(STATUS_AVAILABLE, STATUS_MAINTENANCE));

    @Column(nullable = false)
    private String ward;

    @Column(nullable = false)
    private String room;

    @Column(nullable = false)
    private String bedNumber;

    @Column(nullable = false)
    private String occupancyStatus;

    /**
     * Legacy free-form reference from the pre-Task-6 raw contract. It is no
     * longer client-writable; OCCUPIED semantics become admission-owned in
     * docs/plan3.md Task 7, so this column is carried without a destructive
     * migration and is never exposed through the DTOs.
     */
    @Column
    private String patientId;

    @ManyToOne
    @JoinColumn(name = "branch_id")
    private Branch branch;

    protected Bed() {}

    /**
     * A new bed is owned by the acting branch and starts AVAILABLE; no
     * client input reaches status, patientId, or ownership.
     */
    public Bed(Branch branch, String ward, String room, String bedNumber) {
        this.branch = branch;
        this.ward = ward;
        this.room = room;
        this.bedNumber = bedNumber;
        this.occupancyStatus = STATUS_AVAILABLE;
    }

    public String getWard() {
        return ward;
    }

    public String getRoom() {
        return room;
    }

    public String getBedNumber() {
        return bedNumber;
    }

    public String getOccupancyStatus() {
        return occupancyStatus;
    }

    public Branch getBranch() {
        return branch;
    }

    /** True only when the bed is admission-occupied (docs/plan3.md Task 7 semantics). */
    public boolean isOccupied() {
        return STATUS_OCCUPIED.equals(occupancyStatus);
    }

    /**
     * Admission-owned occupancy seam (docs/plan3.md Task 7): marking a bed
     * OCCUPIED and releasing it back to AVAILABLE belong to the admission
     * workflow alone, so these mutations are deliberately outside
     * {@link #changeOperationalStatus(String)} and have no HTTP caller in
     * Task 6 — tests and the Task 7 admission integration use them to model
     * the admission-owned state honestly.
     */
    public void markOccupiedByAdmission() {
        this.occupancyStatus = STATUS_OCCUPIED;
    }

    /** Admission-owned release from occupancy back to AVAILABLE. */
    public void releaseByAdmission() {
        this.occupancyStatus = STATUS_AVAILABLE;
    }

    /**
     * The single server-side lifecycle mutation. The target must be a
     * client-manageable state, the current state must admit it, and a
     * repeat (target equals current) is refused like any other illegal
     * move — mirroring the emergency-visit lifecycle contract. OCCUPIED
     * beds and legacy unknown statuses refuse every client transition.
     */
    public void changeOperationalStatus(String target) {
        if (target == null || !CLIENT_TARGETS.contains(target)) {
            throw new InvalidStateTransitionException(
                    "Bed status must be one of AVAILABLE, MAINTENANCE, or OUT_OF_SERVICE; "
                            + "OCCUPIED is controlled only by an admission");
        }
        Set<String> legal = LEGAL_TRANSITIONS.get(occupancyStatus);
        if (legal == null) {
            throw new InvalidStateTransitionException(
                    "Bed is " + occupancyStatus + ": no client status transition is defined");
        }
        if (!legal.contains(target)) {
            throw new InvalidStateTransitionException(
                    "Bed cannot transition from " + occupancyStatus + " to " + target);
        }
        this.occupancyStatus = target;
    }
}
