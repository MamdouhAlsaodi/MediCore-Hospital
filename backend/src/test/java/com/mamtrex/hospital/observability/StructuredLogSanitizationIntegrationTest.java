package com.mamtrex.hospital.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mamtrex.hospital.TestRuntimeSecrets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T053/T054: the structured-log boundary (FR-007). Every captured line must
 * be one well-formed JSON object carrying the required bounded fields
 * (timestamp, level, logger, thread, message), the per-request observation
 * lines must carry method/route/status/duration-bucket and exactly the
 * correlation id issued by the existing {@code CorrelationIdFilter} (the
 * single correlation authority), and no sensitive canary submitted to the
 * real HTTP surface — bearer token, login password, request body, patient
 * field, path-variable id, the real issued access token, or the real admin
 * password — may appear anywhere in the output.
 *
 * <p>The capture renders events through the production
 * {@code StructuredJsonEncoder}, so what is asserted is exactly what the
 * application emits. Runs against the isolated H2 developer flow with the
 * opt-in synthetic demo cohort; no runtime environment or real credential
 * is involved anywhere.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:structured-log-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "medicore.demo.seed=true"
})
class StructuredLogSanitizationIntegrationTest {

    /** Distinct canaries submitted to the live HTTP surface; none may ever reach the logs. */
    private static final String TOKEN_CANARY = "CANARY-BEARER-TOKEN-t4Yz8RwMnQ6";
    private static final String PASSWORD_CANARY = "CANARY-LOGIN-PASSWORD-7kJf3GdSbH9x";
    private static final String PATIENT_CANARY = "CANARY-PATIENT-NAME-2vBn6YhGkM5";
    private static final String PATH_ID_CANARY = "CANARY-PATH-ID-5rTq1ZxCvB8n";

    private static final String OBSERVATION_LOGGER = "http.request";
    private static final Set<String> DURATION_BUCKETS = Set.of(
            "under_100ms", "100_499ms", "500_999ms", "1_4s", "5s_or_more");
    /** Route templates the exercised traffic may produce — bounded, never raw ids. */
    private static final Set<String> ALLOWED_ROUTES = Set.of(
            "unmatched", "/api/auth/login", "/api/patients", "/api/patients/{id}");
    private static final Pattern SAFE_CORRELATION_SHAPE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern UUID_SHAPE = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void runtimeSecrets(DynamicPropertyRegistry registry) {
        registry.add("hospital.jwt.secret", TestRuntimeSecrets::jwtSecret);
        registry.add("HOSPITAL_ADMIN_PASSWORD", TestRuntimeSecrets::accountPassword);
    }

    @Autowired
    TestRestTemplate rest;

    private StructuredLogCapture.Appender capture;

    @BeforeEach
    void attachCapture() {
        capture = StructuredLogCapture.attach();
    }

    @AfterEach
    void detachCapture() {
        StructuredLogCapture.detach(capture);
    }

    @Test
    void everyEmittedLineIsOneValidJsonObjectWithTheRequiredBoundedFields() throws Exception {
        // One successful request through the whole stack is enough to prove
        // the runtime format of every line the process emits.
        String token = loginAsAdmin();
        HttpHeaders headers = authed(token);
        headers.set("X-Correlation-Id", "corr-format-ok-1");
        assertEquals(HttpStatus.OK, exchange("/api/patients", HttpMethod.GET, headers, null).getStatusCode());

        List<String> lines = StructuredLogCapture.detach(capture);
        assertFalse(lines.isEmpty(), "the capture must have observed log events");
        Set<String> levels = new HashSet<>();
        Set<String> boundedFieldVocabulary = Set.of(
                "timestamp", "level", "logger", "thread", "message",
                "correlationId", "http_method", "http_route", "http_status", "duration_bucket");
        for (String line : lines) {
            JsonNode node = JSON.readTree(line);
            assertTrue(node.isObject(), () -> "emitted line is not one JSON object: " + line);
            Set<String> fields = fieldNamesOf(node);
            assertTrue(fields.containsAll(Set.of("timestamp", "level", "logger", "thread", "message")),
                    () -> "line must carry the bounded base fields, got: " + fields);
            assertTrue(boundedFieldVocabulary.containsAll(fields),
                    () -> "line carries fields outside the bounded vocabulary: " + fields);
            assertParseableInstant(node.get("timestamp").asText());
            levels.add(node.get("level").asText());
            assertFalse(node.get("logger").asText().isBlank());
            assertFalse(node.get("thread").asText().isBlank());
            assertFalse(node.get("message").asText().isBlank());
        }
        assertTrue(levels.stream().allMatch(l -> List.of("ERROR", "WARN", "INFO", "DEBUG", "TRACE").contains(l)),
                () -> "unexpected log level values: " + levels);
    }

