package com.mamtrex.hospital.observability;

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
 * T051/T052/T054/T055 shipped-configuration contract: the YAML and Logback
 * resources must declare the observability boundary exactly — probes on,
 * liveness process-only, readiness requiring the database (and Flyway on the
 * PostgreSQL profile), a minimal Prometheus web exposure, a fast-failing
 * database pool so readiness probes stay responsive during loss, and
 * structured JSON console logging as the single log output.
 *
 * <p>These assertions run against the shipped resources with no database and
 * no secrets; the live semantics are proven separately by
 * {@code LivenessReadinessDbLossIntegrationTest} and the captured-output
 * sanitization tests.
 */
class ObservabilityConfigContractTest {

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

    /** T051: probes are enabled by the shipped default profile, not only by deployers. */
    @Test
    void healthProbesAreExplicitlyEnabled() throws Exception {
        Map<String, Object> probes = nested(load("application.yml"),
                "management", "endpoint", "health", "probes");
        assertEquals(Boolean.TRUE, probes.get("enabled"), "health probes must be explicitly enabled");
    }

    /** T051/FR-008: liveness is process-only — it must never include the database. */
    @Test
    void livenessGroupIsProcessOnly() throws Exception {
        Map<String, Object> liveness = nested(load("application.yml"),
                "management", "endpoint", "health", "group", "liveness");
        assertEquals("livenessState", liveness.get("include"),
                "liveness must consult exactly the process liveness state");
    }

    /** T051/FR-008: readiness must consult the database so dependency loss fails readiness. */
    @Test
    void readinessGroupRequiresTheDatabase() throws Exception {
        Map<String, Object> readiness = nested(load("application.yml"),
                "management", "endpoint", "health", "group", "readiness");
        assertEquals("readinessState,db", readiness.get("include"),
                "readiness must require the process readiness state AND the database");
    }

    /** T052/FR-010: the web exposure is the explicitly required minimal set. */
    @Test
    void webExposureIsTheExplicitMinimalSet() throws Exception {
        Map<String, Object> exposure = nested(load("application.yml"),
                "management", "endpoints", "web", "exposure");
        assertEquals("health,prometheus", exposure.get("include"),
                "exactly health and prometheus may be web-exposed; everything else stays unexposed");
    }

    /** T051: the PostgreSQL review profile readiness group matches the default bounded set. */
    @Test
    void postgresReadinessGroupMatchesTheBoundedSet() throws Exception {
        Map<String, Object> readiness = nested(load("application-postgres.yml"),
                "management", "endpoint", "health", "group", "readiness");
        assertEquals("readinessState,db", readiness.get("include"),
                "the PostgreSQL profile readiness must require the process readiness state AND the database; "
                        + "migration state gates startup itself (Flyway runs before readiness exists), "
                        + "and Boot 3.5 ships no Flyway health contributor");
    }

    /** T051: database acquisition fails fast so readiness probes stay responsive during loss. */
    @Test
    void postgresPoolFailsFastForProbeResponsiveness() throws Exception {
        Map<String, Object> hikari = nested(load("application-postgres.yml"), "spring", "datasource", "hikari");
        assertEquals(2000, hikari.get("connection-timeout"),
                "the PostgreSQL pool must bound connection acquisition to keep readiness probes responsive");
    }

    /** T054/FR-007: the only log output is the console, structured by the production encoder. */
    @Test
    void loggingIsStructuredJsonThroughTheProductionEncoder() throws Exception {
        ClassPathResource logback = new ClassPathResource("logback-spring.xml");
        assertTrue(logback.exists(), "logback-spring.xml must exist");
        String xml = new String(logback.getInputStream().readAllBytes());
        assertTrue(xml.contains("com.mamtrex.hospital.infrastructure.StructuredJsonEncoder"),
                "the console output must be encoded by the production structured JSON encoder");
        assertTrue(xml.contains("ConsoleAppender"), "the console must be a log output");
        assertTrue(xml.contains("<root level=\"INFO\">"), "the root logger must be declared explicitly");
        assertFalse(xml.contains("FileAppender"), "no file output is part of the shipped logging contract");
    }
}
