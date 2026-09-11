package com.mamtrex.hospital.auth;

import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
import com.mamtrex.hospital.organization.HospitalOrganization;
import com.mamtrex.hospital.organization.HospitalOrganizationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationListener;
import org.springframework.data.domain.Sort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;

/**
 * Seeds the initial {@code admin} account only when it does not exist yet.
 * The password comes exclusively from the {@code HOSPITAL_ADMIN_PASSWORD}
 * runtime environment variable; no default password exists in code.
 *
 * <p>Additionally — and only when explicitly enabled through
 * {@code medicore.review-accounts.enabled} (typically supplied as the
 * {@code MEDICORE_REVIEW_ACCOUNTS_ENABLED} runtime environment variable) —
 * startup creates the {@code doctor} and {@code nurse} review accounts for a
 * local training/review environment. Their passwords come exclusively from
 * {@code HOSPITAL_REVIEW_DOCTOR_PASSWORD} and
 * {@code HOSPITAL_REVIEW_NURSE_PASSWORD}; a missing, blank, or too-short
 * value fails startup with a message that names only the offending variable,
 * never any value. Both creations are lookup-before-create, so an existing
 * account is never duplicated, and its password, roles, or any other field
 * are never modified — repeated startups are idempotent. With the flag false
 * or absent the bean does not exist at all: behavior is exactly the admin
 * bootstrap alone and the review-password variables are never required.</p>
 *
 * <p>Plan 3 Task 3 (packet MEDICORE-PLAN3-TASK3-050) adds idempotent
 * bootstrap assignment provisioning: the named accounts this initializer
 * owns (admin, plus the opt-in review accounts) each receive one missing
 * acting assignment — ADMIN as ORGANIZATION scope, review accounts as
 * BRANCH scope on the deterministic active default branch — and existing
 * accounts are never mutated in any field. Provisioning rides an
 * {@link ApplicationReadyEvent} listener bean, which Spring fires strictly
 * after every {@code Runner}, so the opt-in demo hierarchy deterministically
 * exists before provisioning when both are enabled, without modifying the
 * demo initializer. With no organization or no active branch, nothing is
 * fabricated: account creation follows the established contract and login
 * fails closed until hierarchy and assignment provisioning exist.</p>
 */
@Configuration
public class DevAdminInitializer {

    static final int MIN_ADMIN_PASSWORD_LENGTH = 12;
    static final int MIN_REVIEW_PASSWORD_LENGTH = 12;

    @Bean
    CommandLineRunner seedAdmin(UserAccountRepository repo,
                                PasswordEncoder encoder,
                                @Value("${HOSPITAL_ADMIN_PASSWORD:}") String adminPassword) {
        return args -> {
            if (repo.findByUsername("admin").isPresent()) {
                return; // An existing admin account is never mutated.
            }
            if (adminPassword == null || adminPassword.isBlank()
                    || adminPassword.length() < MIN_ADMIN_PASSWORD_LENGTH) {
                throw new IllegalStateException(
                        "Missing or invalid configuration: HOSPITAL_ADMIN_PASSWORD must be set "
                                + "in the runtime environment and be at least "
                                + MIN_ADMIN_PASSWORD_LENGTH
                                + " characters long so the initial admin account can be created.");
            }
            repo.save(new UserAccount("admin", encoder.encode(adminPassword), Set.of(Role.ADMIN)));
        };
    }

    /**
     * Opt-in review-account runner for training/review environments. The
     * conditional gate keeps this bean (and therefore the review-password
     * requirements) entirely absent unless the property is explicitly
     * {@code true}.
     */
    @Bean
    @ConditionalOnProperty(name = "medicore.review-accounts.enabled", havingValue = "true")
    CommandLineRunner seedReviewAccounts(UserAccountRepository repo,
                                         PasswordEncoder encoder,
                                         @Value("${HOSPITAL_REVIEW_DOCTOR_PASSWORD:}") String doctorPassword,
                                         @Value("${HOSPITAL_REVIEW_NURSE_PASSWORD:}") String nursePassword) {
        return args -> bootstrapReviewAccounts(repo, encoder, doctorPassword, nursePassword);
    }

    /** Creates the review accounts; safe to call repeatedly (idempotent). */
    void bootstrapReviewAccounts(UserAccountRepository repo, PasswordEncoder encoder,
                                 String doctorPassword, String nursePassword) {
        requireReviewPassword("HOSPITAL_REVIEW_DOCTOR_PASSWORD", doctorPassword);
        requireReviewPassword("HOSPITAL_REVIEW_NURSE_PASSWORD", nursePassword);
        createReviewAccountIfAbsent(repo, encoder, "doctor", Set.of(Role.DOCTOR), doctorPassword);
        createReviewAccountIfAbsent(repo, encoder, "nurse", Set.of(Role.NURSE), nursePassword);
    }

