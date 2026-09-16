package com.mamtrex.hospital;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 profile-authority configuration test (plan Task 2, step 1; FR-001).
 *
 * Loads the real shipped configuration resources and proves the PostgreSQL
 * review profile is migration-controlled (Flyway enabled, Hibernate
 * validate-only, environment-only credentials with no baked-in defaults)
 * while the lightweight H2 developer profile stays explicitly isolated
 * (Flyway disabled there, no silent inheritance). These assertions run
 * against the shipped YAML — no database and no secrets required.
 */
class PostgresProfileConfigTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String location) throws Exception {
        Yaml yaml = new Yaml();
        try (InputStream in = new ClassPathResource(location).getInputStream()) {
            Map<String, Object> root = yaml.load(in);
            assertNotNull(root, location + " must be present and parseable");
            return root;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> root, String... path) {
        Map<String, Object> current = root;
        for (String key : path) {
            Object next = current.get(key);
            assertNotNull(next, "missing config key: " + String.join(".", path));
            assertTrue(next instanceof Map, "config key is not a map: " + String.join(".", path));
            current = (Map<String, Object>) next;
        }
        return current;
    }

    /** FR-001: the PostgreSQL review profile is Flyway-managed and validate-only. */
    @Test
    void postgresProfileUsesFlywayAndValidate() throws Exception {
        Map<String, Object> spring = nested(load("application-postgres.yml"), "spring");
        Map<String, Object> flyway = (Map<String, Object>) spring.get("flyway");
        assertNotNull(flyway, "postgres profile must configure spring.flyway explicitly");
        assertEquals(Boolean.TRUE, flyway.get("enabled"), "Flyway must be enabled on the PostgreSQL profile");

        Map<String, Object> jpa = nested(spring, "jpa", "hibernate");
        assertEquals("validate", String.valueOf(jpa.get("ddl-auto")),
                "PostgreSQL profile must use Hibernate validate; ddl-auto=update is forbidden");
    }

    /** FR-001: the default H2 developer profile never runs Flyway (explicit isolation). */
    @Test
    void defaultProfileKeepsFlywayExplicitlyDisabled() throws Exception {
        Map<String, Object> spring = nested(load("application.yml"), "spring");
        Map<String, Object> flyway = (Map<String, Object>) spring.get("flyway");
        assertNotNull(flyway, "default profile must state its Flyway intent explicitly");
        assertEquals(Boolean.FALSE, flyway.get("enabled"),
                "H2 developer profile must keep Flyway disabled explicitly");
    }

    /** Credentials on the PostgreSQL profile are environment placeholders only — no defaults. */
    @Test
    void postgresProfileCredentialsAreEnvironmentOnly() throws Exception {
        String raw = new String(new ClassPathResource("application-postgres.yml").getInputStream().readAllBytes());
        for (String variable : new String[] {"DB_URL", "DB_USER", "DB_PASSWORD"}) {
            assertTrue(raw.contains("${" + variable + "}"),
                    "datasource value must be the bare environment placeholder ${" + variable + "}");
        }
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("password:")) {
                assertEquals("password: ${DB_PASSWORD}", trimmed,
                        "the only password line in tracked configuration must be the environment placeholder");
            }
        }
    }

    /** The versioned migration authority exists and is forward-only ordered. */
    @Test
    void versionedMigrationsExist() {
        for (String migration : new String[] {
                "db/migration/V1__baseline_schema.sql",
                "db/migration/V2__phase4_constraints.sql"}) {
            assertTrue(new ClassPathResource(migration).exists(), migration + " must exist");
        }
    }
}
