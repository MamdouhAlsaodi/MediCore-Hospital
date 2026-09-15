package com.mamtrex.hospital.contract;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 4 (plan Task 10, T068; FR-016): the OpenAPI contract source of
 * truth. Springdoc derives everything it can from the demonstrated
 * controllers and DTO records (paths, request/response schemas, and the
 * jakarta validation constraints — no business rule is ever re-declared
 * here); this class adds ONLY the metadata that cannot be derived safely:
 *
 * <ul>
 *   <li>fixed document identity (title/version/description) and a relative
 *       server URL so the generated document is byte-stable regardless of
 *       the request host/port (T070 determinism);</li>
 *   <li>the bearer security scheme plus the default security requirement —
 *       every documented operation rides the server-issued bearer token
 *       exactly like the real filter chain;</li>
 *   <li>the SHARED response contracts the framework owns and springdoc
 *       cannot see: the security-layer 401/403 bodies written by
 *       {@code SecurityConfig.writeSecurityError} (a single {@code error}
 *       field), the shared {@code ApiError} 400/404 bodies produced by
 *       {@code GlobalExceptionHandler} for malformed bodies, malformed path
 *       values, and unknown path-referenced resources;</li>
 *   <li>deterministic, collision-free operationIds so the generated
 *       TypeScript client (T071) gets stable function names that never
 *       depend on controller scan order;</li>
 *   <li>human-readable tags in stable order.</li>
 * </ul>
 *
 * <p>Statuses that specific operations really produce are documented with
 * narrow {@code @ApiResponse} annotations on those operations (duplicate-MRN
 * 409, appointment availability/overlap 409, admission lifecycle 409/404,
 * login 429, audit filter 400) — never duplicated into shared rules, so a
 * per-operation contract stays owned by the operation that owns the
 * behavior.</p>
 */
@Configuration
public class OpenApiConfig {

    /** Shared client-error component produced by GlobalExceptionHandler. */
    static final String API_ERROR_REF = "#/components/schemas/ApiError";

    /**
     * The security layer writes one generic JSON body for 401/403
     * (SecurityConfig.writeSecurityError), so the shared schema is a single
     * required non-enumerating "error" string field.
     */
    private static MediaType securityErrorBody() {
        return new MediaType().schema(new ObjectSchema()
                .addProperties("error", new StringSchema().example("authentication required"))
                .addRequiredItem("error"));
    }

    private static ApiResponse securityErrorResponse(String description) {
        return new ApiResponse().description(description)
                .content(new Content().addMediaType("application/json", securityErrorBody()));
    }

    private static ApiResponse apiErrorResponse(String description) {
        return new ApiResponse().description(description)
                .content(new Content().addMediaType("application/json",
                        new MediaType().schema(new io.swagger.v3.oas.models.media.Schema<>().$ref(API_ERROR_REF))));
    }

