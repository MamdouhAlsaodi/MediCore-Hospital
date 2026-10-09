package com.mamtrex.hospital.infrastructure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Database-enforced transfer ownership, independent of HTTP/service prechecks. */
class TransferV7IntegrityIntegrationTest {

    @Test
    void transferAndReservationRejectCrossOwnerReferences() throws Exception {
        var db = PostgresContainerSupport.newIsolatedDatabase();
        Flyway.configure().locations("classpath:db/migration")
                .dataSource(db.jdbcUrl(), db.user(), db.password()).load().migrate();
        try (Connection connection = FlywayPostgresIntegrationTest.connection(db);
             Statement sql = connection.createStatement()) {
            String org = id(), source = id(), destination = id(), sourceBranch = id();
            String destinationBranch = id(), otherDestinationBranch = id();
            String destinationBed = id(), otherBed = id(), patient = id(), transfer = id();
            sql.execute("insert into hospital_organizations (id, code, name, created_at, updated_at, version) values ('"
                    + org + "', 'V7-ORG', 'Synthetic Transfer Network', now(), now(), 0)");
            hospital(sql, source, org, "V7-SOURCE");
            hospital(sql, destination, org, "V7-DESTINATION");
            branch(sql, sourceBranch, source, org, "V7-SOURCE-BRANCH");
            branch(sql, destinationBranch, destination, org, "V7-DESTINATION-BRANCH");
            branch(sql, otherDestinationBranch, destination, org, "V7-OTHER-BRANCH");
            bed(sql, destinationBed, destinationBranch);
            bed(sql, otherBed, otherDestinationBranch);
            sql.execute("insert into patients (id, branch_id, organization_id, medical_record_number, full_name, active, "
                    + "created_at, updated_at, version) values ('" + patient + "', '" + sourceBranch + "', '"
                    + org + "', 'V7-MRN', 'Synthetic Patient', true, now(), now(), 0)");
            String insertTransfer = "insert into transfer_requests (id, transfer_number, organization_id, patient_id, "
                    + "source_hospital_id, source_branch_id, destination_hospital_id, destination_branch_id, "
                    + "destination_bed_id, status, reason_code, requested_by_assignment_id, requested_at, "
                    + "created_at, updated_at, version) values ('" + transfer + "', 'V7-TRANSFER', '" + org
                    + "', '" + patient + "', '" + source + "', '" + sourceBranch + "', '" + destination
                    + "', '" + destinationBranch + "', '" + destinationBed
                    + "', 'ACCEPTED', 'BED_SHORTAGE', '" + id() + "', now(), now(), now(), 0)";
            sql.execute(insertTransfer); // The correctly owned path stays legal.

            assertForeignKey(connection, "update transfer_requests set source_branch_id = '" + destinationBranch
                    + "' where id = '" + transfer + "'", "fk_transfer_requests_source_branch_hospital");
            assertForeignKey(connection, "update transfer_requests set destination_branch_id = '" + sourceBranch
                    + "' where id = '" + transfer + "'", "fk_transfer_requests_destination_branch_hospital");
            assertForeignKey(connection, "update transfer_requests set destination_bed_id = '" + otherBed
                    + "' where id = '" + transfer + "'", "fk_transfer_requests_destination_bed_branch");
            assertCheck(connection, "update transfer_requests set destination_branch_id = null where id = '"
                    + transfer + "'", "ck_transfer_requests_bed_requires_branch");

            String reservation = id();
            sql.execute("insert into transfer_bed_reservations (id, transfer_id, bed_id, status, reserved_at, "
                    + "created_at, updated_at, version) values ('" + reservation + "', '" + transfer + "', '"
                    + destinationBed + "', 'ACTIVE', now(), now(), now(), 0)");
            assertForeignKey(connection, "update transfer_bed_reservations set bed_id = '" + otherBed
                    + "' where id = '" + reservation + "'", "fk_reservation_transfer_destination_bed");
        }
    }

    @Test
    void responseSnapshotColumnIsNullableTextForImmutableReplay() throws Exception {
        var db = PostgresContainerSupport.newIsolatedDatabase();
        Flyway.configure().locations("classpath:db/migration")
                .dataSource(db.jdbcUrl(), db.user(), db.password()).load().migrate();
        try (Connection connection = FlywayPostgresIntegrationTest.connection(db);
             Statement sql = connection.createStatement();
             var result = sql.executeQuery("select data_type, is_nullable from information_schema.columns "
                     + "where table_name = 'idempotency_records' and column_name = 'response_snapshot'")) {
            assertEquals(true, result.next(), "V7 must expose response_snapshot");
            assertEquals("text", result.getString(1));
            assertEquals("YES", result.getString(2));
        }
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static void hospital(Statement sql, String id, String org, String code) throws SQLException {
        sql.execute("insert into hospitals (id, organization_id, code, name, region_label, time_zone, active, "
                + "created_at, updated_at, version) values ('" + id + "', '" + org + "', '" + code
                + "', 'Synthetic Hospital', 'Region', 'UTC', true, now(), now(), 0)");
    }

    private static void branch(Statement sql, String id, String hospital, String org, String code)
            throws SQLException {
        sql.execute("insert into branches (id, hospital_id, organization_id, code, name, location_label, active, "
                + "created_at, updated_at, version) values ('" + id + "', '" + hospital + "', '" + org
                + "', '" + code + "', 'Synthetic Branch', 'Synthetic Location', true, now(), now(), 0)");
    }

    private static void bed(Statement sql, String id, String branch) throws SQLException {
        sql.execute("insert into beds (id, branch_id, ward, room, bed_number, occupancy_status, "
                + "created_at, updated_at, version) values ('" + id + "', '" + branch
                + "', 'W', 'R', '" + id + "', 'AVAILABLE', now(), now(), 0)");
    }

    private static void assertForeignKey(Connection connection, String mutation, String constraint)
            throws SQLException {
        assertViolation(connection, mutation, "23503", constraint);
    }

    private static void assertCheck(Connection connection, String mutation, String constraint)
            throws SQLException {
        assertViolation(connection, mutation, "23514", constraint);
    }

    private static void assertViolation(Connection connection, String mutation, String state, String constraint)
            throws SQLException {
        try (Statement sql = connection.createStatement()) {
            SQLException violation = assertThrows(SQLException.class, () -> sql.execute(mutation),
                    "a mismatched ownership reference must be rejected by PostgreSQL: " + constraint);
            assertEquals(state, violation.getSQLState());
            assertEquals(constraint, ((org.postgresql.util.PSQLException) violation)
                    .getServerErrorMessage().getConstraint());
        }
    }
}
