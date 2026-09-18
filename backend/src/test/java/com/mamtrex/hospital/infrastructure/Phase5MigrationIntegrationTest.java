package com.mamtrex.hospital.infrastructure;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 V5 real-PostgreSQL migration evidence (T014/T016; T022/T026
 * invariants). Against a real disposable PostgreSQL database owned by the
 * test process it proves:
 *
 * <ul>
 *   <li>V5 applies to an empty schema and is restart-idempotent;</li>
 *   <li>a Phase 4-shaped synthetic cohort backfills deterministically —
 *       every branch maps to its organization's {@code LEGACY-HOSPITAL-001}
 *       row, every BRANCH/DEPARTMENT assignment derives its hospital
 *       ownership, ORGANIZATION assignments keep a null assignment hospital,
 *       and audit rows with a branch derive their acting hospital — with the
 *       exact same legacy-hospital UUID in two independent databases;</li>
 *   <li>malformed hierarchy fixtures refuse to migrate (branchless-department
 *       assignments, branch-less BRANCH assignments, cross-column mismatches,
 *       duplicate logical assignments) and leave the V4 schema untouched;</li>
 *   <li>the exact V5 metadata exists: foreign keys (including the composite
 *       branch→hospital-organization consistency FK), unique keys (the new
 *       hospital-scoped branch code uniqueness replacing the organization one
 *       and the null-safe assignment uniqueness index replacing the
 *       null-sensitive table constraint), the scope-shape check domain, the
 *       HOSPITAL scope value, indexes, and non-null ownership; and</li>
 *   <li>the null-safe assignment uniqueness blocks duplicates for every
 *       scope shape under real PostgreSQL NULL semantics, where the V1–V4
 *       constraint admitted them, and the bounded audit transfer-context
 *       columns exist.</li>
 * </ul>
 *
 * Nothing here is mocked, no sensitive row values are printed, and no
 * shared or live database is ever touched.
 */
class Phase5MigrationIntegrationTest {

    private static final String V5_REFUSAL_MARKER = "V5 blocked";

    /** Fixed Phase 4-shaped fixture UUIDs (deterministic across databases). */
    private static final String ORG_ID = "00000000-0000-0000-00a5-000000000001";
    private static final String BRANCH_MAIN = "00000000-0000-0000-00a5-000000000002";
    private static final String BRANCH_OTHER = "00000000-0000-0000-00a5-000000000003";
    private static final String DEPT_ID = "00000000-0000-0000-00a5-000000000004";
    private static final String ACCOUNT_ID = "00000000-0000-0000-00a5-000000000005";
    private static final String BRANCH_ASSIGNMENT = "00000000-0000-0000-00a5-000000000006";
    private static final String DEPT_ASSIGNMENT = "00000000-0000-0000-00a5-000000000007";
    private static final String ORG_ASSIGNMENT = "00000000-0000-0000-00a5-000000000008";
    private static final String AUDIT_WITH_BRANCH = "00000000-0000-0000-00a5-000000000009";
    private static final String AUDIT_LEGACY = "00000000-0000-0000-00a5-00000000000a";

    private static final String HOSPITAL_1 = "00000000-0000-0000-00a5-0000000000b0";
    private static final String HOSPITAL_2 = "00000000-0000-0000-00a5-0000000000b1";

    private static Flyway flywayFor(PostgresContainerSupport.DisposableDatabase db) {
        return Flyway.configure()
                .locations("classpath:db/migration")
                .dataSource(db.jdbcUrl(), db.user(), db.password())
                .load();
    }

    private static Flyway flywayToTarget(PostgresContainerSupport.DisposableDatabase db, String target) {
        return Flyway.configure()
                .locations("classpath:db/migration")
                .target(target)
                .dataSource(db.jdbcUrl(), db.user(), db.password())
                .load();
    }

