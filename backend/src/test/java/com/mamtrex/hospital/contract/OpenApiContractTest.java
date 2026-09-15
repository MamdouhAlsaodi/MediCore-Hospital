package com.mamtrex.hospital.contract;

import com.mamtrex.hospital.TestRuntimeSecrets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.StreamUtils;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T067 (FR-016, plan Task 10 step 1): the generated-OpenAPI contract gate
 * for the six demonstrated route families — auth, patients, appointments,
 * admissions, command-center dashboard, and audit.
 *
 * <p>Three guarantees are pinned against a REAL running HTTP server:</p>
 * <ol>
 *   <li>the served {@code /v3/api-docs.yaml} document exposes every required
 *       path/operation with the documented {@code 200/400/401/403/404/409}
 *       status contracts, the bearer security scheme, and the validation
 *       constraints derived from the request DTO annotations;</li>
 *   <li>the tracked generated artifact
 *       {@code api/openapi/medicore-v1.yaml} is byte-for-byte the served
 *       document, so backend route/DTO drift fails this test until the
 *       artifact is regenerated (in-process twin of
 *       {@code scripts/phase4/check-openapi-drift.sh});</li>
 *   <li>representative documented statuses are real: anonymous 401, denied
 *       role 403, malformed value 400, unknown reference 404, and duplicate
 *       key 409 are each exercised over live HTTP.</li>
 * </ol>
 *
 * <p>Runs against the lightweight isolated in-memory H2 flow with the
 * opt-in synthetic demo cohort (deterministic admin acting assignment) and
 * the opt-in synthetic review accounts (a DOCTOR context for the denied
 * 403 cases), so no runtime environment, credential store, or real secret
 * is ever touched and every account value is process-local and disposable.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:openapi-contract-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "medicore.demo.seed=true",
        "medicore.review-accounts.enabled=true"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenApiContractTest {

    /** The tracked deterministic OpenAPI artifact (T069), relative to the Maven module. */
    private static final Path ARTIFACT =
            Paths.get("..", "api", "openapi", "medicore-v1.yaml").normalize();

    private static final String DOCS_PATH = "/v3/api-docs.yaml";

    /** Synthetic process-local suffix for disposable demo-domain records. */
    private final String suffix = "oc" + Long.toHexString(System.nanoTime() & 0xFFFFFFFFL);

    @Autowired
    TestRestTemplate rest;

    private String adminToken;
    private String doctorToken;

    @DynamicPropertySource
    static void runtimeSecrets(DynamicPropertyRegistry registry) {
        registry.add("hospital.jwt.secret", TestRuntimeSecrets::jwtSecret);
        registry.add("HOSPITAL_ADMIN_PASSWORD", TestRuntimeSecrets::accountPassword);
        registry.add("HOSPITAL_REVIEW_DOCTOR_PASSWORD", TestRuntimeSecrets::accountPassword);
        registry.add("HOSPITAL_REVIEW_NURSE_PASSWORD", TestRuntimeSecrets::accountPassword);
    }

    @BeforeAll
    void loginSyntheticAccounts() {
        adminToken = login("admin");
        doctorToken = login("doctor");
    }

    // ------------------------------------------------------------------
    // 1. The served document exposes the demonstrated contract.
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void servedOpenApiDocumentExposesTheDemonstratedContract() {
        Map<String, Object> doc = servedDoc();

        assertEquals("MediCore Hospital API", ((Map<String, Object>) doc.get("info")).get("title"));
        assertEquals("v1", ((Map<String, Object>) doc.get("info")).get("version"));
        assertTrue(String.valueOf(doc.get("openapi")).startsWith("3."),
                "the contract must be an OpenAPI 3.x document");

        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
        Map<String, Object> schemas = ((Map<String, Object>) doc.get("components")).get("schemas") == null
                ? Map.of()
                : (Map<String, Object>) ((Map<String, Object>) doc.get("components")).get("schemas");

        // --- auth -------------------------------------------------------
        Map<String, Object> login = op(paths, "/api/auth/login", "post");
        assertStatuses(login, 200, 400, 401, 429);
        assertTrue(((List<?>) login.getOrDefault("security", List.of())).isEmpty(),
                "login is anonymous: it must carry an explicit empty security requirement");
        assertRequestBodyRef(login, "LoginRequest");
        assertHeaderDocumented(login, "429", "Retry-After");

        Map<String, Object> switchContext = op(paths, "/api/auth/context", "post");
        assertStatuses(switchContext, 200, 400, 401, 403);
        assertRequestBodyRef(switchContext, "ContextSwitchRequest");
        assertErrorSchemaDocutesClientFault(switchContext, "403");

        // --- patients ---------------------------------------------------
        assertStatuses(op(paths, "/api/patients", "post"), 200, 400, 401, 403, 409);
        assertErrorSchemaDocutesClientFault(op(paths, "/api/patients", "post"), "409");
        assertStatuses(op(paths, "/api/patients", "get"), 200, 401, 403);
        for (String method : List.of("get", "put")) {
            Map<String, Object> byId = op(paths, "/api/patients/{id}", method);
            assertStatuses(byId, 200, 400, 401, 403, 404);
            assertErrorSchemaDocutesClientFault(byId, "404");
        }
        assertRequestBodyRef(op(paths, "/api/patients", "post"), "CreatePatientRequest");
        assertRequestBodyRef(op(paths, "/api/patients/{id}", "put"), "UpdatePatientRequest");

        // --- appointments ------------------------------------------------
        Map<String, Object> createAppointment = op(paths, "/api/appointments", "post");
        assertStatuses(createAppointment, 200, 400, 401, 403, 404, 409);
        assertErrorSchemaDocutesClientFault(createAppointment, "404");
        assertErrorSchemaDocutesClientFault(createAppointment, "409");
        assertRequestBodyRef(createAppointment, "CreateAppointmentRequest");
        assertStatuses(op(paths, "/api/appointments", "get"), 200, 401, 403);
        for (String method : List.of("get", "delete")) {
            Map<String, Object> byId = op(paths, "/api/appointments/{id}", method);
            assertStatuses(byId, 200, 400, 401, 403, 404);
            assertErrorSchemaDocutesClientFault(byId, "404");
        }

        // --- admissions --------------------------------------------------
        Map<String, Object> createAdmission = op(paths, "/api/admissions", "post");
        assertStatuses(createAdmission, 200, 400, 401, 403, 404, 409);
        assertErrorSchemaDocutesClientFault(createAdmission, "404");
        assertErrorSchemaDocutesClientFault(createAdmission, "409");
        assertRequestBodyRef(createAdmission, "CreateAdmissionRequest");
        assertStatuses(op(paths, "/api/admissions", "get"), 200, 401, 403);
        Map<String, Object> assignBed = op(paths, "/api/admissions/{id}/bed", "put");
        assertStatuses(assignBed, 200, 400, 401, 403, 404, 409);
        assertErrorSchemaDocutesClientFault(assignBed, "409");
        assertRequestBodyRef(assignBed, "AssignBedRequest");
        Map<String, Object> discharge = op(paths, "/api/admissions/{id}/status", "put");
        assertStatuses(discharge, 200, 400, 401, 403, 404, 409);
        assertErrorSchemaDocutesClientFault(discharge, "409");
        assertRequestBodyRef(discharge, "UpdateAdmissionStatusRequest");
        for (String method : List.of("get", "delete")) {
            Map<String, Object> byId = op(paths, "/api/admissions/{id}", method);
            assertStatuses(byId, 200, 400, 401, 403, 404);
            assertErrorSchemaDocutesClientFault(byId, "404");
        }

        // --- dashboard ---------------------------------------------------
        Map<String, Object> alias = op(paths, "/api/dashboard", "get");
        assertStatuses(alias, 200, 401);
        assertEquals(Boolean.TRUE, alias.get("deprecated"),
                "the retained compatibility alias must be documented as deprecated");
        assertStatuses(op(paths, "/api/dashboard/branch", "get"), 200, 401);
        assertStatuses(op(paths, "/api/dashboard/network", "get"), 200, 401, 403);

        // --- audit --------------------------------------------------------
        Map<String, Object> audit = op(paths, "/api/audit", "get");
        assertStatuses(audit, 200, 400, 401, 403);
        assertErrorSchemaDocutesClientFault(audit, "400");

        // --- security scheme ----------------------------------------------
        Map<String, Object> securitySchemes =
                (Map<String, Object>) ((Map<String, Object>) doc.get("components")).get("securitySchemes");
        assertNotNull(securitySchemes, "the document must define security schemes");
        Map<String, Object> bearer = (Map<String, Object>) securitySchemes.get("bearerAuth");
        assertNotNull(bearer, "the demonstrated routes ride the server-issued bearer token");
        assertEquals("http", bearer.get("type"));
        assertEquals("bearer", bearer.get("scheme"));
        Map<String, Object> branchRead = op(paths, "/api/dashboard/branch", "get");
        assertTrue(branchRead.get("security") == null
                        || branchRead.get("security").equals(List.of(Map.of("bearerAuth", List.of()))),
                "authenticated reads must require the bearer scheme (directly or from the document default)");
        assertEquals(List.of(Map.of("bearerAuth", List.of())), doc.get("security"),
                "the document default must be the bearer scheme");

        // --- request validation constraints derived from the DTOs ---------
        Map<String, Object> loginRequest = schema(schemas, "LoginRequest");
        assertRequired(loginRequest, "username", "password");

        Map<String, Object> createPatient = schema(schemas, "CreatePatientRequest");
        assertRequired(createPatient, "medicalRecordNumber", "fullName");
        assertEquals("string", prop(createPatient, "email").get("type"),
                "the email field must be documented as a string");

        Map<String, Object> createAppointmentSchema = schema(schemas, "CreateAppointmentRequest");
        assertRequired(createAppointmentSchema, "patientId", "professionalId", "scheduledAt",
                "durationMinutes", "type", "status");
        Map<String, Object> duration = prop(createAppointmentSchema, "durationMinutes");
        assertEquals(5, ((Number) duration.get("minimum")).intValue(), "the 5-480 bound is contract");
        assertEquals(480, ((Number) duration.get("maximum")).intValue(), "the 5-480 bound is contract");
        String statusPattern = String.valueOf(prop(createAppointmentSchema, "status").get("pattern"));
        assertTrue(statusPattern.contains("scheduled|confirmed|completed|cancelled"),
                "the lowercase status contract must surface as a pattern, got: " + statusPattern);

        Map<String, Object> createAdmissionSchema = schema(schemas, "CreateAdmissionRequest");
        assertRequired(createAdmissionSchema, "patientId", "admittedAt", "reason");
        assertFalse(((Map<String, Object>) createAdmissionSchema.get("properties")).containsKey("status"),
                "the server owns the lifecycle: no client status field exists");
        assertRequired(schema(schemas, "AssignBedRequest"), "bedId");
        assertRequired(schema(schemas, "UpdateAdmissionStatusRequest"), "status");

        // --- response schema components ------------------------------------
        for (String name : List.of("ApiError", "Session", "PatientResponse", "AppointmentResponse",
                "AdmissionResponse", "CurrentBed", "BranchSummary", "NetworkSummary", "AuditEventView")) {
            assertTrue(schemas.containsKey(name), "components.schemas must contain " + name);
        }
        Map<String, Object> apiError = schema(schemas, "ApiError");
        Map<String, Object> apiErrorProps = (Map<String, Object>) apiError.get("properties");
        assertEquals(Set.of("timestamp", "status", "error", "message", "path"), apiErrorProps.keySet(),
                "the shared client-error shape is exactly the five ApiError fields");

        Map<String, Object> patientResponse = schema(schemas, "PatientResponse");
        Map<String, Object> patientProps = (Map<String, Object>) patientResponse.get("properties");
        assertTrue(patientProps.keySet().containsAll(
                        Set.of("id", "branchId", "medicalRecordNumber", "fullName", "active")),
                "PatientResponse must expose the stable public fields");
        assertFalse(patientProps.containsKey("version"), "persistence internals never enter the contract");

        Map<String, Object> auditView = schema(schemas, "AuditEventView");
        Map<String, Object> auditProps = (Map<String, Object>) auditView.get("properties");
        assertTrue(auditProps.keySet().containsAll(
                        Set.of("id", "actor", "action", "resourceType", "resourceId", "occurredAt", "correlationId")),
                "AuditEventView must expose the allowlisted evidence fields");

        Map<String, Object> branchSummary = schema(schemas, "BranchSummary");
        Map<String, Object> branchProps = (Map<String, Object>) branchSummary.get("properties");
        assertTrue(branchProps.containsKey("todayAppointments")
                        && branchProps.containsKey("bedsOccupied") && branchProps.containsKey("invoicesVoid"),
                "the typed command-center summary must expose its metric contract");
    }

    // ------------------------------------------------------------------
    // 2. The tracked artifact is byte-for-byte the served document.
    // ------------------------------------------------------------------

    @Test
    void trackedArtifactMatchesTheServedContractByteForByte() throws Exception {
        byte[] served = fetchServedDocBytes();
        assertTrue(Files.exists(ARTIFACT),
                "the generated artifact " + ARTIFACT + " must exist (run scripts/phase4/generate-openapi.sh)");
        byte[] tracked = Files.readAllBytes(ARTIFACT);
        assertEquals(new String(served, StandardCharsets.UTF_8), new String(tracked, StandardCharsets.UTF_8),
                "tracked api/openapi/medicore-v1.yaml has drifted from the served contract — regenerate it");
    }

    // ------------------------------------------------------------------
    // 3. Representative documented statuses are real over live HTTP.
    // ------------------------------------------------------------------

    @Test
    void documentedAuthBoundaryStatusesAreReal() {
        assertEquals(HttpStatus.UNAUTHORIZED, exchange("GET", "/api/patients", null, null).getStatusCode(),
                "401 anonymous is a documented contract");
        assertEquals(HttpStatus.FORBIDDEN, exchange("GET", "/api/audit", doctorToken, null).getStatusCode(),
                "403 denied role on the ADMIN-only audit read is a documented contract");
        assertEquals(HttpStatus.FORBIDDEN, exchange("POST", "/api/patients", doctorToken,
                        Map.of("medicalRecordNumber", "MRN-" + suffix + "-D", "fullName", "Synthetic Denied"))
                .getStatusCode(), "403 denied role on patient create is a documented contract");
        assertEquals(HttpStatus.FORBIDDEN, exchange("GET", "/api/dashboard/network", doctorToken, null)
                .getStatusCode(), "403 non-organization network read is a documented contract");
    }

    @Test
    void documentedClientFaultStatusesAreReal() {
        String mrn = "MRN-" + suffix + "-409";
        assertEquals(HttpStatus.OK, exchange("POST", "/api/patients", adminToken,
                Map.of("medicalRecordNumber", mrn, "fullName", "Synthetic Contract " + suffix)).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, exchange("POST", "/api/patients", adminToken,
                        Map.of("medicalRecordNumber", mrn, "fullName", "Synthetic Duplicate " + suffix))
                .getStatusCode(), "409 duplicate MRN is a documented contract");

        assertEquals(HttpStatus.BAD_REQUEST,
                exchange("GET", "/api/patients/not-a-uuid", adminToken, null).getStatusCode(),
                "400 malformed path value is a documented contract");
        assertEquals(HttpStatus.NOT_FOUND,
                exchange("GET", "/api/patients/" + "00000000-0000-4000-8000-000000000000", adminToken, null)
                .getStatusCode(), "404 unknown reference is a documented contract");
        assertEquals(HttpStatus.BAD_REQUEST,
                exchange("GET", "/api/audit?branchId=not-a-uuid", adminToken, null).getStatusCode(),
                "400 malformed audit filter is a documented contract");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> servedDoc() {
        byte[] body = fetchServedDocBytes();
        Object loaded = new Yaml().load(new String(body, StandardCharsets.UTF_8));
        assertTrue(loaded instanceof Map, "the served document must parse as a YAML mapping");
        return (Map<String, Object>) loaded;
    }

    private byte[] fetchServedDocBytes() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        org.springframework.http.HttpStatusCode[] status = new org.springframework.http.HttpStatusCode[1];
        byte[] body = rest.execute(DOCS_PATH, HttpMethod.GET,
                request -> request.getHeaders().putAll(headers),
                clientResponse -> {
                    status[0] = clientResponse.getStatusCode();
                    return StreamUtils.copyToByteArray(clientResponse.getBody());
                });
        assertEquals(HttpStatus.OK, status[0],
                "the generated contract document must be served at " + DOCS_PATH);
        assertNotNull(body, "the served document must carry a body");
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> op(Map<String, Object> paths, String path, String method) {
        Map<String, Object> node = (Map<String, Object>) paths.get(path);
        assertNotNull(node, "the contract must expose path " + path);
        Map<String, Object> operation = (Map<String, Object>) node.get(method);
        assertNotNull(operation, "the contract must expose " + method.toUpperCase() + " " + path);
        return operation;
    }

    @SuppressWarnings("unchecked")
    private static void assertStatuses(Map<String, Object> operation, Integer... expected) {
        Map<String, Object> responses = (Map<String, Object>) operation.get("responses");
        for (Integer status : expected) {
            assertTrue(responses.containsKey(String.valueOf(status)),
                    "operation must document status " + status + " (has " + responses.keySet() + ")");
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertRequestBodyRef(Map<String, Object> operation, String schemaName) {
        Map<String, Object> requestBody = (Map<String, Object>) operation.get("requestBody");
        assertNotNull(requestBody, "requestBody must be documented");
        Map<String, Object> json = (Map<String, Object>) ((Map<String, Object>) requestBody.get("content"))
                .get("application/json");
        assertNotNull(json, "the request body must be application/json");
        Map<String, Object> schema = (Map<String, Object>) json.get("schema");
        String ref = schema == null ? null : (String) schema.get("$ref");
        assertEquals("#/components/schemas/" + schemaName, ref,
                "the request body must reference components.schemas." + schemaName);
    }

    /**
     * The documented client-fault response must carry the shared ApiError
     * contract: either a direct reference to the shared component or an
     * inline equivalent of its five-field shape.
     */
    @SuppressWarnings("unchecked")
    private static void assertErrorSchemaDocutesClientFault(Map<String, Object> operation, String status) {
        Map<String, Object> response =
                (Map<String, Object>) ((Map<String, Object>) operation.get("responses")).get(status);
        assertNotNull(response, "status " + status + " must be documented");
        Map<String, Object> json = (Map<String, Object>) ((Map<String, Object>) response.get("content"))
                .get("application/json");
        assertNotNull(json, "status " + status + " must document an application/json body");
        Map<String, Object> schema = (Map<String, Object>) json.get("schema");
        assertNotNull(schema, "status " + status + " must document a body schema");
        String ref = (String) schema.get("$ref");
        if (ref != null) {
            assertEquals("#/components/schemas/ApiError", ref,
                    "client faults must use the shared ApiError component");
        } else {
            Map<String, Object> props = (Map<String, Object>) schema.get("properties");
            assertTrue(props != null && props.keySet().containsAll(Set.of("status", "error", "message", "path")),
                    "an inline client-fault schema must match the shared ApiError shape");
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertHeaderDocumented(Map<String, Object> operation, String status, String headerName) {
        Map<String, Object> response =
                (Map<String, Object>) ((Map<String, Object>) operation.get("responses")).get(status);
        assertNotNull(response, "status " + status + " must be documented");
        Map<String, Object> headers = (Map<String, Object>) response.get("headers");
        assertNotNull(headers, "status " + status + " must document its headers");
        assertTrue(headers.containsKey(headerName), "status " + status + " must document " + headerName);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(Map<String, Object> schemas, String name) {
        Map<String, Object> schema = (Map<String, Object>) schemas.get(name);
        assertNotNull(schema, "components.schemas must contain " + name);
        return schema;
    }

    @SuppressWarnings("unchecked")
    private static void assertRequired(Map<String, Object> schema, String... propertyNames) {
        Object required = schema.get("required");
        assertNotNull(required, schemaName(schema) + " must declare required properties");
        for (String name : propertyNames) {
            assertTrue(((List<Object>) required).contains(name),
                    schemaName(schema) + " must require " + name + " (has " + required + ")");
        }
    }

    private static String schemaName(Map<String, Object> schema) {
        Object title = schema.get("title");
        return title == null ? "schema" : String.valueOf(title);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> prop(Map<String, Object> schema, String name) {
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        Map<String, Object> property = (Map<String, Object>) props.get(name);
        assertNotNull(property, "schema property " + name + " must be documented");
        return property;
    }

    private String login(String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", TestRuntimeSecrets.accountPassword()), headers),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        assertEquals(HttpStatus.OK, response.getStatusCode(), "synthetic login must succeed for " + username);
        return String.valueOf(response.getBody().get("accessToken"));
    }

    private ResponseEntity<String> exchange(String method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.valueOf(method), new HttpEntity<>(body, headers), String.class);
    }
}
