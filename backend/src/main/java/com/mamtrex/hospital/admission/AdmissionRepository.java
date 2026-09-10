package com.mamtrex.hospital.admission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Admission repository. The derived status count backs the read-only
 * dashboard's open-admissions bucket (docs/plan2.md Task 5).
 */
public interface AdmissionRepository extends JpaRepository<Admission, UUID> {

    /** Count of admissions currently in the given lifecycle status. */
    long countByStatus(String status);
}
