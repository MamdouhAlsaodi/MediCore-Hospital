package com.mamtrex.hospital.idempotency;

import com.mamtrex.hospital.shared.InvalidParameterValueException;
import com.mamtrex.hospital.shared.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5 T080 RED-first behavioral tests for bounded idempotency semantics
 * (specs/005 data-model.md {@code IdempotencyRecord}, T087): a unique
 * {@code (assignment_id, operation, idempotency_key)} key carries a bounded
 * SHA-256 request fingerprint; a repeated call with the same key and
 * fingerprint replays the stored outcome; the same key with a different
 * payload is a conflict; keys are bounded and validated before anything is
 * stored. Uses an in-memory fake store — no database.
 */
class IdempotencyServiceTest {

    private static final class FakeStore implements IdempotencyRecordGateway {
        final Map<String, IdempotencyRecord> rows = new HashMap<>();

        private static String key(UUID a, String op, String k) {
            return a + "/" + op + "/" + k;
        }

        @Override
        public Optional<IdempotencyRecord> findByAssignmentIdAndOperationAndIdempotencyKey(
                UUID assignmentId, String operation, String idempotencyKey) {
            return Optional.ofNullable(rows.get(key(assignmentId, operation, idempotencyKey)));
        }

        @Override
        public IdempotencyRecord save(IdempotencyRecord record) {
            rows.put(key(record.getAssignmentId(), record.getOperation(), record.getIdempotencyKey()), record);
            return record;
        }
    }

    private static final UUID ASSIGNMENT = UUID.randomUUID();
    private static final String OP = "requestTransfer";
    private static final String KEY = "client-key-1";
    private static final String BODY = "{\"patientId\":\"p1\"}";

    private FakeStore newStore() {
        return new FakeStore();
    }

    @Test
    void firstCallStartsInProgressRecordWithSha256Fingerprint() {
        FakeStore store = newStore();
        IdempotencyService service = new IdempotencyService(store);
        String fingerprint = IdempotencyService.sha256Fingerprint(BODY);

        IdempotencyService.IdempotencyResult result =
                service.begin(ASSIGNMENT, OP, KEY, fingerprint, "TransferRequest");
        assertFalse(result.replay());
        assertEquals(IdempotencyRecordState.IN_PROGRESS, result.record().getState());
        assertEquals(64, fingerprint.length());
        assertEquals(fingerprint, result.record().getRequestFingerprint());
        assertEquals(ASSIGNMENT, result.record().getAssignmentId());
        assertEquals(OP, result.record().getOperation());
        assertEquals(KEY, result.record().getIdempotencyKey());
        assertNull(result.record().getResourceId());
    }

    @Test
    void sameKeyAndFingerprintReplaysStoredOutcomeWithoutNewRow() {
        FakeStore store = newStore();
        IdempotencyService service = new IdempotencyService(store);
        String fingerprint = IdempotencyService.sha256Fingerprint(BODY);
        IdempotencyService.IdempotencyResult first =
                service.begin(ASSIGNMENT, OP, KEY, fingerprint, "TransferRequest");
        service.complete(first.record(), UUID.randomUUID(), 201);

        IdempotencyService.IdempotencyResult second =
                service.begin(ASSIGNMENT, OP, KEY, IdempotencyService.sha256Fingerprint(BODY), "TransferRequest");
        assertTrue(second.replay());
        assertEquals(1, store.rows.size());
        assertSame(first.record(), second.record());
        assertEquals(IdempotencyRecordState.COMPLETED, second.record().getState());
        assertEquals(201, second.record().getHttpStatus());
    }

    @Test
    void sameKeyWithDifferentFingerprintIsAConflict() {
        FakeStore store = newStore();
        IdempotencyService service = new IdempotencyService(store);
        service.begin(ASSIGNMENT, OP, KEY,
                IdempotencyService.sha256Fingerprint(BODY), "TransferRequest");

        assertThrows(InvalidStateTransitionException.class, () -> service.begin(
                ASSIGNMENT, OP, KEY, IdempotencyService.sha256Fingerprint("{\"patientId\":\"p2\"}"),
                "TransferRequest"));
    }

    @Test
    void sameKeyForDifferentAssignmentOrOperationIsIndependent() {
        FakeStore store = newStore();
        IdempotencyService service = new IdempotencyService(store);
        String fingerprint = IdempotencyService.sha256Fingerprint(BODY);
        service.begin(ASSIGNMENT, OP, KEY, fingerprint, "TransferRequest");

        IdempotencyService.IdempotencyResult otherAssignment = service.begin(
                UUID.randomUUID(), OP, KEY, fingerprint, "TransferRequest");
        IdempotencyService.IdempotencyResult otherOperation = service.begin(
                ASSIGNMENT, "acceptTransfer", KEY, fingerprint, "TransferRequest");
        assertFalse(otherAssignment.replay());
        assertFalse(otherOperation.replay());
        assertEquals(3, store.rows.size());
    }

    @Test
    void completeGuardsStateAndStampsResource() {
        FakeStore store = newStore();
        IdempotencyService service = new IdempotencyService(store);
        IdempotencyService.IdempotencyResult result = service.begin(ASSIGNMENT, OP, KEY,
                IdempotencyService.sha256Fingerprint(BODY), "TransferRequest");
        UUID resourceId = UUID.randomUUID();
        service.complete(result.record(), resourceId, 201);
        assertEquals(IdempotencyRecordState.COMPLETED, result.record().getState());
        assertEquals(resourceId, result.record().getResourceId());
        assertNotNull(result.record().getCompletedAt());
        // completing a completed record is an idempotent no-op, not a state error
        service.complete(result.record(), resourceId, 201);
        assertEquals(IdempotencyRecordState.COMPLETED, result.record().getState());
    }

    @Test
    void unboundedOrBlankKeysAreRejectedBeforeAnyStorage() {
        FakeStore store = newStore();
        IdempotencyService service = new IdempotencyService(store);
        String fingerprint = IdempotencyService.sha256Fingerprint(BODY);

        assertThrows(InvalidParameterValueException.class,
                () -> service.begin(ASSIGNMENT, OP, " ", fingerprint, "TransferRequest"));
        assertThrows(InvalidParameterValueException.class,
                () -> service.begin(ASSIGNMENT, OP, "k".repeat(129), fingerprint, "TransferRequest"));
        assertThrows(InvalidParameterValueException.class,
                () -> service.begin(ASSIGNMENT, " ", KEY, fingerprint, "TransferRequest"));
        assertThrows(InvalidParameterValueException.class,
                () -> service.begin(ASSIGNMENT, OP, KEY, null, "TransferRequest"));
        assertThrows(NullPointerException.class,
                () -> service.begin(ASSIGNMENT, OP, KEY, fingerprint, null));
        assertTrue(store.rows.isEmpty());
    }

    @Test
    void fingerprintIsDeterministicHexSha256() {
        String a = IdempotencyService.sha256Fingerprint(BODY);
        String b = IdempotencyService.sha256Fingerprint(BODY);
        assertEquals(a, b);
        assertEquals(64, a.length());
        assertTrue(a.matches("[0-9a-f]{64}"));
        assertNotEquals(a, IdempotencyService.sha256Fingerprint(BODY + " "));
    }
}
