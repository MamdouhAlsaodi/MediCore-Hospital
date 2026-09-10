import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import AuditPage from './AuditPage.jsx';

// Real AuditEvent contract fields (backend/.../audit/AuditEvent.java over
// GET /api/audit): id, actor, action, resourceType, resourceId, details,
// occurredAt (+ BaseEntity id/createdAt/updatedAt/version). The screen must
// render only the evidence columns — timestamp, actor, action, entity type,
// identifier — never the internal metadata or the raw details payload.
const NEWEST_EVENT = {
  id: '88888888-8888-4888-8888-888888888888',
  createdAt: '2026-03-02T10:00:00Z',
  updatedAt: '2026-03-02T10:00:00Z',
  version: 0,
  actor: 'admin',
  action: 'CREATE',
  resourceType: 'Appointment',
  resourceId: '77777777-7777-4777-8777-777777777777',
  details: 'created',
  occurredAt: '2026-03-02T10:00:00Z',
};

const OLDER_EVENT = {
  id: '99999999-9999-4999-8999-999999999999',
  createdAt: '2026-03-01T08:30:00Z',
  updatedAt: '2026-03-01T08:30:00Z',
  version: 0,
  actor: 'receptionist',
  action: 'UPDATE',
  resourceType: 'Patient',
  resourceId: '55555555-5555-4555-8555-555555555555',
  details: 'profile',
  occurredAt: '2026-03-01T08:30:00Z',
};

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function sessionFor(roles) {
  return { token: 'synthetic-token', username: 'testuser', roles };
}

function renderAudit(roles = ['ADMIN']) {
  const onSessionExpired = vi.fn();
  const view = render(
    <AuditPage session={sessionFor(roles)} onSessionExpired={onSessionExpired} />
  );
  return { onSessionExpired, ...view };
}

function stubAudit(fetchMock, payload, status = 200) {
  fetchMock.mockImplementation((path) =>
    path === '/api/audit'
      ? Promise.resolve(jsonResponse(payload, status))
      : Promise.resolve(jsonResponse({}, 404))
  );
}

