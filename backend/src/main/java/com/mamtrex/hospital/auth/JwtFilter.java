package com.mamtrex.hospital.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Parses the bearer token once into its structural claims and rebuilds the
 * acting context from server state on every request (docs/plan3.md Task 3):
 * enabled account, the exact enabled assignment owned by that account, and
 * current organization/branch/department state — installing exactly one
 * {@code ROLE_<assignment role>} authority with {@link ActingContext} as
 * principal. Nothing in the token is authoritative: the structural claims
 * only name the server state to reload, and every structural claim —
 * assignment, scope, organization, selected branch, department — must match
 * that rebuilt state exactly, so deleted/disabled/foreign/inconsistent
 * assignments, inactive/deleted selected branches, and stale or tampered
 * scope/organization/branch/department claims all leave the request
 * unauthenticated. A signed token with an altered role claim still derives
 * its authority from the server row because the role claim is never read.
 * Invalid, malformed, and expired tokens are the filter's intentional
 * fail-closed boundary — the request continues anonymously and no details
 * are ever thrown to the client.
 */
@Component
public class JwtFilter extends OncePerRequestFilter {

    private final JwtService jwt;
    private final ActingContextService contexts;

    public JwtFilter(JwtService jwt, ActingContextService contexts) {
        this.jwt = jwt;
        this.contexts = contexts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                contexts.reload(jwt.parseStructural(header.substring(7)))
                        .ifPresent(context -> SecurityContextHolder.getContext().setAuthentication(
                                new UsernamePasswordAuthenticationToken(context, null,
                                        List.of(new SimpleGrantedAuthority("ROLE_" + context.role().name())))));
            } catch (RuntimeException invalidToken) {
                // Intentional fail-closed boundary: the request stays anonymous.
            }
        }
        chain.doFilter(request, response);
    }
}
