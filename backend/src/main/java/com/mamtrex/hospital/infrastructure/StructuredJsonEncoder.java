package com.mamtrex.hospital.infrastructure;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/**
 * The production structured-log encoder (plan Task 8, T054; FR-007): one
 * bounded JSON object per log event, one line, UTF-8. Fixed fields —
 * {@code timestamp} (ISO-8601 instant), {@code level}, {@code logger},
 * {@code thread}, {@code message} — are followed by the event's MDC entries
 * flattened as top-level string fields; the only MDC producers in the
 * application are the {@code CorrelationIdFilter} (single correlation
 * authority: {@code correlationId}) and the {@link RequestObservationLogFilter}
 * (bounded HTTP observation: {@code http_method}, {@code http_route},
 * {@code http_status}, {@code duration_bucket}).
 *
 * <p>Security boundary: values are JSON-escaped (control characters become
 * \\u escapes), so header-injected or hostile input can never break out of
 * the JSON structure or forge additional fields. Stack traces and raw
 * exception bodies are intentionally NOT rendered — the structured stream
 * carries message text only — and no credential, token, request body, or
 * domain value is ever placed into the MDC by the two producing filters.
 */
public class StructuredJsonEncoder extends EncoderBase<ILoggingEvent> {

    private static final byte[] EMPTY = new byte[0];
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    @Override
    public byte[] headerBytes() {
        return EMPTY;
    }

    @Override
    public byte[] footerBytes() {
        return EMPTY;
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        StringBuilder json = new StringBuilder(256);
        json.append('{');
        field(json, "timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        field(json, "level", event.getLevel().toString());
        field(json, "logger", event.getLoggerName());
        field(json, "thread", event.getThreadName());
        field(json, "message", event.getFormattedMessage());
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null) {
            for (Map.Entry<String, String> entry : mdc.entrySet()) {
                field(json, entry.getKey(), entry.getValue());
            }
        }
        json.append('}');
        json.append('\n');
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void field(StringBuilder json, String key, String value) {
        if (json.length() > 1) {
            json.append(',');
        }
        escapeAndQuote(json, key);
        json.append(':');
        escapeAndQuote(json, value == null ? "null" : value);
    }

    /** Strict JSON string escaping: quote, backslash, and every control character. */
    private static void escapeAndQuote(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append("\\u00");
                        json.append(HEX[(c >> 4) & 0xF]);
                        json.append(HEX[c & 0xF]);
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        json.append('"');
    }
}