describe('AuditPage', () => {
  let fetchMock;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  it('shows the loading state first, then the real contract columns for ADMIN over one read-only GET', async () => {
    let resolveAudit;
    fetchMock.mockImplementation((path) => {
      if (path === '/api/audit') {
        return new Promise((resolve) => { resolveAudit = resolve; });
      }
      return Promise.resolve(jsonResponse({}, 404));
    });

    renderAudit(['ADMIN']);

    expect(screen.getByRole('status')).toHaveTextContent('Loading audit events…');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();

    resolveAudit(jsonResponse([OLDER_EVENT, NEWEST_EVENT]));

    const table = await screen.findByRole('table', { name: 'Audit events' });
    for (const header of ['Time', 'Actor', 'Action', 'Entity type', 'Identifier']) {
      expect(within(table).getByRole('columnheader', { name: header })).toBeInTheDocument();
    }
    const newestRow = within(table).getByRole('row', { name: /2026-03-02T10:00:00Z/ });
    expect(within(newestRow).getByText('admin')).toBeInTheDocument();
    expect(within(newestRow).getByText('CREATE')).toBeInTheDocument();
    expect(within(newestRow).getByText('Appointment')).toBeInTheDocument();
    expect(within(newestRow).getByText('77777777-7777-4777-8777-777777777777')).toBeInTheDocument();

    // Exactly one read-only GET carrying the session bearer token; this
    // screen never sends a request body.
    expect(fetchMock.mock.calls).toHaveLength(1);
    const [path, options] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/audit');
    expect(options.method).toBe('GET');
    expect(options.headers.Authorization).toBe('Bearer synthetic-token');
    expect(options.body).toBeUndefined();
  });

  it('renders events newest-first regardless of the order the API returns', async () => {
    stubAudit(fetchMock, [OLDER_EVENT, NEWEST_EVENT]);
    renderAudit(['ADMIN']);

    const table = await screen.findByRole('table', { name: 'Audit events' });
    const rows = within(table).getAllByRole('row');
    expect(rows).toHaveLength(3); // header row + two events
    expect(rows[1]).toHaveTextContent('2026-03-02T10:00:00Z');
    expect(rows[1]).toHaveTextContent('Appointment');
    expect(rows[2]).toHaveTextContent('2026-03-01T08:30:00Z');
    expect(rows[2]).toHaveTextContent('Patient');
  });

  it('shows an honest empty state when no audit events exist', async () => {
    stubAudit(fetchMock, []);
    renderAudit(['ADMIN']);

    // Wait for the load to settle: the loading status element is the first
    // match, so wait for the empty-state text before asserting the panel.
    await screen.findByText('No audit events recorded');
    expect(screen.getByRole('status')).toHaveTextContent('No audit events recorded');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('shows the shared error message when the audit feed fails to load (500)', async () => {
    stubAudit(fetchMock, [], 500);
    const { onSessionExpired } = renderAudit(['ADMIN']);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The request failed (500). Please try again.');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('shows the permission-denial message when the server refuses the audit read (403)', async () => {
    stubAudit(fetchMock, [], 403);
    const { onSessionExpired } = renderAudit(['ADMIN']);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('You do not have permission to view this data.');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('returns to the shell on session expiry instead of raising a local error (401)', async () => {
    stubAudit(fetchMock, [], 401);
    const { onSessionExpired } = renderAudit(['ADMIN']);

    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('renders hostile-looking event strings as literal text, never as markup', async () => {
    stubAudit(fetchMock, [
      {
        id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        actor: '<b>admin</b>',
        action: '<img src=x onerror=alert(1)>',
        resourceType: '<script>alert(1)</script>',
        resourceId: '"><svg onload=alert(1)>',
        details: 'created',
        occurredAt: '2026-03-02T10:00:00Z',
      },
    ]);
    const { container } = renderAudit(['ADMIN']);

    const table = await screen.findByRole('table', { name: 'Audit events' });
    expect(table).toHaveTextContent('<img src=x onerror=alert(1)>');
    expect(table).toHaveTextContent('<script>alert(1)</script>');
    expect(table).toHaveTextContent('"><svg onload=alert(1)>');
    expect(table).toHaveTextContent('<b>admin</b>');
    expect(container.querySelector('img, script, svg, iframe')).toBeNull();
  });

  it('stays data-minimized: details payloads, internal metadata, and the session token are never rendered', async () => {
    stubAudit(fetchMock, [
      {
        ...NEWEST_EVENT,
        details: 'TOP-SECRET-REQUEST-BODY',
        accessToken: 'should-never-leak',
        version: 7,
      },
    ]);
    renderAudit(['ADMIN']);

    await screen.findByRole('table', { name: 'Audit events' });
    expect(screen.queryByText('TOP-SECRET-REQUEST-BODY')).not.toBeInTheDocument();
    expect(screen.queryByText('should-never-leak')).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('synthetic-token');
  });

  it('renders Admission and Invoice evidence rows through the HTTP boundary with only the five evidence fields', async () => {
    // Real care-operations events (Task 7) including the persistence
    // metadata the API actually returns and canonical transition details.
    stubAudit(fetchMock, [
      {
        id: 'c1111111-1111-4111-8111-111111111111',
        createdAt: '2026-03-03T09:14:59Z',
        updatedAt: '2026-03-03T09:14:59Z',
        version: 3,
        actor: 'nurse-salma',
        action: 'CREATE',
        resourceType: 'Admission',
        resourceId: 'aaaa1111-1111-4111-8111-111111111111',
        details: 'created',
        occurredAt: '2026-03-03T09:15:00Z',
      },
      {
        id: 'c2222222-2222-4222-8222-222222222222',
        createdAt: '2026-03-03T11:44:59Z',
        updatedAt: '2026-03-03T11:44:59Z',
        version: 2,
        actor: 'billing-lina',
        action: 'UPDATE',
        resourceType: 'Invoice',
        resourceId: 'bbbb2222-2222-4222-8222-222222222222',
        details: 'status: ISSUED',
        occurredAt: '2026-03-03T11:45:00Z',
      },
    ]);
    const { container } = renderAudit(['ADMIN']);

    const table = await screen.findByRole('table', { name: 'Audit events' });
    for (const header of ['Time', 'Actor', 'Action', 'Entity type', 'Identifier']) {
      expect(within(table).getByRole('columnheader', { name: header })).toBeInTheDocument();
    }

    const admissionRow = within(table).getByRole('row', { name: /2026-03-03T09:15:00Z/ });
    expect(within(admissionRow).getByText('nurse-salma')).toBeInTheDocument();
    expect(within(admissionRow).getByText('CREATE')).toBeInTheDocument();
    expect(within(admissionRow).getByText('Admission')).toBeInTheDocument();
    expect(within(admissionRow).getByText('aaaa1111-1111-4111-8111-111111111111')).toBeInTheDocument();

    const invoiceRow = within(table).getByRole('row', { name: /2026-03-03T11:45:00Z/ });
    expect(within(invoiceRow).getByText('billing-lina')).toBeInTheDocument();
    expect(within(invoiceRow).getByText('UPDATE')).toBeInTheDocument();
    expect(within(invoiceRow).getByText('Invoice')).toBeInTheDocument();
    expect(within(invoiceRow).getByText('bbbb2222-2222-4222-8222-222222222222')).toBeInTheDocument();

    // Data minimization: the raw details payload (even canonical transition
    // text) and the persistence metadata stay internal, the session token
    // never leaks, and every evidence cell is literal text, never markup.
    expect(document.body).not.toHaveTextContent('status: ISSUED');
    expect(document.body).not.toHaveTextContent('2026-03-03T09:14:59Z');
    expect(document.body).not.toHaveTextContent('c1111111-1111-4111-8111-111111111111');
    expect(document.body).not.toHaveTextContent('synthetic-token');
    expect(container.querySelector('script, img, svg, iframe')).toBeNull();

    // Through the real adapter boundary: exactly one ADMIN read-only GET
    // carrying the session bearer token.
    expect(fetchMock.mock.calls).toHaveLength(1);
    const [path, options] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/audit');
    expect(options.method).toBe('GET');
    expect(options.headers.Authorization).toBe('Bearer synthetic-token');
    expect(options.body).toBeUndefined();
  });
});
