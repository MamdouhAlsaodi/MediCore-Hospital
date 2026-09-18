package com.mamtrex.hospital.infrastructure;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-PostgreSQL migration evidence (plan Task 3; FR-002, SC-001).
 *
 * Proves, against a real disposable PostgreSQL database owned by the test
 * process: the accepted schema migrates from empty; a second startup
 * applies zero migrations; and a Phase 3-shaped synthetic rehearsal cohort
 * keeps its allowlisted counts/relationships across a restart. Nothing
 * here is mocked, no row bodies are printed, and no shared or live
 * database is ever touched. The shipped-configuration validate-mode boot
 * against the migrated schema is covered by
 * {@link PostgresProfileContextIntegrationTest}.
 */
class FlywayPostgresIntegrationTest {

    /** Required table inventory of the accepted baseline (V1) plus the Phase 5 hospitals table (V5). */
    private static final List<String> REQUIRED_TABLES = List.of(
            "hospital_organizations", "hospitals", "branches", "departments", "user_accounts",
            "user_account_roles", "acting_assignments", "patients", "staff_members",
            "staff_availability", "beds", "appointments", "admissions",
            "admission_bed_assignments", "emergency_visits", "invoices", "audit_events",
            "blood_units", "clinical_encounters", "diet_orders", "document_records",
            "drugs", "insurance_claims", "inventory_items", "lab_orders",
            "medication_orders", "notifications", "nursing_observations",
            "radiology_orders", "shifts", "surgical_cases", "work_orders");

    /** Required named constraints across baseline + phase constraints + Phase 5 hierarchy. */
    private static final List<String> REQUIRED_CONSTRAINTS = List.of(
            "uk_branches_hospital_code", "uk_hospitals_organization_code",
            "fk_branches_hospital", "ck_acting_assignments_scope_shape",
            "uk_departments_branch_code",
            "uk_user_accounts_username", "uk_hospital_organizations_code",
            "uk_patients_medical_record_number", "uk_invoices_invoice_number",
            "uq_bed_branch_ward_room_number", "uq_assignment_active_admission",
            "uq_assignment_active_bed", "ck_appointments_status",
            "ck_appointments_duration_range", "ck_admissions_status",
            "ck_emergency_visits_status", "ck_invoices_status",
            "ck_beds_occupancy_status");

    /** Required named indexes of the accepted read paths plus the Phase 5 hospital paths. */
    private static final List<String> REQUIRED_INDEXES = List.of(
            "idx_patient_mrn", "idx_appointments_branch_professional",
            "idx_admissions_branch", "idx_emergency_visits_branch", "idx_invoices_branch",
            "idx_audit_events_branch_occurred", "idx_staff_availability_branch_professional",
            "idx_branches_hospital", "idx_acting_assignments_hospital",
            "idx_audit_events_hospital_occurred");

    private static Flyway flywayFor(PostgresContainerSupport.DisposableDatabase db) {
        return Flyway.configure()
                .locations("classpath:db/migration")
                .dataSource(db.jdbcUrl(), db.user(), db.password())
                .load();
    }

    /** FR-002/SC-001: the accepted schema migrates from an empty database. */
    @Test
    void migratesEmptyDatabaseToCurrentVersion() throws Exception {
        var db = PostgresContainerSupport.newIsolatedDatabase();
        MigrateResult result = flywayFor(db).migrate();
        assertEquals(5, result.migrationsExecuted, "V1..V5 must apply to an empty database");
        assertRequiredSchemaObjects(db);
    }

    /** SC-001: a second startup applies zero migrations (restart idempotency). */
    @Test
    void secondStartupAppliesZeroPendingMigrations() {
        var db = PostgresContainerSupport.newIsolatedDatabase();
        MigrateResult first = flywayFor(db).migrate();
        assertEquals(5, first.migrationsExecuted);
        MigrateResult second = flywayFor(db).migrate();
        assertEquals(0, second.migrationsExecuted, "a restart must reapply nothing");
        assertEquals(0, flywayFor(db).info().pending().length, "no pending migrations may remain");
    }

