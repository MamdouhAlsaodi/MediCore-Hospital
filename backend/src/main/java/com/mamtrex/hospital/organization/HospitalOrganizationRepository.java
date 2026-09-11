package com.mamtrex.hospital.organization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Narrow persistence surface for the single hospital organization
 * (docs/plan3.md Task 2): lookup by the stable immutable business key used
 * by the opt-in demo initializer.
 */
public interface HospitalOrganizationRepository extends JpaRepository<HospitalOrganization, UUID> {

    Optional<HospitalOrganization> findByCode(String code);
}
