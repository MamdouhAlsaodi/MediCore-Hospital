package com.mamtrex.hospital.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * T061 (FR-009): the bounded, expiring login rate limiter. Failure tracking
 * is keyed by the DIRECT SOCKET ADDRESS of the request
 * ({@code HttpServletRequest#getRemoteAddr}) — this system has NO
 * trusted-proxy configuration, so {@code X-Forwarded-For} and every other
 * forwarded header are deliberately never consulted; a client cannot rotate
 * or forge its way around the limit, and a reverse proxy can only ever
 * appear as one well-known address (the review deployment's frontend
 * service), which is the documented, accepted posture for the same-origin
 * review stack.
 *
 * <p>State model, per address: a bounded deque of failed-login instants.
 * An address is blocked when the number of failures inside the configured
 * window has reached the threshold; a successful login clears the address
 * immediately; entries older than the window never count and are purged, so
 * recovery after the documented window is automatic. The whole store is
 * strictly bounded: when the tracked-address cap is hit, fully-expired
 * addresses are purged first, then the least-recently-active address is
 * evicted — a distributed attacker cannot grow memory without limit.
 *
 * <p>Operations are serialized on one monitor: the login surface is
 * low-frequency, the critical sections are tiny, and serialization is what
 * makes the finite-cap invariant strict even under concurrent login storms.
 * Refusals carry no account information — the limiter only ever sees an
 * address and a failure count, and it never writes audit events, tokens, or
 * logs of its own.
 */
@Component
public class LoginRateLimiter {

    private final int maxFailures;
    private final Duration window;
    private final int maxTrackedAddresses;
    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new LinkedHashMap<>();

    @Autowired
    public LoginRateLimiter(@Value("${hospital.security.login.max-failures:5}") int maxFailures,
                            @Value("${hospital.security.login.window:PT15M}") Duration window,
                            @Value("${hospital.security.login.max-tracked-addresses:10000}")
                            int maxTrackedAddresses) {
        this(maxFailures, window, maxTrackedAddresses, Clock.systemUTC());
    }

    /** Test seam: an injected Clock makes window expiry provable without sleeps. */
    LoginRateLimiter(int maxFailures, Duration window, int maxTrackedAddresses, Clock clock) {
        if (maxFailures < 1 || maxTrackedAddresses < 1 || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("login rate-limit bounds must be positive");
        }
        this.maxFailures = maxFailures;
        this.window = window;
        this.maxTrackedAddresses = maxTrackedAddresses;
        this.clock = clock;
    }

    /** True when this direct address has reached the failure threshold inside the window. */
    public synchronized boolean isBlocked(String address) {
        Deque<Instant> attempts = failures.get(address);
        if (attempts == null) {
            return false;
        }
        purgeExpired(attempts);
        if (attempts.isEmpty()) {
            failures.remove(address);
            return false;
        }
        return attempts.size() >= maxFailures;
    }

    /** Records one failed login for the direct address, enforcing the finite store cap. */
    public synchronized void recordFailure(String address) {
        Instant cutoff = clock.instant().minus(window);
        Deque<Instant> attempts = failures.get(address);
        if (attempts == null) {
            if (failures.size() >= maxTrackedAddresses) {
                purgeExpiredEverywhere(cutoff);
            }
            if (failures.size() >= maxTrackedAddresses) {
                evictLeastRecentlyActive();
            }
            attempts = new ArrayDeque<>();
            failures.put(address, attempts);
        }
        attempts.addLast(clock.instant());
        purgeExpired(attempts);
    }

    /** A successful login clears every recorded failure for the address. */
    public synchronized void recordSuccess(String address) {
        failures.remove(address);
    }

    /** Current tracked-address count (bounded observability for tests and the threat model). */
    public synchronized int trackedAddresses() {
        return failures.size();
    }

    /** The configured window in whole seconds (the 429 Retry-After hint). */
    public synchronized long windowSeconds() {
        return window.toSeconds();
    }

    private void purgeExpired(Deque<Instant> attempts) {
        Instant cutoff = clock.instant().minus(window);
        attempts.removeIf(attempt -> attempt.isBefore(cutoff));
    }

    private void purgeExpiredEverywhere(Instant cutoff) {
        failures.values().removeIf(attempts -> {
            attempts.removeIf(attempt -> attempt.isBefore(cutoff));
            return attempts.isEmpty();
        });
    }

    /** Cap is saturated with live entries: drop the address whose latest failure is oldest. */
    private void evictLeastRecentlyActive() {
        String oldestKey = null;
        Instant oldestActivity = null;
        for (Map.Entry<String, Deque<Instant>> entry : failures.entrySet()) {
            Instant latest = entry.getValue().peekLast();
            if (latest != null && (oldestActivity == null || latest.isBefore(oldestActivity))) {
                oldestActivity = latest;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            failures.remove(oldestKey);
        }
    }
}