    private void requireReviewPassword(String variableName, String password) {
        if (password == null || password.isBlank() || password.length() < MIN_REVIEW_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "Missing or invalid configuration: " + variableName
                            + " must be set in the runtime environment and be at least "
                            + MIN_REVIEW_PASSWORD_LENGTH
                            + " characters long because review accounts are enabled"
                            + " (medicore.review-accounts.enabled=true).");
        }
    }

    private void createReviewAccountIfAbsent(UserAccountRepository repo, PasswordEncoder encoder,
                                             String username, Set<Role> roles, String password) {
        if (repo.findByUsername(username).isPresent()) {
            return; // An existing account is never mutated, duplicated, or reset.
        }
        repo.save(new UserAccount(username, encoder.encode(password), roles));
    }

    /**
     * The assignment-provisioning listener: an {@link ApplicationReadyEvent}
     * fires strictly after all {@code Runner} beans have run, which is the
     * deterministic ordering guarantee this contract needs — the opt-in demo
     * hierarchy always exists before assignments are provisioned when both
     * are enabled. Provisioning is lookup-before-create (idempotent), covers
     * only the named bootstrap accounts, and never mutates an existing
     * account's password, legacy roles, or any other field.
     */
    @Bean
    ApplicationListener<ApplicationReadyEvent> provisionBootstrapAssignments(
            UserAccountRepository accounts,
            ActingAssignmentRepository assignments,
            HospitalOrganizationRepository organizations,
            BranchRepository branches,
            @Value("${medicore.review-accounts.enabled:false}") boolean reviewAccountsEnabled) {
        return event -> provisionNamedBootstrapAccounts(accounts, assignments, organizations, branches,
                reviewAccountsEnabled);
    }

    /** Provisioning body; package-private so the unit suite can exercise it directly. */
    void provisionNamedBootstrapAccounts(UserAccountRepository accounts,
                                       ActingAssignmentRepository assignments,
                                       HospitalOrganizationRepository organizations,
                                       BranchRepository branches,
                                       boolean reviewAccountsEnabled) {
        provisionBootstrapAssignment(accounts, assignments, organizations, branches,
                "admin", Role.ADMIN, true);
        if (reviewAccountsEnabled) {
            provisionBootstrapAssignment(accounts, assignments, organizations, branches,
                    "doctor", Role.DOCTOR, false);
            provisionBootstrapAssignment(accounts, assignments, organizations, branches,
                    "nurse", Role.NURSE, false);
        }
    }

    /**
     * One named account: when the account exists and is enabled, the
     * hierarchy exists, and the matching assignment is missing, create it.
     * ORGANIZATION scope for ADMIN; BRANCH scope on the deterministic active
     * default branch for the review accounts. Nothing is fabricated without
     * a hierarchy.
     */
    private void provisionBootstrapAssignment(UserAccountRepository accounts,
                                              ActingAssignmentRepository assignments,
                                              HospitalOrganizationRepository organizations,
                                              BranchRepository branches,
                                              String username, Role role, boolean organizationScope) {
        deterministicHierarchy(organizations, branches).ifPresent(hierarchy ->
                accounts.findByUsername(username)
                        .filter(UserAccount::isEnabled)
                        .ifPresent(account -> {
                            ActingAssignment existing = organizationScope
                                    ? assignments.findByAccountIdAndRoleAndScopeAndBranchIsNullAndDepartmentIsNull(
                                            account.getId(), role, AssignmentScope.ORGANIZATION).orElse(null)
                                    : assignments.findByAccountIdAndRoleAndScopeAndBranchId(
                                            account.getId(), role, AssignmentScope.BRANCH,
                                            hierarchy.branch().getId()).orElse(null);
                            if (existing != null) {
                                return; // An existing assignment is never duplicated or modified.
                            }
                            ActingAssignment created = organizationScope
                                    ? ActingAssignment.organization(account, hierarchy.organization(), role)
                                    : ActingAssignment.branch(account, hierarchy.organization(), role,
                                            hierarchy.branch());
                            assignments.save(created);
                        }));
    }

    /** One organization plus its deterministic active default branch, or empty when no hierarchy exists. */
    private record Hierarchy(HospitalOrganization organization, Branch branch) {
    }

    private Optional<Hierarchy> deterministicHierarchy(HospitalOrganizationRepository organizations,
                                                       BranchRepository branches) {
        return organizations.findAll(Sort.by(Sort.Direction.ASC, "code")).stream()
                .flatMap(organization -> branches.findByOrganizationIdAndActiveTrueOrderByCodeAsc(organization.getId())
                        .stream()
                        .findFirst()
                        .map(branch -> new Hierarchy(organization, branch))
                        .stream())
                .findFirst();
    }
}