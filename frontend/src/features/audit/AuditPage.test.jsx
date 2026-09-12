import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AuditPage from './AuditPage.jsx';

// Real Task 11 AuditEventView contract fields (backend/.../audit/
// AuditController.java over GET /api/audit): the strict fifteen-field DTO
// allowlist — id, actor, action, resourceType, resourceId, details,
// occurredAt, assignmentId, role, scope, organizationId, branchId,
// departmentId, correlationId, branchAttribution. The screen must render
// only the evidence columns — time, actor, acting role/scope, branch
// attribution (or the legacy/unassigned mark), action, entity type,
// identifier, correlation id — never the raw details payload, the
// assignment/organization/department pointers, or any persistence metadata.
const ORG_ID = '11111111-1111-4111-8111-111111111111';
const BRANCH_A_ID = '22222222-2222-4222-8222-222222222222';
const BRANCH_B_ID = '33333333-3333-4333-8333-333333333333';

const NEWEST_EVENT = {
  id: '88888888-8888-4888-8888-888888888888',
  actor: 'admin',
  action: 'CREATE',
  resourceType: 'Appointment',
  resourceId: '77777777-7777-4777-8777-777777777777',
  details: 'created',
  occurredAt: '2026-03-02T10:00:00Z',
  assignmentId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  role: 'ADMIN',
  scope: 'ORGANIZATION',
  organizationId: ORG_ID,
  branchId: BRANCH_A_ID,
  departmentId: null,
  correlationId: 'evidence.run-42',
  branchAttribution: null,
};

const OLDER_EVENT = {
  id: '99999999-9999-4999-8999-999999999999',
  actor: 'receptionist',
  action: 'UPDATE',
  resourceType: 'Patient',
  resourceId: '55555555-5555-4555-8555-555555555555',
  details: 'profile',
  occurredAt: '2026-03-01T08:30:00Z',
  assignmentId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  role: 'RECEPTIONIST',
  scope: 'BRANCH',
  organizationId: ORG_ID,
  branchId: BRANCH_A_ID,
  departmentId: null,
  correlationId: 'careops-evidence-77',
  branchAttribution: null,
};

const LEGACY_EVENT = {
  id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
  actor: 'system',
  action: 'CREATE',
  resourceType: 'Branch',
  resourceId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
  details: 'created',
  occurredAt: '2026-02-28T07:00:00Z',
  assignmentId: null,
  role: null,
  scope: null,
  organizationId: null,
  branchId: null,
  departmentId: null,
  correlationId: null,
  branchAttribution: 'legacy/unassigned',
};

const ORGANIZATION_VIEW = {
  id: ORG_ID,
  name: 'Synthetic Organization',
  activeBranches: [
    { id: BRANCH_A_ID, organizationId: ORG_ID, code: 'BR-A', name: 'Branch A', locationLabel: '1 A Way', active: true },
    { id: BRANCH_B_ID, organizationId: ORG_ID, code: 'BR-B', name: 'Branch B', locationLabel: '2 B Way', active: true },
  ],
};

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function sessionFor(roles, scope = 'ORGANIZATION') {
  return {
    token: 'synthetic-token',
    username: 'testuser',
    roles,
    actingContext: {
      username: 'testuser',
      assignmentId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      role: roles[0],
      scope,
      organizationId: ORG_ID,
      branchId: scope === 'ORGANIZATION' ? BRANCH_A_ID : 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
      departmentId: null,
    },
  };
}

function renderAudit(roles = ['ADMIN'], scope = 'ORGANIZATION') {
  const onSessionExpired = vi.fn();
  const view = render(
    <AuditPage session={sessionFor(roles, scope)} onSessionExpired={onSessionExpired} />
  );
  return { onSessionExpired, ...view };
}

function stubAudit(fetchMock, payload, status = 200) {
  fetchMock.mockImplementation((path) =>
    path === '/api/audit' || path.startsWith('/api/audit?')
      ? Promise.resolve(jsonResponse(payload, status))
      : Promise.resolve(jsonResponse({}, 404))
  );
}

