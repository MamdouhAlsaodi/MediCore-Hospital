package com.mamtrex.hospital.transfer;

import com.mamtrex.hospital.auth.ActingContext;
import com.mamtrex.hospital.auth.AssignmentScope;
import com.mamtrex.hospital.auth.Role;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 5 US4 RED-first authorization matrix (specs/005 tasks T088, FR-016):
 * only authorized SOURCE actors (DOCTOR/NURSE/ADMIN of the source hospital)
 * may request/cancel/start transit, and only authorized DESTINATION actors
 * (DOCTOR/ADMIN of the destination hospital) may accept/reject/complete.
 * Every check is server-side over the derived acting context; the hospital
 * scope of the context is never taken from client input. Pure unit test —
 * no database.
 */
class TransferAuthorizationMatrixTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID HOSPITAL_A = UUID.randomUUID();
    private static final UUID HOSPITAL_B = UUID.randomUUID();
    private static final UUID BRANCH_A = UUID.randomUUID();
    private static final UUID BRANCH_B = UUID.randomUUID();

    private final TransferAuthorizationService auth = new TransferAuthorizationService();

    private static ActingContext context(Role role, UUID hospitalId, UUID branchId) {
        return new ActingContext("user-" + role, UUID.randomUUID(), role,
                AssignmentScope.BRANCH, ORG, hospitalId, branchId, null);
    }

    // ------------------------------------------------------ source-side roles

    @Test
    void sourceDoctorNurseAdminMayActOnSourceSide() {
        for (Role role : new Role[] {Role.DOCTOR, Role.NURSE, Role.ADMIN}) {
            ActingContext ctx = context(role, HOSPITAL_A, BRANCH_A);
            assertDoesNotThrow(() -> auth.requireSourceRole(ctx));
            assertDoesNotThrow(() -> auth.requireSourceHospital(ctx, HOSPITAL_A));
        }
    }

    @Test
    void nonClinicalSourceRolesAreRefused() {
        for (Role role : new Role[] {Role.RECEPTIONIST, Role.HR, Role.BILLING,
                Role.LAB_TECH, Role.PHARMACIST, Role.STAFF}) {
            ActingContext ctx = context(role, HOSPITAL_A, BRANCH_A);
            assertThrows(TransferAuthorizationService.UnauthorizedRoleException.class,
                    () -> auth.requireSourceRole(ctx));
        }
    }

    // ------------------------------------------------- destination-side roles

    @Test
    void destinationDoctorAdminMayActOnDestinationSide() {
        for (Role role : new Role[] {Role.DOCTOR, Role.ADMIN}) {
            ActingContext ctx = context(role, HOSPITAL_B, BRANCH_B);
            assertDoesNotThrow(() -> auth.requireDestinationRole(ctx));
            assertDoesNotThrow(() -> auth.requireDestinationHospital(ctx, HOSPITAL_B));
        }
    }

    @Test
    void destinationNurseIsRefusedForDestinationDecisions() {
        ActingContext ctx = context(Role.NURSE, HOSPITAL_B, BRANCH_B);
        assertThrows(TransferAuthorizationService.UnauthorizedRoleException.class,
                () -> auth.requireDestinationRole(ctx));
    }

    // ------------------------------------------------------- hospital scoping

    @Test
    void sourceActorOfAnotherHospitalIsRefused() {
        ActingContext ctx = context(Role.DOCTOR, HOSPITAL_B, BRANCH_B);
        assertThrows(TransferAuthorizationService.ForeignHospitalScopeException.class,
                () -> auth.requireSourceHospital(ctx, HOSPITAL_A));
    }

    @Test
    void destinationActorOfAnotherHospitalIsRefused() {
        ActingContext ctx = context(Role.DOCTOR, HOSPITAL_A, BRANCH_A);
        assertThrows(TransferAuthorizationService.ForeignHospitalScopeException.class,
                () -> auth.requireDestinationHospital(ctx, HOSPITAL_B));
    }

    @Test
    void sourceRoleCheckDoesNotAuthorizeDestinationActions() {
        ActingContext nurseAtSource = context(Role.NURSE, HOSPITAL_A, BRANCH_A);
        assertThrows(TransferAuthorizationService.UnauthorizedRoleException.class,
                () -> auth.requireDestinationRole(nurseAtSource));
    }
}
