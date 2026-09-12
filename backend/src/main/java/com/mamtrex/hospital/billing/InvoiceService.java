package com.mamtrex.hospital.billing;

import com.mamtrex.hospital.audit.AuditService;
import com.mamtrex.hospital.auth.ActingContext;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
import com.mamtrex.hospital.patient.Patient;
import com.mamtrex.hospital.patient.PatientRepository;
import com.mamtrex.hospital.shared.InvalidStateTransitionException;
import com.mamtrex.hospital.shared.NotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Invoice workflow rules (docs/plan2.md Task 4, branch scope added by
 * docs/plan3.md Task 8) — a FINANCIAL SIMULATION ONLY: demo amounts and
 * currency labels; no payments, collection, charges, external gateways,
 * taxes, currency conversion, FX, real money, or financial advice of any
 * kind.
 *
 * <p>Branch isolation (docs/plan3.md Task 8): creation derives ownership
 * from the acting context (never client input) and resolves the patient
 * reference scoped to that branch — a cross-branch or unknown patient
 * answers the same generic 404, so no existence information leaks. Every
 * invoice read and command resolves its row through the branch-scoped
 * repository methods, so a cross-branch id and a legacy null-ownership row
 * answer the same generic 404 as a nonexistent one, with no mutation and no
 * audit event. No branch-scoped path ever touches whole-table
 * {@code findAll()}, unscoped {@code findById()}, or {@code existsById()}.
 * Invoice-number uniqueness deliberately stays global in Phase 3 (the DB
 * constraint is table-wide), so the duplicate pre-check below stays
 * unscoped by design: reusing another branch's number is still the shared
 * conflict, mirroring the global-MRN precedent.</p>
 *
 * <p>Creation stores the validated amount as its canonical plain string for
 * the legacy String column (no column migration). Duplicate invoice numbers
 * are refused twice: a service pre-check throws the cause-free
 * duplicate-key conflict whose controlled message maps to the shared 409,
 * and the DB unique constraint on the invoices table is the concurrency
 * backstop — a race-lost violation arrives Spring-translated and the shared
 * mapper answers with the generic conflict message, so SQL or entity
 * internals never leak either way.</p>
 *
 * <p>The server owns the lifecycle through one explicit transition map:
 * DRAFT -> ISSUED | VOID and ISSUED -> PAID | VOID, with PAID and VOID
 * terminal. Illegal, repeated, backward, or unknown targets are the shared
 * 409 with a controlled client-safe message. The client never supplies a
 * status: the create DTO has no such field and the server sets DRAFT.
 * Create, each legal transition, and delete own exactly one audit event;
 * rejected writes record nothing. Queries are read-only.</p>
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
    private final BranchRepository branches;
    private final AuditService audit;

    public InvoiceService(InvoiceRepository invoices, PatientRepository patients,
                          BranchRepository branches, AuditService audit) {
        this.invoices = invoices;
        this.patients = patients;
        this.branches = branches;
        this.audit = audit;
    }

    public InvoiceDtos.InvoiceResponse create(InvoiceDtos.CreateInvoiceRequest r) {
        Branch acting = actingBranch();
        Patient patient = patients.findByIdAndBranchId(r.patientId(), acting.getId())
                .orElseThrow(() -> new NotFoundException("Patient not found: " + r.patientId()));
        String invoiceNumber = r.invoiceNumber().trim();
        invoices.findByInvoiceNumber(invoiceNumber).ifPresent(existing -> {
            throw new DuplicateKeyException("Invoice number already exists");
        });
        // Ownership is server-stamped from the acting branch; the create
        // request carries no branch field, so client input can never choose
        // it. Canonical plain-string store for the legacy amount column: no
        // exponent notation can reach storage or responses (no migration).
        Invoice saved = invoices.save(new Invoice(acting.getId(), patient.getId().toString(),
                invoiceNumber, r.amount().toPlainString(), r.currency(), STATUS_DRAFT));
        audit.record("CREATE", "Invoice", saved.getId().toString(), "created");
        return InvoiceDtos.InvoiceResponse.from(saved);
    }

    /** Branch-scoped list (docs/plan3.md Task 8): only the acting branch's own invoices. */
    @Transactional(readOnly = true)
    public List<InvoiceDtos.InvoiceResponse> list() {
        return invoices.findByBranchId(currentContext().branchId()).stream()
                .map(InvoiceDtos.InvoiceResponse::from).toList();
    }

    /** Branch-scoped detail (docs/plan3.md Task 8): the generic 404 for any other branch's row. */
    @Transactional(readOnly = true)
    public InvoiceDtos.InvoiceResponse get(UUID id) {
        return invoices.findByIdAndBranchId(id, currentContext().branchId())
                .map(InvoiceDtos.InvoiceResponse::from)
                .orElseThrow(() -> new NotFoundException("Invoice not found: " + id));
    }

    /**
     * Applies the single requested target through the explicit transition
     * map inside the acting branch; the invoice is resolved through the
     * branch-scoped lookup. Unknown invoices are the shared 404; illegal,
     * repeated, backward, and unknown targets are the shared 409 with a
     * controlled message that names the current state and never an entity
     * or persistence internal.
     */
    public InvoiceDtos.InvoiceResponse transition(UUID id, InvoiceDtos.UpdateInvoiceStatusRequest r) {
        Invoice invoice = invoices.findByIdAndBranchId(id, currentContext().branchId())
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
        audit.record("UPDATE", "Invoice", id.toString(), "status: " + target);
        return InvoiceDtos.InvoiceResponse.from(saved);
    }

    /** Branch-scoped delete (docs/plan3.md Task 8): 404-safe for any other branch's row. */
    public void delete(UUID id) {
        Invoice invoice = invoices.findByIdAndBranchId(id, currentContext().branchId())
                .orElseThrow(() -> new NotFoundException("Invoice not found: " + id));
        invoices.delete(invoice);
        audit.record("DELETE", "Invoice", id.toString(), "deleted");
    }

    private Branch actingBranch() {
        return branches.findById(currentContext().branchId())
                .orElseThrow(() -> new AccessDeniedException("The acting branch is not available"));
    }

    /**
     * Fail-closed seam: the JWT filter guarantees an {@link ActingContext}
     * principal with a verified assignment and an existing active selected
     * branch for every authorized request; anything else is refused, never
     * guessed.
     */
    private static ActingContext currentContext() {
        var a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof ActingContext c) {
            return c;
        }
        throw new AccessDeniedException("No acting context is available");
    }
}
