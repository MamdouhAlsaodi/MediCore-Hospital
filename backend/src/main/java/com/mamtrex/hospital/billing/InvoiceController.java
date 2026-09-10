package com.mamtrex.hospital.billing;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Narrow HTTP/DTO mapper over {@link InvoiceService} (docs/plan2.md Task 4
 * — a FINANCIAL SIMULATION ONLY: no payments, collection, charges, external
 * gateways, taxes, currency conversion, FX, real money, or financial
 * advice). Parses requests, delegates reference resolution, uniqueness, the
 * server-owned lifecycle, and audit recording to the service, and returns
 * {@link InvoiceDtos.InvoiceResponse} — never a JPA entity. It owns no
 * repositories and records no audit events; shared client-error mapping
 * (404/400/409) lives in
 * {@link com.mamtrex.hospital.shared.GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    private final InvoiceService service;

    public InvoiceController(InvoiceService service) {
        this.service = service;
    }

    @PostMapping
    public InvoiceDtos.InvoiceResponse create(@Valid @RequestBody InvoiceDtos.CreateInvoiceRequest r) {
        return service.create(r);
    }

    @GetMapping
    public List<InvoiceDtos.InvoiceResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public InvoiceDtos.InvoiceResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}/status")
    public InvoiceDtos.InvoiceResponse transition(@PathVariable UUID id,
                                                  @Valid @RequestBody InvoiceDtos.UpdateInvoiceStatusRequest r) {
        return service.transition(id, r);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
