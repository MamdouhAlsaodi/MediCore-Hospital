package com.mamtrex.hospital.infrastructure;

import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The metrics export boundary (plan Task 8, T055; FR-008/FR-010): the
 * review-facing Prometheus output carries ONLY the bounded server-side
 * request metric plus JVM/system gauges — never client-side request
 * metrics.
 *
 * <p>{@code http.client.requests} is excluded because it has no stable,
 * low-cardinality URI label set (a client-side observation records the raw
 * target URI when no template exists), and the demonstrated runtime makes
 * no outbound HTTP calls. The task mandates adding ONLY low-cardinality
 * HTTP/business metrics; a metric family whose label values can become raw
 * identifiers is therefore never exported, whatever bean in the context
 * (now or later) happens to make an outbound call. Server-side requests
 * remain bounded because Spring MVC records URI TEMPLATES, which the
 * boundary tests enforce.
 */
@Configuration
public class MetricsBoundaryConfig {

    @Bean
    MeterFilter noClientRequestMetricsExport() {
        return MeterFilter.deny(id -> id.getName().startsWith("http.client.requests"));
    }
}
