package com.mamtrex.hospital.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Invoice repository (docs/plan2.md Task 4). The lookup query backs the
 * service's duplicate-invoice-number pre-check; the DB unique constraint on
 * the invoices table (declared on the entity) is the concurrency backstop,
 * so a race-lost duplicate surfaces as the Spring-translated integrity
 * violation the shared exception mapper turns into the generic safe 409.
 */
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    /** Duplicate-invoice-number pre-check lookup (docs/plan2.md Task 4). */
    Optional<Invoice> findByInvoiceNumber(String invoiceNumber);

    /** Count of invoices currently in the given lifecycle status (Task 5 dashboard). */
    long countByStatus(String status);
}
