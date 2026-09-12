package com.mamtrex.hospital.appointment;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Public appointment contract for /api/appointments (docs/plan1.md Task 4,
 * docs/plan3.md Tasks 4 and 9). patientId/professionalId are typed UUID
 * references resolved by {@link AppointmentService} against their
 * repositories inside the acting branch (a cross-branch reference answers
 * the same 404 as a nonexistent one); a malformed non-UUID body value fails
 * deserialization with 400 via the shared GlobalExceptionHandler
 * malformed-body mapping. scheduledAt is a typed ISO {@link LocalDateTime}
 * (unparseable JSON fails deserialization with 400), and status is bound to
 * the explicit lowercase Training/Portfolio contract
 * scheduled|confirmed|completed|cancelled.
 *
 * <p>Since plan3 Task 9 the create additionally requires
 * {@code durationMinutes} — a bounded engineering validation range
 * (5-480 inclusive) for the synthetic demo, never clinical, care, or
 * staffing policy — and the server computes the half-open window end
 * {@code endsAt = scheduledAt + durationMinutes} itself; the client never
 * sends an end. An oversized JSON number cannot even reach validation as an
 * Integer (Jackson fails deserialization with 400), and the year bound
 * below keeps that server-side addition overflow-safe. The response exposes
 * {@code durationMinutes} and the computed {@code endsAt}; legacy rows
 * created before Task 9 carry nulls there and stay read-only.</p>
 */
public final class AppointmentDtos {

    private AppointmentDtos() {}

    /**
     * Stable public appointment representation. patientId/professionalId are
     * the canonical UUID strings of the verified references; legacy rows
     * created before plan3 Task 4 keep their earlier stored values read-only
     * and carry a null branchId, undisclosed by branch-scoped endpoints.
     */
    public record AppointmentResponse(UUID id, UUID branchId, String patientId, String professionalId,
                                      String scheduledAt, Integer durationMinutes, String endsAt,
                                      String type, String status) {

        public static AppointmentResponse from(Appointment a) {
            return new AppointmentResponse(a.getId(), a.getBranch() == null ? null : a.getBranch().getId(),
                    a.getPatientId(), a.getProfessionalId(),
                    a.getScheduledAt(), a.getDurationMinutes(), a.getEndsAt(),
                    a.getType(), a.getStatus());
        }
    }

    public record CreateAppointmentRequest(@NotNull UUID patientId,
                                           @NotNull UUID professionalId,
                                           @NotNull LocalDateTime scheduledAt,
                                           @NotNull @Min(5) @Max(480) Integer durationMinutes,
                                           @NotBlank String type,
                                           @NotBlank
                                           @Pattern(regexp = "scheduled|confirmed|completed|cancelled")
                                           String status) {

        /**
         * Overflow-safe contract guard: the server computes
         * {@code scheduledAt.plusMinutes(durationMinutes)}, so a (rare but
         * parseable) far-future timestamp outside the four-digit year range
         * must fail validation here — as a shared 400 — instead of becoming
         * a server arithmetic error.
         */
        @AssertTrue(message = "scheduledAt must stay within the representable four-digit year range")
        public boolean hasOverflowSafeScheduledAt() {
            return scheduledAt == null || scheduledAt.getYear() <= 9999;
        }
    }
}
