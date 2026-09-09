package com.mamtrex.hospital.patient;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Public Patient Journey contract for /api/patients (docs/plan1.md Task 3).
 * Response records are immutable and deliberately exclude persistence
 * internals (version, createdAt, updatedAt) so no mutable JPA entity and no
 * storage metadata ever reaches the public JSON.
 */
public final class PatientDtos {

    private PatientDtos() {}

    /** Stable public patient representation returned by every patient route. */
    public record PatientResponse(UUID id, String medicalRecordNumber, String fullName, LocalDate dateOfBirth,
                                  String sex, String phone, String email, String nationalId, String address,
                                  boolean active) {

        public static PatientResponse from(Patient p) {
            return new PatientResponse(p.getId(), p.getMedicalRecordNumber(), p.getFullName(), p.getDateOfBirth(),
                    p.getSex(), p.getPhone(), p.getEmail(), p.getNationalId(), p.getAddress(), p.isActive());
        }
    }

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
