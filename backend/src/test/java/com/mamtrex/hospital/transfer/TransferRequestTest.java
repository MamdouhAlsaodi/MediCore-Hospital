package com.mamtrex.hospital.transfer;

import com.mamtrex.hospital.shared.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5 T078 RED-first behavioral tests for the transfer status machine
 * (specs/005 data-model.md "Transfer" lifecycle):
 *
 * <pre>
 * REQUESTED -&gt; ACCEPTED -&gt; IN_TRANSIT -&gt; COMPLETED
 * REQUESTED -&gt; REJECTED
 * REQUESTED -&gt; CANCELLED
 * ACCEPTED  -&gt; CANCELLED
 * </pre>
 *
 * REJECTED, CANCELLED, and COMPLETED are terminal; every successful
 * transition stamps its matching state timestamp; source and destination
 * hospitals differ; no mutable patient/source ownership after creation.
 * Pure unit assertions, no database.
 */
class TransferRequestTest {

    private static final Instant T0 = Instant.parse("2025-01-01T10:00:00Z");

    private static TransferRequest requested() {
        return new TransferRequest("TR-0001", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, "BED_SHORTAGE", UUID.randomUUID(), T0);
    }

    // ------------------------------------------- transition table (legal)

    @Test
    void requestedAcceptsIntoAcceptedWithDestinationAndTimestamp() {
        TransferRequest t = requested();
        UUID branch = UUID.randomUUID();
        UUID bed = UUID.randomUUID();
        Instant at = T0.plusSeconds(60);
        t.accept(branch, bed, at);
        assertEquals(TransferStatus.ACCEPTED, t.getStatus());
        assertEquals(branch, t.getDestinationBranchId());
        assertEquals(bed, t.getDestinationBedId());
        assertEquals(at, t.getAcceptedAt());
        assertEquals(0, t.getVersion());
    }

    @Test
    void acceptedStartsTransitAndTransitCompletes() {
        TransferRequest t = requested();
        t.accept(UUID.randomUUID(), UUID.randomUUID(), T0.plusSeconds(60));
        Instant transitAt = T0.plusSeconds(120);
        t.startTransit(transitAt);
        assertEquals(TransferStatus.IN_TRANSIT, t.getStatus());
        assertEquals(transitAt, t.getTransitStartedAt());
        Instant doneAt = T0.plusSeconds(180);
        t.complete(doneAt);
        assertEquals(TransferStatus.COMPLETED, t.getStatus());
        assertEquals(doneAt, t.getCompletedAt());
    }

    @Test
    void requestedTransferCanBeRejectedWithTimestamp() {
        TransferRequest t = requested();
        Instant at = T0.plusSeconds(30);
        t.reject(at);
        assertEquals(TransferStatus.REJECTED, t.getStatus());
        assertEquals(at, t.getRejectedAt());
    }

    @Test
    void requestedAndAcceptedTransfersCanBeCancelled() {
        TransferRequest a = requested();
        Instant at = T0.plusSeconds(10);
        a.cancel(at);
        assertEquals(TransferStatus.CANCELLED, a.getStatus());
        assertEquals(at, a.getCancelledAt());

        TransferRequest b = requested();
        b.accept(UUID.randomUUID(), UUID.randomUUID(), T0.plusSeconds(60));
        b.cancel(T0.plusSeconds(70));
        assertEquals(TransferStatus.CANCELLED, b.getStatus());
    }

    // ---------------------------------------- transition table (illegal)

    @Test
    void everyIllegalTransitionFromRequestedIsRejected() {
        TransferRequest t = requested();
        assertThrows(InvalidStateTransitionException.class, () -> t.startTransit(T0));
        assertThrows(InvalidStateTransitionException.class, () -> t.complete(T0));
    }

    @Test
    void completedTransferIsTerminal() {
        TransferRequest t = requested();
        t.accept(UUID.randomUUID(), UUID.randomUUID(), T0.plusSeconds(1));
        t.startTransit(T0.plusSeconds(2));
        t.complete(T0.plusSeconds(3));
        assertThrows(InvalidStateTransitionException.class, () -> t.accept(UUID.randomUUID(), UUID.randomUUID(), T0));
        assertThrows(InvalidStateTransitionException.class, () -> t.reject(T0));
        assertThrows(InvalidStateTransitionException.class, () -> t.cancel(T0));
        assertThrows(InvalidStateTransitionException.class, () -> t.startTransit(T0));
        assertThrows(InvalidStateTransitionException.class, () -> t.complete(T0));
    }

    @Test
    void rejectedAndCancelledTransfersAreTerminal() {
        TransferRequest r = requested();
        r.reject(T0);
        assertThrows(InvalidStateTransitionException.class, () -> r.accept(UUID.randomUUID(), UUID.randomUUID(), T0));
        assertThrows(InvalidStateTransitionException.class, () -> r.cancel(T0));

        TransferRequest c = requested();
        c.cancel(T0);
        assertThrows(InvalidStateTransitionException.class, () -> c.accept(UUID.randomUUID(), UUID.randomUUID(), T0));
        assertThrows(InvalidStateTransitionException.class, () -> c.reject(T0));
    }

