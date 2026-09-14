package com.mamtrex.hospital.organization;

import com.mamtrex.hospital.shared.InvalidParameterValueException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRulesException;
import java.util.List;

/**
 * The single branch-zone time boundary (plan Task 4; FR-012). Branch-local
 * wall-clock values and stored instants meet exactly here:
 *
 * <ul>
 *   <li>Storage is always an unambiguous {@link Instant}; a branch-local
 *       {@link LocalDateTime} is only ever an input or an output
 *       rendering.</li>
 *   <li>The zone comes from the server-side branch row — never the client,
 *       never the JVM default. A branch without a configured zone fails
 *       closed instead of guessing (unknown legacy rows are never adopted;
 *       the V3 migration blocks on them rather than backfilling from host
 *       time).</li>
 *   <li>A branch-local time inside a DST gap (nonexistent) is rejected as
 *       an invalid parameter; an ambiguous DST-overlap time resolves to
 *       the documented earlier offset (the first occurrence). The JVM
 *       default zone can never influence a persisted instant.</li>
 * </ul>
 *
 * A pure static utility: no state, no collaborators, nothing to mock —
 * the conversion policy is the contract itself.
 */
public final class BranchTimeService {

    private BranchTimeService() {
    }

    /** Converts a branch-local wall-clock value to the unambiguous stored instant. */
    public static Instant toInstant(Branch branch, LocalDateTime local) {
        ZoneId zone = requireZone(branch);
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        if (offsets.isEmpty()) {
            throw new InvalidParameterValueException(
                    "The time " + local + " does not exist in the branch time zone " + zone
                            + " (daylight-saving gap); choose a time that exists");
        }
        // Ambiguous overlap: getValidOffsets returns both occurrences in
        // order; the first is the earlier offset (the documented policy).
        return local.toInstant(offsets.get(0));
    }

    /** Renders a stored instant as the branch-local wall-clock value. */
    public static LocalDateTime toLocalDateTime(Branch branch, Instant instant) {
        return LocalDateTime.ofInstant(instant, requireZone(branch));
    }

    /**
     * Renders a stored instant with an explicit zone — the DTO-mapper form
     * used where only the zone (not the entity) is at hand. Null zone is
     * the legacy fail-closed seam.
     */
    public static LocalDateTime render(ZoneId zone, Instant instant) {
        if (zone == null) {
            throw new IllegalStateException("No branch time zone is configured; unknown legacy rows are never guessed");
        }
        return LocalDateTime.ofInstant(instant, zone);
    }

    /** Parses and validates an IANA zone id supplied to the branch surface. */
    public static ZoneId validatedZone(String candidate) {
        try {
            return ZoneId.of(candidate.trim());
        } catch (ZoneRulesException | NullPointerException | IllegalArgumentException invalid) {
            throw new InvalidParameterValueException("timeZone must be a valid IANA time zone id");
        }
    }

    private static ZoneId requireZone(Branch branch) {
        ZoneId zone = branch.getTimeZone();
        if (zone == null) {
            throw new IllegalStateException(
                    "Branch " + branch.getCode() + " has no configured time zone; "
                            + "unknown legacy rows are never guessed");
        }
        return zone;
    }
}