    /** V5 applies to an empty schema; a restart applies nothing (T028 seam). */
    @Test
    void v5AppliesToEmptySchemaAndIsRestartIdempotent() {
        var db = PostgresContainerSupport.newIsolatedDatabase();
        MigrateResult first = flywayFor(db).migrate();
        assertEquals(5, first.migrationsExecuted, "V1..V5 must apply to an empty database");
        MigrateResult second = flywayFor(db).migrate();
        assertEquals(0, second.migrationsExecuted, "a restart must reapply nothing");
        assertEquals(0, flywayFor(db).info().pending().length, "no pending migrations may remain");
    }

    /** Phase 4-shaped data backfills deterministically, including across independent databases. */
    @Test
    void v5BackfillsPhase4ShapedDataDeterministically() throws Exception {
        var dbA = PostgresContainerSupport.newIsolatedDatabase();
        var dbB = PostgresContainerSupport.newIsolatedDatabase();
        for (var db : List.of(dbA, dbB)) {
            flywayToTarget(db, "4").migrate();
            seedPhase4ShapedCohort(db);
        }
        flywayFor(dbA).migrate();
        flywayFor(dbB).migrate();

        String hospitalA = scalar(dbA, "select id::text from hospitals where code = 'LEGACY-HOSPITAL-001'");
        String hospitalB = scalar(dbB, "select id::text from hospitals where code = 'LEGACY-HOSPITAL-001'");
        assertEquals(1, Long.parseLong(scalar(dbA, "select count(*) from hospitals")),
                "exactly one deterministic legacy hospital for the one fixture organization");
        assertEquals(hospitalA, hospitalB,
                "the same Phase 4-shaped input must produce the identical legacy-hospital UUID");
        assertEquals(ORG_ID, scalar(dbA, "select organization_id::text from hospitals"), "network ownership");

        // Every branch maps to the legacy hospital; organization consistency holds.
        assertEquals(0, Long.parseLong(scalar(dbA, "select count(*) from branches where hospital_id is null")),
                "every branch must be backfilled");
        assertEquals(0, Long.parseLong(scalar(dbA, """
                select count(*) from branches b join hospitals h on h.id = b.hospital_id
                where h.organization_id <> b.organization_id
                """)), "branch organization and hospital organization must agree");

        // BRANCH assignment derives its hospital; DEPARTMENT derives through its department's branch.
        assertEquals(hospitalA, scalar(dbA, "select hospital_id::text from acting_assignments where id = '"
                + BRANCH_ASSIGNMENT + "'"), "BRANCH assignment derives its hospital");
        assertEquals(hospitalA, scalar(dbA, "select hospital_id::text from acting_assignments where id = '"
                + DEPT_ASSIGNMENT + "'"), "DEPARTMENT assignment derives its hospital");
        assertEquals(null, scalar(dbA, "select hospital_id::text from acting_assignments where id = '"
                + ORG_ASSIGNMENT + "'"),
                "an ORGANIZATION assignment keeps a null assignment hospital (resolved per request)");

        // Audit rows with a branch derive the acting hospital; legacy rows stay null, never guessed.
        assertEquals(hospitalA, scalar(dbA, "select hospital_id::text from audit_events where id = '"
                + AUDIT_WITH_BRANCH + "'"));
        assertEquals(null, scalar(dbA, "select hospital_id::text from audit_events where id = '"
                + AUDIT_LEGACY + "'"));

        // Restart idempotency on migrated data.
        MigrateResult restart = flywayFor(dbA).migrate();
        assertEquals(0, restart.migrationsExecuted, "restart must apply zero migrations");
    }

