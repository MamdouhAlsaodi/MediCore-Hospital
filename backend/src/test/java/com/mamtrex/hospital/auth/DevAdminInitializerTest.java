package com.mamtrex.hospital.auth;

import org.junit.jupiter.api.Test;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
import com.mamtrex.hospital.organization.HospitalOrganization;
import com.mamtrex.hospital.organization.HospitalOrganizationRepository;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationListener;
import org.springframework.data.domain.Sort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the opt-in review-account bootstrap (packet
 * MEDICORE-REVIEW-ROLE-ACCOUNTS-028). Pure unit level: the repository and the
 * password encoder are Mockito doubles — no Spring context, database, or real
 * credential is involved; every password below is an obviously disposable
 * synthetic value that never leaves this test.
 *
 * <p>Coverage: the feature is gated off unless
 * {@code medicore.review-accounts.enabled=true} (so the default and
 * {@code false} create nothing and never require the review-password
 * variables), enabled seeding creates exactly {@code doctor}/{@code nurse}
 * with exact roles and encoder-produced hashes, repeats are idempotent and
 * never mutate an existing account, and missing/short review passwords fail
 * startup with a message naming only the variable — never its value.</p>
 *
 * <p>Plan 3 Task 3 adds bootstrap assignment provisioning (packet
 * MEDICORE-PLAN3-TASK3-050): the provisioning bean rides
 * {@link ApplicationReadyEvent}, which Spring fires strictly after every
 * {@code Runner}, so the opt-in demo hierarchy deterministically exists
 * before assignments are provisioned when both are enabled — without
 * modifying the forbidden {@code DemoDataInitializer}. Provisioning is
 * lookup-before-create, covers only the named bootstrap accounts, fabricates
 * nothing without an organization and an active branch, never mutates
 * existing accounts, and provisions ADMIN as ORGANIZATION scope and enabled
 * review accounts as BRANCH scope on the deterministic active default
 * branch.</p>
 */
class DevAdminInitializerTest {

    /** Disposable synthetic test value; never a real or shared credential. */
    private static final String VALID_DOCTOR_PASSWORD = "disposable-doctor-review-pw-123";
    /** Disposable synthetic test value; never a real or shared credential. */
    private static final String VALID_NURSE_PASSWORD = "disposable-nurse-review-pw-456";

    @Test
    void reviewAccountSeedingIsGatedOffUnlessExplicitlyEnabled() throws Exception {
        ConditionalOnProperty gate = DevAdminInitializer.class
                .getDeclaredMethod("seedReviewAccounts", UserAccountRepository.class, PasswordEncoder.class,
                        String.class, String.class)
                .getAnnotation(ConditionalOnProperty.class);
        assertNotNull(gate, "the review-account runner bean must carry an explicit property gate");
        assertArrayEquals(new String[] {"medicore.review-accounts.enabled"}, gate.name());
        assertEquals("true", gate.havingValue());
        assertFalse(gate.matchIfMissing(),
                "absent property (the default) must keep the gate closed: no runner bean, "
                        + "no account creation, and no required review-password variables");
    }

    @Test
    void reviewPasswordMinimumLengthIsAtLeastTwelveCharacters() {
        assertTrue(DevAdminInitializer.MIN_REVIEW_PASSWORD_LENGTH >= 12,
                "review passwords must require at least 12 characters");
    }

