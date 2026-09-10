import React, { useEffect, useState } from 'react';
import { ApiError } from '../../api.js';
import { can } from '../../authorization.js';
import { fetchPatients } from '../patients/patientApi.js';
import { createInvoice, fetchInvoices, transitionInvoice } from './invoiceApi.js';

// The whole screen is a FINANCIAL SIMULATION (docs/plan2.md Task 4): demo
// amounts and three-letter currency labels only — no payments, collection,
// charges, gateways, taxes, currency conversion, FX, real money, or
// financial advice anywhere in this feature.
const SIMULATION_HINT =
  'Financial simulation — no real payments. Amounts and currency labels are demo values with no conversion, FX, or tax meaning.';
const AMOUNT_HINT = 'A demo amount: up to 12 digits with up to 2 decimals, 0 or more — never real money.';
const CURRENCY_HINT = 'A 3-letter uppercase demo label (e.g. USD) with no conversion or FX.';

// The transitions each status legally admits (docs/plan2.md Task 4). This
// mirrors the server map for the UI hint only; the server still validates
// and refuses anything else.
function transitionsFor(invoiceStatus) {
  if (invoiceStatus === 'DRAFT') return ['ISSUED', 'VOID'];
  if (invoiceStatus === 'ISSUED') return ['PAID', 'VOID'];
  return [];
}

const ACTION_LABELS = { ISSUED: 'Issue', PAID: 'Mark paid', VOID: 'Void' };
const CONFIRM_LABELS = { ISSUED: 'Confirm issue', PAID: 'Confirm mark paid', VOID: 'Confirm void' };

function confirmationMessage(target) {
  if (target === 'VOID') return 'Invoice voided.';
  if (target === 'PAID') return 'Invoice marked paid.';
  return 'Invoice issued.';
}

// Create form (docs/plan2.md Task 4). Patients arrive as already-loaded
// domain records (the host screen's list); there is no free-typed reference
// anywhere. The submitted body mirrors CreateInvoiceRequest exactly —
// patientId, invoiceNumber, amount, currency — because the server owns the
// lifecycle: it sets status=DRAFT and applies every transition itself. The
// amount/currency checks mirror the server validation only as a convenience;
// the server stays authoritative. Server errors render inline with zero
// field loss; 401 is ownership of the shell.
export function InvoiceForm({ session, patients, onCreated, onCancel, onSessionExpired }) {
  const [patientId, setPatientId] = useState('');
  const [invoiceNumber, setInvoiceNumber] = useState('');
  const [amount, setAmount] = useState('');
  const [currency, setCurrency] = useState('');
  const [pending, setPending] = useState(false);
  const [validationError, setValidationError] = useState('');
  const [serverError, setServerError] = useState('');

  async function handleSubmit(event) {
    event.preventDefault();
    if (pending) return;
    setValidationError('');
    setServerError('');
    const patient = patients.find((record) => record.id === patientId);
    const trimmedNumber = invoiceNumber.trim();
    const normalizedCurrency = currency.trim().toUpperCase();
    const trimmedAmount = amount.trim();
    const validAmount = /^\d{1,12}(\.\d{1,2})?$/.test(trimmedAmount);
    if (!patient || !trimmedNumber || !validAmount || !/^[A-Z]{3}$/.test(normalizedCurrency)) {
      setValidationError(
        'Select a patient and provide the invoice number, a demo amount of up to 12 digits with up to 2 decimals, and a 3-letter uppercase demo currency label.'
      );
      return;
    }
    setPending(true);
    try {
      const created = await createInvoice({
        token: session.token,
        invoice: {
          patientId: patient.id,
          invoiceNumber: trimmedNumber,
          amount: trimmedAmount,
          currency: normalizedCurrency,
        },
        onUnauthorized: onSessionExpired,
      });
      onCreated(created);
    } catch (error) {
      // 401 is ownership of the shell: no local error on top of it.
      if (error instanceof ApiError && error.status === 401) return;
      setServerError(
        error instanceof ApiError ? error.message : 'The invoice could not be created.'
      );
    } finally {
      setPending(false);
    }
  }

  return (
    <form
      className="panel invoice-form"
      aria-label="Create an invoice"
      aria-busy={pending}
      onSubmit={handleSubmit}
    >
      <h3>Create an invoice</h3>
      <p className="panel-hint">
        Choose the patient from the loaded records, then add the invoice number, a demo
        amount, and a demo currency label. {SIMULATION_HINT} The server owns the invoice
        status and always starts it as DRAFT.
      </p>

      {validationError && (
        <p className="notice error" role="alert">{validationError}</p>
      )}
      {serverError && (
        <p className="notice error" role="alert">{serverError}</p>
      )}

      <div className="invoice-form-grid">
        <div className="invoice-field">
          <label htmlFor="invoice-patient">Patient</label>
          <select
            id="invoice-patient"
            value={patientId}
            onChange={(event) => setPatientId(event.target.value)}
          >
            <option value="">Select a patient</option>
            {patients.map((patient) => (
              <option key={patient.id} value={patient.id}>
                {patient.fullName} (MRN {patient.medicalRecordNumber})
              </option>
            ))}
          </select>
        </div>

        <div className="invoice-field">
          <label htmlFor="invoice-number">Invoice number</label>
          <input
            id="invoice-number"
            type="text"
            value={invoiceNumber}
            onChange={(event) => setInvoiceNumber(event.target.value)}
          />
        </div>

        <div className="invoice-field">
          <label htmlFor="invoice-amount">Amount (demo)</label>
          <input
            id="invoice-amount"
            type="text"
            inputMode="decimal"
            value={amount}
            onChange={(event) => setAmount(event.target.value)}
          />
          <span className="invoice-field-hint">{AMOUNT_HINT}</span>
        </div>

        <div className="invoice-field">
          <label htmlFor="invoice-currency">Currency (demo label)</label>
          <input
            id="invoice-currency"
            type="text"
            value={currency}
            onChange={(event) => setCurrency(event.target.value)}
          />
          <span className="invoice-field-hint">{CURRENCY_HINT}</span>
        </div>
      </div>

      <div className="invoice-form-actions">
        <button type="submit" className="invoice-save" disabled={pending}>
          {pending ? 'Creating…' : 'Create invoice'}
        </button>
        <button type="button" className="invoice-cancel" onClick={onCancel} disabled={pending}>
          Cancel
        </button>
      </div>
    </form>
  );
}

