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
