package com.mamtrex.hospital.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5 T011 RED-first entity/constraint tests for the new
 * {@link HospitalFacility} aggregate (specs/005 data-model.md): a hospital
 * facility belongs to exactly one {@link HospitalOrganization} (the one
 * synthetic network), carries a bounded code unique inside its organization
 * (the DB {@code (organization_id, code)} constraint is the backstop, pinned
 * by name in {@code V5__hospital_network_hierarchy.sql} and asserted at the
 * PostgreSQL level by {@code Phase5MigrationIntegrationTest}), carries a
 * validated IANA time zone (an invalid zone is inexpressible — construction
 * fails closed), is active by default, and keeps a synthetic display region
 * label. The human code is an identifier only and is never authorization
 * evidence. Pure unit assertions run without a database; the deterministic
 * repository queries run against an isolated in-memory H2 context.
 */
class HospitalFacilityTest {

    private static HospitalOrganization org(String code) {
        return new HospitalOrganization(code, "Synthetic Network " + code);
    }

    // ------------------------------------------------- entity behavior

    @Test
    void hospitalKeepsCodeNameRegionZoneAndOrganizationOwnership() {
        HospitalOrganization network = org("FAC-ORG-1");
        HospitalFacility hospital = new HospitalFacility(
                network, "HOSP-001", "Synthetic Legacy Hospital", "Synthetic Region", "UTC");
        assertEquals("HOSP-001", hospital.getCode());
        assertEquals("Synthetic Legacy Hospital", hospital.getName());
        assertEquals("Synthetic Region", hospital.getRegionLabel());
        assertEquals(java.time.ZoneId.of("UTC"), hospital.getTimeZone());
        assertEquals(network, hospital.getOrganization(),
                "the hospital must keep its network ownership reference");
    }

    @Test
    void hospitalRequiresOrganizationOwnership() {
        assertThrows(NullPointerException.class,
                () -> new HospitalFacility(null, "HOSP-002", "Name", "Region", "UTC"),
                "a hospital without network ownership must be inexpressible");
    }

    @Test
    void hospitalValidatesIanaZoneAtConstruction() {
        assertThrows(RuntimeException.class,
                () -> new HospitalFacility(org("FAC-ORG-2"), "HOSP-003", "Name", "Region", "Not/AZone"),
                "an invalid IANA zone must fail closed at construction, never be stored raw");
    }

    @Test
    void hospitalRequiresEveryDescribedField() {
        HospitalOrganization network = org("FAC-ORG-3");
        assertThrows(NullPointerException.class,
                () -> new HospitalFacility(network, null, "Name", "Region", "UTC"), "code required");
        assertThrows(NullPointerException.class,
                () -> new HospitalFacility(network, "HOSP-004", null, "Region", "UTC"), "name required");
        assertThrows(NullPointerException.class,
                () -> new HospitalFacility(network, "HOSP-004", "Name", null, "UTC"), "region required");
        assertThrows(NullPointerException.class,
                () -> new HospitalFacility(network, "HOSP-004", "Name", "Region", null), "zone required");
    }

    @Test
    void hospitalIsActiveByDefaultAndDeactivatesThroughTheLifecycleSeam() {
        HospitalFacility hospital = new HospitalFacility(
                org("FAC-ORG-4"), "HOSP-005", "Name", "Region", "UTC");
        assertTrue(hospital.isActive(), "a hospital must be active by default");
        hospital.deactivate();
        assertFalse(hospital.isActive(), "the lifecycle seam must deactivate the hospital");
    }

    /**
     * The exact data-model column metadata: bounded code (32), bounded
     * display columns (160), bounded IANA zone (60), and the named
     * (organization_id, code) uniqueness the V5 constraint mirrors.
     */
    @Test
    void hospitalColumnMetadataMatchesTheDataModel() throws Exception {
        Table table = HospitalFacility.class.getAnnotation(Table.class);
        assertNotNull(table);
        assertEquals("hospitals", table.name());
        UniqueConstraint[] uniqueness = table.uniqueConstraints();
        assertEquals(1, uniqueness.length, "exactly one declared unique constraint");
        assertEquals("uk_hospitals_organization_code", uniqueness[0].name());
        assertArrayEquals(new String[] {"organization_id", "code"}, uniqueness[0].columnNames());

        assertEquals(32, column(HospitalFacility.class, "code").length(), "code is varchar(32)");
        assertEquals(160, column(HospitalFacility.class, "name").length(), "name is varchar(160)");
        assertEquals(160, column(HospitalFacility.class, "regionLabel").length(),
                "region_label is varchar(160)");
        assertEquals(60, column(HospitalFacility.class, "timeZone").length(),
                "time_zone is varchar(60)");
        assertTrue(column(HospitalFacility.class, "code").nullable() == false, "code not null");
        assertTrue(column(HospitalFacility.class, "timeZone").nullable() == false, "zone not null");
    }

    private static Column column(Class<?> type, String field) throws Exception {
        Field declared = type.getDeclaredField(field);
        Column annotation = declared.getAnnotation(Column.class);
        assertNotNull(annotation, "missing @Column on " + field);
        return annotation;
    }
}