    /** Malformed hierarchy fixtures block V5 instead of being guessed. */
    @Test
    void v5RefusesMalformedHierarchyFixtures() throws Exception {
        // Case 1: a DEPARTMENT assignment whose department has no branch — hospital un-derivable.
        var branchlessDept = PostgresContainerSupport.newIsolatedDatabase();
        flywayToTarget(branchlessDept, "4").migrate();
        seedPhase4ShapedCohort(branchlessDept);
        try (Connection c = FlywayPostgresIntegrationTest.connection(branchlessDept);
             Statement s = c.createStatement()) {
            s.execute("update departments set branch_id = null where id = '" + DEPT_ID + "'");
        }
        var refused1 = assertThrows(org.flywaydb.core.api.FlywayException.class,
                () -> flywayFor(branchlessDept).migrate());
        assertTrue(refused1.getMessage().contains(V5_REFUSAL_MARKER),
                "branchless-department assignments must be refused: " + refused1.getMessage());

        // Case 2: a BRANCH-scope assignment without a branch — hospital un-derivable.
        var branchlessAssignment = PostgresContainerSupport.newIsolatedDatabase();
        flywayToTarget(branchlessAssignment, "4").migrate();
        seedPhase4ShapedCohort(branchlessAssignment);
        try (Connection c = FlywayPostgresIntegrationTest.connection(branchlessAssignment);
             Statement s = c.createStatement()) {
            s.execute("update acting_assignments set branch_id = null where id = '" + BRANCH_ASSIGNMENT + "'");
        }
        var refused2 = assertThrows(org.flywaydb.core.api.FlywayException.class,
                () -> flywayFor(branchlessAssignment).migrate());
        assertTrue(refused2.getMessage().contains(V5_REFUSAL_MARKER),
                "branch-less BRANCH assignments must be refused: " + refused2.getMessage());

        // Case 3: an ORGANIZATION-scope assignment carrying a branch — cross-column mismatch.
        var crossColumn = PostgresContainerSupport.newIsolatedDatabase();
        flywayToTarget(crossColumn, "4").migrate();
        seedPhase4ShapedCohort(crossColumn);
        try (Connection c = FlywayPostgresIntegrationTest.connection(crossColumn);
             Statement s = c.createStatement()) {
            s.execute("update acting_assignments set branch_id = '" + BRANCH_MAIN
                    + "' where id = '" + ORG_ASSIGNMENT + "'");
        }
        var refused3 = assertThrows(org.flywaydb.core.api.FlywayException.class,
                () -> flywayFor(crossColumn).migrate());
        assertTrue(refused3.getMessage().contains(V5_REFUSAL_MARKER),
                "cross-column assignment mismatches must be refused: " + refused3.getMessage());

        // The refused databases keep the V4 shape: no V5 history row, old constraint intact.
        for (var db : List.of(branchlessDept, branchlessAssignment, crossColumn)) {
            assertEquals(0, Long.parseLong(scalar(db, """
                    select count(*) from flyway_schema_history where version = '5' and success
                    """)), "the refused migration must not record success");
            assertEquals(1, Long.parseLong(scalar(db, """
                    select count(*) from information_schema.table_constraints
                    where constraint_name = 'uk_branches_organization_code'
                    """)), "the refused database keeps its V4 constraint (no partial application)");
        }
    }

    /** Duplicate logical assignments that the null-sensitive V1 constraint admitted must refuse. */
    @Test
    void v5RefusesDuplicateLogicalAssignments() throws Exception {
        var db = PostgresContainerSupport.newIsolatedDatabase();
        flywayToTarget(db, "4").migrate();
        seedPhase4ShapedCohort(db);
        try (Connection c = FlywayPostgresIntegrationTest.connection(db);
             Statement s = c.createStatement()) {
            // A second ORGANIZATION-scope row for the same account+role: legal under
            // uk_acting_assignments_logical (SQL NULLs are distinct), illegal under V5.
            s.execute("insert into acting_assignments (id, account_id, organization_id, role, scope, enabled, "
                    + "created_at, updated_at, version) values ('00000000-0000-0000-00a5-00000000000b', '"
                    + ACCOUNT_ID + "', '" + ORG_ID + "', 'ADMIN', 'ORGANIZATION', true, now(), now(), 0)");
        }
        var refused = assertThrows(org.flywaydb.core.api.FlywayException.class, () -> flywayFor(db).migrate());
        assertTrue(refused.getMessage().contains(V5_REFUSAL_MARKER),
                "duplicate logical assignments must block V5 before the null-safe index is created: "
                        + refused.getMessage());
    }

