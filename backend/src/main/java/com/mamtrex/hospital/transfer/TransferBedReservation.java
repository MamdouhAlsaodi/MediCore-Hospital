package com.mamtrex.hospital.transfer;

import com.mamtrex.hospital.shared.BaseEntity;
import com.mamtrex.hospital.shared.InvalidStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The destination bed reservation of one transfer (specs/005 data-model.md
 * {@code TransferBedReservation}; Phase 5 T084). One reservation per
 * transfer and the partial unique index allowing only one ACTIVE
 * reservation per bed are persistence constraints (V7, downstream); this
 * aggregate guarantees its own invariants: it is born ACTIVE for exactly
 * one (transfer, bed) pair, moves exactly once to CONSUMED or RELEASED,
 * stamps {@code released_at} only on release, and carries the inherited
 * optimistic {@code @Version}. Only an ACCEPTED (or IN_TRANSIT, until
 * completion) transfer owns an active reservation — that ownership link is
 * enforced by the service layer, not here.
 */
@Entity
@Table(name = "transfer_bed_reservations")
public class TransferBedReservation extends BaseEntity {

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Column(name = "bed_id", nullable = false)
    private UUID bedId;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private ReservationStatus status;

    @Column(name = "reserved_at", nullable = false)
    private Instant reservedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    protected TransferBedReservation() {
    }

    /** The one creating constructor: the reservation is born ACTIVE. */
    public TransferBedReservation(UUID transferId, UUID bedId, Instant reservedAt) {
        this.transferId = Objects.requireNonNull(transferId, "transferId");
        this.bedId = Objects.requireNonNull(bedId, "bedId");
        this.reservedAt = Objects.requireNonNull(reservedAt, "reservedAt");
        this.status = ReservationStatus.ACTIVE;
    }

    private void transitionTo(ReservationStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(
                    "Reservation " + status + " cannot transition to " + target);
        }
        this.status = target;
    }

    /** ACTIVE -&gt; CONSUMED; completion binds exactly one destination admission. */
    public void consume() {
        transitionTo(ReservationStatus.CONSUMED);
    }

    /** ACTIVE -&gt; RELEASED; stamps the release timestamp exactly once. */
    public void release(Instant at) {
        Objects.requireNonNull(at, "release timestamp");
        transitionTo(ReservationStatus.RELEASED);
        this.releasedAt = at;
    }

    public UUID getTransferId() { return transferId; }
    public UUID getBedId() { return bedId; }
    public ReservationStatus getStatus() { return status; }
    public Instant getReservedAt() { return reservedAt; }
    public Instant getReleasedAt() { return releasedAt; }
}
