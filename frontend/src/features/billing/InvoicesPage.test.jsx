import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import InvoicesPage, { invoiceDisplayState } from './InvoicesPage.jsx';

const PATIENT_A = {
  id: '11111111-1111-4111-8111-111111111111',
  medicalRecordNumber: 'MRN-1001',
  fullName: 'Amal Hassan',
  dateOfBirth: '1990-05-14',
  sex: 'female',
  phone: '',
  email: '',
  nationalId: '',
  address: '',
  active: true,
};

const PATIENT_B = {
  id: '22222222-2222-4222-8222-222222222222',
  medicalRecordNumber: 'MRN-1002',
  fullName: 'Omar Diab',
  dateOfBirth: '1985-11-02',
  sex: 'male',
  phone: '',
  email: '',
  nationalId: '',
  address: '',
  active: false,
};

const PATIENTS = [PATIENT_A, PATIENT_B];

// docs/plan2.md Task 4 DTO contract: id/patientId/invoiceNumber/amount/
// currency/status — no persistence metadata anywhere. The whole family is a
// FINANCIAL SIMULATION: demo amounts and currency labels, no real payments.
const INVOICE_DRAFT = {
  id: '99999999-9999-4999-8999-999999999911',
  patientId: PATIENT_A.id,
  invoiceNumber: 'INV-1001',
  amount: '150.00',
  currency: 'USD',
  status: 'DRAFT',
};

const INVOICE_ISSUED = {
  id: '99999999-9999-4999-8999-999999999912',
  patientId: PATIENT_B.id,
  invoiceNumber: 'INV-1002',
  amount: '42.5',
  currency: 'EUR',
  status: 'ISSUED',
};

const INVOICE_PAID = {
  id: '99999999-9999-4999-8999-999999999913',
  patientId: PATIENT_A.id,
  invoiceNumber: 'INV-1003',
  amount: '999999999999.99',
  currency: 'USD',
  status: 'PAID',
};

const INVOICE_VOID = {
  id: '99999999-9999-4999-8999-999999999914',
  patientId: PATIENT_B.id,
  invoiceNumber: 'INV-1005',
  amount: '0',
  currency: 'USD',
  status: 'VOID',
};

// A list row whose patient reference cannot be resolved against the loaded
// records. The list must render an honest placeholder, never the raw value.
const INVOICE_UNKNOWN_REF = {
  id: '99999999-9999-4999-8999-999999999915',
  patientId: 'raw unresolved reference value',
  invoiceNumber: 'INV-1004',
  amount: '20',
  currency: 'USD',
  status: 'DRAFT',
};

const INVOICES_INITIAL = [INVOICE_DRAFT, INVOICE_ISSUED, INVOICE_PAID, INVOICE_VOID, INVOICE_UNKNOWN_REF];

const NEW_INVOICE = {
  id: '99999999-9999-4999-8999-999999999916',
  patientId: PATIENT_A.id,
  invoiceNumber: 'INV-2026-0099',
  amount: '250.5',
  currency: 'USD',
  status: 'DRAFT',
};

const INVOICES_AFTER_CREATE = [...INVOICES_INITIAL, NEW_INVOICE];

// Task 8 branch fixtures: two acting contexts bound to different branches.
// The branch-B invoice carries its own unique number so a stale branch-A
// row can never be confused with branch-B content.
const INVOICE_BRANCH_B = {
  id: '99999999-9999-4999-8999-999999999921',
  patientId: PATIENT_B.id,
  invoiceNumber: 'INV-WEST-9001',
  amount: '60',
  currency: 'USD',
  status: 'DRAFT',
};

function actingSessionFor(token, assignmentId, branchId) {
  return {
    token,
    username: 'testuser',
    roles: ['BILLING'],
    assignments: [{
      id: assignmentId,
      role: 'BILLING',
      scope: 'ORGANIZATION',
      organizationId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      organizationLabel: 'Main Hospital Group',
      branchId,
      branchLabel: branchId === 'branch-b' ? 'West Clinic' : 'East Clinic',
      departmentId: null,
      departmentLabel: null,
      enabled: true,
    }],
    actingContext: {
      username: 'testuser',
      assignmentId,
      role: 'BILLING',
      scope: 'ORGANIZATION',
      organizationId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      branchId,
      departmentId: null,
    },
  };
}

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function sessionFor(roles) {
  return { token: 'synthetic-token', username: 'testuser', roles };
}

