package com.mamtrex.hospital.organization;

import com.mamtrex.hospital.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.Objects;

/**
 * One branch of the synthetic network (docs/plan3.md Task 2; Phase 5
 * data-model.md). Since Phase 5 a branch belongs to exactly one
 * {@link HospitalFacility} and, through it, to its organization — the
 * hospital is required, and the organization reference is derived from the
 * facility's own organization, so {@code branch.hospital.organization ==
 * branch.organization} holds by construction and the database's composite
 * consistency FK ({@code fk_branches_hospital_organization}) keeps the
 * stored pair unrepresentable-divergent. The legacy {@code organization_id}
 * column stays only as the migration compatibility seam.
 *
 * <p>A branch is active by default and carries a code that is unique inside
 * its hospital — enforced by the {@code (hospital_id, code)} DB unique
 * constraint ({@code uk_branches_hospital_code}, V5) as the concurrency
 * backstop behind the service pre-check; the replaced organization-scoped
 * constraint never widened back. The human code is an identifier only and
 * is never authorization evidence.</p>
 */
@Entity
@Table(name = "branches", uniqueConstraints = @UniqueConstraint(
        name = "uk_branches_hospital_code", columnNames = {"hospital_id", "code"}))
public class Branch extends BaseEntity {

    /**
     * The owning hospital facility (required). The organization column is
     * derived from it at construction and never set independently.
     */
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false)
    private HospitalFacility hospital;

    /** Migration compatibility seam: derived from {@link #hospital}; never client-assigned. */
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private HospitalOrganization organization;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "location_label", nullable = false)
    private String locationLabel;

    /**
     * The branch's validated IANA time zone (plan Task 4; FR-012). Every
     * branch-local time rendering and every branch-local -> instant
     * conversion for this branch's workflow rows flows through
     * {@link BranchTimeService} with this zone. Nullable only as the
     * deliberate legacy seam: pre-Phase-4 rows have no zone, are never
     * guessed from host time, and fail closed on conversion until
     * reconciled (the V3 migration blocks on unknown rows instead of
     * backfilling them).
     */
    @Column(name = "time_zone", length = 60)
    private java.time.ZoneId timeZone;

    @Column(nullable = false)
    private boolean active = true;

    protected Branch() {
    }

    /**
     * Legacy-shape constructor: kept so existing callers (tests, the demo
     * initializer's legacy seams) stay deterministic. It assigns UTC —
     * a fixed, documented, host-independent zone — never the JVM default.
     */
    public Branch(HospitalFacility hospital, String code, String name, String locationLabel) {
        this(hospital, code, name, locationLabel, java.time.ZoneId.of("UTC"));
    }

    /**
     * The full constructor: the owning facility is required, the organization
     * reference is derived from it (validated consistency — no independent,
     * possibly divergent organization parameter exists), and the zone is
     * validated by the branch surface, never taken raw from a client.
     */
    public Branch(HospitalFacility hospital, String code, String name, String locationLabel,
                  java.time.ZoneId timeZone) {
        this.hospital = Objects.requireNonNull(hospital, "hospital");
        this.organization = hospital.getOrganization();
        this.code = code;
        this.name = name;
        this.locationLabel = locationLabel;
        this.timeZone = timeZone;
    }

    public HospitalFacility getHospital() {
        return hospital;
    }

    /** The network owner, derived from the facility's own organization. */
    public HospitalOrganization getOrganization() {
        return organization;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getLocationLabel() {
        return locationLabel;
    }

    /** The branch's IANA zone; null only on legacy rows (never guessed). */
    public java.time.ZoneId getTimeZone() {
        return timeZone;
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Package-private lifecycle seam (docs/plan3.md Task 2): the contract
     * already owns the active state — the organization view shows only
     * active branches and inactive branches cannot own departments — but
     * this task ships no public deactivation endpoint. Widen visibility
     * only when a real branch lifecycle feature calls for it.
     */
    void deactivate() {
        this.active = false;
    }
}
