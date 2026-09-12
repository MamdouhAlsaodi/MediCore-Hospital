package com.mamtrex.hospital.admission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Live-assignment reads for the admission bed lifecycle (docs/plan3.md
 * Task 7). A row exists only while its admission actively holds a bed, so
 * the entity's {@code (admissionId)} and {@code (bedId)} DB unique
 * constraints are the concurrency backstop behind the service pre-checks:
 * a racing second assignment for the same admission or the same bed fails
 * at flush with a constraint violation instead of persisting.
 */
public interface AdmissionBedAssignmentRepository extends JpaRepository<AdmissionBedAssignment, UUID> {

    /** The admission's single live assignment; empty while it holds no bed. */
    Optional<AdmissionBedAssignment> findByAdmissionId(UUID admissionId);

    /** Batch form for list-response assembly; never exposed through any HTTP route. */
    List<AdmissionBedAssignment> findByAdmissionIdIn(Collection<UUID> admissionIds);
}
