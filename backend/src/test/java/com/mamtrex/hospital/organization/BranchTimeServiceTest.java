package com.mamtrex.hospital.organization;

import com.mamtrex.hospital.shared.InvalidParameterValueException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Branch time-zone conversion unit evidence (plan Task 4, step 1; FR-012).
 *
 * Pins the narrow conversion policy: an ordinary branch-local time converts
 * through the branch's validated IANA zone; a nonexistent DST-gap local
 * time is rejected (never silently shifted); an ambiguous DST-overlap local
 * time resolves to the documented earlier offset; a legacy branch with no
 * zone fails closed; and no conversion depends on the JVM default time
 * zone (proven under two different host zones).
 */
class BranchTimeServiceTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ZoneId UTC = ZoneId.of("UTC");

    private TimeZone originalZone;

    private void useHostZone(String zone) {
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
    }

    @AfterEach
    void restoreHostZone() {
        if (originalZone != null) {
            TimeZone.setDefault(originalZone);
        }
    }

    private static Branch branch(ZoneId zone) {
        HospitalOrganization org = new HospitalOrganization("ZONE-ORG", "Demo Synthetic Hospital");
        return new Branch(org, "ZONE-BR", "Demo Branch", "1 Demo Campus", zone);
    }

    private static Branch legacyBranch() {
        HospitalOrganization org = new HospitalOrganization("ZONE-ORG", "Demo Synthetic Hospital");
        return new Branch(org, "ZONE-BR-LEGACY", "Demo Legacy Branch", "2 Demo Campus", (java.time.ZoneId) null);
    }

    /** Ordinary conversion: a New York local summer time is UTC-4. */
    @Test
    void ordinaryLocalTimeConvertsThroughBranchZone() {
        Instant instant = BranchTimeService.toInstant(branch(NEW_YORK), LocalDateTime.parse("2031-06-01T12:00"));
        assertEquals(Instant.parse("2031-06-01T16:00:00Z"), instant);
        assertEquals(LocalDateTime.parse("2031-06-01T12:00"),
                BranchTimeService.toLocalDateTime(branch(NEW_YORK), instant));
    }

    /** A nonexistent DST-gap local time is rejected, never silently shifted. */
    @Test
    void dstGapLocalTimeIsRejected() {
        // 2031-03-09 02:30 does not exist in America/New_York (spring forward).
        assertThrows(InvalidParameterValueException.class,
                () -> BranchTimeService.toInstant(branch(NEW_YORK), LocalDateTime.parse("2031-03-09T02:30")));
    }

    /** An ambiguous DST-overlap local time resolves to the documented earlier offset. */
    @Test
    void dstOverlapResolvesToEarlierOffset() {
        // 2031-11-02 01:30 occurs twice in America/New_York (fall back):
        // first occurrence is EDT (-04:00) -> 05:30Z, second is EST (-05:00) -> 06:30Z.
        Instant instant = BranchTimeService.toInstant(branch(NEW_YORK), LocalDateTime.parse("2031-11-02T01:30"));
        assertEquals(Instant.parse("2031-11-02T05:30:00Z"), instant);
        assertEquals(LocalDateTime.parse("2031-11-02T01:30"),
                BranchTimeService.toLocalDateTime(branch(NEW_YORK), instant));
    }

    /** A legacy branch without a zone fails closed instead of guessing. */
    @Test
    void legacyBranchWithoutZoneFailsClosed() {
        Branch legacy = legacyBranch();
        assertThrows(IllegalStateException.class,
                () -> BranchTimeService.toInstant(legacy, LocalDateTime.parse("2031-06-01T12:00")));
        assertThrows(IllegalStateException.class,
                () -> BranchTimeService.toLocalDateTime(legacy, Instant.parse("2031-06-01T16:00:00Z")));
    }

    /** No conversion may depend on the JVM default zone (host-zone independence). */
    @Test
    void conversionsAreIndependentOfTheJvmDefaultZone() {
        Branch nyBranch = branch(NEW_YORK);
        LocalDateTime local = LocalDateTime.parse("2031-06-01T12:00");

        useHostZone("Asia/Kolkata");
        Instant underKolkata = BranchTimeService.toInstant(nyBranch, local);
        LocalDateTime renderedKolkata = BranchTimeService.toLocalDateTime(nyBranch, underKolkata);

        useHostZone("Pacific/Honolulu");
        Instant underHonolulu = BranchTimeService.toInstant(nyBranch, local);
        LocalDateTime renderedHonolulu = BranchTimeService.toLocalDateTime(nyBranch, underHonolulu);

        assertEquals(underKolkata, underHonolulu, "the persisted instant must not depend on the host zone");
        assertEquals(LocalDateTime.parse("2031-06-01T12:00"), renderedKolkata);
        assertEquals(renderedKolkata, renderedHonolulu, "the rendered local time must not depend on the host zone");
        assertEquals(UTC.getRules().getOffset(underKolkata),
                UTC.getRules().getOffset(underHonolulu));
    }
}
