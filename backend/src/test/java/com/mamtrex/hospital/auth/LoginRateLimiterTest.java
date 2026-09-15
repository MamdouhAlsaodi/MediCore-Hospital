package com.mamtrex.hospital.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T060/T061 unit contract for the bounded expiring login rate limiter
 * (FR-009): the store is keyed by direct socket address, blocks after the
 * configured failure threshold inside the window, expires deterministically
 * (recovery after the window), clears on success, and — critically — the
 * store can never grow without bound: when the tracked-address cap is hit,
 * expired entries are purged first and the least-recently-active address is
 * evicted, so a distributed attacker cannot grow memory without limit.
 *
 * <p>Time comes from an injected mutable Clock, so expiry and recovery are
 * proven exactly — with no sleeps. The direct-address keying rule (no
 * X-Forwarded-For trust) is proven on the live HTTP surface by
 * {@code LoginRateLimitIntegrationTest}; this class owns the pure state
 * machine.
 */
class LoginRateLimiterTest {

    /** Mutable test clock so window expiry is exercised exactly, without sleeps. */
    private static final class MutableClock extends java.time.Clock {
        private Instant now = Instant.parse("2030-01-01T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final int THRESHOLD = 3;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final String CLIENT = "192.0.2.10";

    private MutableClock clock;
    private LoginRateLimiter limiter;

    private LoginRateLimiter newLimiter(int threshold, Duration window, int cap) {
        clock = new MutableClock();
        return new LoginRateLimiter(threshold, window, cap, clock);
    }

    @Test
    @DisplayName("failures below the threshold never block; the threshold attempt blocks")
    void thresholdBlocking() {
        limiter = newLimiter(THRESHOLD, WINDOW, 10_000);
        for (int i = 1; i < THRESHOLD; i++) {
            limiter.recordFailure(CLIENT);
            assertFalse(limiter.isBlocked(CLIENT), i + " failures must not block yet");
        }
        limiter.recordFailure(CLIENT);
        assertTrue(limiter.isBlocked(CLIENT), "reaching the failure threshold must block the address");
        assertFalse(limiter.isBlocked("192.0.2.11"), "a different direct address stays unblocked");
    }

    @Test
    @DisplayName("a successful login clears the address so recovery is immediate after success")
    void successClearsFailures() {
        limiter = newLimiter(THRESHOLD, WINDOW, 10_000);
        limiter.recordFailure(CLIENT);
        limiter.recordFailure(CLIENT);
        limiter.recordSuccess(CLIENT);
        assertFalse(limiter.isBlocked(CLIENT), "success must clear prior failures");
        limiter.recordFailure(CLIENT);
        limiter.recordFailure(CLIENT);
        assertFalse(limiter.isBlocked(CLIENT), "counting restarts from zero after a success");
    }

    @Test
    @DisplayName("failures expire after the window: expiry unblocks and old failures stop counting")
    void windowExpiryRecoverAndDoNotStack() {
        limiter = newLimiter(THRESHOLD, WINDOW, 10_000);
        limiter.recordFailure(CLIENT);
        clock.advance(WINDOW.minusSeconds(1));
        limiter.recordFailure(CLIENT);
        assertFalse(limiter.isBlocked(CLIENT), "two in-window failures stay below the threshold");
        clock.advance(Duration.ofSeconds(1));
        // The first failure just expired; only one in-window failure remains.
        assertFalse(limiter.isBlocked(CLIENT), "expired failures must stop counting toward the threshold");
        limiter.recordFailure(CLIENT);
        limiter.recordFailure(CLIENT);
        assertTrue(limiter.isBlocked(CLIENT),
                "the threshold counts only failures inside the window");
        clock.advance(WINDOW.plusSeconds(1));
        assertFalse(limiter.isBlocked(CLIENT), "after the whole window passes the address recovers");
        assertEquals(0, limiter.trackedAddresses(), "an expired-only address must not stay tracked");
    }

    @Test
    @DisplayName("the store is strictly bounded: expired purge first, then least-recently-active eviction")
    void storeNeverExceedsTheFiniteCap() {
        int cap = 3;
        // Threshold 1 makes one recorded failure block, so isBlocked doubles
        // as an exact presence check for the eviction assertions.
        limiter = newLimiter(1, WINDOW, cap);
        List<String> addresses = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            addresses.add("10.0." + (i / 256) + "." + (i % 256));
        }
        for (String address : addresses) {
            limiter.recordFailure(address);
            assertTrue(limiter.trackedAddresses() <= cap,
                    "the tracked-address count must never exceed the finite cap (saw "
                            + limiter.trackedAddresses() + ")");
        }
        assertEquals(cap, limiter.trackedAddresses(), "at a saturated cap the store holds exactly cap keys");
        // The three most recently recorded addresses must be the survivors.
        assertTrue(limiter.isBlocked(addresses.get(49)), "the newest address stays tracked");
        assertTrue(limiter.isBlocked(addresses.get(48)), "the second-newest address stays tracked");
        assertTrue(limiter.isBlocked(addresses.get(47)), "the third-newest address stays tracked");
        assertFalse(limiter.isBlocked(addresses.get(0)),
                "the least-recently-active address was evicted at cap saturation");
    }

    @Test
    @DisplayName("expired entries are purged before live ones are evicted at cap saturation")
    void expiryPurgeRunsBeforeEviction() {
        int cap = 2;
        limiter = newLimiter(1, WINDOW, cap);
        limiter.recordFailure("10.0.0.1");
        clock.advance(WINDOW.plusSeconds(1)); // first entry is now fully expired
        limiter.recordFailure("10.0.0.2");
        limiter.recordFailure("10.0.0.3");
        assertEquals(2, limiter.trackedAddresses());
        assertFalse(limiter.isBlocked("10.0.0.1"),
                "the expired entry was purged rather than evicting a live address");
        assertTrue(limiter.isBlocked("10.0.0.2"), "the live older entry survives the purge");
        assertTrue(limiter.isBlocked("10.0.0.3"), "the live newer entry survives the purge");
    }
}
