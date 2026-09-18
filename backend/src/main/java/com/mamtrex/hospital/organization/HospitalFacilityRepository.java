package com.mamtrex.hospital.organization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Narrow persistence surface for hospital facilities (Phase 5 T013): the
 * per-organization code lookup behind duplicate pre-checks, the
 * deterministic active-hospital views behind acting-context resolution, the
 * ancestor-filtered read that refuses foreign organizations, and the
 * deterministic full review list. Ordering lives in the derived queries so
 * every read is stable. No query trusts a human code as authorization
 * evidence — every finder is scoped by the network organization id.
 */
public interface HospitalFacilityRepository extends JpaRepository<HospitalFacility, UUID> {

    Optional<HospitalFacility> findByOrganizationIdAndCode(UUID organizationId, String code);

    List<HospitalFacility> findByOrganizationIdAndActiveTrueOrderByCodeAsc(UUID organizationId);

    /** The deterministic first active hospital of one organization (acting-hospital default). */
    Optional<HospitalFacility> findFirstByOrganizationIdAndActiveTrueOrderByCodeAsc(UUID organizationId);

    /** Ancestor-filtered read: a hospital of another organization is simply absent. */
    Optional<HospitalFacility> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<HospitalFacility> findAllByOrderByCodeAscIdAsc();
}
