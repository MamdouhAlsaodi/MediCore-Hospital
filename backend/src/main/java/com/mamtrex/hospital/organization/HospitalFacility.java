package com.mamtrex.hospital.organization;

import com.mamtrex.hospital.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * One hospital facility of the single synthetic network (specs/005
 * data-model.md; Phase 5 T012). The {@code HospitalOrganization} stays the
 * one network aggregate; a facility is the hierarchy level between it and a
 * {@link Branch}. A facility belongs to exactly one organization, carries a
 * code unique inside that organization (enforced here by the named
 * {@code uk_hospitals_organization_code} declaration and at the database by
 * the identical V5 constraint), a synthetic display region label, a
 * validated IANA time zone, and an active flag that defaults to true —
 * an invalid zone or a missing field is inexpressible because the
 * constructor parses and requires every value. The human code is an
 * identifier only and is never authorization evidence. Exactly one
 * provisioned facility exists in the Phase 5 demo network, but no code may
 * depend on that fact (no {@code findAll().first()} shortcuts).
 */
@Entity
@Table(name = "hospitals", uniqueConstraints = @UniqueConstraint(
        name = "uk_hospitals_organization_code", columnNames = {"organization_id", "code"}))
public class HospitalFacility extends BaseEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private HospitalOrganization organization;

    @Column(nullable = false, length = 32)
    private String code;

    @Column(nullable = false, length = 160)
    private String name;

    /** Synthetic display label only — never a private address. */
    @Column(name = "region_label", nullable = false, length = 160)
    private String regionLabel;

    /**
     * The hospital's validated IANA time zone (data-model.md). Parsed in the
     * constructor, so an unknown zone fails closed at creation and no raw
     * unvalidated string is ever stored.
     */
    @Column(name = "time_zone", nullable = false, length = 60)
    private ZoneId timeZone;

    @Column(nullable = false)
    private boolean active = true;

    protected HospitalFacility() {
    }

    /**
     * The one creating constructor: every described field is required and
     * the IANA zone is validated here, so an invalid facility cannot be
     * built. Pure fixed-offset inputs (for example {@code +01:00}) are not
     * named IANA regions and are rejected; named regions and the fixed
     * {@code UTC} region id are accepted.
     */
    public HospitalFacility(HospitalOrganization organization, String code, String name,
                            String regionLabel, String timeZoneId) {
        this.organization = Objects.requireNonNull(organization, "organization");
        this.code = Objects.requireNonNull(code, "code");
        this.name = Objects.requireNonNull(name, "name");
        this.regionLabel = Objects.requireNonNull(regionLabel, "regionLabel");
        Objects.requireNonNull(timeZoneId, "timeZoneId");
        ZoneId parsed = ZoneId.of(timeZoneId); // invalid ids throw before anything is stored
        if (parsed instanceof ZoneOffset) {
            throw new java.time.DateTimeException("Hospital time zones must be IANA region ids: " + timeZoneId);
        }
        this.timeZone = parsed;
    }

    public HospitalOrganization getOrganization() {
        return organization;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getRegionLabel() {
        return regionLabel;
    }

    /** The hospital's validated IANA zone. */
    public ZoneId getTimeZone() {
        return timeZone;
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Package-private lifecycle seam (mirrors {@link Branch#deactivate()}):
     * hospitals are only ever deactivated through server-side lifecycle
     * features; this task ships no public endpoint. Widen visibility only
     * when a real hospital-lifecycle feature calls for it.
     */
    void deactivate() {
        this.active = false;
    }
}
