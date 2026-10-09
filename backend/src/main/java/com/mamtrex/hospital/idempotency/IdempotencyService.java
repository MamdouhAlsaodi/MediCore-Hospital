package com.mamtrex.hospital.idempotency;

import com.mamtrex.hospital.shared.InvalidParameterValueException;
import com.mamtrex.hospital.shared.InvalidStateTransitionException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;

/**
 * Bounded idempotency semantics (specs/005 US4, FR-018; Phase 5 T087).
 * One call identity is the triple (acting assignment, operation,
 * client-supplied key); the SHA-256 request fingerprint pins the exact
 * payload, so:
 *
 * <ul>
 *   <li>a first call starts an IN_PROGRESS record and runs the mutation;</li>
 *   <li>a repeat with the SAME key and fingerprint replays the stored
 *       outcome (original resource id and HTTP status) instead of
 *       mutating again;</li>
 *   <li>the same key with a DIFFERENT payload is a typed conflict
 *       (mapped to 409), because a replayed key must never execute a
 *       different mutation;</li>
 *   <li>keys are validated (non-blank, at most 128 chars) before anything
 *       is stored.</li>
 * </ul>
 *
 * <p>Begin/complete are called by the transfer service inside its single
 * transaction, so a rolled-back mutation leaves no completed record and a
 * committed one always leaves the stored outcome. Keys are never logged or
 * used as telemetry labels.
 */
@Service
public class IdempotencyService {

    /** The data-model bound for a client idempotency key. */
    public static final int MAX_KEY_LENGTH = 128;

    private static final int MAX_OPERATION_LENGTH = 64;

    private final IdempotencyRecordGateway gateway;

    public IdempotencyService(IdempotencyRecordGateway gateway) {
        this.gateway = gateway;
    }

    /** One begin() outcome: either a fresh IN_PROGRESS record or a replay. */
    public record IdempotencyResult(boolean replay, IdempotencyRecord record) {
    }

    /** Deterministic bounded SHA-256 hex fingerprint of the exact request payload. */
    public static String sha256Fingerprint(String payload) {
        Objects_requireNonNull(payload);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is always available", impossible);
        }
    }

    private static void Objects_requireNonNull(String payload) {
        java.util.Objects.requireNonNull(payload, "payload");
    }

    /**
     * Starts or replays one idempotent call. Validation happens before any
     * storage; a same-key/different-fingerprint repeat throws the typed
     * conflict that the shared exception handler maps to 409.
     */
    public IdempotencyResult begin(UUID assignmentId, String operation, String idempotencyKey,
                                   String requestFingerprint, String resourceType) {
        java.util.Objects.requireNonNull(assignmentId, "assignmentId");
        if (operation == null || operation.isBlank() || operation.length() > MAX_OPERATION_LENGTH) {
            throw new InvalidParameterValueException(
                    "Idempotency operation must be a non-blank label of at most "
                            + MAX_OPERATION_LENGTH + " characters");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new InvalidParameterValueException(
                    "Idempotency-Key must be a non-blank value of at most "
                            + MAX_KEY_LENGTH + " characters");
        }
        if (requestFingerprint == null || requestFingerprint.isBlank()) {
            throw new InvalidParameterValueException(
                    "An idempotent call requires the request fingerprint");
        }
        java.util.Objects.requireNonNull(resourceType, "resourceType");

        var existing = gateway
                .findByAssignmentIdAndOperationAndIdempotencyKey(assignmentId, operation, idempotencyKey);
        if (existing.isPresent()) {
            var record = existing.orElseThrow();
            if (!record.getRequestFingerprint().equals(requestFingerprint)) {
                throw new InvalidStateTransitionException(
                        "Idempotency-Key was already used for a different request payload");
            }
            return new IdempotencyResult(true, record);
        }
        try {
            return new IdempotencyResult(false, gateway.save(
                    new IdempotencyRecord(assignmentId, operation, idempotencyKey,
                            requestFingerprint, resourceType)));
        } catch (org.springframework.dao.DataIntegrityViolationException lostInsertRace) {
            // A concurrent call with the SAME key committed first; its stored
            // outcome is now visible and must be replayed (never a duplicate
            // mutation). A different-fingerprint winner is still a conflict.
            var winner = gateway
                    .findByAssignmentIdAndOperationAndIdempotencyKey(assignmentId, operation, idempotencyKey)
                    .orElseThrow(() -> lostInsertRace);
            if (!winner.getRequestFingerprint().equals(requestFingerprint)) {
                throw new InvalidStateTransitionException(
                        "Idempotency-Key was already used for a different request payload");
            }
            return new IdempotencyResult(true, winner);
        }
    }

    /**
     * Commits the stored outcome of one guarded mutation exactly once; a
     * repeat completion is an idempotent no-op, never a state error.
     */
    public void complete(IdempotencyRecord record, UUID resourceId, int httpStatus) {
        complete(record, resourceId, httpStatus, null);
    }

    public void complete(IdempotencyRecord record, UUID resourceId, int httpStatus, String responseSnapshot) {
        java.util.Objects.requireNonNull(record, "record");
        record.completeIfInProgress(resourceId, httpStatus, Instant.now(), responseSnapshot);
        gateway.save(record);
    }
}
