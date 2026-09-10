package com.mamtrex.hospital.emergency;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/**
 * Public emergency-visit contract for /api/emergency-visits (docs/plan2.md
 * Task 3). patientId is a typed UUID reference resolved by
 * {@link EmergencyVisitService} against the patient repository; a malformed
 * non-UUID body value fails deserialization with 400 via the shared
 * GlobalExceptionHandler malformed-body mapping, and an unresolvable
 * reference returns the shared 404. arrivalAt is a typed ISO value stored as
 * its canonical string, following the appointment and admission precedent
 * (no destructive column migration). The create request deliberately carries
 * NO status field: the server owns the lifecycle and sets status=WAITING.
 *
 * triageLevel is a NEUTRAL DEMO LABEL restricted to the strings "1"–"5".
 * It is a non-clinical training simulation value with NO clinical meaning:
 * it is not ATS, ESI, MTS, or any real triage protocol, carries no
 * assessment or prioritization semantics, and never influences any
 * clinical decision. MediCore remains educational synthetic-data software.
 */
public final class EmergencyVisitDtos {

    private EmergencyVisitDtos() {}

    /** The only legal triage demo labels — a neutral 1–5 simulation scale. */
    public static final String TRIAGE_PATTERN = "1|2|3|4|5";

    /**
     * Stable public emergency-visit representation. patientId is the
     * canonical UUID string of the verified patient; status is the
     * server-owned lifecycle state WAITING | IN_TREATMENT | CLOSED; the
     * triage label is the neutral demo value above. No persistence metadata
     * is exposed.
     */
    public record EmergencyVisitResponse(UUID id, String patientId, String arrivalAt,
                                         String triageLevel, String chiefComplaint, String status) {

        public static EmergencyVisitResponse from(EmergencyVisit v) {
            return new EmergencyVisitResponse(v.getId(), v.getPatientId(), v.getArrivalAt(),
                    v.getTriageLevel(), v.getChiefComplaint(), v.getStatus());
        }
    }

    public record CreateEmergencyVisitRequest(@NotNull UUID patientId,
                                              @NotNull java.time.LocalDateTime arrivalAt,
                                              @NotBlank @Pattern(regexp = TRIAGE_PATTERN) String triageLevel,
                                              @NotBlank String chiefComplaint) {}

    /** The only transition request: the single legal target status. */
    public record UpdateEmergencyVisitStatusRequest(@NotBlank String status) {}
}
