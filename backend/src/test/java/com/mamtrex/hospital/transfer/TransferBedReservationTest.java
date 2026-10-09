package com.mamtrex.hospital.transfer;

import com.mamtrex.hospital.shared.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5 T079 RED-first behavioral tests for the bed reservation aggregate
 * (specs/005 data-model.md {@code TransferBedReservation}): a reservation is
 * created ACTIVE for one transfer/bed pair, can be consumed exactly once
 * (completion) or released exactly once (cancellation/rejection), and its
 * terminal states are invariants. One reservation per transfer and the
 * partial one-ACTIVE-per-bed index are persistence constraints; the pure
 * aggregate guards its own lifecycle. Pure unit assertions, no database.
 */
class TransferBedReservationTest {

    private static final Instant T0 = Instant.parse("2025-01-01T10:00:00Z");

    private static TransferBedReservation active() {
        return new TransferBedReservation(UUID.randomUUID(), UUID.randomUUID(), T0);
    }

    @Test
    void newReservationStartsActiveForOneTransferAndBed() {
        UUID transferId = UUID.randomUUID();
        UUID bedId = UUID.randomUUID();
        TransferBedReservation r = new TransferBedReservation(transferId, bedId, T0);
        assertEquals(transferId, r.getTransferId());
        assertEquals(bedId, r.getBedId());
        assertEquals(ReservationStatus.ACTIVE, r.getStatus());
        assertEquals(T0, r.getReservedAt());
        assertNull(r.getReleasedAt());
        assertEquals(0, r.getVersion());
    }

    @Test
    void activeReservationCanBeConsumedExactlyOnce() {
        TransferBedReservation r = active();
        r.consume();
        assertEquals(ReservationStatus.CONSUMED, r.getStatus());
        assertThrows(InvalidStateTransitionException.class, r::consume);
        assertThrows(InvalidStateTransitionException.class, () -> r.release(T0));
    }

    @Test
    void activeReservationCanBeReleasedExactlyOnceWithTimestamp() {
        TransferBedReservation r = active();
        Instant at = T0.plusSeconds(30);
        r.release(at);
        assertEquals(ReservationStatus.RELEASED, r.getStatus());
        assertEquals(at, r.getReleasedAt());
        assertThrows(InvalidStateTransitionException.class, () -> r.release(at));
        assertThrows(InvalidStateTransitionException.class, r::consume);
    }

    @Test
    void missingTransferOrBedFailsClosed() {
        assertThrows(NullPointerException.class,
                () -> new TransferBedReservation(null, UUID.randomUUID(), T0));
        assertThrows(NullPointerException.class,
                () -> new TransferBedReservation(UUID.randomUUID(), null, T0));
    }
}