function renderInvoices(roles = ['BILLING']) {
  const onSessionExpired = vi.fn();
  const view = render(
    <InvoicesPage session={sessionFor(roles)} onSessionExpired={onSessionExpired} />
  );
  return { onSessionExpired, ...view };
}

// Router over the consumed endpoint families. `state` is read live so tests
// can switch payloads and outcomes between calls, and the transition route
// rewrites the stored list the way a real server would.
function stubBackend(fetchMock, state) {
  fetchMock.mockImplementation((path, options = {}) => {
    const method = options.method ?? 'GET';
    if (path === '/api/patients') {
      return Promise.resolve(jsonResponse(PATIENTS, state.patientsStatus ?? 200));
    }
    if (path === '/api/invoices') {
      if (method === 'POST') {
        state.postCalls.push(JSON.parse(options.body));
        if (state.postResponse) return Promise.resolve(state.postResponse);
        return Promise.resolve(jsonResponse(NEW_INVOICE, state.postStatus ?? 200));
      }
      return Promise.resolve(jsonResponse(state.invoicesList, state.invoicesStatus ?? 200));
    }
    if (/^\/api\/invoices\/[^/]+\/status$/.test(path)) {
      state.putCalls.push({ path, body: JSON.parse(options.body) });
      if (state.putResponse) return Promise.resolve(state.putResponse);
      const target = JSON.parse(options.body).status;
      const id = path.split('/')[3];
      state.invoicesList = state.invoicesList.map((invoice) =>
        invoice.id === id ? { ...invoice, status: target } : invoice
      );
      return Promise.resolve(jsonResponse({ ...INVOICE_DRAFT, id, status: target }));
    }
    return Promise.resolve(jsonResponse({}, 404));
  });
}

// Records the text content of every committed render pass, in commit order,
// into `frames`. The inline ref is invoked by React during each commit phase
// — before passive effects run — so whatever the screen painted for a given
// commit is captured exactly as the browser would have received it. This is
// what makes the render-phase boundary observable in integration without
// touching React internals.
function CommitLog({ frames, children }) {
  return (
    <div ref={(node) => { if (node) frames.push(node.textContent); }}>
      {children}
    </div>
  );
}

function invoicesTable() {
  return screen.queryByRole('table', { name: 'Registered invoices' });
}

