package com.mamtrex.hospital.organization;

import com.mamtrex.hospital.audit.AuditService;
import com.mamtrex.hospital.shared.NotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Organization and branch rules (docs/plan3.md Task 2). The single
 * organization is resolved server-side — a missing row is the shared safe
 * 404, so a branch can never bind to a nonexistent organization through
 * the public contract. Branch creation trims every accepted value,
 * refuses a duplicate code inside the organization twice (the cause-free
 * duplicate pre-check maps to the safe 409 and the
 * {@code (organization_id, code)} DB unique constraint is the concurrency
 * backstop whose translated violation answers the generic conflict), and
 * records exactly one CREATE audit event per real insert. Successful
 * lookups record nothing, and the human code never carries authorization
 * meaning.
 */
@Service
@Transactional
public class OrganizationService {

    private final HospitalOrganizationRepository organizations;
    private final BranchRepository branches;
    private final AuditService audit;

    public OrganizationService(HospitalOrganizationRepository organizations,
                               BranchRepository branches,
                               AuditService audit) {
        this.organizations = organizations;
        this.branches = branches;
        this.audit = audit;
    }

    /**
     * The organization view: the sole organization with only its active
     * branches in deterministic code order. No organization row is the
     * shared safe 404.
     */
    @Transactional(readOnly = true)
    public OrganizationDtos.OrganizationResponse loadOrganization() {
        HospitalOrganization organization = soleOrganization();
        List<OrganizationDtos.BranchResponse> active = branches
                .findByOrganizationIdAndActiveTrueOrderByCodeAsc(organization.getId())
                .stream()
                .map(OrganizationDtos.BranchResponse::from)
                .toList();
        return OrganizationDtos.OrganizationResponse.from(organization, active);
    }

    @Transactional(readOnly = true)
    public List<OrganizationDtos.BranchResponse> listBranches() {
        return branches.findAllByOrderByCodeAscIdAsc().stream()
                .map(OrganizationDtos.BranchResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrganizationDtos.BranchResponse getBranch(UUID id) {
        return branches.findById(id)
                .map(OrganizationDtos.BranchResponse::from)
                .orElseThrow(() -> new NotFoundException("Branch not found: " + id));
    }

    public OrganizationDtos.BranchResponse createBranch(OrganizationDtos.CreateBranchRequest request) {
        HospitalOrganization organization = soleOrganization();
        String code = request.code().trim();
        branches.findByOrganizationIdAndCode(organization.getId(), code).ifPresent(existing -> {
            throw new DuplicateKeyException("Branch code already exists in this organization");
        });
        Branch saved = branches.save(new Branch(organization, code,
                request.name().trim(), request.locationLabel().trim()));
        audit.record("CREATE", "Branch", saved.getId().toString(), "created");
        return OrganizationDtos.BranchResponse.from(saved);
    }

    /** The single organization row; its absence is the shared safe 404. */
    private HospitalOrganization soleOrganization() {
        return organizations.findAll().stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Organization not found"));
    }
}
