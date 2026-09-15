package com.mamtrex.hospital.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.mamtrex.hospital.TestRuntimeSecrets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T060/T061 live HTTP contract for the bounded expiring login rate limiter
 * (FR-009, plan Task 9 steps 3-5), driven through the real security filter
 * chain and controller against the isolated H2 flow with the synthetic demo
 * cohort (deterministic admin acting assignment):
 *
 * <ul>
 *   <li>repeated failed logins stay generic 401 until the threshold, then the
 *       address receives one generic, non-enumerating 429 with Retry-After;</li>
 *   <li>a spoofed {@code X-Forwarded-For} can never rotate or bypass the key —
 *       this system has NO trusted-proxy configuration, so only the direct
 *       socket address is ever consulted;</li>
 *   <li>other addresses are unaffected;</li>
 *   <li>a successful login from the address clears its failures (recovery by
 *       success);</li>
 *   <li>after the configured window passes, the blocked address recovers
 *       (recovery after the documented rate-limit window);</li>
 *   <li>rate-limit refusals and every 401 failure create zero audit events.</li>
 * </ul>
 *
 * <p>Distinct RFC 5737 documentation addresses are simulated per request via
 * the servlet request's remote address — the exact value the limiter keys on.
 * The window is deliberately short (2s) so the expiry test uses one bounded
 * sleep with a wide margin; threshold behavior itself is instantaneous.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:login-rate-limit-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "medicore.demo.seed=true",
        "hospital.security.login.max-failures=3",
        "hospital.security.login.window=PT2S"
})
@AutoConfigureMockMvc
class LoginRateLimitIntegrationTest {

    private static final String LOGIN = "/api/auth/login";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @DynamicPropertySource
    static void runtimeSecrets(DynamicPropertyRegistry registry) {
        registry.add("hospital.jwt.secret", TestRuntimeSecrets::jwtSecret);
        registry.add("HOSPITAL_ADMIN_PASSWORD", TestRuntimeSecrets::accountPassword);
    }

    /** Keys every request of one perform() to a chosen direct socket address. */
    private static RequestPostProcessor fromAddress(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private MvcResult failedLogin(String address, String password) throws Exception {
        return mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + password + "\"}")
                        .with(fromAddress(address)))
                .andReturn();
    }

    @Test
    void repeatedFailuresStayGenericUntilTheThresholdThenTheAddressIsRateLimited() throws Exception {
        String address = "192.0.2.10";
        for (int i = 1; i <= 3; i++) {
            MvcResult result = failedLogin(address, "wrong-pass-" + i);
            status().isUnauthorized().match(result);
            jsonPath("$.error").value("Invalid username or password.").match(result);
        }
        MvcResult blocked = failedLogin(address, "wrong-pass-4");
        status().isTooManyRequests().match(blocked);
        // Generic, non-enumerating refusal: identical body for every blocked caller.
        jsonPath("$.error").value("Too many failed attempts. Try again later.").match(blocked);
        blocked.getResponse().getHeader("Retry-After");
        header().exists("Retry-After").match(blocked);

        // The refusal precedes authentication: a CORRECT password is refused too.
        status().isTooManyRequests().match(failedLogin(address, TestRuntimeSecrets.accountPassword()));

        // A spoofed forwarded header cannot rotate the key or bypass the block:
        // no trusted-proxy configuration exists in this system.
        MvcResult spoofed = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"whatever\"}")
                        .header("X-Forwarded-For", "8.8.8.8, 192.0.2.99")
                        .with(fromAddress(address)))
                .andReturn();
        status().isTooManyRequests().match(spoofed);

        // A different direct address is completely unaffected.
        status().isUnauthorized().match(failedLogin("192.0.2.11", "wrong-pass"));
    }

    @Test
    void aSuccessfulLoginClearsTheAddressFailureCount() throws Exception {
        String address = "192.0.2.20";
        String password = TestRuntimeSecrets.accountPassword();
        status().isUnauthorized().match(failedLogin(address, "wrong-a"));
        status().isUnauthorized().match(failedLogin(address, "wrong-b"));
        // Success from the same address clears the recorded failures.
        status().isOk().match(mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + password + "\"}")
                        .with(fromAddress(address)))
                .andReturn());
        status().isUnauthorized().match(failedLogin(address, "wrong-c"));
        status().isUnauthorized().match(failedLogin(address, "wrong-d"));
        // Only two failures count after the success — still below the threshold
        // of 3, so a correct login from the same address goes straight through.
        status().isOk().match(mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + password + "\"}")
                        .with(fromAddress(address)))
                .andReturn());
    }

    @Test
    void blockedAddressRecoversAfterTheDocumentedWindow() throws Exception {
        String address = "192.0.2.30";
        String password = TestRuntimeSecrets.accountPassword();
        for (int i = 1; i <= 3; i++) {
            status().isUnauthorized().match(failedLogin(address, "wrong-" + i));
        }
        status().isTooManyRequests().match(failedLogin(address, "blocked"));
        // One bounded sleep with a wide margin over the configured PT2S window;
        // no other timing in this test depends on wall-clock duration.
        Thread.sleep(2500);
        status().isOk().match(mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + password + "\"}")
                        .with(fromAddress(address)))
                .andReturn());
    }

    @Test
    void rateLimitRefusalsAndFailuresCreateZeroAuditEvents() throws Exception {
        String address = "192.0.2.40";
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class);
        for (int i = 1; i <= 4; i++) {
            MvcResult result = failedLogin(address, "nope-" + i);
            status().is(i <= 3 ? 401 : 429).match(result);
        }
        Integer after = jdbc.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(before, after,
                "failed and rate-limited logins must create zero audit events");
    }
}
