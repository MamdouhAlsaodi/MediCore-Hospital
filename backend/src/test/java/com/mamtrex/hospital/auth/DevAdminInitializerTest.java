package com.mamtrex.hospital.auth;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
}