    /**
     * T018 rehearsal: a documented Phase 3-shaped synthetic cohort inserted
     * into the migrated schema keeps allowlisted counts and relationships
     * across a restart, and the restart applies zero migrations. Only
     * allowlisted counts are read — never row bodies.
     */
    @Test
    void phase3RehearsalCohortKeepsInvariantsAcrossRestart() throws Exception {
        var db = PostgresContainerSupport.newIsolatedDatabase();
        Flyway flyway = flywayFor(db);
        flyway.migrate();
        seedRehearsalCohort(db);
        Map<String, Long> before = allowlistedCounts(db);

        MigrateResult restart = flyway.migrate();
        assertEquals(0, restart.migrationsExecuted, "restart must apply zero migrations");
        Map<String, Long> after = allowlistedCounts(db);
        assertEquals(before, after, "allowlisted counts must not change across restart");
        assertRehearsalRelationships(after, db);
    }

    /**
     * T023 rehearsal (legacy path): a Phase 3-shaped cohort written into the
     * V2-era schema (varchar wall-clock values + branch zones) converts to
     * typed timezone-unambiguous values through each branch's own zone, and
     * the audit/lifecycle invariants survive. UTC rows stay identity; a
     * New York row pinned at 12:00 local becomes 16:00Z (summer, EDT).
     */
    @Test
    void phase3LegacyCohortConvertsToTypedValuesThroughBranchZones() throws Exception {
        var db = PostgresContainerSupport.newIsolatedDatabase();
        org.flywaydb.core.Flyway toV2 = org.flywaydb.core.Flyway.configure()
                .locations("classpath:db/migration").target("2")
                .dataSource(db.jdbcUrl(), db.user(), db.password()).load();
        toV2.migrate();
        try (Connection c = connection(db); Statement s = c.createStatement()) {
            // True Phase 3 shape: no time_zone column exists yet; V3
            // backfills the KNOWN demo branch codes deterministically.
            s.execute("insert into hospital_organizations (id, code, name, created_at, updated_at, version) "
                    + "values ('00000000-0000-0000-0002-000000000001', 'DEMO-ORG-001', 'Demo Synthetic Hospital', now(), now(), 0)");
            s.execute("insert into branches (id, organization_id, code, name, location_label, active, created_at, updated_at, version) "
                    + "values ('00000000-0000-0000-0002-000000000002', '00000000-0000-0000-0002-000000000001', 'DEMO-BR-001', 'Demo Main', '1 Demo Campus', true, now(), now(), 0)");
            s.execute("insert into branches (id, organization_id, code, name, location_label, active, created_at, updated_at, version) "
                    + "values ('00000000-0000-0000-0002-000000000003', '00000000-0000-0000-0002-000000000001', 'DEMO-BR-002', 'Demo North', '9 Demo North Road', true, now(), now(), 0)");
            s.execute("insert into patients (id, branch_id, medical_record_number, full_name, active, created_at, updated_at, version) "
                    + "values ('00000000-0000-0000-0002-000000000004', '00000000-0000-0000-0002-000000000002', 'DEMO-MRN-9001', 'Demo Legacy Patient', true, now(), now(), 0)");
            // Legacy wall-clock strings, as the Phase 3 store wrote them.
            s.execute("insert into appointments (id, branch_id, patient_id, professional_id, scheduled_at, duration_minutes, ends_at, type, status, created_at, updated_at, version) "
                    + "values ('00000000-0000-0000-0002-000000000005', '00000000-0000-0000-0002-000000000002', '00000000-0000-0000-0002-000000000004', 'nobody', '2031-06-01T09:30', 30, '2031-06-01T10:00', 'consultation', 'scheduled', now(), now(), 0)");
            s.execute("insert into appointments (id, branch_id, patient_id, professional_id, scheduled_at, duration_minutes, ends_at, type, status, created_at, updated_at, version) "
                    + "values ('00000000-0000-0000-0002-000000000006', '00000000-0000-0000-0002-000000000003', '00000000-0000-0000-0002-000000000004', 'nobody', '2031-06-01T12:00', 30, '2031-06-01T12:30', 'consultation', 'scheduled', now(), now(), 0)");
            s.execute("insert into invoices (id, branch_id, patient_id, invoice_number, amount, currency, status, created_at, updated_at, version) "
                    + "values ('00000000-0000-0000-0002-000000000007', '00000000-0000-0000-0002-000000000002', '00000000-0000-0000-0002-000000000004', 'DEMO-INV-9100', '120.00', 'USD', 'DRAFT', now(), now(), 0)");
        }
        flywayFor(db).migrate(); // applies V3
        try (Connection c = connection(db); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("""
                     select a.id::text, a.scheduled_at, a.ends_at from appointments a
                     order by a.scheduled_at
                     """)) {
            assertTrue(rs.next());
            assertEquals("00000000-0000-0000-0002-000000000005", rs.getString(1), "UTC row sorts first");
            assertEquals(java.time.Instant.parse("2031-06-01T09:30:00Z"), rs.getObject(2, java.time.OffsetDateTime.class).toInstant(),
                    "the UTC branch row converts identically");
            assertEquals(java.time.Instant.parse("2031-06-01T10:00:00Z"), rs.getObject(3, java.time.OffsetDateTime.class).toInstant());
            assertTrue(rs.next());
            assertEquals("00000000-0000-0000-0002-000000000006", rs.getString(1), "NY row second");
            assertEquals(java.time.Instant.parse("2031-06-01T16:00:00Z"), rs.getObject(2, java.time.OffsetDateTime.class).toInstant(),
                    "the New York branch row converts through its own zone (EDT, UTC-4)");
            assertEquals(java.time.Instant.parse("2031-06-01T16:30:00Z"), rs.getObject(3, java.time.OffsetDateTime.class).toInstant());
        }
        try (Connection c = connection(db); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select amount from invoices where invoice_number = 'DEMO-INV-9100'")) {
            assertTrue(rs.next());
            assertEquals(0, new java.math.BigDecimal("120.00").compareTo(rs.getBigDecimal(1)),
                    "the legacy amount becomes the exact numeric value");
        }
        Map<String, Long> counts = allowlistedCounts(db);
        assertEquals(2L, counts.get("appointments"));
        assertEquals(1L, counts.get("invoices"));
    }

