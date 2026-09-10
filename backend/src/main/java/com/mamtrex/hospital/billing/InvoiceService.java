package com.mamtrex.hospital.billing;

import com.mamtrex.hospital.audit.AuditService;
import com.mamtrex.hospital.patient.Patient;
import com.mamtrex.hospital.patient.PatientRepository;
import com.mamtrex.hospital.shared.InvalidStateTransitionException;
import com.mamtrex.hospital.shared.NotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Invoice workflow rules (docs/plan2.md Task 4) — a FINANCIAL SIMULATION
 * ONLY: demo amounts and currency labels; no payments, collection, charges,
 * external gateways, taxes, currency conversion, FX, real money, or
 * financial advice of any kind.
 *
 * Creation resolves the patient reference through the patient repository
 * (unknown -> the shared 404) and stores the validated amount as its
 * canonical plain string for the legacy String column (no column
 * migration). Duplicate invoice numbers are refused twice: a service
 * pre-check throws the cause-free duplicate-key conflict whose controlled
 * message maps to the shared 409, and the DB unique constraint on the
 * invoices table is the concurrency backstop — a race-lost violation
 * arrives Spring-translated and the shared mapper answers with the generic
 * conflict message, so SQL or entity internals never leak either way.
 *
 * The server owns the lifecycle through one explicit transition map:
 * DRAFT -> ISSUED | VOID and ISSUED -> PAID | VOID, with PAID and VOID
 * terminal. Illegal, repeated, backward, or unknown targets are the shared
 * 409 with a controlled client-safe message. The client never supplies a
 * status: the create DTO has no such field and the server sets DRAFT.
 * Create, each legal transition, and delete own exactly one audit event;
 * rejected writes record nothing. Queries are read-only.
 */
@Service
@Transactional
public class InvoiceService {

    static final String STATUS_DRAFT = "DRAFT";
    static final String STATUS_ISSUED = "ISSUED";
    static final String STATUS_PAID = "PAID";
    static final String STATUS_VOID = "VOID";

    /**
     * The complete lifecycle (docs/plan2.md Task 4): keys are source states,
     * values are the legal targets; terminal states map to nothing. Kept
     * explicit here so the server, not the client or the UI hint, owns every
     * transition.
     */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            STATUS_DRAFT, Set.of(STATUS_ISSUED, STATUS_VOID),
            STATUS_ISSUED, Set.of(STATUS_PAID, STATUS_VOID),
            STATUS_PAID, Set.of(),
            STATUS_VOID, Set.of());

    private final InvoiceRepository invoices;
    private final PatientRepository patients;
    private final AuditService audit;

    public InvoiceService(InvoiceRepository invoices, PatientRepository patients, AuditService audit) {
        this.invoices = invoices;
        this.patients = patients;
        this.audit = audit;
    }

    public InvoiceDtos.InvoiceResponse create(InvoiceDtos.CreateInvoiceRequest r) {
        Patient patient = patients.findById(r.patientId())
                .orElseThrow(() -> new NotFoundException("Patient not found: " + r.patientId()));
        String invoiceNumber = r.invoiceNumber().trim();
        invoices.findByInvoiceNumber(invoiceNumber).ifPresent(existing -> {
            throw new DuplicateKeyException("Invoice number already exists");
        });
        // Canonical plain-string store for the legacy amount column: no
        // exponent notation can reach storage or responses (no migration).
        Invoice saved = invoices.save(new Invoice(patient.getId().toString(),
                invoiceNumber, r.amount().toPlainString(), r.currency(), STATUS_DRAFT));
        audit.record("CREATE", "Invoice", saved.getId().toString(), "created");
        return InvoiceDtos.InvoiceResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<InvoiceDtos.InvoiceResponse> list() {
        return invoices.findAll().stream().map(InvoiceDtos.InvoiceResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public InvoiceDtos.InvoiceResponse get(UUID id) {
        return invoices.findById(id).map(InvoiceDtos.InvoiceResponse::from)
                .orElseThrow(() -> new NotFoundException("Invoice not found: " + id));
    }

    /**
     * Applies the single requested target through the explicit transition
     * map. Unknown invoices are the shared 404; illegal, repeated, backward,
     * and unknown targets are the shared 409 with a controlled message that
     * names the current state and never an entity or persistence internal.
     */
    public InvoiceDtos.InvoiceResponse transition(UUID id, InvoiceDtos.UpdateInvoiceStatusRequest r) {
        Invoice invoice = invoices.findById(id)
                .orElseThrow(() -> new NotFoundException("Invoice not found: " + id));
        String current = invoice.getStatus();
        Set<String> targets = TRANSITIONS.getOrDefault(current, Set.of());
        String target = r.status() == null ? "" : r.status().trim();
        if (!targets.contains(target)) {
            throw new InvalidStateTransitionException(targets.isEmpty()
                    ? "Invoice " + id + " is " + current + " and terminal: no further transition exists"
                    : "Invoice " + id + " has no "
                            + (target.isEmpty() ? "blank" : target)
                            + " transition from " + current + ": legal targets are " + targets);
        }
        invoice.changeStatus(target);
        Invoice saved = invoices.save(invoice);
        audit.record("UPDATE", "Invoice", id.toString(), transitionDetails(target));
        return InvoiceDtos.InvoiceResponse.from(saved);
    }

    public void delete(UUID id) {
        if (!invoices.existsById(id)) {
            throw new NotFoundException("Invoice not found: " + id);
        }
        invoices.deleteById(id);
        audit.record("DELETE", "Invoice", id.toString(), "deleted");
    }

    /** Stable audit label per lifecycle target (CREATE stays "created"). */
    private static String transitionDetails(String target) {
        return switch (target) {
            case STATUS_ISSUED -> "issued";
            case STATUS_PAID -> "paid";
            case STATUS_VOID -> "voided";
            default -> "transitioned to " + target;
        };
    }
}
