package com.mamtrex.hospital.transfer;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Strict transfer DTOs (specs/005 tasks T091, FR-014): records with
 * allowlisted fields only — no entities, no persistence metadata. Every
 * response carries the optimistic {@code version}. Reason codes are a
 * bounded operational allowlist, never clinical narrative.
 */
public final class TransferDtos {

    private TransferDtos() {
    }

    /** Bounded operational reason codes (synthetic, non-clinical). */
    public static final Set<String> ALLOWED_REASON_CODES = Set.of(
            "BED_SHORTAGE",
            "SPECIALIST_CARE",
            "EQUIPMENT_LIMITATION",
            "ISOLATION_CAPACITY",
            "PATIENT_PREFERENCE");

    /** POST /api/transfers body; the source hospital is NEVER client input. */
    public record CreateTransferRequest(@NotNull UUID patientId, @NotNull UUID sourceAdmissionId,
                                        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID destinationHospitalId, @NotBlank String reasonCode) {
    }

    /** POST /api/transfers/{id}/accept body. */
    public record AcceptTransferRequest(@NotNull UUID destinationBranchId, @NotNull UUID destinationBedId,
                                        @NotNull @PositiveOrZero Long expectedVersion) {
    }

    /** POST /api/transfers/{id}/reject|cancel body. */
    public record TransitionReasonRequest(@NotBlank String reasonCode,
                                          @NotNull @PositiveOrZero Long expectedVersion) {
    }

    /** The one transfer response shape; every field is an allowlisted value. */
    public record TransferView(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String transferNumber, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID patientId,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID sourceHospitalId, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID sourceBranchId,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID destinationHospitalId,
                               @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) UUID destinationBranchId,
                               @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) UUID destinationBedId, @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"REQUESTED", "ACCEPTED",
        "IN_TRANSIT", "COMPLETED", "REJECTED", "CANCELLED"}) String status, String reasonCode,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant requestedAt,
                               @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) Instant acceptedAt,
                               @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) Instant transitStartedAt,
                               @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) Instant completedAt,
                               @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) Instant cancelledAt,
                               @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) Instant rejectedAt,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long version) {

        public static TransferView of(TransferRequest transfer) {
            return new TransferView(transfer.getId(), transfer.getTransferNumber(),
                    transfer.getPatientId(), transfer.getSourceHospitalId(),
                    transfer.getSourceBranchId(), transfer.getDestinationHospitalId(),
                    transfer.getDestinationBranchId(), transfer.getDestinationBedId(),
                    transfer.getStatus().name(), transfer.getReasonCode(),
                    transfer.getRequestedAt(), transfer.getAcceptedAt(),
                    transfer.getTransitStartedAt(), transfer.getCompletedAt(),
                    transfer.getCancelledAt(), transfer.getRejectedAt(),
                    transfer.getVersion());
        }
    }
}
