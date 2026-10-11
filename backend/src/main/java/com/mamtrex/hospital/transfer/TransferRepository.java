package com.mamtrex.hospital.transfer;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for transfer aggregates (Phase 5 T085). Reads are always
 * organization-scoped: a transfer of another organization is simply absent.
 * Mutation paths load with a pessimistic write lock so concurrent accept/
 * reject/cancel/complete losers fail instead of interleaving; the inherited
 * optimistic {@code @Version} remains the second line of defense.
 */
public interface TransferRepository extends JpaRepository<TransferRequest, UUID> {

    /** Organization-scoped read: cross-organization ids are invisible. */
    Optional<TransferRequest> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** Locked read for transition transactions. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query(
            "select t from TransferRequest t where t.id = :id")
    Optional<TransferRequest> findByIdForUpdate(UUID id);

    /** Locked, organization-scoped read for transition transactions. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query(
            "select t from TransferRequest t where t.id = :id and t.organizationId = :organizationId")
    Optional<TransferRequest> findByIdAndOrganizationIdForUpdate(UUID id, UUID organizationId);

    /** Active transfer work is attributed to the validated destination branch only. */
    @org.springframework.data.jpa.repository.Query("select t.destinationBranchId as branchId, count(t) as total "
            + "from TransferRequest t join Branch b on b.id = t.destinationBranchId "
            + "where t.organizationId = :organizationId "
            + "and b.id in :branchIds and b.active = true "
            + "and b.hospital.active = true and b.hospital.organization.id = :organizationId "
            + "and t.destinationHospitalId = b.hospital.id "
            + "and t.status in (com.mamtrex.hospital.transfer.TransferStatus.ACCEPTED, "
            + "com.mamtrex.hospital.transfer.TransferStatus.IN_TRANSIT) "
            + "group by t.destinationBranchId")
    List<BranchMetric> countActiveByDestinationBranch(UUID organizationId, java.util.Collection<UUID> branchIds);

    interface BranchMetric {
        UUID getBranchId();
        long getTotal();
    }

    /** Synthetic display id must be unique inside the organization. */
    boolean existsByOrganizationIdAndTransferNumber(UUID organizationId, String transferNumber);

    List<TransferRequest> findByOrganizationIdAndStatusOrderByRequestedAtDesc(
            UUID organizationId, TransferStatus status);

    @org.springframework.data.jpa.repository.Query("select t from TransferRequest t "
            + "where t.organizationId = :organizationId "
            + "and (t.sourceHospitalId = :hospitalId or t.destinationHospitalId = :hospitalId) "
            + "and (:status is null or t.status = :status) order by t.requestedAt desc")
    List<TransferRequest> findVisible(UUID organizationId, UUID hospitalId,
                                      TransferStatus status, Pageable pageable);
}
