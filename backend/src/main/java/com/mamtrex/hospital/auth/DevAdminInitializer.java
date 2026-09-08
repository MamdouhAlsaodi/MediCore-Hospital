package com.mamtrex.hospital.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Set;

/**
 * Seeds the initial {@code admin} account only when it does not exist yet.
 * The password comes exclusively from the {@code HOSPITAL_ADMIN_PASSWORD}
 * runtime environment variable; no default password exists in code.
 */
@Configuration
public class DevAdminInitializer {

    static final int MIN_ADMIN_PASSWORD_LENGTH = 12;

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
}
