package com.mamtrex.hospital.transfer;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for bed reservations (Phase 5 T085). The database partial
 * unique index (only one ACTIVE reservation per bed; V7, downstream) is the
 * race backstop; the locking active-bed query is how the accept transaction
 * serializes against a concurrent accept of the same bed.
 */
public interface TransferBedReservationRepository extends JpaRepository<TransferBedReservation, UUID> {

    /** One reservation per transfer (data-model constraint). */
    Optional<TransferBedReservation> findByTransferId(UUID transferId);

    /** Locked read of a transfer's reservation for transition transactions. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from TransferBedReservation r where r.transferId = :transferId")
    Optional<TransferBedReservation> findByTransferIdForUpdate(UUID transferId);

    /** Locked read of the ACTIVE reservation (if any) of one bed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from TransferBedReservation r where r.bedId = :bedId and r.status = com.mamtrex.hospital.transfer.ReservationStatus.ACTIVE")
    Optional<TransferBedReservation> findActiveByBedIdForUpdate(UUID bedId);

    /** Only live reservations whose bed and transfer destination have the same active ancestry. */
    @Query("select b.branch.id as branchId, count(r) as total from TransferBedReservation r "
            + "join Bed b on b.id = r.bedId join TransferRequest t on t.id = r.transferId "
            + "where b.branch.id in :branchIds and b.branch.active = true "
            + "and b.branch.hospital.active = true and b.branch.hospital.organization.id = :organizationId "
            + "and t.organizationId = :organizationId and t.destinationHospitalId = b.branch.hospital.id "
            + "and t.destinationBranchId = b.branch.id and t.destinationBedId = b.id "
            + "and r.status = com.mamtrex.hospital.transfer.ReservationStatus.ACTIVE "
            + "and t.status in (com.mamtrex.hospital.transfer.TransferStatus.ACCEPTED, "
            + "com.mamtrex.hospital.transfer.TransferStatus.IN_TRANSIT) "
            + "group by b.branch.id")
    List<BranchMetric> countActiveCapacityByBranch(@Param("organizationId") UUID organizationId,
            @Param("branchIds") Collection<UUID> branchIds);

    interface BranchMetric {
        UUID getBranchId();
        long getTotal();
    }
}
