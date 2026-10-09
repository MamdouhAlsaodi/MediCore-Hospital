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
 * One inter-hospital transfer request aggregate (specs/005 data-model.md
 * {@code TransferRequest}; Phase 5 T083). Patient and source ownership are
 * immutable after creation; the destination branch/bed are only expressible
 * through the guarded {@link #accept} transition; every lifecycle change
 * goes through the {@link TransferStatus} machine and stamps exactly the
 * matching state timestamp. The optimistic {@code version} is inherited
 * from {@link BaseEntity} ({@code @Version}), so every flushed successful
 * transition increments it and a concurrent loser fails instead of
 * overwriting. Persistence-level organization/branch membership, uniqueness
 * of {@code transfer_number} inside the organization, and the audit-in-same-
 * transaction rule belong to the service/migration layers (V7 and T092–T096,
 * downstream), not to this pure aggregate.
 */
@Entity
@Table(name = "transfer_requests")
public class TransferRequest extends BaseEntity {

    @Column(name = "transfer_number", nullable = false, length = 40)
    private String transferNumber;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "source_hospital_id", nullable = false)
    private UUID sourceHospitalId;

    @Column(name = "source_branch_id", nullable = false)
    private UUID sourceBranchId;

    @Column(name = "source_admission_id")
    private UUID sourceAdmissionId;

    @Column(name = "destination_hospital_id", nullable = false)
    private UUID destinationHospitalId;

    @Column(name = "destination_branch_id")
    private UUID destinationBranchId;

    @Column(name = "destination_bed_id")
    private UUID destinationBedId;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private TransferStatus status;

    /** Bounded operational synthetic code, never a clinical narrative. */
    @Column(name = "reason_code", nullable = false, length = 40)
    private String reasonCode;

    @Column(name = "requested_by_assignment_id", nullable = false)
    private UUID requestedByAssignmentId;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "transit_started_at")
    private Instant transitStartedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    protected TransferRequest() {
    }

    /**
     * The one creating constructor: the transfer is born REQUESTED with no
     * destination branch/bed, source and destination hospitals must differ,
     * and every identity field is required (missing values fail closed).
     */
    public TransferRequest(String transferNumber, UUID organizationId, UUID patientId,
                           UUID sourceHospitalId, UUID sourceBranchId, UUID sourceAdmissionId,
                           UUID destinationHospitalId, UUID destinationBranchId,
                           String reasonCode, UUID requestedByAssignmentId, Instant requestedAt) {
        this.transferNumber = Objects.requireNonNull(transferNumber, "transferNumber");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.patientId = Objects.requireNonNull(patientId, "patientId");
        this.sourceHospitalId = Objects.requireNonNull(sourceHospitalId, "sourceHospitalId");
        this.sourceBranchId = Objects.requireNonNull(sourceBranchId, "sourceBranchId");
        this.sourceAdmissionId = sourceAdmissionId;
        this.destinationHospitalId = Objects.requireNonNull(destinationHospitalId, "destinationHospitalId");
        if (sourceHospitalId.equals(destinationHospitalId)) {
            throw new IllegalArgumentException(
                    "Transfer source and destination hospitals must differ");
        }
        this.destinationBranchId = destinationBranchId;
        this.destinationBedId = null;
        this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        this.requestedByAssignmentId = Objects.requireNonNull(requestedByAssignmentId, "requestedByAssignmentId");
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        this.status = TransferStatus.REQUESTED;
    }

    private void transitionTo(TransferStatus target, Instant at) {
        Objects.requireNonNull(at, "transition timestamp");
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(
                    "Transfer " + status + " cannot transition to " + target);
        }
        this.status = target;
    }

    /** REQUESTED -&gt; ACCEPTED; binds the destination branch and bed exactly once. */
    public void accept(UUID destinationBranchId, UUID destinationBedId, Instant at) {
        Objects.requireNonNull(destinationBranchId, "destinationBranchId");
        Objects.requireNonNull(destinationBedId, "destinationBedId");
        transitionTo(TransferStatus.ACCEPTED, at);
        this.destinationBranchId = destinationBranchId;
        this.destinationBedId = destinationBedId;
        this.acceptedAt = at;
    }

    /** REQUESTED -&gt; REJECTED (terminal). */
    public void reject(Instant at) {
        transitionTo(TransferStatus.REJECTED, at);
        this.rejectedAt = at;
    }

    /** REQUESTED|ACCEPTED -&gt; CANCELLED (terminal). */
    public void cancel(Instant at) {
        transitionTo(TransferStatus.CANCELLED, at);
        this.cancelledAt = at;
    }

    /** ACCEPTED -&gt; IN_TRANSIT; the active reservation is retained until completion. */
    public void startTransit(Instant at) {
        transitionTo(TransferStatus.IN_TRANSIT, at);
        this.transitStartedAt = at;
    }

    /** IN_TRANSIT -&gt; COMPLETED (terminal). */
    public void complete(Instant at) {
        transitionTo(TransferStatus.COMPLETED, at);
        this.completedAt = at;
    }

    public String getTransferNumber() { return transferNumber; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getPatientId() { return patientId; }
    public UUID getSourceHospitalId() { return sourceHospitalId; }
    public UUID getSourceBranchId() { return sourceBranchId; }
    public UUID getSourceAdmissionId() { return sourceAdmissionId; }
    public UUID getDestinationHospitalId() { return destinationHospitalId; }
    public UUID getDestinationBranchId() { return destinationBranchId; }
    public UUID getDestinationBedId() { return destinationBedId; }
    public TransferStatus getStatus() { return status; }
    public String getReasonCode() { return reasonCode; }
    public UUID getRequestedByAssignmentId() { return requestedByAssignmentId; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public Instant getTransitStartedAt() { return transitStartedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public Instant getRejectedAt() { return rejectedAt; }
}
