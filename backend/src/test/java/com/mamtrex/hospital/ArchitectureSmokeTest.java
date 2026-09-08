package com.mamtrex.hospital;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Minimal application-context smoke test. Runs fully test-local: an isolated
 * in-memory H2 database (never the production file store) and disposable
 * synthetic JWT/admin-seed secrets, so no runtime environment or .env file
 * is required.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:smoke-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "hospital.jwt.secret=" + ArchitectureSmokeTest.TEST_JWT_SECRET,
        "HOSPITAL_ADMIN_PASSWORD=" + ArchitectureSmokeTest.TEST_ADMIN_PASSWORD
})
class ArchitectureSmokeTest {

    /** Long disposable test-only value; never a production secret. */
    static final String TEST_JWT_SECRET =
            "disposable-test-only-secret-9876543210abcdef9876543210abcdef";

    /** Long disposable test-only value; never a production credential. */
    static final String TEST_ADMIN_PASSWORD = "disposable-test-password-987654";

    @Test
    void contextLoads() {
    }
}