    @Test
    void enabledSeedingCreatesDoctorAndNurseWithEncodedPasswordsAndExactRoles() throws Exception {
        UserAccountRepository repo = mock(UserAccountRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(repo.findByUsername("doctor")).thenReturn(Optional.empty());
        when(repo.findByUsername("nurse")).thenReturn(Optional.empty());
        when(encoder.encode(VALID_DOCTOR_PASSWORD)).thenReturn("encoded-doctor-hash");
        when(encoder.encode(VALID_NURSE_PASSWORD)).thenReturn("encoded-nurse-hash");

        CommandLineRunner runner = new DevAdminInitializer().seedReviewAccounts(
                repo, encoder, VALID_DOCTOR_PASSWORD, VALID_NURSE_PASSWORD);
        runner.run();

        ArgumentCaptor<UserAccount> saved = ArgumentCaptor.forClass(UserAccount.class);
        verify(repo, times(2)).save(saved.capture());
        List<UserAccount> accounts = saved.getAllValues();

        UserAccount doctor = accounts.get(0);
        assertEquals("doctor", doctor.getUsername());
        assertEquals("encoded-doctor-hash", doctor.getPasswordHash(),
                "the doctor hash must be exactly what the established encoder produced");
        assertEquals(java.util.Set.of(Role.DOCTOR), doctor.getRoles(),
                "doctor must carry exactly the DOCTOR role");
        assertTrue(doctor.isEnabled());

        UserAccount nurse = accounts.get(1);
        assertEquals("nurse", nurse.getUsername());
        assertEquals("encoded-nurse-hash", nurse.getPasswordHash(),
                "the nurse hash must be exactly what the established encoder produced");
        assertEquals(java.util.Set.of(Role.NURSE), nurse.getRoles(),
                "nurse must carry exactly the NURSE role");
        assertTrue(nurse.isEnabled());
    }

    @Test
    void repeatSeedingNeverRecreatesOrMutatesExistingAccounts() throws Exception {
        UserAccountRepository repo = mock(UserAccountRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        Map<String, UserAccount> store = new HashMap<>();
        UserAccount preExistingDoctor =
                new UserAccount("doctor", "pre-existing-untouched-hash", java.util.Set.of(Role.DOCTOR));
        store.put("doctor", preExistingDoctor);
        when(repo.findByUsername(anyString())).thenAnswer(
                invocation -> Optional.ofNullable(store.get(invocation.getArgument(0, String.class))));
        when(repo.save(any(UserAccount.class))).thenAnswer(invocation -> {
            UserAccount account = invocation.getArgument(0, UserAccount.class);
            store.put(account.getUsername(), account);
            return account;
        });

        CommandLineRunner runner = new DevAdminInitializer().seedReviewAccounts(
                repo, encoder, VALID_DOCTOR_PASSWORD, VALID_NURSE_PASSWORD);
        runner.run(); // doctor already exists; nurse is created exactly once
        runner.run(); // both exist now; nothing may be saved again

        verify(repo, times(1)).save(any(UserAccount.class));
        assertEquals(2, store.size(), "a repeat run must not duplicate any account");
        assertEquals(preExistingDoctor, store.get("doctor"));
        assertEquals("pre-existing-untouched-hash", store.get("doctor").getPasswordHash(),
                "an existing account's password must never be reset");
        assertEquals(java.util.Set.of(Role.DOCTOR), store.get("doctor").getRoles(),
                "an existing account's roles must never be mutated");
        assertEquals("nurse", store.get("nurse").getUsername());
        assertEquals(java.util.Set.of(Role.NURSE), store.get("nurse").getRoles());
    }

    @Test
    void missingDoctorPasswordFailsNamingOnlyTheVariable() throws Exception {
        UserAccountRepository repo = mock(UserAccountRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        CommandLineRunner runner = new DevAdminInitializer().seedReviewAccounts(
                repo, encoder, "   ", VALID_NURSE_PASSWORD);

        IllegalStateException failure = assertThrows(IllegalStateException.class, runner::run,
                "a blank required review password must fail startup");
        assertTrue(failure.getMessage().contains("HOSPITAL_REVIEW_DOCTOR_PASSWORD"),
                "the failure must name the offending variable");
        verify(repo, never()).save(any());
        verify(encoder, never()).encode(anyString());
    }

    @Test
    void shortNursePasswordFailsNamingOnlyTheVariable() throws Exception {
        UserAccountRepository repo = mock(UserAccountRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        String shortNursePassword = "tiny123";
        CommandLineRunner runner = new DevAdminInitializer().seedReviewAccounts(
                repo, encoder, VALID_DOCTOR_PASSWORD, shortNursePassword);

        IllegalStateException failure = assertThrows(IllegalStateException.class, runner::run,
                "a too-short required review password must fail startup");
        assertTrue(failure.getMessage().contains("HOSPITAL_REVIEW_NURSE_PASSWORD"),
                "the failure must name the offending variable");
        assertFalse(failure.getMessage().contains(shortNursePassword),
                "the failure message must never contain the password value");
        verify(repo, never()).save(any());
        verify(encoder, never()).encode(anyString());
    }

    // ------------------------------------------------------------------
    // Plan 3 Task 3 — bootstrap assignment provisioning.
    // ------------------------------------------------------------------

    /** Runs the provisioning listener body the same way the ready event does. */
    @SuppressWarnings("unchecked")
    private void runProvisioning(UserAccountRepository accounts, ActingAssignmentRepository assignments,
                                 HospitalOrganizationRepository organizations, BranchRepository branches,
                                 boolean reviewAccountsEnabled) throws Exception {
        Object listener = new DevAdminInitializer().provisionBootstrapAssignments(
                accounts, assignments, organizations, branches, reviewAccountsEnabled);
        ((ApplicationListener<ApplicationReadyEvent>) listener).onApplicationEvent(null);
    }

    private record HierarchyFixtures(UserAccount account, HospitalOrganization organization, Branch branch) {
    }

    private HierarchyFixtures stubHierarchy(UserAccountRepository accounts, String username,
                                            HospitalOrganizationRepository organizations,
                                            BranchRepository branches) {
        UserAccount account = new UserAccount(username, "pre-existing-untouched-hash", Set.of(Role.ADMIN));
        when(accounts.findByUsername(username)).thenReturn(Optional.of(account));
        HospitalOrganization organization = new HospitalOrganization("DEMO-ORG-001", "Demo Synthetic Hospital");
        Branch branch = new Branch(organization, "DEMO-BR-001", "Demo Main Branch", "1 Demo Campus");
        when(organizations.findAll(any(Sort.class))).thenReturn(List.of(organization));
        when(branches.findByOrganizationIdAndActiveTrueOrderByCodeAsc(organization.getId()))
                .thenReturn(List.of(branch));
        return new HierarchyFixtures(account, organization, branch);
    }

    /**
     * Ordering mechanism pin: provisioning is an ApplicationListener for the
     * ready event — Spring fires that event strictly after every Runner, so
     * the opt-in demo hierarchy deterministically exists first without
     * modifying the forbidden DemoDataInitializer.
     */
    @Test
    void bootstrapAssignmentProvisioningRidesTheReadyEventThatFiresAfterAllRunners() throws Exception {
        Method bean = DevAdminInitializer.class.getDeclaredMethod("provisionBootstrapAssignments",
                UserAccountRepository.class, ActingAssignmentRepository.class,
                HospitalOrganizationRepository.class, BranchRepository.class, boolean.class);
        ParameterizedType returnType = assertInstanceOf(ParameterizedType.class, bean.getGenericReturnType(),
                "the provisioning bean must be a parameterized ApplicationListener");
        assertEquals(ApplicationListener.class, returnType.getRawType());
        Type[] eventArgument = returnType.getActualTypeArguments();
        assertEquals(1, eventArgument.length);
        assertEquals(ApplicationReadyEvent.class, eventArgument[0],
                "the listener must target the ready event (after all runners)");
    }

    @Test
    void adminProvisioningCreatesOrganizationScopeAssignmentWhenHierarchyExists() throws Exception {
        UserAccountRepository accounts = mock(UserAccountRepository.class);
        ActingAssignmentRepository assignments = mock(ActingAssignmentRepository.class);
        HospitalOrganizationRepository organizations = mock(HospitalOrganizationRepository.class);
        BranchRepository branches = mock(BranchRepository.class);
        HierarchyFixtures fixtures = stubHierarchy(accounts, "admin", organizations, branches);
        when(assignments.findByAccountIdAndRoleAndScopeAndBranchIsNullAndDepartmentIsNull(
                any(), any(), any())).thenReturn(Optional.empty());
        when(assignments.save(any(ActingAssignment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, ActingAssignment.class));

        runProvisioning(accounts, assignments, organizations, branches, false);

        ArgumentCaptor<ActingAssignment> saved = ArgumentCaptor.forClass(ActingAssignment.class);
        verify(assignments).save(saved.capture());
        ActingAssignment assignment = saved.getValue();
        assertEquals("admin", assignment.getAccount().getUsername());
        assertEquals(Role.ADMIN, assignment.getRole());
        assertEquals(AssignmentScope.ORGANIZATION, assignment.getScope());
        assertNull(assignment.getBranch(), "an ADMIN bootstrap assignment is organization-scoped with no branch");
        assertNull(assignment.getDepartment());
        assertEquals(fixtures.organization, assignment.getOrganization());
        assertTrue(assignment.isEnabled());
    }

    @Test
    void reviewProvisioningCreatesBranchScopeAssignmentsOnTheDeterministicDefaultBranch() throws Exception {
        UserAccountRepository accounts = mock(UserAccountRepository.class);
        ActingAssignmentRepository assignments = mock(ActingAssignmentRepository.class);
        HospitalOrganizationRepository organizations = mock(HospitalOrganizationRepository.class);
        BranchRepository branches = mock(BranchRepository.class);
        // One shared deterministic hierarchy: both review accounts act on the
        // same active default branch of the same organization.
        HospitalOrganization organization = new HospitalOrganization("DEMO-ORG-001", "Demo Synthetic Hospital");
        Branch branch = new Branch(organization, "DEMO-BR-001", "Demo Main Branch", "1 Demo Campus");
        UserAccount doctor = new UserAccount("doctor", "pre-existing-doctor-hash", Set.of(Role.DOCTOR));
        UserAccount nurse = new UserAccount("nurse", "pre-existing-nurse-hash", Set.of(Role.NURSE));
        when(accounts.findByUsername("doctor")).thenReturn(Optional.of(doctor));
        when(accounts.findByUsername("nurse")).thenReturn(Optional.of(nurse));
        when(organizations.findAll(any(Sort.class))).thenReturn(List.of(organization));
        when(branches.findByOrganizationIdAndActiveTrueOrderByCodeAsc(organization.getId()))
                .thenReturn(List.of(branch));
        when(assignments.findByAccountIdAndRoleAndScopeAndBranchId(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(assignments.save(any(ActingAssignment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, ActingAssignment.class));

        runProvisioning(accounts, assignments, organizations, branches, true);

        ArgumentCaptor<ActingAssignment> saved = ArgumentCaptor.forClass(ActingAssignment.class);
        verify(assignments, times(2)).save(saved.capture());
        ActingAssignment doctorAssignment = saved.getAllValues().get(0);
        assertEquals("doctor", doctorAssignment.getAccount().getUsername());
        assertEquals(Role.DOCTOR, doctorAssignment.getRole());
        assertEquals(AssignmentScope.BRANCH, doctorAssignment.getScope());
        assertEquals(branch, doctorAssignment.getBranch(),
                "review accounts act on the deterministic active default branch");
        assertNull(doctorAssignment.getDepartment());

        ActingAssignment nurseAssignment = saved.getAllValues().get(1);
        assertEquals("nurse", nurseAssignment.getAccount().getUsername());
        assertEquals(Role.NURSE, nurseAssignment.getRole());
        assertEquals(AssignmentScope.BRANCH, nurseAssignment.getScope());
        assertEquals(branch, nurseAssignment.getBranch());
        assertNull(nurseAssignment.getDepartment());
    }

    @Test
    void provisioningFabricatesNothingWithoutAnOrganizationOrAnActiveBranch() throws Exception {
        UserAccountRepository accounts = mock(UserAccountRepository.class);
        ActingAssignmentRepository assignments = mock(ActingAssignmentRepository.class);
        HospitalOrganizationRepository organizations = mock(HospitalOrganizationRepository.class);
        BranchRepository branches = mock(BranchRepository.class);

        when(accounts.findByUsername(anyString())).thenReturn(Optional.empty());
        when(organizations.findAll(any(Sort.class))).thenReturn(List.of());
        runProvisioning(accounts, assignments, organizations, branches, true);
        verify(assignments, never()).save(any());
        verify(accounts, never()).save(any());

        HospitalOrganization organization = new HospitalOrganization("DEMO-ORG-001", "Demo Synthetic Hospital");
        when(organizations.findAll(any(Sort.class))).thenReturn(List.of(organization));
        when(branches.findByOrganizationIdAndActiveTrueOrderByCodeAsc(organization.getId())).thenReturn(List.of());
        runProvisioning(accounts, assignments, organizations, branches, true);
        verify(assignments, never()).save(any());
        verify(accounts, never()).save(any());
    }

    @Test
    void repeatProvisioningIsIdempotentAndNeverMutatesAccountsOrAssignments() throws Exception {
        UserAccountRepository accounts = mock(UserAccountRepository.class);
        ActingAssignmentRepository assignments = mock(ActingAssignmentRepository.class);
        HospitalOrganizationRepository organizations = mock(HospitalOrganizationRepository.class);
        BranchRepository branches = mock(BranchRepository.class);
        HierarchyFixtures fixtures = stubHierarchy(accounts, "admin", organizations, branches);
        ActingAssignment existing = ActingAssignment.organization(
                fixtures.account(), fixtures.organization(), Role.ADMIN);
        when(assignments.findByAccountIdAndRoleAndScopeAndBranchIsNullAndDepartmentIsNull(
                any(), any(), any())).thenReturn(Optional.of(existing));

        runProvisioning(accounts, assignments, organizations, branches, false);

        verify(assignments, never()).save(any());
        verify(accounts, never()).save(any());
        assertEquals("pre-existing-untouched-hash", fixtures.account().getPasswordHash(),
                "an existing account's password must never be reset by provisioning");
    }

    @Test
    void provisioningCoversOnlyTheNamedBootstrapAccountsBehindTheReviewGate() throws Exception {
        UserAccountRepository accounts = mock(UserAccountRepository.class);
        ActingAssignmentRepository assignments = mock(ActingAssignmentRepository.class);
        HospitalOrganizationRepository organizations = mock(HospitalOrganizationRepository.class);
        BranchRepository branches = mock(BranchRepository.class);
        stubHierarchy(accounts, "admin", organizations, branches);
        stubHierarchy(accounts, "doctor", organizations, branches);
        stubHierarchy(accounts, "nurse", organizations, branches);
        when(assignments.findByAccountIdAndRoleAndScopeAndBranchIsNullAndDepartmentIsNull(
                any(), any(), any())).thenReturn(Optional.empty());

        runProvisioning(accounts, assignments, organizations, branches, false);

        verify(accounts).findByUsername("admin");
        verify(accounts, never()).findByUsername("doctor");
        verify(accounts, never()).findByUsername("nurse");
        verify(assignments, times(1)).save(any(ActingAssignment.class));
    }
}