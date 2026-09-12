package com.mamtrex.hospital.admission;

import com.mamtrex.hospital.bed.Bed;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Public admission contract for /api/admissions (docs/plan2.md Task 2,
 * extended by docs/plan3.md Task 7). patientId is a typed UUID reference
 * resolved by {@link AdmissionService} against the patient repository; a
 * malformed non-UUID body value fails deserialization with 400 via the
 * shared GlobalExceptionHandler malformed-body mapping, and an unresolvable
 * or cross-branch reference returns the shared 404. admittedAt is a typed
 * ISO value stored as its canonical string, following the appointment
 * precedent (no destructive column migration). The create request
 * deliberately carries NO status or dischargedAt field: the server owns the
 * lifecycle and sets status=ADMITTED, and only the server stamps the
 * discharge time. The transition request carries the single legal target
 * status; anything an admission cannot transition to is refused by the
 * service with the shared 409, never accepted verbatim.
 *
 * <p>Task 7 additions: the create request may carry one optional bedId —
 * the server verifies the patient and that bed inside the acting branch and
 * the bed's AVAILABLE state before occupying it — and every response
 * exposes the allowlisted branchId plus a currentBed summary. The response
 * is a strict allowlist: no assignment persistence metadata (no assignment
 * row, version, or timestamps) and never a raw entity.</p>
 */
public final class AdmissionDtos {

    private AdmissionDtos() {}

    /**
     * Stable public admission representation. patientId is the canonical
     * UUID string of the verified patient; status is the server-owned
     * lifecycle state ADMITTED | DISCHARGED; dischargedAt is null until the
     * server stamps it at discharge. branchId is the admission's branch
     * ownership, stamped by the server from the authenticated acting branch
     * at creation (docs/plan3.md Task 7A) — never client input, and null
     * only on legacy transitional rows, which stay invisible through
     * branch-scoped reads. currentBed is the allowlisted summary of the bed
     * the admission currently holds, or null while it holds none. No
     * persistence metadata is exposed.
     */
    public record AdmissionResponse(UUID id, UUID branchId, String patientId, String admittedAt,
                                    String dischargedAt, String reason, String status,
                                    CurrentBed currentBed) {

        public static AdmissionResponse from(Admission a, UUID branchId, CurrentBed currentBed) {
            return new AdmissionResponse(a.getId(), branchId, a.getPatientId(), a.getAdmittedAt(),
                    a.getDischargedAt(), a.getReason(), a.getStatus(), currentBed);
        }
    }

    /**
     * Allowlisted current-bed summary: the held bed's identity and location
     * only. Occupancy state is intentionally absent (a bed in this summary
     * is by definition OCCUPIED) and no assignment metadata appears.
     */
    public record CurrentBed(UUID bedId, String ward, String room, String bedNumber) {

        public static CurrentBed from(Bed bed) {
            return new CurrentBed(bed.getId(), bed.getWard(), bed.getRoom(), bed.getBedNumber());
        }
    }

    /**
     * Create input: a verified patientId, typed ISO admittedAt, non-blank
     * reason, and one optional bedId to occupy at creation. bedId is the
     * only optional field; unknown extra client fields are ignored and no
     * client input reaches status, dischargedAt, or branch ownership.
     */
    public record CreateAdmissionRequest(@NotNull UUID patientId,
                                         @NotNull java.time.LocalDateTime admittedAt,
                                         @NotBlank String reason,
                                         UUID bedId) {}

    /**
     * The PUT /api/admissions/{id}/bed request (docs/plan3.md Task 7): the
     * same command performs the initial assignment and every later atomic
     * transfer; the service refuses an unavailable or repeated target with
     * the shared 409.
     */
    public record AssignBedRequest(@NotNull UUID bedId) {}

    /** The only discharge request: the sole transition target in the map. */
    public record UpdateAdmissionStatusRequest(@NotBlank String status) {}
}