describe('InvoicesPage', () => {
  let fetchMock;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  it('loads the invoices list with resolved names, status badges, and honest placeholders', async () => {
    const state = { postCalls: [], putCalls: [], invoicesList: INVOICES_INITIAL };
    stubBackend(fetchMock, state);
    const { onSessionExpired } = renderInvoices(['BILLING']);

    const table = await screen.findByRole('table', { name: 'Registered invoices' });
    const draftRow = within(table).getByRole('row', { name: /INV-1001/ });
    expect(within(draftRow).getByText('Amal Hassan')).toBeInTheDocument();
    expect(within(draftRow).getByText('DRAFT')).toBeInTheDocument();
    expect(within(draftRow).getByText('150.00')).toBeInTheDocument();
    expect(within(draftRow).getByText('USD')).toBeInTheDocument();

    const issuedRow = within(table).getByRole('row', { name: /INV-1002/ });
    expect(within(issuedRow).getByText('Omar Diab')).toBeInTheDocument();
    expect(within(issuedRow).getByText('ISSUED')).toBeInTheDocument();

    const paidRow = within(table).getByRole('row', { name: /INV-1003/ });
    expect(within(paidRow).getByText('PAID')).toBeInTheDocument();

    const unknownRow = within(table).getByRole('row', { name: /INV-1004/ });
    expect(within(unknownRow).getByText('Unknown record')).toBeInTheDocument();
    expect(table).not.toHaveTextContent('raw unresolved reference value');

    // The financial-simulation boundary is demonstrably stated on the screen.
    const screenRegion = screen.getByRole('region', { name: 'Invoices screen' });
    expect(screenRegion).toHaveTextContent(/financial simulation/i);
    expect(screenRegion).toHaveTextContent(/no real payments/i);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('shows the empty state when no invoices exist', async () => {
    const state = { postCalls: [], putCalls: [], invoicesList: [] };
    stubBackend(fetchMock, state);
    renderInvoices(['BILLING']);

    const empty = await screen.findByText('No invoices registered');
    expect(empty).toBeInTheDocument();
    expect(invoicesTable()).not.toBeInTheDocument();
  });

  it('shows an honest error when the invoices list fails to load', async () => {
    const state = { postCalls: [], putCalls: [], invoicesList: [], invoicesStatus: 500 };
    stubBackend(fetchMock, state);
    renderInvoices(['BILLING']);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/500/);
    expect(invoicesTable()).not.toBeInTheDocument();
  });

  it('shows the permission message when the invoices list is refused (403)', async () => {
    const state = { postCalls: [], putCalls: [], invoicesList: [], invoicesStatus: 403 };
    stubBackend(fetchMock, state);
    renderInvoices(['BILLING']);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('You do not have permission to view this data.');
    expect(invoicesTable()).not.toBeInTheDocument();
  });

  it('creates an invoice from the selected records on the typed contract and refreshes the list', async () => {
    const user = userEvent.setup();
    let resolvePost;
    const state = { postCalls: [], putCalls: [], invoicesList: INVOICES_INITIAL };
    stubBackend(fetchMock, state);
    fetchMock.mockImplementation((path, options = {}) => {
      const method = options.method ?? 'GET';
      if (path === '/api/invoices' && method === 'POST') {
        state.postCalls.push(JSON.parse(options.body));
        return new Promise((resolve) => { resolvePost = resolve; });
      }
      if (path === '/api/patients') return Promise.resolve(jsonResponse(PATIENTS));
      if (path === '/api/invoices') return Promise.resolve(jsonResponse(state.invoicesList));
      return Promise.resolve(jsonResponse({}, 404));
    });

    const { onSessionExpired } = renderInvoices(['BILLING']);
    await screen.findByRole('table', { name: 'Registered invoices' });
    await user.click(screen.getByRole('button', { name: 'Create invoice' }));

    const patientSelect = await screen.findByLabelText('Patient');
    expect(within(patientSelect).getAllByRole('option')).toHaveLength(3); // placeholder + two records
    await user.selectOptions(patientSelect, PATIENT_A.id);
    await user.type(screen.getByLabelText('Invoice number'), 'INV-2026-0099');
    await user.type(screen.getByLabelText('Amount (demo)'), '250.5');
    await user.type(screen.getByLabelText('Currency (demo label)'), 'usd');
    await user.click(screen.getByRole('button', { name: 'Create invoice' }));

    // While the create request is in flight the submit is disabled.
    const pendingButton = await screen.findByRole('button', { name: 'Creating…' });
    expect(pendingButton).toBeDisabled();

    state.invoicesList = INVOICES_AFTER_CREATE;
    resolvePost(jsonResponse(NEW_INVOICE));

    // List refresh: the new invoice appears through a fresh GET.
    const table = await screen.findByRole('table', { name: 'Registered invoices' });
    await waitFor(() => {
      expect(within(table).getAllByRole('row', { name: /INV-2026-0099/ })).toHaveLength(1);
    });
    expect(screen.getByRole('status')).toHaveTextContent(/invoice created/i);

    expect(state.postCalls).toHaveLength(1);
    // The body mirrors CreateInvoiceRequest exactly — four fields, and no
    // client status anywhere (the server owns the lifecycle).
    expect(Object.keys(state.postCalls[0]).sort())
      .toEqual(['amount', 'currency', 'invoiceNumber', 'patientId']);
    expect(state.postCalls[0]).toEqual({
      patientId: PATIENT_A.id,
      invoiceNumber: 'INV-2026-0099',
      amount: '250.5',
      currency: 'USD',
    });
    const postCall = fetchMock.mock.calls.find(
      ([path, options]) => path === '/api/invoices' && options?.method === 'POST'
    );
    expect(postCall[1].headers.Authorization).toBe('Bearer synthetic-token');
    expect(postCall[1].headers['Content-Type']).toBe('application/json');
    const invoiceGetCalls = fetchMock.mock.calls.filter(
      ([path, options]) => path === '/api/invoices' && (options?.method ?? 'GET') === 'GET'
    );
    expect(invoiceGetCalls).toHaveLength(2);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('labels the demo amount and currency fields demonstrably in the form', async () => {
    const user = userEvent.setup();
    const state = { postCalls: [], putCalls: [], invoicesList: INVOICES_INITIAL };
    stubBackend(fetchMock, state);
    renderInvoices(['BILLING']);
    await screen.findByRole('table', { name: 'Registered invoices' });

    await user.click(screen.getByRole('button', { name: 'Create invoice' }));

    expect(await screen.findByLabelText('Amount (demo)')).toBeInTheDocument();
    expect(screen.getByLabelText('Currency (demo label)')).toBeInTheDocument();
    const form = screen.getByRole('region', { name: 'Invoices screen' });
    expect(form).toHaveTextContent(/financial simulation/i);
    expect(form).toHaveTextContent(/no real payments/i);
  });

  it('validates before submitting: an incomplete or invalid form shows an inline error and never posts', async () => {
    const user = userEvent.setup();
    const state = { postCalls: [], putCalls: [], invoicesList: INVOICES_INITIAL };
    stubBackend(fetchMock, state);
    renderInvoices(['BILLING']);
    await screen.findByRole('table', { name: 'Registered invoices' });
    await user.click(screen.getByRole('button', { name: 'Create invoice' }));

    await screen.findByLabelText('Patient');
    await user.click(screen.getByRole('button', { name: 'Create invoice' }));

    let alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/select a patient/i);
    expect(state.postCalls).toHaveLength(0);

    await user.selectOptions(screen.getByLabelText('Patient'), PATIENT_A.id);
    await user.type(screen.getByLabelText('Invoice number'), 'INV-BAD-AMOUNT');
    await user.type(screen.getByLabelText('Amount (demo)'), '-5');
    await user.type(screen.getByLabelText('Currency (demo label)'), 'USD');
    await user.click(screen.getByRole('button', { name: 'Create invoice' }));

    alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/demo amount/i);
    expect(state.postCalls).toHaveLength(0);

    await user.clear(screen.getByLabelText('Amount (demo)'));
    await user.type(screen.getByLabelText('Amount (demo)'), '12.345');
    await user.click(screen.getByRole('button', { name: 'Create invoice' }));

    alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/demo amount/i);
    expect(state.postCalls).toHaveLength(0);
  });

  it('keeps every field value when the server refuses the create, and shows the server error', async () => {
    const user = userEvent.setup();
    const state = {
      postCalls: [],
      putCalls: [],
      invoicesList: INVOICES_INITIAL,
      postResponse: jsonResponse({ timestamp: '', status: 409, error: 'Conflict', message: 'Invoice number already exists', path: '/api/invoices' }, 409),
    };
    stubBackend(fetchMock, state);
    renderInvoices(['BILLING']);
    await screen.findByRole('table', { name: 'Registered invoices' });
    await user.click(screen.getByRole('button', { name: 'Create invoice' }));

    await screen.findByLabelText('Patient');
    await user.selectOptions(screen.getByLabelText('Patient'), PATIENT_B.id);
    await user.type(screen.getByLabelText('Invoice number'), 'INV-DUP');
    await user.type(screen.getByLabelText('Amount (demo)'), '10.00');
    await user.type(screen.getByLabelText('Currency (demo label)'), 'USD');
    await user.click(screen.getByRole('button', { name: 'Create invoice' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The request failed (409). Please try again.');
    // Zero field loss after the failed create: every entered value survives.
    expect(screen.getByLabelText('Patient')).toHaveValue(PATIENT_B.id);
    expect(screen.getByLabelText('Invoice number')).toHaveValue('INV-DUP');
    expect(screen.getByLabelText('Amount (demo)')).toHaveValue('10.00');
    expect(screen.getByLabelText('Currency (demo label)')).toHaveValue('USD');
    expect(state.postCalls).toHaveLength(1);
  });

  it('transitions an invoice through a two-step confirmation and refreshes the list', async () => {
    const user = userEvent.setup();
    const state = { postCalls: [], putCalls: [], invoicesList: INVOICES_INITIAL };
    stubBackend(fetchMock, state);
    const { onSessionExpired } = renderInvoices(['BILLING']);

    const table = await screen.findByRole('table', { name: 'Registered invoices' });
    const draftRow = within(table).getByRole('row', { name: /INV-1001/ });

    // Nothing is sent until the confirmation button is clicked.
    await user.click(within(draftRow).getByRole('button', { name: 'Issue' }));
    expect(state.putCalls).toHaveLength(0);
    await user.click(within(draftRow).getByRole('button', { name: 'Confirm issue' }));

    await waitFor(() => expect(state.putCalls).toHaveLength(1));
    expect(state.putCalls[0].body).toEqual({ status: 'ISSUED' });
    expect(state.putCalls[0].path).toBe(`/api/invoices/${INVOICE_DRAFT.id}/status`);

    // The row reflects the server-applied state through the fresh GET.
    await waitFor(() => {
      expect(within(screen.getByRole('table', { name: 'Registered invoices' }))
        .getByRole('row', { name: /INV-1001/ })).toHaveTextContent('ISSUED');
    });
    expect(screen.getByRole('status')).toHaveTextContent(/invoice issued/i);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('offers no transition actions on terminal PAID and VOID invoices', async () => {
    const user = userEvent.setup();
    const state = { postCalls: [], putCalls: [], invoicesList: INVOICES_INITIAL };
    stubBackend(fetchMock, state);
    renderInvoices(['BILLING']);

    const table = await screen.findByRole('table', { name: 'Registered invoices' });
    const paidRow = within(table).getByRole('row', { name: /INV-1003/ });
    expect(within(paidRow).queryByRole('button')).not.toBeInTheDocument();
    const voidRow = within(table).getByRole('row', { name: /INV-1005/ });
    expect(within(voidRow).queryByRole('button')).not.toBeInTheDocument();
  });

  it('reloads the branch-scoped list under the new context-bound token after a context switch and never renders the previous branch\'s rows', async () => {
    let resolveBranchBInvoices;
    fetchMock.mockImplementation((path, options = {}) => {
      const token = options.headers?.Authorization;
      if (path === '/api/patients') return Promise.resolve(jsonResponse(PATIENTS));
      if (path === '/api/invoices' && (options.method ?? 'GET') === 'GET') {
        if (token === 'Bearer branch-b-token') {
          return new Promise((resolve) => { resolveBranchBInvoices = resolve; });
        }
        return Promise.resolve(jsonResponse([INVOICE_DRAFT]));
      }
      return Promise.resolve(jsonResponse({}, 404));
    });
    const onSessionExpired = vi.fn();
    const sessionA = actingSessionFor('branch-a-token', 'assign-a', 'branch-a');
    const sessionB = actingSessionFor('branch-b-token', 'assign-b', 'branch-b');

    const view = render(<InvoicesPage session={sessionA} onSessionExpired={onSessionExpired} />);
    const tableA = await screen.findByRole('table', { name: 'Registered invoices' });
    expect(within(tableA).getByText('INV-1001')).toBeInTheDocument();

    // The shell swapped in the complete switched session (new context-bound
    // token, new branch). The screen reloads for the acting context and
    // drops the previous branch's rows: while the new request is pending no
    // stale branch-A row may remain, and after it resolves only branch-B
    // content is shown.
    view.rerender(<InvoicesPage session={sessionB} onSessionExpired={onSessionExpired} />);

    expect(screen.queryByRole('table', { name: 'Registered invoices' })).not.toBeInTheDocument();
    expect(screen.queryByText('INV-1001')).not.toBeInTheDocument();

    resolveBranchBInvoices(jsonResponse([INVOICE_BRANCH_B]));
    const tableB = await screen.findByRole('table', { name: 'Registered invoices' });
    expect(within(tableB).getByText('INV-WEST-9001')).toBeInTheDocument();
    expect(tableB).not.toHaveTextContent('INV-1001');

    const invoiceGets = fetchMock.mock.calls.filter(
      ([path, options]) => path === '/api/invoices' && (options?.method ?? 'GET') === 'GET'
    );
    expect(invoiceGets).toHaveLength(2);
    expect(invoiceGets[0][1].headers.Authorization).toBe('Bearer branch-a-token');
    expect(invoiceGets[1][1].headers.Authorization).toBe('Bearer branch-b-token');
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('hands a 401 to the shell during create and renders no local error on top of it', async () => {
    const user = userEvent.setup();
    const state = {
      postCalls: [],
      putCalls: [],
      invoicesList: INVOICES_INITIAL,
      postStatus: 401,
    };
    stubBackend(fetchMock, state);
    const { onSessionExpired } = renderInvoices(['BILLING']);
    await screen.findByRole('table', { name: 'Registered invoices' });
    await user.click(screen.getByRole('button', { name: 'Create invoice' }));

    await screen.findByLabelText('Patient');
    await user.selectOptions(screen.getByLabelText('Patient'), PATIENT_A.id);
    await user.type(screen.getByLabelText('Invoice number'), 'INV-401');
    await user.type(screen.getByLabelText('Amount (demo)'), '5');
    await user.type(screen.getByLabelText('Currency (demo label)'), 'USD');
    await user.click(screen.getByRole('button', { name: 'Create invoice' }));

    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(1));
    expect(state.postCalls).toHaveLength(1);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  // ---- Task 8 review repair (MEDICORE-PLAN3-TASK8-REVIEW-REPAIR-065) ----
  // Render-phase contract. Loaded branch-owned data is tagged with the
  // actingContextKey it resolved under, and invoiceDisplayState — the pure
  // selector the page's render calls on every pass — exposes it only while
  // that tag equals the current contextKey. The selector pins are
  // deterministic by construction (they prove the decision made during
  // render, independent of effect timing); the commit-log integration test
  // below proves the same boundary on actually committed DOM frames.
  describe('invoiceDisplayState render contract', () => {
    const KEY_A = 'assign-a:branch-a';
    const KEY_B = 'assign-b:branch-b';
    const loadedUnderA = {
      contextKey: KEY_A,
      status: 'ready',
      loadError: '',
      patients: [PATIENT_A],
      invoices: [INVOICE_DRAFT],
    };

    it('exposes data loaded under context A while A is the current context', () => {
      const display = invoiceDisplayState({ contextKey: KEY_A, loaded: loadedUnderA });
      expect(display).toBe(loadedUnderA);
      expect(display.status).toBe('ready');
      expect(display.patients).toEqual([PATIENT_A]);
      expect(display.invoices).toEqual([INVOICE_DRAFT]);
    });

    it('stops exposing that same data the moment the current context becomes B', () => {
      const display = invoiceDisplayState({ contextKey: KEY_B, loaded: loadedUnderA });
      expect(display).not.toBe(loadedUnderA);
      expect(display.patients).toEqual([]);
      expect(display.invoices).toEqual([]);
    });

    it('shows the loading state for B — never the old rows and never a misleading empty state', () => {
      const display = invoiceDisplayState({ contextKey: KEY_B, loaded: loadedUnderA });
      // status 'loading' is what keeps the empty-state panel unrenderable:
      // that branch requires status 'ready'.
      expect(display.status).toBe('loading');
      expect(display.loadError).toBe('');
    });

    it('exposes only B rows once B has resolved under tag B', () => {
      const loadedUnderB = {
        contextKey: KEY_B,
        status: 'ready',
        loadError: '',
        patients: [PATIENT_B],
        invoices: [INVOICE_BRANCH_B],
      };
      const display = invoiceDisplayState({ contextKey: KEY_B, loaded: loadedUnderB });
      expect(display.status).toBe('ready');
      expect(display.patients).toEqual([PATIENT_B]);
      expect(display.invoices).toEqual([INVOICE_BRANCH_B]);
    });

    it('refuses a late A-tagged publication while B is current', () => {
      const lateA = {
        contextKey: KEY_A,
        status: 'ready',
        loadError: '',
        patients: [PATIENT_A],
        invoices: [INVOICE_DRAFT],
      };
      const display = invoiceDisplayState({ contextKey: KEY_B, loaded: lateA });
      expect(display.invoices).toEqual([]);
      expect(display.patients).toEqual([]);
      expect(display.status).toBe('loading');
    });

    it('does not carry a load error across a context boundary', () => {
      const failedA = {
        contextKey: KEY_A,
        status: 'ready',
        loadError: 'Invoices could not be loaded.',
        patients: [],
        invoices: [],
      };
      const display = invoiceDisplayState({ contextKey: KEY_B, loaded: failedA });
      expect(display.loadError).toBe('');
      expect(display.status).toBe('loading');
    });
  });

  it('never commits the previous branch\'s rows to the DOM while the new context is pending', async () => {
    let resolveBranchBInvoices;
    fetchMock.mockImplementation((path, options = {}) => {
      const token = options.headers?.Authorization;
      if (path === '/api/patients') return Promise.resolve(jsonResponse(PATIENTS));
      if (path === '/api/invoices' && (options.method ?? 'GET') === 'GET') {
        if (token === 'Bearer branch-b-token') {
          return new Promise((resolve) => { resolveBranchBInvoices = resolve; });
        }
        return Promise.resolve(jsonResponse([INVOICE_DRAFT]));
      }
      return Promise.resolve(jsonResponse({}, 404));
    });
    const onSessionExpired = vi.fn();
    const sessionA = actingSessionFor('branch-a-token', 'assign-a', 'branch-a');
    const sessionB = actingSessionFor('branch-b-token', 'assign-b', 'branch-b');
    const frames = [];

    const view = render(
      <CommitLog frames={frames}>
        <InvoicesPage session={sessionA} onSessionExpired={onSessionExpired} />
      </CommitLog>
    );
    const tableA = await screen.findByRole('table', { name: 'Registered invoices' });
    expect(within(tableA).getByText('INV-1001')).toBeInTheDocument();
    const commitsBeforeSwitch = frames.length;

    // The shell swaps in the switched session while branch B's request is
    // still pending. Every commit this rerender produces — including the
    // first one, which happens BEFORE the effect can run — must show the
    // loading state and must not contain branch-A rows. A rerender-time
    // assertion alone cannot see that first frame; the commit log can.
    view.rerender(
      <CommitLog frames={frames}>
        <InvoicesPage session={sessionB} onSessionExpired={onSessionExpired} />
      </CommitLog>
    );

    const framesAfterSwitch = frames.slice(commitsBeforeSwitch);
    expect(framesAfterSwitch.length).toBeGreaterThan(0);
    for (const frame of framesAfterSwitch) {
      expect(frame).toContain('Loading invoices');
      expect(frame).not.toContain('INV-1001');
    }
    expect(screen.queryByRole('table', { name: 'Registered invoices' })).not.toBeInTheDocument();

    // Only branch-B content appears once branch B resolves.
    resolveBranchBInvoices(jsonResponse([INVOICE_BRANCH_B]));
    const tableB = await screen.findByRole('table', { name: 'Registered invoices' });
    expect(within(tableB).getByText('INV-WEST-9001')).toBeInTheDocument();
    expect(screen.queryByText('INV-1001')).not.toBeInTheDocument();
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('drops a context-A response that resolves late, after the switch to B', async () => {
    let resolveBranchAInvoices;
    fetchMock.mockImplementation((path, options = {}) => {
      const token = options.headers?.Authorization;
      if (path === '/api/patients') return Promise.resolve(jsonResponse(PATIENTS));
      if (path === '/api/invoices' && (options.method ?? 'GET') === 'GET') {
        if (token === 'Bearer branch-a-token') {
          return new Promise((resolve) => { resolveBranchAInvoices = resolve; });
        }
        return Promise.resolve(jsonResponse([INVOICE_BRANCH_B]));
      }
      return Promise.resolve(jsonResponse({}, 404));
    });
    const onSessionExpired = vi.fn();
    const sessionA = actingSessionFor('branch-a-token', 'assign-a', 'branch-a');
    const sessionB = actingSessionFor('branch-b-token', 'assign-b', 'branch-b');

    const view = render(<InvoicesPage session={sessionA} onSessionExpired={onSessionExpired} />);
    expect(screen.getByRole('status')).toHaveTextContent(/loading invoices/i);

    // The switch happens while A is still in flight: the effect cleanup
    // cancels the pending A load. Its response then arrives late and must
    // never publish — not over B, and not at all.
    view.rerender(<InvoicesPage session={sessionB} onSessionExpired={onSessionExpired} />);
    resolveBranchAInvoices(jsonResponse([INVOICE_DRAFT]));

    const tableB = await screen.findByRole('table', { name: 'Registered invoices' });
    expect(within(tableB).getByText('INV-WEST-9001')).toBeInTheDocument();
    // By the time B's table is visible every pending microtask has flushed:
    // exactly one data row (branch B's — the name matcher ignores the
    // header row), and no branch-A content anywhere.
    expect(within(tableB).getAllByRole('row', { name: /INV-WEST-9001/ })).toHaveLength(1);
    expect(screen.queryByText('INV-1001')).not.toBeInTheDocument();
    expect(onSessionExpired).not.toHaveBeenCalled();
  });
});
