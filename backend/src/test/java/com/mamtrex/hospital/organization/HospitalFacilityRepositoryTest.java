package com.mamtrex.hospital.organization;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 T013 repository determinism evidence for
 * {@link HospitalFacilityRepository}: every query is organization-scoped,
 * deterministic in order, and active-filtered where documented. Runs against
 * an isolated in-memory H2 context; the PostgreSQL constraint/backstop
 * evidence for the same table lives in
 * {@code infrastructure.Phase5MigrationIntegrationTest}.
 */
@DataJpaTest
class HospitalFacilityRepositoryTest {

    @Autowired
    HospitalFacilityRepository hospitals;

    @Autowired
    TestEntityManager em;

    @Test
    void queriesAreOrganizationScopedDeterministicAndActiveFiltered() {
        HospitalOrganization netA = em.persistAndFlush(new HospitalOrganization("RQ-ORG-A", "Network A"));
        HospitalOrganization netB = em.persistAndFlush(new HospitalOrganization("RQ-ORG-B", "Network B"));

        HospitalFacility aSecond = new HospitalFacility(netA, "HOSP-B", "B", "Region", "UTC");
        HospitalFacility aFirst = new HospitalFacility(netA, "HOSP-A", "A", "Region", "UTC");
        HospitalFacility bSameCode = new HospitalFacility(netB, "HOSP-A", "A other network", "Region", "UTC");
        HospitalFacility aInactive = new HospitalFacility(netA, "HOSP-C", "C", "Region", "UTC");
        aInactive.deactivate();
        em.persist(aSecond);
        em.persist(aFirst);
        em.persist(bSameCode);
        em.persist(aInactive);
        em.flush();

        assertEquals(List.of("HOSP-A", "HOSP-B"),
                hospitals.findByOrganizationIdAndActiveTrueOrderByCodeAsc(netA.getId())
                        .stream().map(HospitalFacility::getCode).toList(),
                "active hospitals of one network in deterministic code order");
        assertEquals("HOSP-A",
                hospitals.findFirstByOrganizationIdAndActiveTrueOrderByCodeAsc(netA.getId())
                        .orElseThrow().getCode(),
                "the deterministic acting-hospital selection is stable");
        assertEquals(bSameCode.getId(),
                hospitals.findByOrganizationIdAndCode(netB.getId(), "HOSP-A").orElseThrow().getId(),
                "the same code resolves inside its own organization only");
        assertTrue(hospitals.findByOrganizationIdAndCode(netA.getId(), "HOSP-A").isPresent());
        assertTrue(hospitals.findByOrganizationIdAndCode(netA.getId(), "MISSING").isEmpty(),
                "unknown codes are simply absent");
        assertTrue(hospitals.findByIdAndOrganizationId(aFirst.getId(), netB.getId()).isEmpty(),
                "an ancestor filter must refuse a foreign organization");
        assertEquals(aFirst.getId(),
                hospitals.findByIdAndOrganizationId(aFirst.getId(), netA.getId()).orElseThrow().getId());
        assertTrue(hospitals.findAllByOrderByCodeAscIdAsc().size() >= 3,
                "the deterministic review list covers every hospital");
    }
}