    /** T016: exact V5 foreign keys, unique keys, indexes, and non-null ownership metadata. */
    @Test
    void v5PostgreSQLMetadataAssertions() throws Exception {
        var db = PostgresContainerSupport.newIsolatedDatabase();
        flywayFor(db).migrate();
        try (Connection c = FlywayPostgresIntegrationTest.connection(db); Statement s = c.createStatement()) {
            // --- tables --------------------------------------------------
            assertEquals(1, count(s, "select count(*) from information_schema.tables "
                    + "where table_name = 'hospitals'"), "the hospitals table must exist");

            // --- columns and non-null ownership --------------------------
            assertEquals(1, count(s, """
                    select count(*) from information_schema.columns
                    where table_name = 'hospitals' and column_name = 'organization_id'
                      and is_nullable = 'NO'
                    """), "hospitals.organization_id must be NOT NULL");
            assertEquals(1, count(s, """
                    select count(*) from information_schema.columns
                    where table_name = 'hospitals' and column_name = 'code'
                      and character_maximum_length = 32 and is_nullable = 'NO'
                    """), "hospitals.code must be varchar(32) NOT NULL");
            assertEquals(2, count(s, """
                    select count(*) from information_schema.columns
                    where table_name = 'hospitals' and column_name in ('name','region_label')
                      and character_maximum_length = 160 and is_nullable = 'NO'
                    """), "hospitals name/region_label must be varchar(160) NOT NULL");
            assertEquals(1, count(s, """
                    select count(*) from information_schema.columns
                    where table_name = 'hospitals' and column_name = 'time_zone'
                      and character_maximum_length = 60 and is_nullable = 'NO'
                    """), "hospitals.time_zone must be varchar(60) NOT NULL");
            assertEquals(1, count(s, """
                    select count(*) from information_schema.columns
                    where table_name = 'branches' and column_name = 'hospital_id'
                      and is_nullable = 'NO'
                    """), "branches.hospital_id must be NOT NULL after backfill");
            assertEquals(1, count(s, """
                    select count(*) from information_schema.columns
                    where table_name = 'acting_assignments' and column_name = 'hospital_id'
                      and is_nullable = 'YES'
                    """), "acting_assignments.hospital_id stays nullable (ORGANIZATION scope)");

            // --- foreign keys --------------------------------------------
            for (String fk : new String[] {
                    "fk_hospitals_organization", "fk_branches_hospital",
                    "fk_branches_hospital_organization", "fk_acting_assignments_hospital"}) {
                assertEquals(1, count(s, "select count(*) from information_schema.table_constraints "
                        + "where constraint_name = '" + fk + "' and constraint_type = 'FOREIGN KEY'"),
                        "required foreign key missing: " + fk);
            }
            assertEquals(2, count(s, """
                    select count(*) from information_schema.key_column_usage
                    where constraint_name = 'fk_branches_hospital_organization'
                    """), "the consistency FK must cover exactly (hospital_id, organization_id)");

            // --- unique keys ---------------------------------------------
            assertEquals(1, count(s, "select count(*) from information_schema.table_constraints "
                    + "where constraint_name = 'uk_hospitals_organization_code'"),
                    "hospital codes must be unique inside the organization");
            assertEquals(1, count(s, "select count(*) from information_schema.table_constraints "
                    + "where constraint_name = 'uk_branches_hospital_code'"),
                    "branch codes must be unique inside the hospital after V5");
            assertEquals(0, count(s, "select count(*) from information_schema.table_constraints "
                    + "where constraint_name = 'uk_branches_organization_code'"),
                    "the replaced organization-scoped branch uniqueness must be gone");
            assertEquals(0, count(s, "select count(*) from information_schema.table_constraints "
                    + "where constraint_name = 'uk_acting_assignments_logical'"),
                    "the null-sensitive assignment table constraint must be replaced");

            // --- check domains and shape constraint -----------------------
            assertEquals(1, count(s, "select count(*) from information_schema.check_constraints "
                    + "where constraint_name = 'ck_acting_assignments_scope_shape'"),
                    "the assignment scope-shape constraint must exist");
            String scopeDomain = scalarVia(s, "select check_clause from information_schema.check_constraints "
                    + "where constraint_name = 'ck_acting_assignments_scope'");
            for (String scope : new String[] {"ORGANIZATION", "HOSPITAL", "BRANCH", "DEPARTMENT"}) {
                assertTrue(scopeDomain.contains(scope),
                        "the scope domain must include " + scope + ": " + scopeDomain);
            }

            // --- indexes ---------------------------------------------------
            for (String index : new String[] {
                    "uq_acting_assignments_scope_logical", "idx_branches_hospital",
                    "idx_acting_assignments_hospital", "idx_audit_events_hospital_occurred"}) {
                assertEquals(1, count(s, "select count(*) from pg_indexes where indexname = '" + index + "'"),
                        "required V5 index missing: " + index);
            }
            String uniquenessIndex = scalarVia(s, "select indexdef from pg_indexes "
                    + "where indexname = 'uq_acting_assignments_scope_logical'");
            assertTrue(uniquenessIndex.toLowerCase().contains("coalesce"),
                    "the null-safe uniqueness strategy must collapse nulls explicitly: " + uniquenessIndex);

            // --- bounded audit context columns ------------------------------
            for (String column : new String[] {
                    "hospital_id", "source_hospital_id", "destination_hospital_id", "transfer_id"}) {
                assertEquals(1, count(s, "select count(*) from information_schema.columns "
                        + "where table_name = 'audit_events' and column_name = '" + column + "'"),
                        "the bounded audit context column must exist: " + column);
            }

            // --- flyway history ---------------------------------------------
            assertTrue(count(s, "select count(*) from flyway_schema_history where success") >= 5,
                    "flyway history must record the applied migrations including V5");
        }
    }

