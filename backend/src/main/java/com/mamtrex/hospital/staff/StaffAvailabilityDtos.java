package com.mamtrex.hospital.staff;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Public availability contract for the /api/staff/{id}/availability surface
 * (docs/plan3.md Task 9, §4.6). Intervals are half-open
 * {@code [startsAt, endsAt)} typed {@link LocalDateTime} values in the
 * current API UTC/ISO contract — no branch-local time zone is invented.
 * The create request carries no branch input (ownership derives from the
 * acting context and the verified same-branch professional), and the
 * response exposes exactly the allowlisted fields — never persistence
 * internals or entity metadata.
 */
public final class StaffAvailabilityDtos {

    private StaffAvailabilityDtos() {}

    /** Validated create body: an explicit dated interval with endsAt strictly after startsAt. */
    public record CreateAvailabilityRequest(@NotNull LocalDateTime startsAt,
                                            @NotNull LocalDateTime endsAt) {

        @AssertTrue(message = "endsAt must be after startsAt")
        public boolean isValidInterval() {
            return startsAt != null && endsAt != null && endsAt.isAfter(startsAt);
        }
    }

    /** Stable public availability representation returned by both routes. */
    public record AvailabilityResponse(UUID id, UUID branchId, UUID staffMemberId,
                                       String startsAt, String endsAt) {

        public static AvailabilityResponse from(StaffAvailability a) {
            return new AvailabilityResponse(a.getId(), a.getBranchId(), a.getStaffMemberId(),
                    a.getStartsAt().toString(), a.getEndsAt().toString());
        }
    }

    /**
     * Validated GET window model: both bounds are required and ordered, so a
     * missing, unordered, or unparseable window fails as a shared client
     * error (400) instead of degrading into an unbounded read.
     */
    public record AvailabilityWindow(
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {

        @AssertTrue(message = "from must be before to")
        public boolean isValidWindow() {
            return from != null && to != null && from.isBefore(to);
        }
    }
}
