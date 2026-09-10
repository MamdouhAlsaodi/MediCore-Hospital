import { apiFetch } from '../../api.js';

// The only invoice transport adapter (docs/plan2.md Task 4). Bodies mirror
// InvoiceDtos exactly:
//   - list:       GET /api/invoices -> InvoiceResponse[]
//                 (id, patientId, invoiceNumber, amount, currency, status)
//   - create:     POST /api/invoices with CreateInvoiceRequest
//                 { patientId: UUID, invoiceNumber, amount, currency } —
//                 the server sets status=DRAFT and owns the lifecycle, so
//                 the adapter deliberately sends exactly the four contract
//                 fields and never a client status value.
//   - transition: PUT /api/invoices/{id}/status with { status } — legal
//                 only along DRAFT -> ISSUED | VOID and
//                 ISSUED -> PAID | VOID; PAID and VOID are terminal, and a
//                 repeat, backward move, or unknown target is refused with
//                 a safe 409.
// patientId is a typed UUID reference validated server-side; unknown
// references are rejected with 404 and malformed bodies with 400 by the
// shared GlobalExceptionHandler. This family is a FINANCIAL SIMULATION —
// demo amounts and currency labels only: no payments, collection, charges,
// gateways, taxes, currency conversion, FX, real money, or financial
// advice. All transport goes through the shared apiFetch (Bearer token,
// JSON, ApiError, onUnauthorized); components never call fetch directly.
export function fetchInvoices({ token, onUnauthorized } = {}) {
  return apiFetch('/api/invoices', { method: 'GET', token, onUnauthorized });
}

export function createInvoice({ token, invoice, onUnauthorized } = {}) {
  return apiFetch('/api/invoices', {
    method: 'POST',
    token,
    body: {
      patientId: invoice?.patientId,
      invoiceNumber: invoice?.invoiceNumber,
      amount: invoice?.amount,
      currency: invoice?.currency,
    },
    onUnauthorized,
  });
}

export function transitionInvoice({ token, id, status, onUnauthorized } = {}) {
  const safeId = encodeURIComponent(String(id ?? ''));
  return apiFetch(`/api/invoices/${safeId}/status`, {
    method: 'PUT',
    token,
    body: { status },
    onUnauthorized,
  });
}
