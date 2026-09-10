package com.mamtrex.hospital.billing;

import com.mamtrex.hospital.shared.BaseEntity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Invoice aggregate (docs/plan2.md Task 4) — a FINANCIAL SIMULATION ONLY:
 * demo amounts and currency labels; no payments, collection, charges,
 * external gateways, taxes, currency conversion, FX, real money, or
 * financial advice of any kind.
 *
 * The legacy columns stay String-typed (no destructive column migration):
 * amount holds the service-validated canonical plain string, currency the
 * demo label, and status the server-owned lifecycle state DRAFT | ISSUED |
 * PAID | VOID. invoiceNumber carries the DB unique constraint that backs
 * the service pre-check under concurrency; the constraint name is fixed so
 * repeated schema generation stays stable. The only mutation the lifecycle
 * admits is {@link #changeStatus(String)} — every other field is immutable
 * after construction, and only the InvoiceService transition map decides
 * legal targets.
 */
@Entity
@Table(name = "invoices", uniqueConstraints = @UniqueConstraint(
        name = "uk_invoices_invoice_number", columnNames = "invoice_number"))
public class Invoice extends BaseEntity {

    private String patientId;
    private String invoiceNumber;
    private String amount;
    private String currency;
    private String status;

    protected Invoice() {}

    public Invoice(String patientId, String invoiceNumber, String amount, String currency, String status) {
        this.patientId = patientId;
        this.invoiceNumber = invoiceNumber;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
    }

    public String getPatientId() {
        return patientId;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public String getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    /**
     * Server-owned lifecycle mutation (docs/plan2.md Task 4): applies an
     * already-validated target state chosen by the InvoiceService transition
     * map. Package-private so no other layer can move the lifecycle.
     */
    void changeStatus(String newStatus) {
        this.status = newStatus;
    }
}
