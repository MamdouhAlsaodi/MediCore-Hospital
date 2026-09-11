package com.mamtrex.hospital.appointment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Public appointment contract for /api/appointments (docs/plan1.md Task 4,
 * docs/plan3.md Task 4). patientId/professionalId are typed UUID references
 * resolved by {@link AppointmentService} against their repositories inside
 * the acting branch (a cross-branch reference is the shared 404); a
 * malformed non-UUID body value fails deserialization with 400 via the
 * shared GlobalExceptionHandler malformed-body mapping. scheduledAt is a
 * typed ISO {@link LocalDateTime} (unparseable JSON fails deserialization
 * with 400), and status is bound to the explicit lowercase
 * Training/Portfolio contract scheduled|confirmed|completed|cancelled.
 * Since plan3 Task 4 the response exposes the owning {@code branchId},
 * while the create request accepts no branch input — ownership derives
 * from the acting context alone.
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
                                      String scheduledAt, String type, String status) {

        public static AppointmentResponse from(Appointment a) {
            return new AppointmentResponse(a.getId(), a.getBranch() == null ? null : a.getBranch().getId(),
                    a.getPatientId(), a.getProfessionalId(),
                    a.getScheduledAt(), a.getType(), a.getStatus());
        }
    }

    public record CreateAppointmentRequest(@NotNull UUID patientId,
                                           @NotNull UUID professionalId,
                                           @NotNull LocalDateTime scheduledAt,
                                           @NotBlank String type,
                                           @NotBlank
                                           @Pattern(regexp = "scheduled|confirmed|completed|cancelled")
                                           String status) {}
}
