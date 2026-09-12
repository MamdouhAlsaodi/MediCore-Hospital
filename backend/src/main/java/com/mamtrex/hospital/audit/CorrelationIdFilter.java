package com.mamtrex.hospital.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The request-boundary correlation identity (docs/plan3.md Task 11). Every
 * request carries exactly one bounded correlation id: an inbound
 * {@code X-Correlation-Id} header is accepted only when it matches the safe
 * bounded shape (1-64 characters of letters, digits, dot, underscore, or
 * hyphen, starting alphanumeric); anything absent, blank, over-long, or
 * malformed is replaced by a server-generated UUID, so hostile input is
 * never echoed back or stored. The validated value is returned to the caller
 * in the {@code X-Correlation-Id} response header and exposed to
 * {@link AuditService} for the current thread only (the holder is cleared in
 * a {@code finally} block), so every successful sensitive command stores
 * exactly the correlation id its caller could observe in the response.
 *
 * <p>The filter validates and labels only: it never reads bodies, never
 * authenticates, never rejects a request, and changes no endpoint behavior.
 * The bounded shape keeps stored correlation ids to a fixed maximum length
 * and keeps header/log-injection characters out of the evidence chain.</p>
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** The single correlation-id header name used on requests and responses. */
    public static final String HEADER = "X-Correlation-Id";

    /** The maximum stored correlation-id length; generated UUIDs (36 chars) fit comfortably. */
    public static final int MAX_LENGTH = 64;

    /** The exact accepted inbound shape: bounded, no separators beyond dot/underscore/hyphen. */
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    /** Request-scoped holder; set and always cleared by this filter on the request thread. */
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /**
     * The validated bounded correlation id of the current request, or
     * {@code null} outside a request (e.g. unauthenticated startup
     * recording, which stays legacy/unassigned).
     */
    public static String current() {
        return CURRENT.get();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String candidate = request.getHeader(HEADER);
        String correlationId = candidate != null && VALID.matcher(candidate).matches()
                ? candidate
                : UUID.randomUUID().toString();
        CURRENT.set(correlationId);
        try {
            response.setHeader(HEADER, correlationId);
            chain.doFilter(request, response);
        } finally {
            CURRENT.remove();
        }
    }
}