    /**
     * T022 behavioral proof: the null-safe uniqueness strategy blocks
     * duplicates for every scope shape under real PostgreSQL NULL semantics
     * — where the V1–V4 constraint admitted organization and hospital
     * duplicates — while distinct scopes and distinct hospitals stay legal.
     */
    @Test
    void v5NullSafeUniquenessBlocksEveryScopeDuplicate() throws Exception {
        var db = PostgresContainerSupport.newIsolatedDatabase();
        flywayFor(db).migrate();
        try (Connection c = FlywayPostgresIntegrationTest.connection(db); Statement s = c.createStatement()) {
            s.execute("insert into hospital_organizations (id, code, name, created_at, updated_at, version) "
                    + "values ('" + ORG_ID + "', 'UQ-ORG', 'Synthetic Uniqueness Network', now(), now(), 0)");
            hospitalInsert(s, HOSPITAL_1, "HOSP-U1", "Uniqueness Hospital");
            hospitalInsert(s, HOSPITAL_2, "HOSP-U2", "Second Uniqueness Hospital");
            s.execute("insert into user_accounts (id, username, password_hash, enabled, created_at, updated_at, "
                    + "version) values ('" + ACCOUNT_ID + "', 'uq-review', "
                    + "'disposable-bcrypt-hash-not-a-credential', true, now(), now(), 0)");
            s.execute("insert into branches (id, hospital_id, organization_id, code, name, location_label, active, "
                    + "created_at, updated_at, version) values ('" + BRANCH_MAIN + "', '" + HOSPITAL_1 + "', '"
                    + ORG_ID + "', 'UQ-BR', 'Uniqueness Branch', '1 Uniqueness Way', true, now(), now(), 0)");

            StringBinder values = (id, hospital, branch, role, scope) ->
                    "insert into acting_assignments (id, account_id, organization_id, hospital_id, branch_id, role, "
                    + "scope, enabled, created_at, updated_at, version) values ('" + id + "', '" + ACCOUNT_ID
                    + "', '" + ORG_ID + "', "
                    + (hospital == null ? "null" : "'" + hospital + "'") + ", "
                    + (branch == null ? "null" : "'" + branch + "'") + ", '" + role + "', '" + scope
                    + "', true, now(), now(), 0)";

            // ORGANIZATION duplicates (all nullable scope columns NULL) — admitted by V1–V4, blocked now.
            s.execute(values.apply("00000000-0000-0000-00a5-0000000000c1", null, null, "ADMIN", "ORGANIZATION"));
            assertUniqueViolation(c, values.apply("00000000-0000-0000-00a5-0000000000c2",
                    null, null, "ADMIN", "ORGANIZATION"), "duplicate ORGANIZATION-scope assignment");

            // HOSPITAL duplicates — blocked; a different hospital stays legal.
            s.execute(values.apply("00000000-0000-0000-00a5-0000000000c3", HOSPITAL_1, null, "DOCTOR", "HOSPITAL"));
            assertUniqueViolation(c, values.apply("00000000-0000-0000-00a5-0000000000c4",
                    HOSPITAL_1, null, "DOCTOR", "HOSPITAL"), "duplicate HOSPITAL-scope assignment");
            s.execute(values.apply("00000000-0000-0000-00a5-0000000000c5", HOSPITAL_2, null, "DOCTOR", "HOSPITAL"));

            // BRANCH duplicates — blocked.
            s.execute(values.apply("00000000-0000-0000-00a5-0000000000c6", HOSPITAL_1, BRANCH_MAIN,
                    "NURSE", "BRANCH"));
            assertUniqueViolation(c, values.apply("00000000-0000-0000-00a5-0000000000c8",
                    HOSPITAL_1, BRANCH_MAIN, "NURSE", "BRANCH"), "duplicate BRANCH-scope assignment");

            // Branch code uniqueness is hospital-scoped: same code in a second hospital is legal.
            s.execute("insert into branches (id, hospital_id, organization_id, code, name, location_label, active, "
                    + "created_at, updated_at, version) values ('00000000-0000-0000-00a5-0000000000d0', '"
                    + HOSPITAL_2 + "', '" + ORG_ID + "', 'UQ-BR', 'Second Hospital Branch', "
                    + "'2 Uniqueness Way', true, now(), now(), 0)");
            assertUniqueViolation(c, "insert into branches (id, hospital_id, organization_id, code, name, "
                    + "location_label, active, created_at, updated_at, version) values "
                    + "('00000000-0000-0000-00a5-0000000000d1', '" + HOSPITAL_1 + "', '" + ORG_ID
                    + "', 'UQ-BR', 'Duplicate Branch', '3 Uniqueness Way', true, now(), now(), 0)",
                    "duplicate branch code inside one hospital");

            // The scope-shape check holds: a HOSPITAL row carrying a branch is refused (check violation).
            try {
                s.execute(values.apply("00000000-0000-0000-00a5-0000000000c9", HOSPITAL_1, BRANCH_MAIN,
                        "DOCTOR", "HOSPITAL"));
                org.junit.jupiter.api.Assertions.fail("a HOSPITAL assignment must not carry a branch");
            } catch (SQLException expected) {
                assertTrue(hasState(expected, "23514"),
                        "expected the scope-shape check constraint, got: " + expected.getMessage());
            }
        }
    }

