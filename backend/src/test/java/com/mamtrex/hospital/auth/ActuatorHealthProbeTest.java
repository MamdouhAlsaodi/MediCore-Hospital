package com.mamtrex.hospital.auth;

import com.mamtrex.hospital.TestRuntimeSecrets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 4 health-probe boundary (FR-008/FR-010): the health probes are the
 * ONLY anonymous actuator surface. Liveness stays process-only; readiness is
 * anonymous so the container/compose healthchecks can gate service order;
 * every other actuator endpoint (metrics, env, ...) stays authenticated with
 * the shared generic 401. Anonymous = no Authorization header at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.endpoint.health.probes.enabled=true"
})
class ActuatorHealthProbeTest {

    @DynamicPropertySource
    static void runtimeSecrets(DynamicPropertyRegistry registry) {
        registry.add("hospital.jwt.secret", TestRuntimeSecrets::jwtSecret);
        registry.add("HOSPITAL_ADMIN_PASSWORD", TestRuntimeSecrets::accountPassword);
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    void anonymousLivenessIsPublicAndProcessOnly() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health/liveness", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void anonymousReadinessIsPublicForOrchestrationHealthchecks() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health/readiness", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void anonymousMetricsStayProtected() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/metrics", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
}