function stubAuditAndOrganization(fetchMock, payload, status = 200) {
  fetchMock.mockImplementation((path) => {
    if (path === '/api/audit' || path.startsWith('/api/audit?')) {
      return Promise.resolve(jsonResponse(payload, status));
    }
    if (path === '/api/organization') {
      return Promise.resolve(jsonResponse(ORGANIZATION_VIEW));
    }
    return Promise.resolve(jsonResponse({}, 404));
  });
}

async function auditCalls(fetchMock) {
  await waitFor(() => {
    const statuses = screen.queryAllByRole('status');
    expect(statuses.some((el) => el.textContent.includes('Loading audit events…'))).toBe(false);
  });
  return fetchMock.mock.calls.filter(([path]) => path === '/api/audit' || path.startsWith('/api/audit?'));
}

describe('AuditPage', () => {
  let fetchMock;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  it('shows the loading state first, then the Task 11 evidence columns for ADMIN over one read-only GET', async () => {
    let resolveAudit;
    fetchMock.mockImplementation((path) => {
      if (path === '/api/audit') {
        return new Promise((resolve) => { resolveAudit = resolve; });
      }
      if (path === '/api/organization') {
        return Promise.resolve(jsonResponse(ORGANIZATION_VIEW));
      }
      return Promise.resolve(jsonResponse({}, 404));
    });

    renderAudit(['ADMIN']);

    expect(screen.getByRole('status')).toHaveTextContent('Loading audit events…');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();

    resolveAudit(jsonResponse([OLDER_EVENT, NEWEST_EVENT]));

    const table = await screen.findByRole('table', { name: 'Audit events' });
    for (const header of ['Time', 'Actor', 'Role', 'Scope', 'Branch', 'Action', 'Entity type', 'Identifier', 'Correlation ID']) {
      expect(within(table).getByRole('columnheader', { name: header })).toBeInTheDocument();
    }
    const newestRow = within(table).getByRole('row', { name: /2026-03-02T10:00:00Z/ });
    expect(within(newestRow).getByText('admin')).toBeInTheDocument();
    expect(within(newestRow).getByText('ADMIN')).toBeInTheDocument();
    expect(within(newestRow).getByText('ORGANIZATION')).toBeInTheDocument();
    expect(within(newestRow).getByText('Branch A')).toBeInTheDocument();
    expect(within(newestRow).getByText('CREATE')).toBeInTheDocument();
    expect(within(newestRow).getByText('Appointment')).toBeInTheDocument();
    expect(within(newestRow).getByText('77777777-7777-4777-8777-777777777777')).toBeInTheDocument();
    expect(within(newestRow).getByText('evidence.run-42')).toBeInTheDocument();

    // Exactly one read-only audit GET carrying the session bearer token and
    // no filter query on the initial load; this screen never sends a body.
    const calls = await auditCalls(fetchMock);
    expect(calls).toHaveLength(1);
    const [path, options] = calls[0];
    expect(path).toBe('/api/audit');
    expect(options.method).toBe('GET');
    expect(options.headers.Authorization).toBe('Bearer synthetic-token');
    expect(options.body).toBeUndefined();
  });

  it('fetches the server-owned branch options only for an ORGANIZATION-scope acting context', async () => {
    stubAuditAndOrganization(fetchMock, [NEWEST_EVENT]);
    renderAudit(['ADMIN'], 'BRANCH');
    await auditCalls(fetchMock);
    // A BRANCH-scope acting context is already branch-bound server-side, so
    // the page never fetches branch options and never offers a branch filter.
    expect(fetchMock.mock.calls.every(([path]) => !path.startsWith('/api/organization'))).toBe(true);
    expect(screen.queryByRole('combobox', { name: 'Branch' })).not.toBeInTheDocument();

    stubAuditAndOrganization(fetchMock, [NEWEST_EVENT]);
    renderAudit(['ADMIN'], 'ORGANIZATION');
    await auditCalls(fetchMock);
    const branchSelect = await screen.findByRole('combobox', { name: 'Branch' });
    expect(within(branchSelect).getByRole('option', { name: 'All branches' })).toBeInTheDocument();
    expect(within(branchSelect).getByRole('option', { name: 'Branch A' })).toBeInTheDocument();
    expect(within(branchSelect).getByRole('option', { name: 'Branch B' })).toBeInTheDocument();
  });

  it('applies the four server filters through the transport exactly as entered and clears back to the unfiltered view', async () => {
    const user = userEvent.setup();
    stubAuditAndOrganization(fetchMock, [NEWEST_EVENT]);
    renderAudit(['ADMIN']);

    await screen.findByRole('table', { name: 'Audit events' });
    await user.selectOptions(screen.getByRole('combobox', { name: 'Branch' }), BRANCH_B_ID);
    await user.type(screen.getByLabelText('Entity type'), 'Patient');
    await user.type(screen.getByLabelText('Actor'), 'admin');
    await user.type(screen.getByLabelText('Correlation ID'), 'evidence.run-42');
    await user.click(screen.getByRole('button', { name: 'Apply filters' }));

    await waitFor(() => {
      const calls = fetchMock.mock.calls.filter(([path]) => path.startsWith('/api/audit?'));
      expect(calls).toHaveLength(1);
    });
    expect(fetchMock.mock.calls.filter(([path]) => path.startsWith('/api/audit?'))[0][0]).toBe(
      '/api/audit?branchId=' + BRANCH_B_ID + '&resourceType=Patient&actor=admin&correlationId=evidence.run-42'
    );

    await user.click(screen.getByRole('button', { name: 'Clear' }));
    await waitFor(() => {
      const unfiltered = fetchMock.mock.calls.filter(([path]) => path === '/api/audit');
      expect(unfiltered.length).toBeGreaterThanOrEqual(2);
    });
    // The last audit call is the cleared, unfiltered view.
    const auditPaths = fetchMock.mock.calls.map(([path]) => path).filter((p) => p === '/api/audit' || p.startsWith('/api/audit?'));
    expect(auditPaths[auditPaths.length - 1]).toBe('/api/audit');
  });

  it('degrades honestly when branch options fail: the evidence list still loads and a notice explains the fallback', async () => {
    fetchMock.mockImplementation((path) =>
      path === '/api/audit'
        ? Promise.resolve(jsonResponse([NEWEST_EVENT]))
        : Promise.resolve(jsonResponse({}, 500))
    );
    renderAudit(['ADMIN']);

    const table = await screen.findByRole('table', { name: 'Audit events' });
    expect(table).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent(
      'Branch filter options could not be loaded; showing every branch the server allows.'
    );
    const branchSelect = screen.getByRole('combobox', { name: 'Branch' });
    expect(within(branchSelect).getByRole('option', { name: 'All branches' })).toBeInTheDocument();
  });

  it('renders events newest-first regardless of the order the API returns', async () => {
    stubAudit(fetchMock, [OLDER_EVENT, NEWEST_EVENT]);
    renderAudit(['ADMIN'], 'BRANCH');

    const table = await screen.findByRole('table', { name: 'Audit events' });
    const rows = within(table).getAllByRole('row');
    expect(rows).toHaveLength(3); // header row + two events
    expect(rows[1]).toHaveTextContent('2026-03-02T10:00:00Z');
    expect(rows[1]).toHaveTextContent('Appointment');
    expect(rows[2]).toHaveTextContent('2026-03-01T08:30:00Z');
    expect(rows[2]).toHaveTextContent('Patient');
  });

  it('renders context-less events with the legacy/unassigned attribution and placeholder evidence cells', async () => {
    stubAudit(fetchMock, [LEGACY_EVENT, NEWEST_EVENT]);
    renderAudit(['ADMIN'], 'BRANCH');

    const table = await screen.findByRole('table', { name: 'Audit events' });
    const legacyRow = within(table).getByRole('row', { name: /2026-02-28T07:00:00Z/ });
    expect(within(legacyRow).getByText('system')).toBeInTheDocument();
    expect(within(legacyRow).getByText('legacy/unassigned')).toBeInTheDocument();
    expect(within(legacyRow).getAllByText('—').length).toBeGreaterThanOrEqual(3);
  });

  it('shows an honest empty state when no audit events exist', async () => {
    stubAudit(fetchMock, []);
    renderAudit(['ADMIN'], 'BRANCH');

    // Wait for the load to settle: the loading status element is the first
    // match, so wait for the empty-state text before asserting the panel.
    await screen.findByText('No audit events recorded');
    expect(screen.getByRole('status')).toHaveTextContent('No audit events recorded');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('distinguishes a filtered empty result from an empty evidence trail', async () => {
    const user = userEvent.setup();
    stubAudit(fetchMock, []);
    renderAudit(['ADMIN'], 'BRANCH');

    await screen.findByText('No audit events recorded');
    await user.type(screen.getByLabelText('Actor'), 'no-such-actor');
    await user.click(screen.getByRole('button', { name: 'Apply filters' }));

    await screen.findByText('No audit events match the current filters');
  });

  it('shows the shared error message when the audit feed fails to load (500)', async () => {
    stubAudit(fetchMock, [], 500);
    const { onSessionExpired } = renderAudit(['ADMIN'], 'BRANCH');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The request failed (500). Please try again.');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('shows the permission-denial message when the server refuses the audit read (403)', async () => {
    stubAudit(fetchMock, [], 403);
    const { onSessionExpired } = renderAudit(['ADMIN'], 'BRANCH');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('You do not have permission to view this data.');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('returns to the shell on session expiry instead of raising a local error (401)', async () => {
    stubAudit(fetchMock, [], 401);
    const { onSessionExpired } = renderAudit(['ADMIN'], 'BRANCH');

    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('renders hostile-looking event strings as literal text, never as markup', async () => {
    stubAudit(fetchMock, [
      {
        ...NEWEST_EVENT,
        actor: '<b>admin</b>',
        action: '<img src=x onerror=alert(1)>',
        resourceType: '<script>alert(1)</script>',
        resourceId: '"><svg onload=alert(1)>',
        correlationId: '"><svg onload=alert(1)>',
      },
    ]);
    const { container } = renderAudit(['ADMIN'], 'BRANCH');

    const table = await screen.findByRole('table', { name: 'Audit events' });
    expect(table).toHaveTextContent('<img src=x onerror=alert(1)>');
    expect(table).toHaveTextContent('<script>alert(1)</script>');
    expect(table).toHaveTextContent('"><svg onload=alert(1)>');
    expect(table).toHaveTextContent('<b>admin</b>');
    expect(container.querySelector('img, script, svg, iframe')).toBeNull();
  });

  it('stays data-minimized: details payloads, context pointers, metadata, and the session token are never rendered', async () => {
    stubAudit(fetchMock, [
      {
        ...NEWEST_EVENT,
        details: 'TOP-SECRET-REQUEST-BODY',
        accessToken: 'should-never-leak',
        assignmentId: 'leaked-assignment-uuid',
        organizationId: 'leaked-organization-uuid',
        departmentId: 'leaked-department-uuid',
      },
    ]);
    renderAudit(['ADMIN'], 'BRANCH');

    await screen.findByRole('table', { name: 'Audit events' });
    expect(screen.queryByText('TOP-SECRET-REQUEST-BODY')).not.toBeInTheDocument();
    expect(screen.queryByText('should-never-leak')).not.toBeInTheDocument();
    expect(screen.queryByText('leaked-assignment-uuid')).not.toBeInTheDocument();
    expect(screen.queryByText('leaked-organization-uuid')).not.toBeInTheDocument();
    expect(screen.queryByText('leaked-department-uuid')).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('synthetic-token');
  });

  it('renders care-operations evidence rows through the HTTP boundary with acting context and correlation id', async () => {
    // Real care-operations events (Task 7 evidence, Task 11 context).
    stubAudit(fetchMock, [
      {
        id: 'c1111111-1111-4111-8111-111111111111',
        actor: 'nurse-salma',
        action: 'CREATE',
        resourceType: 'Admission',
        resourceId: 'aaaa1111-1111-4111-8111-111111111111',
        details: 'created',
        occurredAt: '2026-03-03T09:15:00Z',
        assignmentId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        role: 'NURSE',
        scope: 'BRANCH',
        organizationId: ORG_ID,
        branchId: BRANCH_A_ID,
        departmentId: null,
        correlationId: 'careops-evidence-77',
        branchAttribution: null,
      },
      {
        id: 'c2222222-2222-4222-8222-222222222222',
        actor: 'billing-lina',
        action: 'UPDATE',
        resourceType: 'Invoice',
        resourceId: 'bbbb2222-2222-4222-8222-222222222222',
        details: 'status: ISSUED',
        occurredAt: '2026-03-03T11:45:00Z',
        assignmentId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
        role: 'BILLING',
        scope: 'BRANCH',
        organizationId: ORG_ID,
        branchId: BRANCH_A_ID,
        departmentId: null,
        correlationId: 'invoice-issued-01',
        branchAttribution: null,
      },
    ]);
    const { container } = renderAudit(['ADMIN'], 'BRANCH');

    const table = await screen.findByRole('table', { name: 'Audit events' });
    for (const header of ['Time', 'Actor', 'Role', 'Scope', 'Branch', 'Action', 'Entity type', 'Identifier', 'Correlation ID']) {
      expect(within(table).getByRole('columnheader', { name: header })).toBeInTheDocument();
    }

    const admissionRow = within(table).getByRole('row', { name: /2026-03-03T09:15:00Z/ });
    expect(within(admissionRow).getByText('nurse-salma')).toBeInTheDocument();
    expect(within(admissionRow).getByText('NURSE')).toBeInTheDocument();
    expect(within(admissionRow).getByText('BRANCH')).toBeInTheDocument();
    expect(within(admissionRow).getByText('CREATE')).toBeInTheDocument();
    expect(within(admissionRow).getByText('Admission')).toBeInTheDocument();
    expect(within(admissionRow).getByText('aaaa1111-1111-4111-8111-111111111111')).toBeInTheDocument();
    expect(within(admissionRow).getByText('careops-evidence-77')).toBeInTheDocument();

    const invoiceRow = within(table).getByRole('row', { name: /2026-03-03T11:45:00Z/ });
    expect(within(invoiceRow).getByText('billing-lina')).toBeInTheDocument();
    expect(within(invoiceRow).getByText('BILLING')).toBeInTheDocument();
    expect(within(invoiceRow).getByText('UPDATE')).toBeInTheDocument();
    expect(within(invoiceRow).getByText('Invoice')).toBeInTheDocument();
    expect(within(invoiceRow).getByText('invoice-issued-01')).toBeInTheDocument();

    // Data minimization: the raw details payload (even canonical transition
    // text) and the assignment/organization pointers stay internal, the
    // session token never leaks, and every evidence cell is literal text.
    expect(document.body).not.toHaveTextContent('status: ISSUED');
    expect(document.body).not.toHaveTextContent(ORG_ID);
    expect(document.body).not.toHaveTextContent('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
    expect(document.body).not.toHaveTextContent('synthetic-token');
    expect(container.querySelector('script, img, svg, iframe')).toBeNull();

    // Through the real adapter boundary: exactly one ADMIN read-only audit
    // GET carrying the session bearer token and no query filters.
    const calls = await auditCalls(fetchMock);
    expect(calls).toHaveLength(1);
    const [path, options] = calls[0];
    expect(path).toBe('/api/audit');
    expect(options.method).toBe('GET');
    expect(options.headers.Authorization).toBe('Bearer synthetic-token');
    expect(options.body).toBeUndefined();
  });
});
