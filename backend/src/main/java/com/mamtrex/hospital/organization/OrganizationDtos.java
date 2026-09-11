package com.mamtrex.hospital.organization;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.UUID;

/**
 * Public organization/branch contract for /api/organization and
 * /api/branches (docs/plan3.md Task 2). Responses are strict allowlists —
 * identity, labels, and the active flag only, never timestamps, version,
 * or raw JPA entities. The create request accepts exactly
 * {@code code, name, locationLabel}: a human branch code is an identifier
 * inside the organization and is never authorization evidence, and no
 * organization field is client-writable in this task.
 */
public final class OrganizationDtos {

    private OrganizationDtos() {
    }

    /**
     * Stable branch representation: exactly these six fields, no
     * persistence metadata.
     */
    public record BranchResponse(UUID id, UUID organizationId, String code, String name,
                                 String locationLabel, boolean active) {

        public static BranchResponse from(Branch branch) {
            return new BranchResponse(branch.getId(), branch.getOrganization().getId(),
                    branch.getCode(), branch.getName(), branch.getLocationLabel(), branch.isActive());
        }
    }

    /**
     * The single-organization representation: identity, labels, and only
     * the ACTIVE branches in deterministic order — deactivated branches
     * never appear in the organization view.
     */
    public record OrganizationResponse(UUID id, String code, String name,
                                       List<BranchResponse> activeBranches) {

        public static OrganizationResponse from(HospitalOrganization organization,
                                                List<BranchResponse> activeBranches) {
            return new OrganizationResponse(organization.getId(), organization.getCode(),
                    organization.getName(), List.copyOf(activeBranches));
        }
    }

    /**
     * Create request allowlist: code, name, locationLabel — nothing else.
     * The owning organization is resolved server-side (the sole row), the
     * active flag is server-owned (default true), and unknown body members
     * are ignored and never persisted.
     */
    public record CreateBranchRequest(@NotBlank String code,
                                      @NotBlank String name,
                                      @NotBlank String locationLabel) {
    }
}
