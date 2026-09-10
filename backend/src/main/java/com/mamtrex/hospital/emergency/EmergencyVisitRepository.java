package com.mamtrex.hospital.emergency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Emergency-visit repository. The derived status count backs the read-only
 * dashboard's active-visit bucket (docs/plan2.md Task 5).
 */
public interface EmergencyVisitRepository extends JpaRepository<EmergencyVisit, UUID> {

    /** Count of visits currently in the given lifecycle status. */
    long countByStatus(String status);
}
