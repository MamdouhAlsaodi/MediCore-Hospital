package com.mamtrex.hospital.organization;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Public network-hierarchy contract for {@code GET /api/network/hierarchy}
 * (Phase 5 US1, planning contract {@code NetworkHierarchy} /
 * {@code HospitalView} / {@code BranchView}). Responses are explicit,
 * immutable, deterministic allowlists — exactly the identity, label, zone,
 * and active-state fields the contract documents, plus the owning hospital
 * id as an explicit UUID on every branch view. No JPA entity, no
 * persistence metadata (timestamps/version), and no lazy object graph is
 * ever serialized: the service assembles these records from server-derived
 * authorized slices only, and {@code List.copyOf} makes every nested
 * collection defensive and unmodifiable. Property order is the record
 * declaration order, so equal values serialize byte-identically.
 */
public final class NetworkHierarchyDtos {

    private NetworkHierarchyDtos() {
    }

    /**
     * One authorized branch of one hospital: exactly the six contract
     * fields. The owning hospital is carried as the explicit
     * {@code hospitalId} UUID — never as a nested entity graph.
     */
    public record BranchView(UUID id, UUID hospitalId, String code, String name,
                             ZoneId timeZone, boolean active) {

        /** Strict allowlist projection of one branch; the hospital id is supplied by the service slice. */
        public static BranchView from(Branch branch, UUID hospitalId) {
            return new BranchView(branch.getId(), hospitalId, branch.getCode(), branch.getName(),
                    branch.getTimeZone(), branch.isActive());
        }
    }

    /**
     * One authorized hospital with its authorized branches in deterministic
     * order. Exactly the seven contract fields; the branches list is
     * defensively copied and immutable.
     */
    public record HospitalView(UUID id, String code, String name, String regionLabel,
                               ZoneId timeZone, boolean active, List<BranchView> branches) {

        public HospitalView {
            branches = List.copyOf(branches);
        }

        /** Strict allowlist projection of one hospital and its authorized branch slice. */
        public static HospitalView from(HospitalFacility hospital, List<BranchView> branches) {
            return new HospitalView(hospital.getId(), hospital.getCode(), hospital.getName(),
                    hospital.getRegionLabel(), hospital.getTimeZone(), hospital.isActive(), branches);
        }
    }

    /**
     * The one synthetic network with exactly the authorized hospitals in
     * deterministic order. Exactly the four contract fields; the hospitals
     * list is defensively copied and immutable.
     */
    public record NetworkHierarchy(UUID organizationId, String organizationCode, String organizationName,
                                   List<HospitalView> hospitals) {

        public NetworkHierarchy {
            hospitals = List.copyOf(hospitals);
        }

        /** Strict allowlist projection of the authorized network hierarchy. */
        public static NetworkHierarchy from(HospitalOrganization organization, List<HospitalView> hospitals) {
            return new NetworkHierarchy(organization.getId(), organization.getCode(),
                    organization.getName(), hospitals);
        }
    }
}