    @Test
    void correlationIdPropagatesFromTheExistingAuthorityAndHostileHeadersAreReplaced() throws Exception {
        String token = loginAsAdmin();

        // Valid header: the log line must carry exactly the issued correlation id.
        HttpHeaders valid = authed(token);
        valid.set("X-Correlation-Id", "corr-propagation-ok-2");
        ResponseEntity<String> response = exchange("/api/patients", HttpMethod.GET, valid, null);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("corr-propagation-ok-2", response.getHeaders().getFirst("X-Correlation-Id"));

        // Hostile header: over-long with injection characters — the filter's
        // bounded replacement must be what the log carries, never the input.
        HttpHeaders hostile = new HttpHeaders();
        hostile.setBearerAuth(TOKEN_CANARY);
        hostile.set("X-Correlation-Id", "!!bad id<script>" + TOKEN_CANARY + "!!" + "x".repeat(200));
        assertEquals(HttpStatus.UNAUTHORIZED, exchange("/api/patients", HttpMethod.GET, hostile, null).getStatusCode());

        // No header: the server generates the id.
        HttpHeaders anonymous = new HttpHeaders();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange("/api/patients", HttpMethod.GET, anonymous, null).getStatusCode());

        List<String> lines = StructuredLogCapture.detach(capture);
        List<JsonNode> observations = observationLines(lines);

        // The admin login POST is itself observed (status 200, server-generated
        // id); the propagated correlation id is asserted on the AUTHENTICATED GET.
        JsonNode propagated = observations.stream()
                .filter(node -> node.get("http_status").asInt() == 200)
                .filter(node -> node.get("http_method").asText().equals("GET"))
                .findFirst().orElseThrow();
        assertEquals("corr-propagation-ok-2", propagated.get("correlationId").asText(),
                "the request log must carry exactly the correlation id issued by the existing filter");

        // Both 401 observations (hostile header, then absent header) must carry
        // a server-generated bounded UUID — never the injected hostile value.
        List<JsonNode> unauthorized = observations.stream()
                .filter(node -> node.get("http_status").asInt() == 401 && node.get("http_method").asText().equals("GET"))
                .toList();
        assertEquals(2, unauthorized.size(), "both unauthenticated GET observations must be present");
        for (JsonNode node : unauthorized) {
            assertTrue(UUID_SHAPE.matcher(node.get("correlationId").asText()).matches(),
                    "a hostile or absent header must be replaced by the server-generated bounded id");
        }

