package com.mamtrex.hospital.organization;

/**
 * Shared test fixture seam for the Phase 5 hospital level: test cohorts
 * created before the hierarchy existed keep their single-facility shape by
 * resolving (lookup-before-create) one deterministic fixture hospital per
 * test organization. Production code never uses this class.
 */
public final class FixtureHospitals {

    /** Stable synthetic fixture business key (unique per organization by the V5 constraint). */
    public static final String FIXTURE_HOSPITAL_CODE = "FIXTURE-HOSP-001";

    private FixtureHospitals() {
    }

    /** The organization's fixture hospital, created once and reused untouched. */
    public static HospitalFacility ensureHospital(HospitalFacilityRepository hospitals,
                                                  HospitalOrganization organization) {
        return hospitals.findByOrganizationIdAndCode(organization.getId(), FIXTURE_HOSPITAL_CODE)
                .orElseGet(() -> hospitals.save(new HospitalFacility(
                        organization, FIXTURE_HOSPITAL_CODE, "Synthetic Fixture Hospital",
                        "Fixture Region", "UTC")));
    }
}
