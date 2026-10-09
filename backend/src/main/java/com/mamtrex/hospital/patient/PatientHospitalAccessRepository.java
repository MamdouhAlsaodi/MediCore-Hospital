package com.mamtrex.hospital.patient;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Scoped access-grant queries (Phase 5 US3, T067). Visibility authority is
 * the ACTIVE grant row, never patient existence; every scoped patient read
 * in the network flows through {@code existsBy...ACTIVE} here or through
 * the equivalent exists-subqueries in {@link PatientRepository}.
 */
public interface PatientHospitalAccessRepository extends JpaRepository<PatientHospitalAccess, UUID> {

    /** The grant between one patient and one hospital, whatever its status. */
    Optional<PatientHospitalAccess> findByPatientIdAndHospitalId(UUID patientId, UUID hospitalId);

    /** The visibility predicate: an ACTIVE grant for this hospital. */
    boolean existsByPatientIdAndHospitalIdAndStatus(UUID patientId, UUID hospitalId, PatientAccessStatus status);
}