        for (JsonNode node : observations) {
            String correlationId = node.get("correlationId").asText();
            assertTrue(SAFE_CORRELATION_SHAPE.matcher(correlationId).matches(),
                    () -> "correlation id outside the bounded safe shape: " + correlationId);
        }
        assertFalse(lines.stream().anyMatch(line -> line.contains(TOKEN_CANARY)),
                "the hostile header value must never reach the logs");
    }

    @Test
    void sensitiveCanariesNeverReachTheStructuredOutput() throws Exception {
        // Every canary below is submitted to the real HTTP boundary; the
        // correct business statuses prove the values were actually processed.
        HttpHeaders passwordCanary = new HttpHeaders();
        passwordCanary.setContentType(MediaType.APPLICATION_JSON);
        assertEquals(HttpStatus.UNAUTHORIZED, exchange("/api/auth/login", HttpMethod.POST, passwordCanary,
                "{\"username\":\"admin\",\"password\":\"" + PASSWORD_CANARY + "\"}").getStatusCode());

        HttpHeaders tokenCanary = new HttpHeaders();
        tokenCanary.setBearerAuth(TOKEN_CANARY);
        assertEquals(HttpStatus.UNAUTHORIZED,
                exchange("/api/patients", HttpMethod.GET, tokenCanary, null).getStatusCode());

        String token = loginAsAdmin();
        HttpHeaders authed = authedJson(token);
        assertEquals(HttpStatus.BAD_REQUEST,
                exchange("/api/patients/" + PATH_ID_CANARY, HttpMethod.GET, authed, null).getStatusCode());
        // Malformed body carrying a patient-field canary and a body canary.
        assertEquals(HttpStatus.BAD_REQUEST, exchange("/api/patients", HttpMethod.POST, authed,
                "{\"fullName\":\"" + PATIENT_CANARY + "\",\"mrn\":\"" + PATIENT_CANARY + "\"")
                .getStatusCode());

        List<String> lines = StructuredLogCapture.detach(capture);
        List<String> forbidden = List.of(TOKEN_CANARY, PASSWORD_CANARY, PATIENT_CANARY,
                PATH_ID_CANARY, token, TestRuntimeSecrets.accountPassword());
        for (String canary : forbidden) {
            for (String line : lines) {
                assertFalse(line.contains(canary),
                        () -> "sensitive value leaked into the structured log output");
            }
        }
        // The exercised statuses prove the canaries really traversed the app.
        List<Integer> statuses = observationLines(lines).stream()
                .map(node -> node.get("http_status").asInt()).toList();
        assertTrue(statuses.contains(401) && statuses.contains(400),
                () -> "canary requests must actually reach the boundary: " + statuses);
    }

    @Test
    void observationLinesCarryBoundedHttpFieldsOnly() throws Exception {
        String token = loginAsAdmin();
        HttpHeaders authed = authed(token);
        assertEquals(HttpStatus.OK, exchange("/api/patients", HttpMethod.GET, authed, null).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                exchange("/api/patients/" + PATH_ID_CANARY, HttpMethod.GET, authed, null).getStatusCode());

        List<String> lines = StructuredLogCapture.detach(capture);
        List<JsonNode> observations = observationLines(lines);
        assertFalse(observations.isEmpty(), "request observations must be emitted");

        Set<String> routes = new HashSet<>();
        Set<String> exercisedMethods = new HashSet<>();
        for (JsonNode node : observations) {
            assertEquals(OBSERVATION_LOGGER, node.get("logger").asText());
            assertEquals(Set.of("timestamp", "level", "logger", "thread", "message",
                            "correlationId", "http_method", "http_route", "http_status", "duration_bucket"),
                    fieldNamesOf(node), "observation lines must carry exactly the bounded field set");
            // The observation boundary is boundedness of the field values: this
            // test's own traffic (login POST, patients GETs) defines the bounded
            // method vocabulary; a raw or unexpected method fails the boundary.
            exercisedMethods.add(node.get("http_method").asText());
            assertTrue(Set.of("GET", "POST").contains(node.get("http_method").asText()),
                    () -> "http_method outside the bounded exercised set: " + node.get("http_method"));
            assertTrue(DURATION_BUCKETS.contains(node.get("duration_bucket").asText()),
                    () -> "duration bucket outside the bounded set: " + node.get("duration_bucket"));
            routes.add(node.get("http_route").asText());
        }
        assertTrue(ALLOWED_ROUTES.containsAll(routes),
                () -> "route values must stay inside the bounded template set, got: " + routes);
        assertEquals(Set.of("GET", "POST"), exercisedMethods,
                "the exercised observation methods must be exactly the bounded set this test drove");
        // A malformed path id must be recorded as its template, never the raw value.
        assertTrue(routes.contains("/api/patients/{id}"), "the templated route must be recorded");
        for (String line : lines) {
            assertFalse(line.contains(PATH_ID_CANARY), "raw path-variable values must never reach the logs");
        }
    }

    private List<JsonNode> observationLines(List<String> lines) throws Exception {
        return lines.stream()
                .map(line -> {
                    try {
                        return JSON.readTree(line);
                    } catch (Exception brokenLine) {
                        throw new AssertionError("emitted line is not one JSON object: " + line, brokenLine);
                    }
                })
                .filter(node -> OBSERVATION_LOGGER.equals(node.get("logger").asText()))
                .toList();
    }

    private Set<String> fieldNamesOf(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private void assertParseableInstant(String value) {
        Instant.parse(value);
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

    private HttpHeaders authed(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private HttpHeaders authedJson(String token) {
        HttpHeaders headers = authed(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, HttpHeaders headers, String body) {
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }
}