    @Test
    void acceptedTransferCannotAcceptAgainRejectOrCompleteDirectly() {
        TransferRequest t = requested();
        t.accept(UUID.randomUUID(), UUID.randomUUID(), T0.plusSeconds(1));
        assertThrows(InvalidStateTransitionException.class,
                () -> t.accept(UUID.randomUUID(), UUID.randomUUID(), T0.plusSeconds(2)));
        assertThrows(InvalidStateTransitionException.class, () -> t.reject(T0.plusSeconds(2)));
        assertThrows(InvalidStateTransitionException.class, () -> t.complete(T0.plusSeconds(2)));
    }

    // --------------------------------------------------- creation guards

    @Test
    void sourceAndDestinationHospitalsMustDiffer() {
        UUID org = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        UUID same = UUID.randomUUID();
        UUID sourceBranch = UUID.randomUUID();
        UUID destBranchless = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new TransferRequest(
                "TR-0002", org, patient, same, sourceBranch, null, same, null, "BED_SHORTAGE",
                UUID.randomUUID(), T0));
    }

    @Test
    void transferKeepsImmutableIdentityAndOwnershipFields() {
        UUID org = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        UUID sourceHospital = UUID.randomUUID();
        UUID sourceBranch = UUID.randomUUID();
        UUID destinationHospital = UUID.randomUUID();
        UUID requestedBy = UUID.randomUUID();
        TransferRequest t = new TransferRequest("TR-0003", org, patient, sourceHospital,
                sourceBranch, null, destinationHospital, null, "BED_SHORTAGE", requestedBy, T0);
        assertEquals("TR-0003", t.getTransferNumber());
        assertEquals(org, t.getOrganizationId());
        assertEquals(patient, t.getPatientId());
        assertEquals(sourceHospital, t.getSourceHospitalId());
        assertEquals(sourceBranch, t.getSourceBranchId());
        assertEquals(destinationHospital, t.getDestinationHospitalId());
        assertEquals(requestedBy, t.getRequestedByAssignmentId());
        assertEquals(T0, t.getRequestedAt());
        assertEquals(TransferStatus.REQUESTED, t.getStatus());
        assertNull(t.getDestinationBranchId());
        assertNull(t.getDestinationBedId());
        assertNull(t.getAcceptedAt());
    }

    @Test
    void missingRequiredFieldsFailClosed() {
        UUID org = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        UUID sourceHospital = UUID.randomUUID();
        UUID sourceBranch = UUID.randomUUID();
        UUID destinationHospital = UUID.randomUUID();
        UUID requestedBy = UUID.randomUUID();
        assertThrows(NullPointerException.class,
                () -> new TransferRequest(null, org, patient, sourceHospital, sourceBranch,
                        null, destinationHospital, null, "BED_SHORTAGE", requestedBy, T0));
        assertThrows(NullPointerException.class,
                () -> new TransferRequest("TR-0004", org, patient, sourceHospital, sourceBranch,
                        null, destinationHospital, null, null, requestedBy, T0));
        assertThrows(NullPointerException.class,
                () -> new TransferRequest("TR-0005", org, patient, sourceHospital, sourceBranch,
                        null, destinationHospital, null, "BED_SHORTAGE", requestedBy, null));
    }

    // --------------------------------------------- status enum semantics

    @Test
    void statusEnumKnowsExactlyTheDocumentedTransitions() {
        assertTrue(TransferStatus.REQUESTED.canTransitionTo(TransferStatus.ACCEPTED));
        assertTrue(TransferStatus.REQUESTED.canTransitionTo(TransferStatus.REJECTED));
        assertTrue(TransferStatus.REQUESTED.canTransitionTo(TransferStatus.CANCELLED));
        assertTrue(TransferStatus.ACCEPTED.canTransitionTo(TransferStatus.IN_TRANSIT));
        assertTrue(TransferStatus.ACCEPTED.canTransitionTo(TransferStatus.CANCELLED));
        assertTrue(TransferStatus.IN_TRANSIT.canTransitionTo(TransferStatus.COMPLETED));

        assertFalse(TransferStatus.REQUESTED.canTransitionTo(TransferStatus.IN_TRANSIT));
        assertFalse(TransferStatus.REQUESTED.canTransitionTo(TransferStatus.COMPLETED));
        assertFalse(TransferStatus.ACCEPTED.canTransitionTo(TransferStatus.ACCEPTED));
        assertFalse(TransferStatus.ACCEPTED.canTransitionTo(TransferStatus.REJECTED));
        assertFalse(TransferStatus.IN_TRANSIT.canTransitionTo(TransferStatus.CANCELLED));
        for (TransferStatus terminal : new TransferStatus[]{
                TransferStatus.COMPLETED, TransferStatus.REJECTED, TransferStatus.CANCELLED}) {
            for (TransferStatus target : TransferStatus.values()) {
                if (target != terminal) {
                    assertFalse(terminal.canTransitionTo(target),
                            terminal + " must be terminal against " + target);
                }
            }
        }
    }
}