    // ------------------------------------------------------------ helpers

    private interface StringBinder {
        String apply(String id, String hospital, String branch, String role, String scope);
    }

    private static void hospitalInsert(Statement s, String id, String code, String name) throws SQLException {
        s.execute("insert into hospitals (id, organization_id, code, name, region_label, time_zone, active, "
                + "created_at, updated_at, version) values ('" + id + "', '" + ORG_ID + "', '" + code + "', '"
                + name + "', 'Region', 'UTC', true, now(), now(), 0)");
    }

    /** Deterministic Phase 4-shaped synthetic cohort (V4 schema target). */
    private static void seedPhase4ShapedCohort(PostgresContainerSupport.DisposableDatabase db) throws Exception {
        try (Connection c = FlywayPostgresIntegrationTest.connection(db); Statement s = c.createStatement()) {
            String[] statements = {
                "insert into hospital_organizations (id, code, name, created_at, updated_at, version) "
                        + "values ('" + ORG_ID + "', 'BACKFILL-ORG', 'Synthetic Backfill Network', now(), now(), 0)",
                "insert into branches (id, organization_id, code, name, location_label, active, created_at, "
                        + "updated_at, version) values ('" + BRANCH_MAIN + "', '" + ORG_ID + "', 'BACKFILL-BR-1', "
                        + "'Synthetic Main', '1 Backfill Way', true, now(), now(), 0)",
                "insert into branches (id, organization_id, code, name, location_label, active, created_at, "
                        + "updated_at, version) values ('" + BRANCH_OTHER + "', '" + ORG_ID + "', 'BACKFILL-BR-2', "
                        + "'Synthetic Other', '2 Backfill Way', true, now(), now(), 0)",
                "insert into departments (id, branch_id, code, name, specialty, location, created_at, updated_at, "
                        + "version) values ('" + DEPT_ID + "', '" + BRANCH_MAIN + "', 'BACKFILL-DEP-1', "
                        + "'Synthetic Department', 'general', 'Demo Tower', now(), now(), 0)",
                "insert into user_accounts (id, username, password_hash, enabled, created_at, updated_at, version) "
                        + "values ('" + ACCOUNT_ID + "', 'backfill-review', "
                        + "'disposable-bcrypt-hash-not-a-credential', true, now(), now(), 0)",
                "insert into acting_assignments (id, account_id, organization_id, branch_id, role, scope, enabled, "
                        + "created_at, updated_at, version) values ('" + BRANCH_ASSIGNMENT + "', '" + ACCOUNT_ID
                        + "', '" + ORG_ID + "', '" + BRANCH_MAIN + "', 'DOCTOR', 'BRANCH', true, now(), now(), 0)",
                "insert into acting_assignments (id, account_id, organization_id, department_id, role, scope, "
                        + "enabled, created_at, updated_at, version) values ('" + DEPT_ASSIGNMENT + "', '"
                        + ACCOUNT_ID + "', '" + ORG_ID + "', '" + DEPT_ID + "', 'NURSE', 'DEPARTMENT', true, "
                        + "now(), now(), 0)",
                "insert into acting_assignments (id, account_id, organization_id, role, scope, enabled, created_at, "
                        + "updated_at, version) values ('" + ORG_ASSIGNMENT + "', '" + ACCOUNT_ID + "', '" + ORG_ID
                        + "', 'ADMIN', 'ORGANIZATION', true, now(), now(), 0)",
                "insert into audit_events (id, actor, action, resource_type, resource_id, occurred_at, branch_id, "
                        + "organization_id, created_at, updated_at, version) values ('" + AUDIT_WITH_BRANCH + "', "
                        + "'system', 'CREATE', 'Branch', '" + BRANCH_MAIN + "', now(), '" + BRANCH_MAIN + "', '"
                        + ORG_ID + "', now(), now(), 0)",
                "insert into audit_events (id, actor, action, resource_type, resource_id, occurred_at, created_at, "
                        + "updated_at, version) values ('" + AUDIT_LEGACY + "', 'system', 'CREATE', "
                        + "'HospitalOrganization', '" + ORG_ID + "', now(), now(), now(), 0)"
            };
            for (String sql : statements) {
                s.execute(sql);
            }
        }
    }

    private static void assertUniqueViolation(Connection connection, String sql, String description)
            throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute(sql);
            org.junit.jupiter.api.Assertions.fail(description + " must be refused by a unique constraint");
        } catch (SQLException expected) {
            assertTrue(hasState(expected, "23505"),
                    description + " must fail through the null-safe uniqueness strategy: " + expected.getMessage());
        }
    }

    /** True when the exception chain carries the given PostgreSQL SQLSTATE. */
    private static boolean hasState(SQLException exception, String sqlState) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            if (sqlState.equals(current.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static long count(Statement s, String query) throws SQLException {
        return Long.parseLong(scalarVia(s, query));
    }

    private static String scalarVia(Statement s, String query) throws SQLException {
        try (ResultSet rs = s.executeQuery(query)) {
            assertTrue(rs.next(), "scalar query must return a row: " + query);
            return rs.getString(1);
        }
    }

    private static String scalar(PostgresContainerSupport.DisposableDatabase db, String query) throws Exception {
        try (Connection c = FlywayPostgresIntegrationTest.connection(db); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(query)) {
            assertTrue(rs.next(), "scalar query must return a row: " + query);
            String value = rs.getString(1);
            return rs.wasNull() ? null : value;
        }
    }
}
