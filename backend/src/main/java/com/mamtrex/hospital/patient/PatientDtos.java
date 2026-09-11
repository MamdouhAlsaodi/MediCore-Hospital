package com.mamtrex.hospital.patient;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Public Patient Journey contract for /api/patients (docs/plan1.md Task 3,
 * docs/plan3.md Task 4). Response records are immutable and deliberately
 * exclude persistence internals (version, createdAt, updatedAt) so no
 * mutable JPA entity and no storage metadata ever reaches the public JSON.
 * Since Task 4 every patient is branch-owned: the response exposes the
 * owning branch id, while the create request accepts no branch input —
 * ownership derives from the acting context alone.
 */
public final class PatientDtos {

    private PatientDtos() {}

    /**
     * Stable public patient representation returned by every patient route.
     * {@code branchId} is the owning branch; it is null only on unassigned
     * legacy rows, which branch-scoped endpoints never disclose.
     */
    public record PatientResponse(UUID id, UUID branchId, String medicalRecordNumber, String fullName, LocalDate dateOfBirth,
                                  String sex, String phone, String email, String nationalId, String address,
                                  boolean active) {

        public static PatientResponse from(Patient p) {
            return new PatientResponse(p.getId(), p.getBranch() == null ? null : p.getBranch().getId(),
                    p.getMedicalRecordNumber(), p.getFullName(), p.getDateOfBirth(),
                    p.getSex(), p.getPhone(), p.getEmail(), p.getNationalId(), p.getAddress(), p.isActive());
        }
    }

    /** Create input carries no branch field: ownership is server-derived (docs/plan3.md Task 4). */
    public record CreatePatientRequest(@NotBlank String medicalRecordNumber,
                                       @NotBlank String fullName,
                                       LocalDate dateOfBirth,
                                       String sex,
                                       String phone,
                                       @Email String email,
                                       String nationalId,
                                       String address) {}

    public record UpdatePatientRequest(@NotBlank String fullName,
                                       String phone,
                                       @Email String email,
                                       String address) {}
}
