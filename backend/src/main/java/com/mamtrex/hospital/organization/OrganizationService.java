package com.mamtrex.hospital.organization;

import com.mamtrex.hospital.audit.AuditService;
import com.mamtrex.hospital.organization.HospitalFacility;
import com.mamtrex.hospital.organization.HospitalFacilityRepository;
import com.mamtrex.hospital.shared.NotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Organization and branch rules (docs/plan3.md Task 2; Phase 5 hierarchy).
 * The single organization is resolved server-side — a missing row is the
 * shared safe 404, so a branch can never bind to a nonexistent hierarchy
 * through the public contract. Since Phase 5 a branch binds to its hospital
 * facility: the facility is resolved server-side (the deterministic first
 * active hospital of the organization), the branch code pre-check is
 * hospital-scoped, and the {@code (hospital_id, code)} DB unique constraint
 * (uk_branches_hospital_code) is the concurrency backstop whose translated
 * violation answers the generic conflict. Every create records exactly one
 * CREATE audit event per real insert. Successful lookups record nothing,
 * and the human code never carries authorization meaning.
 */
@Service
@Transactional
public class OrganizationService {

    private final HospitalOrganizationRepository organizations;
    private final BranchRepository branches;
    private final HospitalFacilityRepository hospitals;
    private final AuditService audit;

    public OrganizationService(HospitalOrganizationRepository organizations,
                               BranchRepository branches,
                               HospitalFacilityRepository hospitals,
                               AuditService audit) {
        this.organizations = organizations;
        this.branches = branches;
        this.hospitals = hospitals;
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
        // Phase 5: the branch binds to its hospital facility. The acting
        // facility is resolved server-side — the deterministic first active
        // hospital of the organization — never accepted from the client. A
        // network with no hospital is an uninitialized hierarchy and keeps
        // the shared safe 404.
        HospitalFacility hospital = hospitals
                .findFirstByOrganizationIdAndActiveTrueOrderByCodeAsc(organization.getId())
                .orElseThrow(() -> new NotFoundException("Hospital not found"));
        String code = request.code().trim();
        branches.findByHospitalIdAndCode(hospital.getId(), code).ifPresent(existing -> {
            throw new DuplicateKeyException("Branch code already exists in this hospital");
        });
        // Phase 4 (FR-012): the branch carries a validated IANA zone. An
        // absent value defaults to the fixed, documented UTC — never the
        // JVM default; an unparseable zone is the shared 400.
        java.time.ZoneId zone = request.timeZone() == null || request.timeZone().isBlank()
                ? java.time.ZoneId.of("UTC")
                : BranchTimeService.validatedZone(request.timeZone());
        Branch saved = branches.save(new Branch(hospital, code,
                request.name().trim(), request.locationLabel().trim(), zone));
        audit.record("CREATE", "Branch", saved.getId().toString(), "created");
        return OrganizationDtos.BranchResponse.from(saved);
    }

    /** The single organization row; its absence is the shared safe 404. */
    private HospitalOrganization soleOrganization() {
        return organizations.findAll().stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Organization not found"));
    }
}
