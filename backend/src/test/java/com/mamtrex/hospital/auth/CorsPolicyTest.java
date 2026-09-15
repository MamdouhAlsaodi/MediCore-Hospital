package com.mamtrex.hospital.auth;

import com.mamtrex.hospital.TestRuntimeSecrets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T062 explicit CORS allowlist contract (FR-010, plan Task 9 step 4). The
 * allowlist is an explicit, environment-configurable list
 * ({@code hospital.security.cors.allowed-origins}); both shipped profiles
 * default it to EMPTY, so the production-like review deployment is fail-closed:
 * a cross-origin preflight from any origin not on the explicit list is refused
 * with 403 and no {@code Access-Control-Allow-*} headers at all.
 *
 * <p>The whole real frontend is same-origin in every mode (Vite dev/preview
 * proxy and the review nginx {@code /api} route both serve the API on the page
 * origin), so no cross-origin call is needed for any demonstrated workflow —
 * the allowlist is pure defense-in-depth. This test proves, on the real filter
 * chain, that: an explicitly allowlisted origin gets its preflight answered and
 * its origin echoed; any other origin is refused fail-closed; and non-CORS
 * (same-origin) traffic is completely unaffected.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cors-policy-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "medicore.demo.seed=true",
        "hospital.security.cors.allowed-origins=https://review.medicore.example"
})
@AutoConfigureMockMvc
class CorsPolicyTest {

    private static final String ALLOWED = "https://review.medicore.example";
    private static final String EVIL = "https://evil.example";

    @Autowired
    MockMvc mockMvc;

    @DynamicPropertySource
    static void runtimeSecrets(DynamicPropertyRegistry registry) {
        registry.add("hospital.jwt.secret", TestRuntimeSecrets::jwtSecret);
        registry.add("HOSPITAL_ADMIN_PASSWORD", TestRuntimeSecrets::accountPassword);
    }

    private ResultMatcher noCorsHeaders() {
        return result -> {
            if (result.getResponse().getHeader("Access-Control-Allow-Origin") != null) {
                throw new AssertionError("a refused origin must never receive Access-Control-Allow-Origin");
            }
        };
    }

    @Test
    void allowlistedOriginPreflightIsAnsweredAndEchoed() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", ALLOWED)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization, content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED))
                .andExpect(result -> {
                    String methods = result.getResponse().getHeader("Access-Control-Allow-Methods");
                    if (methods == null || !methods.contains("POST")) {
                        throw new AssertionError("the preflight answer must allow the POST method, got: " + methods);
                    }
                });
    }

    @Test
    void unknownOriginPreflightIsRefusedFailClosed() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", EVIL)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization, content-type"))
                .andExpect(status().isForbidden())
                .andExpect(noCorsHeaders());
    }

    @Test
    void unknownOriginActualRequestsNeverReceiveAllowHeaders() throws Exception {
        // Spring's CORS processor rejects an actual request from a disallowed
        // origin fail-closed with 403 and no Access-Control-Allow-* headers
        // (stricter than letting it reach the 401 boundary); a refused origin
        // is never blessed with allow headers either way.
        mockMvc.perform(post("/api/auth/login")
                        .header("Origin", EVIL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"x\",\"password\":\"y\"}"))
                .andExpect(status().isForbidden())
                .andExpect(noCorsHeaders());
    }

    @Test
    void nonCorsSameOriginTrafficIsUnaffected() throws Exception {
        // No Origin header at all — exactly what the same-origin review
        // path (nginx /api route) and the Vite proxy produce. CORS must be
        // invisible to it.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\""
                                + TestRuntimeSecrets.accountPassword() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }
}
