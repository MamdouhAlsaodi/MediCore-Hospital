package com.mamtrex.hospital.admission;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Public admission contract for /api/admissions (docs/plan2.md Task 2).
 * patientId is a typed UUID reference resolved by {@link AdmissionService}
 * against the patient repository; a malformed non-UUID body value fails
 * deserialization with 400 via the shared GlobalExceptionHandler malformed-
 * body mapping, and an unresolvable reference returns the shared 404.
 * admittedAt is a typed ISO value stored as its canonical string, following
 * the appointment precedent (no destructive column migration). The create
 * request deliberately carries NO status or dischargedAt field: the server
 * owns the lifecycle and sets status=ADMITTED, and only the server stamps
 * the discharge time. The transition request carries the single legal
 * target status; anything an admission cannot transition to is refused by
 * the service with the shared 409, never accepted verbatim.
 */
public final class AdmissionDtos {

    private AdmissionDtos() {}

    /**
     * Stable public admission representation. patientId is the canonical
     * UUID string of the verified patient; status is the server-owned
     * lifecycle state ADMITTED | DISCHARGED; dischargedAt is null until the
     * server stamps it at discharge. No persistence metadata is exposed.
     */
    public record AdmissionResponse(UUID id, String patientId, String admittedAt,
                                    String dischargedAt, String reason, String status) {

        public static AdmissionResponse from(Admission a) {
            return new AdmissionResponse(a.getId(), a.getPatientId(), a.getAdmittedAt(),
                    a.getDischargedAt(), a.getReason(), a.getStatus());
        }
    }

    public record CreateAdmissionRequest(@NotNull UUID patientId,
                                         @NotNull java.time.LocalDateTime admittedAt,
                                         @NotBlank String reason) {}

    /** The only discharge request: the sole transition target in the map. */
    public record UpdateAdmissionStatusRequest(@NotBlank String status) {}
}
