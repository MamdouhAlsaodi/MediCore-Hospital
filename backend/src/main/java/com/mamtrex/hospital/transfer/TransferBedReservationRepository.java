package com.mamtrex.hospital.transfer;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

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
}