    /**
     * Fail-on-unknown: a demonstrated workflow row whose branch is unknown
     * (null ownership) blocks V3 instead of being coerced — the migration
     * fails closed and the row stays untouched.
     */
    @Test
    void v3BlocksOnUnknownLegacyRowsInsteadOfGuessing() throws Exception {
        var db = PostgresContainerSupport.newIsolatedDatabase();
        org.flywaydb.core.Flyway toV2 = org.flywaydb.core.Flyway.configure()
                .locations("classpath:db/migration").target("2")
                .dataSource(db.jdbcUrl(), db.user(), db.password()).load();
        toV2.migrate();
        try (Connection c = connection(db); Statement s = c.createStatement()) {
            s.execute("insert into appointments (id, branch_id, patient_id, professional_id, scheduled_at, type, status, created_at, updated_at, version) "
                    + "values ('00000000-0000-0000-0003-000000000001', null, 'orphan', 'nobody', '2031-06-01T09:30', 'consultation', 'scheduled', now(), now(), 0)");
        }
        var blocked = org.junit.jupiter.api.Assertions.assertThrows(org.flywaydb.core.api.FlywayException.class,
                () -> flywayFor(db).migrate());
        org.junit.jupiter.api.Assertions.assertTrue(blocked.getMessage().contains("never guessed"),
                "the V3 guard must name the unknown-legacy-row refusal: " + blocked.getMessage());
        // The blocked row stays exactly as written; the schema was not corrupted.
        try (Connection c = connection(db); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select count(*) from appointments where scheduled_at = '2031-06-01T09:30'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "the unknown legacy row must remain untouched");
        }
    }

    // ------------------------------------------------------------ helpers

    private void assertRequiredSchemaObjects(PostgresContainerSupport.DisposableDatabase db) throws Exception {
        try (Connection connection = connection(db)) {
            for (String table : REQUIRED_TABLES) {
                assertTrue(tableExists(connection, table), "required table missing: " + table);
            }
            for (String constraint : REQUIRED_CONSTRAINTS) {
                assertTrue(count(connection, "select count(*) from information_schema.table_constraints "
                                + "where constraint_name = ?", constraint) >= 1,
                        "required constraint missing: " + constraint);
            }
            for (String index : REQUIRED_INDEXES) {
                assertTrue(count(connection, "select count(*) from pg_indexes where indexname = ?", index) >= 1,
                        "required index missing: " + index);
            }
            assertTrue(count(connection, "select count(*) from flyway_schema_history where success", null) >= 5,
                    "flyway history must record the applied migrations");
            // Phase 5 (V5): the null-safe assignment uniqueness backstop exists.
            assertTrue(count(connection, "select count(*) from pg_indexes where indexname = "
                    + "'uq_acting_assignments_scope_logical'", null) == 1,
                    "the null-safe assignment uniqueness backstop must exist");
            // Phase 5 (V4): the concurrency backstop indexes exist.
            assertTrue(count(connection, "select count(*) from pg_indexes where indexname = "
                    + "'uq_appointments_active_window'", null) == 1,
                    "the appointment active-window unique backstop must exist");
            assertTrue(count(connection, "select count(*) from pg_indexes where indexname = "
                    + "'uq_availability_branch_staff_window'", null) == 1,
                    "the availability window unique backstop must exist");
            // Phase 4 (V3): branch zones exist and the demonstrated workflow
            // columns are timezone-unambiguous timestamps.
            assertTrue(count(connection, "select count(*) from information_schema.columns "
                    + "where table_name = 'branches' and column_name = 'time_zone'", null) == 1,
                    "branches.time_zone must exist");
            assertTrue(count(connection, "select count(*) from information_schema.columns "
                    + "where table_name = 'appointments' and column_name = 'scheduled_at' "
                    + "and data_type = 'timestamp with time zone'", null) == 1,
                    "appointments.scheduled_at must be timestamptz");
            assertTrue(count(connection, "select count(*) from information_schema.columns "
                    + "where table_name = 'invoices' and column_name = 'amount' "
                    + "and data_type = 'numeric' and numeric_scale = 2", null) == 1,
                    "invoices.amount must be numeric(19,2)");
        }
    }

    static Connection connection(PostgresContainerSupport.DisposableDatabase db) throws SQLException {
        return DriverManager.getConnection(db.jdbcUrl(), db.user(), db.password());
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getTables(null, "public", table, new String[] {"TABLE"})) {
            return rs.next();
        }
    }

    private static long count(Connection connection, String query, String parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            if (parameter != null) {
                statement.setString(1, parameter);
            }
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Deterministic Phase 3-shaped synthetic cohort; synthetic values only. */
    private static void seedRehearsalCohort(PostgresContainerSupport.DisposableDatabase db) throws Exception {
        String orgId = "00000000-0000-0000-0001-000000000001";
        String hospitalId = "00000000-0000-0000-0001-0000000000a1";
        String branchId = "00000000-0000-0000-0001-000000000002";
        String departmentId = "00000000-0000-0000-0001-000000000003";
        String accountId = "00000000-0000-0000-0001-000000000004";
        String assignmentId = "00000000-0000-0000-0001-000000000005";
        String patientA = "00000000-0000-0000-0001-000000000006";
        String patientB = "00000000-0000-0000-0001-000000000007";
        String staffId = "00000000-0000-0000-0001-000000000008";
        String availabilityId = "00000000-0000-0000-0001-000000000009";
        String bedId = "00000000-0000-0000-0001-00000000000a";
        String apptA = "00000000-0000-0000-0001-00000000000b";
        String apptB = "00000000-0000-0000-0001-00000000000c";
        String admissionId = "00000000-0000-0000-0001-00000000000d";
        String bedAssignmentId = "00000000-0000-0000-0001-00000000000e";
        String emergencyId = "00000000-0000-0000-0001-00000000000f";
        String auditId = "00000000-0000-0000-0001-000000000010";

        try (Connection c = connection(db); Statement s = c.createStatement()) {
            String[] statements = {
                "insert into hospital_organizations (id, code, name, created_at, updated_at, version) "
                        + "values ('" + orgId + "'::uuid, 'DEMO-ORG-001', 'Demo Synthetic Hospital', now(), now(), 0)",
                "insert into hospitals (id, organization_id, code, name, region_label, time_zone, active, "
                        + "created_at, updated_at, version) values ('" + hospitalId + "'::uuid, '" + orgId
                        + "'::uuid, 'LEGACY-HOSPITAL-001', 'Legacy Synthetic Hospital', 'Legacy Region', 'UTC', "
                        + "true, now(), now(), 0)",
                "insert into branches (id, organization_id, hospital_id, code, name, location_label, active, created_at, updated_at, version) "
                        + "values ('" + branchId + "'::uuid, '" + orgId + "'::uuid, '" + hospitalId + "'::uuid, 'DEMO-BR-001', 'Demo Main Branch', '1 Demo Campus', true, now(), now(), 0)",
                "insert into departments (id, branch_id, code, name, specialty, location, created_at, updated_at, version) "
                        + "values ('" + departmentId + "'::uuid, '" + branchId + "'::uuid, 'DEMO-DEP-0001', 'Demo Internal Medicine', 'internal medicine', 'Demo Tower A', now(), now(), 0)",
                "insert into user_accounts (id, username, password_hash, enabled, created_at, updated_at, version) "
                        + "values ('" + accountId + "'::uuid, 'demo-review', 'disposable-bcrypt-hash-not-a-credential', true, now(), now(), 0)",
                "insert into user_account_roles (user_account_id, roles) values ('" + accountId + "'::uuid, 'DOCTOR')",
                "insert into acting_assignments (id, account_id, organization_id, hospital_id, branch_id, role, scope, enabled, created_at, updated_at, version) "
                        + "values ('" + assignmentId + "'::uuid, '" + accountId + "'::uuid, '" + orgId + "'::uuid, '"
                        + hospitalId + "'::uuid, '" + branchId + "'::uuid, 'DOCTOR', 'BRANCH', true, now(), now(), 0)",
                "insert into patients (id, branch_id, medical_record_number, full_name, date_of_birth, active, created_at, updated_at, version) "
                        + "values ('" + patientA + "'::uuid, '" + branchId + "'::uuid, 'DEMO-MRN-0001', 'Demo Patient Alpha', '1991-02-03', true, now(), now(), 0)",
                "insert into patients (id, branch_id, medical_record_number, full_name, date_of_birth, active, created_at, updated_at, version) "
                        + "values ('" + patientB + "'::uuid, '" + branchId + "'::uuid, 'DEMO-MRN-0002', 'Demo Patient Bravo', '1992-03-04', true, now(), now(), 0)",
                "insert into staff_members (id, branch_id, employee_code, full_name, profession, department, created_at, updated_at, version) "
                        + "values ('" + staffId + "'::uuid, '" + branchId + "'::uuid, 'DEMO-STAFF-001', 'Demo Professional One', 'doctor', 'Demo Internal Medicine', now(), now(), 0)",
                "insert into staff_availability (id, branch_id, staff_member_id, starts_at, ends_at, created_at, updated_at, version) "
                        + "values ('" + availabilityId + "'::uuid, '" + branchId + "'::uuid, '" + staffId
                        + "'::uuid, timestamp '2031-03-02 09:00:00', timestamp '2031-03-02 17:00:00', now(), now(), 0)",
                "insert into beds (id, branch_id, ward, room, bed_number, occupancy_status, created_at, updated_at, version) "
                        + "values ('" + bedId + "'::uuid, '" + branchId + "'::uuid, 'Demo Ward A', '101', 'A-01', 'OCCUPIED', now(), now(), 0)",
                "insert into appointments (id, branch_id, patient_id, professional_id, scheduled_at, duration_minutes, ends_at, type, status, created_at, updated_at, version) "
                        + "values ('" + apptA + "'::uuid, '" + branchId + "'::uuid, '" + patientA + "'::uuid, '" + staffId
                        + "'::uuid, '2031-03-02T09:30', 30, '2031-03-02T10:00', 'consultation', 'scheduled', now(), now(), 0)",
                "insert into appointments (id, branch_id, patient_id, professional_id, scheduled_at, duration_minutes, ends_at, type, status, created_at, updated_at, version) "
                        + "values ('" + apptB + "'::uuid, '" + branchId + "'::uuid, '" + patientB + "'::uuid, '" + staffId
                        + "'::uuid, '2031-03-02T11:00', 30, '2031-03-02T11:30', 'follow-up', 'confirmed', now(), now(), 0)",
                "insert into admissions (id, branch_id, patient_id, admitted_at, reason, status, created_at, updated_at, version) "
                        + "values ('" + admissionId + "'::uuid, '" + branchId + "'::uuid, '" + patientA
                        + "'::uuid, '2031-01-06T08:30', 'Demo admission intake', 'ADMITTED', now(), now(), 0)",
                "insert into admission_bed_assignments (id, admission_id, bed_id, created_at, updated_at, version) "
                        + "values ('" + bedAssignmentId + "'::uuid, '" + admissionId + "'::uuid, '"
                        + bedId + "'::uuid, now(), now(), 0)",
                "insert into emergency_visits (id, branch_id, patient_id, arrival_at, triage_level, chief_complaint, status, created_at, updated_at, version) "
                        + "values ('" + emergencyId + "'::uuid, '" + branchId + "'::uuid, '" + patientB
                        + "'::uuid, '2031-01-08T12:45', '3', 'Demo emergency intake', 'WAITING', now(), now(), 0)",
                "insert into invoices (id, branch_id, patient_id, invoice_number, amount, currency, status, created_at, updated_at, version) "
                        + "values ('00000000-0000-0000-0001-000000000011', '" + branchId + "'::uuid, '" + patientA
                        + "'::uuid, 'DEMO-INV-9001', '120.00', 'USD', 'DRAFT', now(), now(), 0)",
                "insert into invoices (id, branch_id, patient_id, invoice_number, amount, currency, status, created_at, updated_at, version) "
                        + "values ('00000000-0000-0000-0001-000000000012', '" + branchId + "'::uuid, '" + patientB
                        + "'::uuid, 'DEMO-INV-9002', '80.50', 'USD', 'ISSUED', now(), now(), 0)",
                "insert into audit_events (id, actor, action, resource_type, resource_id, occurred_at, created_at, updated_at, version) "
                        + "values ('" + auditId + "'::uuid, 'system', 'CREATE', 'Admission', '" + admissionId + "', now(), now(), now(), 0)"
            };
            for (String sql : statements) {
                s.execute(sql);
            }
        }
    }

    /** The allowlisted count set only — no row contents are read or printed. */
    static Map<String, Long> allowlistedCounts(PostgresContainerSupport.DisposableDatabase db) throws Exception {
        Map<String, Long> counts = new HashMap<>();
        try (Connection c = connection(db); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("""
                     select 'organizations' as k, count(*) from hospital_organizations
                     union all select 'branches', count(*) from branches
                     union all select 'departments', count(*) from departments
                     union all select 'user_accounts', count(*) from user_accounts
                     union all select 'acting_assignments', count(*) from acting_assignments
                     union all select 'patients', count(*) from patients
                     union all select 'staff_members', count(*) from staff_members
                     union all select 'staff_availability', count(*) from staff_availability
                     union all select 'beds', count(*) from beds
                     union all select 'appointments', count(*) from appointments
                     union all select 'admissions', count(*) from admissions
                     union all select 'admission_bed_assignments', count(*) from admission_bed_assignments
                     union all select 'emergency_visits', count(*) from emergency_visits
                     union all select 'invoices', count(*) from invoices
                     union all select 'audit_events', count(*) from audit_events
                     """)) {
            while (rs.next()) {
                counts.put(rs.getString(1), rs.getLong(2));
            }
        }
        return counts;
    }

    /** Lifecycle/relationship invariants over the rehearsal cohort (counts only). */
    private static void assertRehearsalRelationships(Map<String, Long> counts,
                                                     PostgresContainerSupport.DisposableDatabase db) throws Exception {
        assertEquals(1L, counts.get("organizations"));
        assertEquals(1L, counts.get("branches"));
        assertEquals(2L, counts.get("patients"));
        assertEquals(2L, counts.get("appointments"));
        assertEquals(1L, counts.get("admissions"));
        assertEquals(1L, counts.get("admission_bed_assignments"), "exactly one live bed assignment");
        assertEquals(1L, counts.get("emergency_visits"));
        assertEquals(2L, counts.get("invoices"));
        List<String> failed = new ArrayList<>();
        try (Connection c = connection(db); Statement s = c.createStatement()) {
            check(failed, s, "orphan appointments",
                    "select count(*) from appointments a left join branches b on a.branch_id = b.id where b.id is null", 0);
            check(failed, s, "orphan admissions",
                    "select count(*) from admissions a left join branches b on a.branch_id = b.id where b.id is null", 0);
            check(failed, s, "assignment without occupied bed",
                    "select count(*) from admission_bed_assignments g join beds b on b.id = g.bed_id "
                            + "where b.occupancy_status <> 'OCCUPIED'", 0);
            check(failed, s, "admitted admission without bed assignment",
                    "select count(*) from admissions a where a.status = 'ADMITTED' and not exists "
                            + "(select 1 from admission_bed_assignments g where g.admission_id = a.id)", 0);
            check(failed, s, "duplicate mrn",
                    "select count(*) from (select medical_record_number from patients "
                            + "group by medical_record_number having count(*) > 1) d", 0);
            check(failed, s, "duplicate invoice number",
                    "select count(*) from (select invoice_number from invoices "
                            + "group by invoice_number having count(*) > 1) d", 0);
        }
        assertTrue(failed.isEmpty(), "rehearsal relationship invariants failed: " + failed);
    }

    private static void check(List<String> failed, Statement s, String name, String query, long expected) throws SQLException {
        try (ResultSet rs = s.executeQuery(query)) {
            rs.next();
            long actual = rs.getLong(1);
            if (actual != expected) {
                failed.add(name + "=" + actual);
            }
        }
    }
}
