package com.mamtrex.hospital.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Invoice repository (docs/plan2.md Task 4). The lookup query backs the
 * service's duplicate-invoice-number pre-check; the DB unique constraint on
 * the invoices table (declared on the entity) is the concurrency backstop,
 * so a race-lost duplicate surfaces as the Spring-translated integrity
 * violation the shared exception mapper turns into the generic safe 409.
 * Uniqueness is deliberately global in Phase 3, so this one pre-check stays
 * unscoped by design — every workflow read and command below resolves
 * branch-scoped instead.
 *
 * <p>The branch-scoped lookups (docs/plan3.md Task 8) are the only invoice
 * authority path for /api/invoices: a cross-branch id — and a legacy row
 * with null ownership — resolves to empty, indistinguishable from a
 * nonexistent row. Branch-scoped API paths must never fall back to
 * whole-table {@code findAll()}, unscoped {@code findById()}, or
 * {@code existsById()}.</p>
 */
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    /** Duplicate-invoice-number pre-check lookup (docs/plan2.md Task 4); uniqueness is global. */
    Optional<Invoice> findByInvoiceNumber(String invoiceNumber);

    /** Count of invoices currently in the given lifecycle status (Task 5 dashboard). */
    long countByStatus(String status);

    /** Every invoice owned by one branch; legacy null-ownership rows never match. */
    List<Invoice> findByBranchId(UUID branchId);

    /** Branch-scoped detail lookup: the same 404 for unknown, cross-branch, and legacy ids. */
    Optional<Invoice> findByIdAndBranchId(UUID id, UUID branchId);

    /** Branch-scoped existence check backing the delete command. */
    boolean existsByIdAndBranchId(UUID id, UUID branchId);

    /*
     * Task 10 dashboard aggregation (docs/plan3.md §4.7): one grouped
     * (branch, status) count restricted to an explicit branch-id set —
     * never a whole-table read. Totals and the four simulated invoice
     * buckets derive from the same tuples; the buckets remain a financial
     * simulation with no money-movement semantics.
     */
    @Query("select i.branchId as branchId, i.status as status, count(i) as total from Invoice i "
            + "where i.branchId in :branchIds group by i.branchId, i.status")
    List<BranchStatusCount> countByBranchIdInGroupedByStatus(@Param("branchIds") Collection<UUID> branchIds);

    /** One grouped (branch, status) count tuple of the query above. */
    interface BranchStatusCount {
        UUID getBranchId();
        String getStatus();
        long getTotal();
    }
}
