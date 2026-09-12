package com.mamtrex.hospital.bed;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Public bed inventory contract for /api/beds (docs/plan3.md Task 6).
 * Response records are immutable and deliberately exclude persistence
 * internals (createdAt, updatedAt, version) and the legacy raw patientId
 * reference, so no mutable JPA entity and no storage metadata ever reaches
 * the public JSON. Since Task 6 every bed is branch-owned: the response
 * exposes the owning branch id, while the create request accepts no branch,
 * status, or patient input — ownership derives from the acting context and
 * the server alone sets the initial AVAILABLE status.
 */
public final class BedDtos {

    private BedDtos() {}

    /**
     * Stable public bed representation returned by every bed route.
     * {@code branchId} is the owning branch; it is null only on unassigned
     * legacy rows, which branch-scoped endpoints never disclose.
     */
    public record BedResponse(UUID id, UUID branchId, String ward, String room,
                              String bedNumber, String occupancyStatus) {

        public static BedResponse from(Bed bed) {
            return new BedResponse(bed.getId(), bed.getBranch() == null ? null : bed.getBranch().getId(),
                    bed.getWard(), bed.getRoom(), bed.getBedNumber(), bed.getOccupancyStatus());
        }
    }

    /**
     * Create input carries exactly the three location fields: no branch, no
     * status, and no patient reference — all three are server-owned
     * (docs/plan3.md Task 6). Unknown extra client fields are ignored.
     */
    public record CreateBedRequest(@NotBlank String ward,
                                   @NotBlank String room,
                                   @NotBlank String bedNumber) {}

    /**
     * Client status input: only the three client-manageable targets are
     * legal, and a request for OCCUPIED is the shared 409 conflict because
     * occupancy is admission-owned (docs/plan3.md Task 7).
     */
    public record UpdateBedStatusRequest(@NotBlank String status) {}
}
