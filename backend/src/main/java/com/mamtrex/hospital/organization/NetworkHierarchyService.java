package com.mamtrex.hospital.organization;

import com.mamtrex.hospital.auth.ActingContext;
import com.mamtrex.hospital.auth.AssignmentScope;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceUnitUtil;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Derives the authorized network-hierarchy slice of one request (Phase 5
 * US1, T034) from server-owned state only: the verified
 * {@link ActingContext} the JWT filter rebuilt from the acting assignment,
 * plus the organization/hospital/branch relationships in the repositories.
 *
 * <p>Nothing here accepts client-supplied hierarchy identifiers — the
 * endpoint has no parameters — and no scope can widen beyond the acting
 * assignment's own chain:
 *
 * <ul>
 *   <li>{@code ORGANIZATION} (network scope): every ACTIVE hospital of the
 *       acting network and every active branch that belongs to one of those
 *       active hospitals;</li>
 *   <li>{@code HOSPITAL}: only the acting hospital (which must be active
 *       and belong to the acting network) and its active branches;</li>
 *   <li>{@code BRANCH}: only the acting hospital with exactly the acting
 *       active branch — never its siblings;</li>
 *   <li>{@code DEPARTMENT}: the same branch slice as its department's
 *       branch.</li>
 * </ul>
 *
 * <p>An inactive ancestor fails closed: an inactive hospital disappears
 * from the network view and refuses its own scoped actors with the shared
 * generic, non-enumerating access-denied boundary. Branches
 * of inactive hospitals are excluded from the network view by intersecting
 * the two authorized sets — both loaded with one fixed repository query
 * each — so the total query count is constant regardless of how many
 * hospitals or branches the network contains (T037); no per-hospital loop
 * and no lazy graph traversal ever touches the database. The branch-to-
 * hospital grouping reads the foreign-key identifier of the lazy proxy
 * through {@link PersistenceUnitUtil#getIdentifier}, which never
 * initializes the proxy, keeping the loader exactly bounded.</p>
 */
@Service
public class NetworkHierarchyService {

    private final HospitalOrganizationRepository organizations;
    private final HospitalFacilityRepository hospitals;
    private final BranchRepository branches;
    private final PersistenceUnitUtil persistenceUnitUtil;

    public NetworkHierarchyService(HospitalOrganizationRepository organizations,
                                   HospitalFacilityRepository hospitals,
                                   BranchRepository branches,
                                   EntityManagerFactory entityManagerFactory) {
        this.organizations = organizations;
        this.hospitals = hospitals;
        this.branches = branches;
        this.persistenceUnitUtil = entityManagerFactory.getPersistenceUnitUtil();
    }

    /**
     * The authorized hierarchy slice of the acting context, in deterministic
     * order: hospitals by code, branches by code inside each hospital. A
     * context that cannot resolve an authorized slice (wiped organization,
     * inactive acting hospital, missing acting branch) fails closed with the
     * shared generic refusal — never a partial or widened answer.
     */
    @Transactional(readOnly = true)
    public NetworkHierarchyDtos.NetworkHierarchy hierarchy(ActingContext acting) {
        HospitalOrganization organization = organizations.findById(acting.organizationId())
                .orElseThrow(NetworkHierarchyService::refused);

        List<HospitalFacility> authorizedHospitals = authorizedHospitals(acting);
        List<Branch> authorizedBranches = authorizedBranches(acting);

        Set<UUID> authorizedHospitalIds = new HashSet<>();
        for (HospitalFacility hospital : authorizedHospitals) {
            authorizedHospitalIds.add(hospital.getId());
        }
        // Fixed-shape grouping: one pass over the authorized branches, keyed
        // by their owning hospital id, preserving the repository's code order.
        Map<UUID, List<Branch>> branchesByHospitalId = new LinkedHashMap<>();
        for (Branch branch : authorizedBranches) {
            UUID hospitalId = hospitalIdOf(branch);
            if (authorizedHospitalIds.contains(hospitalId)) {
                branchesByHospitalId.computeIfAbsent(hospitalId, id -> new ArrayList<>()).add(branch);
            }
        }

        List<NetworkHierarchyDtos.HospitalView> hospitalViews = new ArrayList<>();
        for (HospitalFacility hospital : authorizedHospitals) {
            List<NetworkHierarchyDtos.BranchView> branchViews = new ArrayList<>();
            for (Branch branch : branchesByHospitalId.getOrDefault(hospital.getId(), List.of())) {
                branchViews.add(NetworkHierarchyDtos.BranchView.from(branch, hospital.getId()));
            }
            hospitalViews.add(NetworkHierarchyDtos.HospitalView.from(hospital, branchViews));
        }
        return NetworkHierarchyDtos.NetworkHierarchy.from(organization, hospitalViews);
    }

    /**
     * The authorized hospital set: the whole active network for
     * ORGANIZATION scope, or exactly the acting (active) hospital for the
     * narrower scopes. One fixed repository query per case.
     */
    private List<HospitalFacility> authorizedHospitals(ActingContext acting) {
        return switch (acting.scope()) {
            case ORGANIZATION -> hospitals.findByOrganizationIdAndActiveTrueOrderByCodeAsc(
                    acting.organizationId());
            case HOSPITAL, BRANCH, DEPARTMENT -> List.of(hospitals
                    .findByIdAndOrganizationId(acting.hospitalId(), acting.organizationId())
                    .filter(HospitalFacility::isActive)
                    .orElseThrow(NetworkHierarchyService::refused));
        };
    }

    /**
     * The authorized branch set: every active branch of the network for
     * ORGANIZATION scope (inactive-hospital descendants are excluded by the
     * grouping intersection), the active branches of the acting hospital for
     * HOSPITAL scope, and exactly the acting active branch for BRANCH and
     * DEPARTMENT scope. One fixed repository query per case.
     */
    private List<Branch> authorizedBranches(ActingContext acting) {
        return switch (acting.scope()) {
            case ORGANIZATION -> branches.findByOrganizationIdAndActiveTrueOrderByCodeAsc(
                    acting.organizationId());
            case HOSPITAL -> branches.findByHospitalIdAndActiveTrueOrderByCodeAsc(acting.hospitalId());
            case BRANCH, DEPARTMENT -> List.of(branches
                    .findByIdAndHospitalId(acting.branchId(), acting.hospitalId())
                    .filter(Branch::isActive)
                    .orElseThrow(NetworkHierarchyService::refused));
        };
    }

    /**
     * The owning hospital foreign key of a branch, read from the lazy proxy
     * without initialization — the proxy keeps its identifier from creation,
     * so grouping never triggers per-branch loading (query count stays
     * independent of descendant cardinality).
     */
    private UUID hospitalIdOf(Branch branch) {
        Object identifier = persistenceUnitUtil.getIdentifier(branch.getHospital());
        if (identifier instanceof UUID id) {
            return id;
        }
        throw refused();
    }

    /**
     * The fail-closed refusal shared by every unresolved hierarchy
     * resolution. {@link AccessDeniedException} rides the established
     * translation boundary: authenticated callers receive the one generic,
     * non-enumerating 403 security-error body, identical for an unknown,
     * foreign, or inactive ancestor — no hierarchy existence leaks.
     */
    private static AccessDeniedException refused() {
        return new AccessDeniedException("The acting hierarchy is not available.");
    }
}
