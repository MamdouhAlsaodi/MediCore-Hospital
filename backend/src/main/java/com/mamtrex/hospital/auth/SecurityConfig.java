package com.mamtrex.hospital.auth;

import org.springframework.context.annotation.*;
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

/**
 * Stateless JWT security. Only {@code /api/auth/**} and actuator health are public.
 * Endpoint families carry explicit role rules ordered before the {@code /api/**}
 * catch-all, which is ADMIN-only (default deny for non-admin accounts).
 * Unauthenticated requests receive 401 so clients can distinguish an expired
 * session (401) from an authenticated but unauthorized role (403).
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

    @Bean
    SecurityFilterChain chain(HttpSecurity http, JwtFilter jwtFilter) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/auth/**", "/actuator/health").permitAll()
                        .requestMatchers("/api/audit/**").hasRole("ADMIN")
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
