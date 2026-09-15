package com.mamtrex.hospital.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The bounded per-request observation line (plan Task 8, T054; FR-007).
 * Wrapped inside the security chain directly after the
 * {@code CorrelationIdFilter}, it measures each request and emits exactly
 * one INFO line on the {@code http.request} logger carrying only bounded
 * fields: the correlation id comes from the existing correlation authority
 * via MDC (never duplicated here), plus {@code method}, the matched route
 * TEMPLATE (Spring's best-matching pattern, so path-variable values are
 * never recorded), the response {@code status}, and a {@code duration_bucket}
 * from a fixed five-bucket scale — never a raw millisecond value, request
 * body, query string, header, or any identifier.
 *
 * <p>Actuator requests are deliberately excluded: liveness/readiness probes
 * are orchestration traffic, not application requests, and polling them
 * would flood the observation stream. The filter never alters any request
 * or response; it is registered only in the security chain (never as a
 * standalone servlet bean), so it runs exactly once per request, and it does
 * not run on ERROR dispatches (error responses are already observed once).
 */
public class RequestObservationLogFilter extends OncePerRequestFilter {

    /** The dedicated observation logger; its lines carry the bounded http_* MDC fields. */
    public static final String OBSERVATION_LOGGER = "http.request";

    /** The fixed, bounded duration scale — never raw millisecond values. */
    public static final Set<String> DURATION_BUCKETS = new LinkedHashSet<>(Arrays.asList(
            "under_100ms", "100_499ms", "500_999ms", "1_4s", "5s_or_more"));

    private static final Logger LOG = LoggerFactory.getLogger(OBSERVATION_LOGGER);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long startNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long millis = (System.nanoTime() - startNanos) / 1_000_000;
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String route = pattern instanceof String template && !template.isBlank() ? template : "unmatched";
            MDC.put("http_method", request.getMethod());
            MDC.put("http_route", route);
            MDC.put("http_status", Integer.toString(response.getStatus()));
            MDC.put("duration_bucket", bucket(millis));
            try {
                LOG.info("http request observed");
            } finally {
                MDC.remove("http_method");
                MDC.remove("http_route");
                MDC.remove("http_status");
                MDC.remove("duration_bucket");
            }
        }
    }

    /** Maps a duration to the fixed bounded bucket scale. Package-visible for tests. */
    static String bucket(long millis) {
        if (millis < 100) {
            return "under_100ms";
        }
        if (millis < 500) {
            return "100_499ms";
        }
        if (millis < 1_000) {
            return "500_999ms";
        }
        if (millis < 5_000) {
            return "1_4s";
        }
        return "5s_or_more";
    }
}
