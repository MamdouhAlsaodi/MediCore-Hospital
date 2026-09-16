package com.mamtrex.hospital.auth;

import com.mamtrex.hospital.audit.CorrelationIdFilter;
import com.mamtrex.hospital.infrastructure.RequestObservationLogFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless JWT security. Only {@code POST /api/auth/login} and actuator
 * health are public; {@code POST /api/auth/context} (the acting-context
 * switch, docs/plan3.md Task 3) is authenticated, and no broad
 * {@code /api/auth/**} permit-all exists.
 * Endpoint families carry explicit role rules ordered before the {@code /api/**}
 * catch-all, which is ADMIN-only (default deny for non-admin accounts).
 * Method-level rules ordered before the family rules enforce the Task 9 write
 * policy: patient create/update and appointment create are ADMIN/RECEPTIONIST
 * only, and the staff directory read additionally allows RECEPTIONIST while
 * staff writes stay ADMIN/HR — so no implemented write action depends solely
 * on frontend hiding. The Plan 3 Task 2 hierarchy policy also rides these
 * method-level rules: the organization/branch surface is ADMIN-only and
 * department create/delete are narrowed to ADMIN (hierarchy writes), while
 * the department read keeps its ADMIN/HR family role.
 * Unauthenticated requests receive 401 so clients can distinguish an expired
 * session (401) from an authenticated but unauthorized role (403). The
 * {@code CorrelationIdFilter} (docs/plan3.md Task 11) runs ahead of the JWT
 * filter on every request: it validates or server-generates one bounded
 * correlation id and echoes it in the {@code X-Correlation-Id} response
 * header, without ever authenticating, rejecting, or altering a request.
 *
 * <p>Phase 4 (T062/T063, FR-010): cross-origin access is governed by an
 * EXPLICIT allowlist — {@code hospital.security.cors.allowed-origins}, empty
 * by default in every shipped profile, so the production-like review
 * deployment is fail-closed (the real frontend is same-origin in every mode:
 * Vite dev/preview proxy and the review nginx {@code /api} route — no
 * cross-origin call is needed for any demonstrated workflow). Every response
 * also carries the hardened API header set: {@code X-Content-Type-Options},
 * {@code X-Frame-Options: DENY}, {@code Referrer-Policy: no-referrer}, and a
 * strict {@code Content-Security-Policy} with no active content — an API
 * serves JSON, never pages. The SPA-appropriate nginx header set is verified
 * live by {@code scripts/phase4/check-security-headers.sh}.</p>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    /**
     * T062: the explicit CORS allowlist. Origins come only from the
     * environment-configurable {@code hospital.security.cors.allowed-origins}
     * property; the default (and every shipped profile default) is EMPTY,
     * which refuses every cross-origin preflight with 403 and no
     * {@code Access-Control-Allow-*} headers — fail-closed. Methods and
     * headers are themselves explicit allowlists of exactly what the API
     * contract uses; credentials are never allowed (the API is bearer-token
     * authenticated, never cookie-authenticated).
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${hospital.security.cors.allowed-origins:}") java.util.List<String> allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins.stream()
                .filter(origin -> origin != null && !origin.isBlank())
                .toList());
        configuration.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "DELETE"));
        configuration.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type", "X-Correlation-Id"));
        configuration.setExposedHeaders(java.util.List.of("X-Correlation-Id", "Retry-After"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    SecurityFilterChain chain(HttpSecurity http, JwtFilter jwtFilter, CorrelationIdFilter correlationIdFilter,
                              CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        return http.cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Phase 4 (T063): the hardened API header set on every response.
                // X-Content-Type-Options/X-Frame-Options/Cache-Control keep their
                // Spring Security defaults; the explicit additions are the strict
                // no-active-content CSP and no-referrer. This cannot affect the
                // same-origin review path: nginx proxies /api bodies untouched and
                // only adds its own SPA-appropriate headers.
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                        .ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(authorize -> authorize
                        // Phase 4 (FR-008/FR-010): the health probes are the
                        // only anonymous actuator surface — liveness stays
                        // process-only and readiness proves dependencies;
                        // every other actuator endpoint stays authenticated.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers("/api/auth/**").authenticated()
                        .requestMatchers("/api/audit/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/staff/**")
                            .hasAnyRole("ADMIN", "HR", "RECEPTIONIST")
                        .requestMatchers("/api/organization/**", "/api/branches/**")
                            .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/departments/**")
                            .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/departments/**")
                            .hasRole("ADMIN")
                        .requestMatchers("/api/staff/**", "/api/departments/**", "/api/shifts/**")
                            .hasAnyRole("ADMIN", "HR")
                        .requestMatchers("/api/invoices/**", "/api/insurance-claims/**")
                            .hasAnyRole("ADMIN", "BILLING")
                        .requestMatchers("/api/lab-orders/**")
                            .hasAnyRole("ADMIN", "DOCTOR", "LAB_TECH")
                        .requestMatchers("/api/radiology-orders/**")
                            .hasAnyRole("ADMIN", "DOCTOR", "RADIOLOGY_TECH")
                        .requestMatchers("/api/drugs/**")
                            .hasAnyRole("ADMIN", "PHARMACIST", "DOCTOR")
                        .requestMatchers("/api/inventory-items/**")
                            .hasAnyRole("ADMIN", "PHARMACIST", "STAFF")
                        .requestMatchers(HttpMethod.POST, "/api/patients")
                            .hasAnyRole("ADMIN", "RECEPTIONIST")
                        .requestMatchers(HttpMethod.PUT, "/api/patients/*")
                            .hasAnyRole("ADMIN", "RECEPTIONIST")
                        .requestMatchers(HttpMethod.POST, "/api/appointments")
                            .hasAnyRole("ADMIN", "RECEPTIONIST")
                        .requestMatchers("/api/patients/**", "/api/appointments/**", "/api/admissions/**",
                                "/api/beds/**", "/api/emergency-visits/**")
                            .hasAnyRole("ADMIN", "DOCTOR", "NURSE", "RECEPTIONIST")
                        .requestMatchers("/api/clinical-encounters/**", "/api/medication-orders/**",
                                "/api/nursing-observations/**", "/api/surgeries/**")
                            .hasAnyRole("ADMIN", "DOCTOR", "NURSE")
                        .requestMatchers("/api/blood-units/**")
                            .hasAnyRole("ADMIN", "DOCTOR", "NURSE", "LAB_TECH")
                        .requestMatchers("/api/diet-orders/**")
                            .hasAnyRole("ADMIN", "DOCTOR", "NURSE", "STAFF")
                        .requestMatchers("/api/facility-work-orders/**")
                            .hasAnyRole("ADMIN", "STAFF")
                        .requestMatchers("/api/documents/**")
                            .hasAnyRole("ADMIN", "DOCTOR", "NURSE", "STAFF")
                        .requestMatchers("/api/notifications/**", "/api/dashboard/**").authenticated()
                        .requestMatchers("/api/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeSecurityError(
                                res, HttpStatus.UNAUTHORIZED, "authentication required"))
                        .accessDeniedHandler((req, res, ex) -> writeSecurityError(
                                res, HttpStatus.FORBIDDEN, "access denied")))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                // Task 11 (docs/plan3.md): the correlation-id boundary runs first on
                // every request — validating/generating the bounded id and echoing it
                // in the X-Correlation-Id response header — before authentication.
                .addFilterBefore(correlationIdFilter, JwtFilter.class)
                // Phase 4 (plan Task 8, T054): the bounded request observation runs
                // immediately after the correlation boundary so its structured line
                // carries exactly the correlation id issued above. It is registered
                // ONLY here (never as a standalone servlet bean) and never alters a
                // request or response.
                .addFilterAfter(new RequestObservationLogFilter(), CorrelationIdFilter.class)
                .build();
    }

    private void writeSecurityError(jakarta.servlet.http.HttpServletResponse response,
                                    HttpStatus status,
                                    String message) throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
