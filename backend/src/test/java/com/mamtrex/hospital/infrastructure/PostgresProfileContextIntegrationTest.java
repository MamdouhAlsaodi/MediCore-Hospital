package com.mamtrex.hospital.infrastructure;

import com.mamtrex.hospital.TestRuntimeSecrets;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US1 acceptance scenario 1: the shipped PostgreSQL review configuration
 * boots the real application against a Flyway-migrated disposable
 * PostgreSQL database with Hibernate in validate mode. A successful boot
 * proves the reviewed baseline matches the accepted entity model and that
 * Flyway is the only schema authority (ddl-auto=validate cannot alter it).
 */
@SpringBootTest(properties = {
        "spring.profiles.active=postgres",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class PostgresProfileContextIntegrationTest {

    @DynamicPropertySource
    static void reviewDataSource(DynamicPropertyRegistry registry) {
        var db = PostgresContainerSupport.newIsolatedDatabase();
        Flyway.configure()
                .locations("classpath:db/migration")
                .dataSource(db.jdbcUrl(), db.user(), db.password())
                .load()
                .migrate();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::user);
        registry.add("spring.datasource.password", db::password);
        registry.add("hospital.jwt.secret", TestRuntimeSecrets::jwtSecret);
        registry.add("HOSPITAL_ADMIN_PASSWORD", TestRuntimeSecrets::accountPassword);
    }

    @Test
    void contextBootsAgainstFlywayManagedSchema(@Autowired org.springframework.context.ApplicationContext context) {
        assertTrue(context.containsBean("entityManagerFactory"),
                "the JPA context must boot in validate mode against the Flyway-managed schema");
    }
}
