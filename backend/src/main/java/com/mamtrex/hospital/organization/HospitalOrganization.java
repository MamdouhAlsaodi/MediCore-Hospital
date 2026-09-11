package com.mamtrex.hospital.organization;

import com.mamtrex.hospital.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * The single hospital organization (docs/plan3.md Task 2). There is no
 * public create endpoint in this task: the row is provisioned by the
 * opt-in demo initializer under the stable {@code DEMO-ORG-001} business
 * key, and {@code GET /api/organization} exposes it through the
 * {@link OrganizationService} DTO allowlist. A missing row is the shared
 * safe 404 — never an auto-created default.
 */
@Entity
@Table(name = "hospital_organizations")
public class HospitalOrganization extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    protected HospitalOrganization() {
    }

    public HospitalOrganization(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}
