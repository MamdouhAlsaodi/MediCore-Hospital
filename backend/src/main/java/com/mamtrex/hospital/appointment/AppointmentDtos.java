package com.mamtrex.hospital.appointment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Public appointment contract for /api/appointments (docs/plan1.md Task 3).
 * patientId/professionalId intentionally remain raw non-blank strings for
 * now; docs/plan1.md Task 4 replaces them with verified relationships.
 * scheduledAt is a typed ISO {@link LocalDateTime} (unparseable JSON fails
 * deserialization with 400), and status is bound to the explicit lowercase
 * Training/Portfolio contract scheduled|confirmed|completed|cancelled.
 */
public final class AppointmentDtos {

    private AppointmentDtos() {}

    /** Stable public appointment representation; raw references survive until Task 4. */
    public record AppointmentResponse(UUID id, String patientId, String professionalId,
                                      String scheduledAt, String type, String status) {

        public static AppointmentResponse from(Appointment a) {
            return new AppointmentResponse(a.getId(), a.getPatientId(), a.getProfessionalId(),
                    a.getScheduledAt(), a.getType(), a.getStatus());
        }
    }

    public record CreateAppointmentRequest(@NotBlank String patientId,
                                           @NotBlank String professionalId,
                                           @NotNull LocalDateTime scheduledAt,
                                           @NotBlank String type,
                                           @NotBlank
                                           @Pattern(regexp = "scheduled|confirmed|completed|cancelled")
                                           String status) {}
}
