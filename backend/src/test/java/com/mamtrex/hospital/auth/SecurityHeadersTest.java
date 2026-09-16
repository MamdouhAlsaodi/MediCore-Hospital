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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T063 API security-header contract (FR-010, plan Task 9 step 5): every API
 * response — success JSON, generic 401 JSON, and public actuator JSON —
 * carries the hardened header set. The nginx review side carries the SPA-appropriate
 * set and is verified live by {@code scripts/phase4/check-security-headers.sh};
 * this test owns the backend responses. The headers are intentionally compatible
 * with the API's JSON-only surface and cannot affect the same-origin review
 * routing, which proxies bodies untouched.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:security-headers-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "medicore.demo.seed=true"
})
@AutoConfigureMockMvc
class SecurityHeadersTest {

    /** The pinned API Content-Security-Policy: an API serves no active content of its own. */
    private static final String API_CSP = "default-src 'none'; frame-ancestors 'none'";

    @Autowired
    MockMvc mockMvc;

    @DynamicPropertySource
    static void runtimeSecrets(DynamicPropertyRegistry registry) {
        registry.add("hospital.jwt.secret", TestRuntimeSecrets::jwtSecret);
        registry.add("HOSPITAL_ADMIN_PASSWORD", TestRuntimeSecrets::accountPassword);
    }

    /** The complete hardened header set every API response must carry. */
    private static ResultMatcher hardenedHeaders() {
        return result -> {
            var response = result.getResponse();
            expect(response.getHeader("X-Content-Type-Options"), "nosniff", "X-Content-Type-Options");
            expect(response.getHeader("X-Frame-Options"), "DENY", "X-Frame-Options");
            expect(response.getHeader("Referrer-Policy"), "no-referrer", "Referrer-Policy");
            expect(response.getHeader("Content-Security-Policy"), API_CSP, "Content-Security-Policy");
        };
    }

    private static void expect(String actual, String wanted, String name) {
        if (!wanted.equalsIgnoreCase(actual == null ? "" : actual.trim())) {
            throw new AssertionError(name + " must be \"" + wanted + "\" but was \"" + actual + "\"");
        }
    }

    @Test
    void actuatorHealthCarriesTheHardenedHeaderSet() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(hardenedHeaders());
    }

    @Test
    void anonymousApiRejectionCarriesTheHardenedHeaderSet() throws Exception {
        mockMvc.perform(get("/api/dashboard"))
                .andExpect(status().isUnauthorized())
                .andExpect(hardenedHeaders());
    }

    @Test
    void successfulLoginCarriesTheHardenedHeaderSetAndStaysFunctional() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\""
                                + TestRuntimeSecrets.accountPassword() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(hardenedHeaders());
    }
}