// Invoices screen (docs/plan2.md Task 4): the invoice list over
// GET /api/invoices, the create flow over POST /api/invoices, and the
// guarded transitions over PUT /api/invoices/{id}/status (DRAFT ->
// ISSUED | VOID and ISSUED -> PAID | VOID; PAID and VOID are terminal and
// offer no actions). The list resolves patient names against the loaded
// records and renders honest "Unknown record" placeholders for references
// it cannot resolve — raw reference values are never displayed. Every
// transition is a two-step confirmation: nothing is sent until its Confirm
// button is clicked. All transport goes through the feature adapter —
// components never call fetch directly.
export default function InvoicesPage({ session, onSessionExpired }) {
  const [patients, setPatients] = useState([]);
  const [invoices, setInvoices] = useState([]);
  const [status, setStatus] = useState('loading');
  const [loadError, setLoadError] = useState('');
  // 'list' | 'form'
  const [view, setView] = useState('list');
  const [confirmation, setConfirmation] = useState('');
  // Bumped after a successful mutation so the list refetches instead of
  // showing a result set that cannot contain the new state.
  const [listRefresh, setListRefresh] = useState(0);
  // The {id, target} awaiting its transition confirmation, or null.
  const [confirmPending, setConfirmPending] = useState(null);
  // The {id, target} whose transition request is in flight, or null.
  const [transitioning, setTransitioning] = useState(null);
  const [transitionError, setTransitionError] = useState('');

  useEffect(() => {
    let active = true;
    setStatus('loading');
    setLoadError('');
    const loadPatients = fetchPatients({
      token: session.token,
      onUnauthorized: onSessionExpired,
    });
    const loadInvoices = fetchInvoices({
      token: session.token,
      onUnauthorized: onSessionExpired,
    });
    Promise.all([loadPatients, loadInvoices])
      .then(([loadedPatients, loadedInvoices]) => {
        if (!active) return;
        setPatients(Array.isArray(loadedPatients) ? loadedPatients : []);
        setInvoices(Array.isArray(loadedInvoices) ? loadedInvoices : []);
        setStatus('ready');
      })
      .catch((error) => {
        if (!active) return;
        // 401 is ownership of the shell: the session-expiry callback returns
        // the app to Login, so no local error is raised on top of it.
        if (error instanceof ApiError && error.status === 401) return;
        setLoadError(error instanceof ApiError ? error.message : 'Invoices could not be loaded.');
        setStatus('ready');
      });
    return () => { active = false; };
  }, [session.token, listRefresh, onSessionExpired]);

  function openForm() {
    setConfirmation('');
    setTransitionError('');
    setView('form');
  }

  function handleFormCancelled() {
    // Cancellation preserves nothing half-saved and returns to the list.
    setConfirmation('');
    setView('list');
  }

  function handleCreated() {
    setConfirmation('Invoice created.');
    setView('list');
    // Server-side state cannot be honestly patched locally: refetch.
    setListRefresh((n) => n + 1);
  }

  function requestTransition(invoiceId, target) {
    setConfirmation('');
    setTransitionError('');
    setConfirmPending({ id: invoiceId, target });
  }

  function cancelTransition() {
    setConfirmPending(null);
  }

  async function confirmTransition(invoiceId, target) {
    if (transitioning) return;
    setTransitioning({ id: invoiceId, target });
    setTransitionError('');
    try {
      await transitionInvoice({
        token: session.token,
        id: invoiceId,
        status: target,
        onUnauthorized: onSessionExpired,
      });
      setConfirmPending(null);
      setConfirmation(confirmationMessage(target));
      // Server-side state cannot be honestly patched locally: refetch.
      setListRefresh((n) => n + 1);
    } catch (error) {
      setConfirmPending(null);
      // 401 is ownership of the shell: no local error on top of it.
      if (error instanceof ApiError && error.status === 401) return;
      setTransitionError(
        error instanceof ApiError ? error.message : 'The status change could not be recorded.'
      );
    } finally {
      setTransitioning(null);
    }
  }

  // UI convenience hints from the shared permission map; backend stays
  // authoritative for every request (the server refuses with 403 and the
  // page shows that state).
  const canCreate = can(session, 'create', 'invoice');
  const canTransition = can(session, 'transition', 'invoice');
  const patientNameById = new Map(patients.map((patient) => [patient.id, patient.fullName]));

  return (
    <section className="invoice-screen" aria-label="Invoices screen">
      {view === 'list' && (
        <>
          {status === 'loading' && (
            <p className="notice" role="status">Loading invoices…</p>
          )}

          {loadError && (
            <p className="notice error" role="alert">{loadError}</p>
          )}

          {status === 'ready' && !loadError && (
            <>
              {confirmation && (
                <p className="notice success" role="status">{confirmation}</p>
              )}

              {transitionError && (
                <p className="notice error" role="alert">{transitionError}</p>
              )}

              <p className="panel-hint invoice-boundary">{SIMULATION_HINT}</p>

              {canCreate && (
                <div className="invoice-actions">
                  <button type="button" className="invoice-new" onClick={openForm}>
                    Create invoice
                  </button>
                </div>
              )}

              {invoices.length === 0 ? (
                <div className="panel invoice-empty" role="status">
                  <h3>No invoices registered</h3>
                  <p className="panel-hint">
                    No invoices exist yet.{' '}
                    {canCreate
                      ? 'Use the create action above to record one for an existing patient.'
                      : 'Invoices appear here once they are created.'}
                  </p>
                </div>
              ) : (
                <div className="invoice-table-wrap">
                  <table className="invoice-table" aria-label="Registered invoices">
                    <thead>
                      <tr>
                        <th scope="col">Patient</th>
                        <th scope="col">Invoice number</th>
                        <th scope="col">Amount (demo)</th>
                        <th scope="col">Currency</th>
                        <th scope="col">Status</th>
                        <th scope="col">Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {invoices.map((invoice) => {
                        const targets = transitionsFor(invoice.status);
                        return (
                          <tr key={invoice.id}>
                            <td>{patientNameById.get(invoice.patientId) ?? 'Unknown record'}</td>
                            <td>{invoice.invoiceNumber}</td>
                            <td>{invoice.amount}</td>
                            <td>{invoice.currency}</td>
                            <td>
                              <span
                                className={`status-badge status-${String(invoice.status).toLowerCase()}`}
                                title="Server-owned invoice lifecycle state"
                              >
                                {invoice.status}
                              </span>
                            </td>
                            <td className="invoice-row-actions">
                              {canTransition
                                && targets.map((target) => {
                                  const pendingHere =
                                    confirmPending?.id === invoice.id && confirmPending.target === target;
                                  const busyHere =
                                    transitioning?.id === invoice.id && transitioning.target === target;
                                  return pendingHere ? (
                                    <React.Fragment key={target}>
                                      <button
                                        type="button"
                                        className="invoice-transition-confirm"
                                        disabled={Boolean(transitioning)}
                                        onClick={() => confirmTransition(invoice.id, target)}
                                      >
                                        {busyHere ? 'Recording…' : CONFIRM_LABELS[target]}
                                      </button>
                                      <button
                                        type="button"
                                        className="invoice-transition-cancel"
                                        disabled={Boolean(transitioning)}
                                        onClick={cancelTransition}
                                      >
                                        Cancel
                                      </button>
                                    </React.Fragment>
                                  ) : (
                                    <button
                                      key={target}
                                      type="button"
                                      className={`invoice-transition invoice-transition-${target.toLowerCase()}`}
                                      onClick={() => requestTransition(invoice.id, target)}
                                    >
                                      {ACTION_LABELS[target]}
                                    </button>
                                  );
                                })}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </>
          )}
        </>
      )}

      {view === 'form' && (
        <InvoiceForm
          session={session}
          patients={patients}
          onCreated={handleCreated}
          onCancel={handleFormCancelled}
          onSessionExpired={onSessionExpired}
        />
      )}
    </section>
  );
}
