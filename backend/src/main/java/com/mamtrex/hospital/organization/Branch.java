package com.mamtrex.hospital.organization;

import com.mamtrex.hospital.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One branch of the single hospital organization (docs/plan3.md Task 2).
 * A branch belongs to exactly one organization, is active by default, and
 * carries a code that is unique inside its organization — enforced here by
 * the {@code (organization_id, code)} DB unique constraint as the
 * concurrency backstop behind the {@link OrganizationService} pre-check.
 * The human code is an identifier only and is never authorization
 * evidence.
 */
@Entity
@Table(name = "branches", uniqueConstraints = @UniqueConstraint(
        name = "uk_branches_organization_code", columnNames = {"organization_id", "code"}))
public class Branch extends BaseEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private HospitalOrganization organization;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "location_label", nullable = false)
    private String locationLabel;

    @Column(nullable = false)
    private boolean active = true;

    protected Branch() {
    }

    public Branch(HospitalOrganization organization, String code, String name, String locationLabel) {
        this.organization = organization;
        this.code = code;
        this.name = name;
        this.locationLabel = locationLabel;
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

    public String getLocationLabel() {
        return locationLabel;
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
