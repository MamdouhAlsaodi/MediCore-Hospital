package com.mamtrex.hospital.idempotency;

import com.mamtrex.hospital.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One stored idempotency outcome (specs/005 data-model.md
 * {@code IdempotencyRecord}; Phase 5 T086). The identity of the row is the
 * normalized unique key {@code (assignment_id, operation, idempotency_key)}
 * enforced by V7; the bounded SHA-256 {@code request_fingerprint} makes
 * same-key-different-payload conflicts detectable; the stored
 * {@code resource_id} and {@code http_status} are what a replay returns.
 * The idempotency key itself is never a telemetry label and is never
 * logged by this package. Completion is stamped exactly once
 * ({@link #completeIfInProgress}); the optimistic {@code @Version} from
 * {@link BaseEntity} guards a concurrent double completion.
 */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord extends BaseEntity {

    @Column(name = "assignment_id", nullable = false)
    private UUID assignmentId;

    @Column(nullable = false, length = 64)
    private String operation;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, columnDefinition = "char(64)")
    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.CHAR)
    private String requestFingerprint;

    @Column(name = "resource_type", nullable = false, length = 64)
    private String resourceType;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "response_snapshot", columnDefinition = "text")
    private String responseSnapshot;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private IdempotencyRecordState state;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected IdempotencyRecord() {
    }

    /** The one creating constructor: a record is born IN_PROGRESS. */
    public IdempotencyRecord(UUID assignmentId, String operation, String idempotencyKey,
                             String requestFingerprint, String resourceType) {
        this.assignmentId = Objects.requireNonNull(assignmentId, "assignmentId");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.requestFingerprint = Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        this.resourceType = Objects.requireNonNull(resourceType, "resourceType");
        this.state = IdempotencyRecordState.IN_PROGRESS;
    }

    /**
     * The single lifecycle mutation: IN_PROGRESS -&gt; COMPLETED, stamping
     * the stored resource id, the replayed HTTP status, and the completion
     * time exactly once; a completed record is an idempotent no-op.
     */
    public void completeIfInProgress(UUID resourceId, int httpStatus, Instant completedAt) {
        completeIfInProgress(resourceId, httpStatus, completedAt, null);
    }

    public void completeIfInProgress(UUID resourceId, int httpStatus, Instant completedAt, String responseSnapshot) {
        Objects.requireNonNull(completedAt, "completedAt");
        if (this.state == IdempotencyRecordState.COMPLETED) {
            return;
        }
        this.state = IdempotencyRecordState.COMPLETED;
        this.resourceId = resourceId;
        this.httpStatus = httpStatus;
        this.responseSnapshot = responseSnapshot;
        this.completedAt = completedAt;
    }

    public UUID getAssignmentId() { return assignmentId; }
    public String getOperation() { return operation; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public String getResourceType() { return resourceType; }
    public UUID getResourceId() { return resourceId; }
    public String getResponseSnapshot() { return responseSnapshot; }
    public Integer getHttpStatus() { return httpStatus; }
    public IdempotencyRecordState getState() { return state; }
    public Instant getCompletedAt() { return completedAt; }
}