    /** Fixed document identity; the relative server keeps runs byte-stable. */
    @Bean
    OpenAPI medicoreOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("MediCore Hospital API")
                        .version("v1")
                        .description("Generated contract for the demonstrated MediCore route families: "
                                + "auth, patients, appointments, admissions, command-center dashboard, and audit. "
                                + "MediCore is an educational Training/Portfolio system operating on synthetic "
                                + "data only: it is non-clinical and makes no production, regulatory, or "
                                + "availability claim. Every authenticated operation rides the single "
                                + "server-issued bearer token; scope and authority are server-owned. "
                                + "Client errors use the shared ApiError body; security refusals (401/403) "
                                + "use one generic non-enumerating error body."))
                .servers(List.of(new Server().url("/").description("Same-origin review deployment")))
                .tags(List.of(
                        new Tag().name("auth").description("Login and authenticated acting-context switch"),
                        new Tag().name("patients").description("Patient registry within the acting branch"),
                        new Tag().name("appointments").description("Booked appointment windows within the acting branch"),
                        new Tag().name("admissions").description("Admission lifecycle and atomic bed assignment"),
                        new Tag().name("dashboard").description("Command-center reads for the acting branch/organization"),
                        new Tag().name("audit").description("Scope-aware audit evidence reads (ADMIN only)")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("The access token issued by POST /api/auth/login (or a successful "
                                        + "acting-context switch); it alone selects the acting context.")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }

    /**
     * Adds the shared framework-owned responses and the deterministic
     * operation ids. Rules are add-if-absent, so a narrow per-operation
     * annotation always wins over the shared rule.
     */
    @Bean
    OpenApiCustomizer medicoreContractCustomizer() {
        // Deterministic, collision-free ids for the generated typed client.
        Map<String, String> operationIds = Map.ofEntries(
                Map.entry("/api/auth/login:post", "login"),
                Map.entry("/api/auth/context:post", "switchActingContext"),
                Map.entry("/api/patients:post", "createPatient"),
                Map.entry("/api/patients:get", "listPatients"),
                Map.entry("/api/patients/{id}:get", "getPatient"),
                Map.entry("/api/patients/{id}:put", "updatePatient"),
                Map.entry("/api/appointments:post", "createAppointment"),
                Map.entry("/api/appointments:get", "listAppointments"),
                Map.entry("/api/appointments/{id}:get", "getAppointment"),
                Map.entry("/api/appointments/{id}:delete", "deleteAppointment"),
                Map.entry("/api/admissions:post", "createAdmission"),
                Map.entry("/api/admissions:get", "listAdmissions"),
                Map.entry("/api/admissions/{id}:get", "getAdmission"),
                Map.entry("/api/admissions/{id}:delete", "deleteAdmission"),
                Map.entry("/api/admissions/{id}/bed:put", "assignAdmissionBed"),
                Map.entry("/api/admissions/{id}/status:put", "dischargeAdmission"),
                Map.entry("/api/dashboard:get", "getBranchSummaryAlias"),
                Map.entry("/api/dashboard/branch:get", "getBranchSummary"),
                Map.entry("/api/dashboard/network:get", "getNetworkSummary"),
                Map.entry("/api/audit:get", "listAuditEvents"));
        // Operations where a status genuinely cannot occur, so the shared
        // rule must not overstate it:
        // - login is anonymous (permitAll) and owns its 401/429 contract;
        // - the branch summary and its alias are reachable by EVERY
        //   authenticated context (plain authenticated() rule), so 403 is
        //   impossible there.
        Map<String, Boolean> skip401 = Map.of("/api/auth/login:post", true);
        Map<String, Boolean> skip403 = Map.of(
                "/api/auth/login:post", true,
                "/api/dashboard:get", true,
                "/api/dashboard/branch:get", true);

        return openApi -> {
            Optional.ofNullable(openApi.getPaths()).ifPresent(paths -> paths.forEach((path, pathItem) -> {
                pathItem.readOperationsMap().forEach((httpMethod, operation) -> {
                    String key = path + ":" + httpMethod.name().toLowerCase();
                    String documented = operationIds.get(key);
                    if (documented != null) {
                        operation.setOperationId(documented);
                    }
                    Map<String, ApiResponse> responses = operation.getResponses();
                    if (!skip401.containsKey(key)) {
                        responses.putIfAbsent("401", securityErrorResponse(
                                "Missing, invalid, or expired bearer token; the acting session is gone. "
                                        + "The body is one generic non-enumerating error field."));
                    }
                    if (!skip403.containsKey(key)) {
                        responses.putIfAbsent("403", securityErrorResponse(
                                "The authenticated acting context is not permitted to use this operation. "
                                        + "The body is one generic non-enumerating error field."));
                    }
                    if (operation.getRequestBody() != null) {
                        responses.putIfAbsent("400", apiErrorResponse(
                                "Malformed request body, malformed field value, or a failed validation constraint "
                                        + "(shared ApiError body)."));
                    }
                    boolean hasPathValue = operation.getParameters() != null && operation.getParameters().stream()
                            .anyMatch(parameter -> "path".equals(parameter.getIn()));
                    if (hasPathValue) {
                        responses.putIfAbsent("400", apiErrorResponse(
                                "A path value is malformed for its typed target (shared ApiError body)."));
                        responses.putIfAbsent("404", apiErrorResponse(
                                "No resource referenced by the path exists inside the acting scope "
                                        + "(shared ApiError body)."));
                    }
                });
            }));
            // POST /api/auth/login is the one anonymous operation: an empty
            // security list documents that no bearer requirement applies.
            if (openApi.getPaths() != null && openApi.getPaths().get("/api/auth/login") != null
                    && openApi.getPaths().get("/api/auth/login").getPost() != null) {
                Operation login = openApi.getPaths().get("/api/auth/login").getPost();
                login.setSecurity(List.of());
            }
        };
    }
}
