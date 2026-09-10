package com.mamtrex.hospital.billing;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Public invoice contract for /api/invoices (docs/plan2.md Task 4) — a
 * FINANCIAL SIMULATION ONLY: demo amounts and currency labels with no
 * payments, collection, charges, external gateways, taxes, currency
 * conversion, FX, real money, or financial advice of any kind.
 *
 * patientId is a typed UUID reference resolved by {@link InvoiceService}
 * against the patient repository; a malformed non-UUID body value fails
 * deserialization with 400 via the shared GlobalExceptionHandler malformed-
 * body mapping, and an unresolvable reference returns the shared 404.
 * amount is a typed non-negative BigDecimal validated to 12 integer digits
 * and 2 fraction digits, stored as its canonical plain string for the legacy
 * String column (no column migration), so exponent forms never reach storage
 * or responses. The create request deliberately carries NO status field:
 * the server owns the lifecycle and sets status=DRAFT, so any client status
 * value in the JSON body is ignored by this allowlist and never persisted.
 * The transition request carries the single requested target; anything the
 * lifecycle cannot accept is refused by the service with the shared 409.
 */
public final class InvoiceDtos {

    private InvoiceDtos() {}

    /**
     * Stable public invoice representation: exactly these six fields, no
     * persistence metadata. amount is the stored canonical plain string,
     * currency is a demo label stored verbatim, and status is the
     * server-owned lifecycle state DRAFT | ISSUED | PAID | VOID.
     */
    public record InvoiceResponse(UUID id, String patientId, String invoiceNumber,
                                  String amount, String currency, String status) {

        public static InvoiceResponse from(Invoice invoice) {
            return new InvoiceResponse(invoice.getId(), invoice.getPatientId(),
                    invoice.getInvoiceNumber(), invoice.getAmount(),
                    invoice.getCurrency(), invoice.getStatus());
        }
    }

    /**
     * Create request allowlist: patientId, invoiceNumber, amount, currency —
     * nothing else, and deliberately no status field (the server sets
     * DRAFT; unknown body members are ignored and never persisted).
     */
    public record CreateInvoiceRequest(@NotNull UUID patientId,
                                       @NotBlank String invoiceNumber,
                                       @NotNull @DecimalMin("0.00")
                                       @Digits(integer = 12, fraction = 2) BigDecimal amount,
                                       @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {}

    /** The only status request: the single requested lifecycle target. */
    public record UpdateInvoiceStatusRequest(@NotBlank String status) {}
}
