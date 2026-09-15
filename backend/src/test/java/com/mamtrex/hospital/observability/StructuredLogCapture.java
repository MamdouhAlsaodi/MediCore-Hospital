package com.mamtrex.hospital.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.mamtrex.hospital.infrastructure.StructuredJsonEncoder;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only capture seam for the structured telemetry boundary. The appender
 * renders every captured log event through the PRODUCTION
 * {@link StructuredJsonEncoder}, so the assertions exercise exactly the bytes
 * the application would emit — not a parallel test implementation. Captured
 * lines are the encoded events only; nothing is written to disk here.
 */
public final class StructuredLogCapture {

    private StructuredLogCapture() {
    }

    /** Root-logger appender that keeps every event encoded through the production encoder. */
    public static final class Appender extends AppenderBase<ILoggingEvent> {

        private final List<String> lines = new CopyOnWriteArrayList<>();
        private final StructuredJsonEncoder encoder = new StructuredJsonEncoder();

        Appender() {
            encoder.start();
            start();
        }

        @Override
        protected void append(ILoggingEvent eventObject) {
            lines.add(new String(encoder.encode(eventObject), StandardCharsets.UTF_8));
        }

        List<String> lines() {
            return List.copyOf(lines);
        }
    }

    /** Attach a fresh appender to the root logger. */
    public static Appender attach() {
        Appender appender = new Appender();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(appender);
        return appender;
    }

    /** Detach the appender and return the captured structured lines. */
    public static List<String> detach(Appender appender) {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(appender);
        return appender.lines();
    }
}
