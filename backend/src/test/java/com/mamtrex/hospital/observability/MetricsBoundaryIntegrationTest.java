package com.mamtrex.hospital.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mamtrex.hospital.TestRuntimeSecrets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T052/T055: the Prometheus exposure and bounded-metrics boundary
 * (FR-008/FR-010). The Prometheus endpoint is an explicitly required review
 * surface but it is NOT anonymous: every non-health actuator endpoint stays
 * authenticated. The scraped output is a security boundary: hostile or
 * sensitive values submitted to the real HTTP surface (bearer-token
 * canaries, login-password canaries, path-variable id canaries) must never
 * appear in it, and the request metric's label set must stay bounded and
 * templated — route labels are URI patterns, never path-variable values.
 *
 * <p>Runs against the lightweight isolated H2 developer flow with the
 * opt-in synthetic demo cohort (which deterministically provisions the
 * admin acting assignment), so no runtime environment or .env file is
 * required and no real credential exists anywhere.
 *
 * <p>{@code @AutoConfigureObservability} is REQUIRED here: Spring Boot's
 * test support deliberately disables metrics-export auto-configuration in
 * {@code @SpringBootTest} contexts unless a test opts in, and this test
 * owns the live Prometheus contract.
 */
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:metrics-boundary-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "medicore.demo.seed=true"
})
class MetricsBoundaryIntegrationTest {

    /** Distinct canaries submitted to the live HTTP surface; none may ever reach telemetry. */
    private static final String TOKEN_CANARY = "CANARY-BEARER-TOKEN-k3Xw9QvLpW7";
    private static final String PASSWORD_CANARY = "CANARY-LOGIN-PASSWORD-8sKd2MfJhR4z";
    private static final String PATH_ID_CANARY = "CANARY-PATH-ID-9qWx4JcVnT2b";

    /** The only route-template label values the exercised traffic may produce. */
    private static final Set<String> ALLOWED_URI_LABELS = Set.of(
            "UNKNOWN", "root", "/api/auth/login", "/api/patients", "/api/patients/{id}",
            "/actuator/prometheus");

    /** The only label keys the bounded request metric may carry — exactly Spring Boot 3.5's designed server-request label vocabulary (all low-cardinality). */
    private static final Set<String> ALLOWED_REQUEST_LABEL_KEYS = Set.of(
            "error", "exception", "method", "outcome", "status", "uri");

    private static final Pattern URI_LABEL = Pattern.compile("uri=\"([^\"]*)\"");
    private static final Pattern SAMPLE_LABEL_KEY = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)=\"");
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void runtimeSecrets(DynamicPropertyRegistry registry) {
        registry.add("hospital.jwt.secret", TestRuntimeSecrets::jwtSecret);
        registry.add("HOSPITAL_ADMIN_PASSWORD", TestRuntimeSecrets::accountPassword);
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    void prometheusEndpointStaysAuthenticated() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/prometheus", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(),
                "the Prometheus endpoint is exposed for authenticated review only — never anonymous");
    }

    @Test
    void authenticatedScrapeIsBoundedAndRejectsSensitiveCanaries() throws Exception {
        String token = loginAsAdmin();

        // Drive real traffic whose hostile values must never become telemetry:
        // a valid template request, a bearer-token canary, a login-password
        // canary, and a path-variable id canary.
        HttpHeaders authenticated = new HttpHeaders();
        authenticated.setBearerAuth(token);
        assertEquals(HttpStatus.OK, exchange("/api/patients", HttpMethod.GET, authenticated, null).getStatusCode());

        HttpHeaders bearerCanary = new HttpHeaders();
        bearerCanary.setBearerAuth(TOKEN_CANARY);
        assertEquals(HttpStatus.UNAUTHORIZED,
                exchange("/api/patients", HttpMethod.GET, bearerCanary, null).getStatusCode());

        HttpHeaders passwordCanary = new HttpHeaders();
        passwordCanary.setContentType(MediaType.APPLICATION_JSON);
        assertEquals(HttpStatus.UNAUTHORIZED, exchange("/api/auth/login", HttpMethod.POST, passwordCanary,
                "{\"username\":\"admin\",\"password\":\"" + PASSWORD_CANARY + "\"}").getStatusCode());

        // A non-UUID path-variable canary is a malformed path value (400) — the
        // important assertion is below: it must be treated as the bounded
        // template /api/patients/{id}, never recorded as a raw identifier.
        assertEquals(HttpStatus.BAD_REQUEST,
                exchange("/api/patients/" + PATH_ID_CANARY, HttpMethod.GET, authenticated, null).getStatusCode());

        ResponseEntity<String> scrape = exchange("/actuator/prometheus", HttpMethod.GET, authenticated, null);
        assertEquals(HttpStatus.OK, scrape.getStatusCode(),
                "an authenticated review scrape must succeed (body=" + scrape.getBody()
                        + ", actuator links=" + exchange("/actuator", HttpMethod.GET, authenticated, null).getBody()
                        + ")");
        String body = scrape.getBody();

        // The bounded HTTP request metric exists.
        assertTrue(body.contains("# TYPE http_server_requests_seconds"),
                "the Prometheus output must expose the bounded http.server.requests metric");

        // Route labels are bounded URI templates — never raw path-variable values.
        Set<String> uriLabels = new HashSet<>();
        Matcher uriMatcher = URI_LABEL.matcher(body);
        while (uriMatcher.find()) {
            uriLabels.add(uriMatcher.group(1));
        }
        assertTrue(uriLabels.size() <= ALLOWED_URI_LABELS.size(),
                () -> "route label cardinality exploded: " + uriLabels.size() + " distinct values");
        assertTrue(ALLOWED_URI_LABELS.containsAll(uriLabels),
                () -> "unexpected route label values outside the bounded template set: " + uriLabels);

        // The request metric's label keys stay exactly inside the designed bounded set.
        for (String line : body.split("\\R")) {
            if (line.startsWith("http_server_requests_seconds_count{")) {
                Matcher keyMatcher = SAMPLE_LABEL_KEY.matcher(line);
                while (keyMatcher.find()) {
                    assertTrue(ALLOWED_REQUEST_LABEL_KEYS.contains(keyMatcher.group(1)),
                            () -> "unexpected label key on the request metric: " + keyMatcher.group(1));
                }
            }
        }

        // No submitted sensitive value may appear anywhere in the scrape.
        for (String canary : List.of(TOKEN_CANARY, PASSWORD_CANARY, PATH_ID_CANARY)) {
            assertFalse(body.contains(canary), () -> "sensitive canary leaked into metrics output");
        }
    }

    private String loginAsAdmin() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"admin\",\"password\":\""
                        + TestRuntimeSecrets.accountPassword() + "\"}", headers), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "the synthetic admin review login must succeed");
        return (String) JSON.readTree(response.getBody()).get("accessToken").asText();
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, HttpHeaders headers, String body) {
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }
}
