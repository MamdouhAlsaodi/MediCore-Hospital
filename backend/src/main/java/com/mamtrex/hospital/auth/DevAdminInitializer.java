package com.mamtrex.hospital.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

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
}
