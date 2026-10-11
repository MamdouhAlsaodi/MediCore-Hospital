import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import HospitalCapacitySummary from './HospitalCapacitySummary.jsx';
import { parseActingSessionPayload } from '../../api.js';

const session = (hospitalId = 'h-1', token = 'synthetic-token') => ({
  token,
  actingContext: { assignmentId: 'a-1', hospitalId, branchId: 'br-1', scope: 'HOSPITAL' },
  assignments: [{ id: 'a-1', hospitalId, scope: 'HOSPITAL' }],
});
const capacity = (hospitalId = 'h-1', branches = [{ branchId: 'br-1', bedsAvailable: 2, bedsReservedForTransfer: 1, bedsOccupied: 3, appointmentsScheduled: 4 }]) => ({
  hospitalId, bedsAvailable: 2, bedsReservedForTransfer: 1, bedsOccupied: 3, appointmentsScheduled: 4, branches,
});
const response = (payload, status = 200) => new Response(JSON.stringify(payload), { status, headers: { 'Content-Type': 'application/json' } });
const deferred = () => { let resolve; const promise = new Promise((done) => { resolve = done; }); return { resolve, promise }; };

afterEach(() => vi.unstubAllGlobals());

describe('bounded hospital capacity (T114)', () => {
  it('loads for a parsed organization-scoped session with a server-issued acting hospital', async () => {
    const parsed = parseActingSessionPayload({
      accessToken: 'synthetic-token', tokenType: 'Bearer', username: 'admin', roles: ['ADMIN'],
      assignments: [{ id: 'a-org', role: 'ADMIN', scope: 'ORGANIZATION',
        organizationId: 'org-1', branchId: null, departmentId: null, enabled: true }],
      actingContext: { username: 'admin', assignmentId: 'a-org', role: 'ADMIN',
        scope: 'ORGANIZATION', organizationId: 'org-1', hospitalId: 'h-1',
        branchId: 'br-1', departmentId: null },
    });
    const fetcher = vi.fn(() => Promise.resolve(response(capacity())));
    vi.stubGlobal('fetch', fetcher);
    render(<HospitalCapacitySummary session={parsed} />);
    expect(await screen.findByText('Hospital: h-1')).toBeInTheDocument();
    expect(fetcher).toHaveBeenCalledWith('/api/capacity/hospital', expect.anything());
  });

  it('shows loading, server totals and branch breakdown for only the acting hospital', async () => {
    const pending = deferred();
    const fetcher = vi.fn(() => pending.promise);
    vi.stubGlobal('fetch', fetcher);
    render(<HospitalCapacitySummary session={session()} />);
    expect(screen.getByRole('status')).toHaveTextContent(/loading hospital capacity/i);
    pending.resolve(response(capacity()));
    expect(await screen.findByText('Hospital capacity')).toBeInTheDocument();
    expect(screen.getAllByText('Beds reserved for transfer')).toHaveLength(2);
    expect(screen.getByText('br-1')).toBeInTheDocument();
    expect(fetcher).toHaveBeenCalledWith('/api/capacity/hospital', expect.objectContaining({ method: 'GET' }));
    expect(screen.queryByText(/network capacity/i)).not.toBeInTheDocument();
  });

  it('shows an empty branch state without creating a branch total', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(response(capacity('h-1', [])))));
    render(<HospitalCapacitySummary session={session()} />);
    expect(await screen.findByText(/no branches in this hospital capacity response/i)).toBeInTheDocument();
  });

  it('hides prior hospital data immediately on context change and ignores its late response', async () => {
    const oldRequest = deferred();
    const newRequest = deferred();
    vi.stubGlobal('fetch', vi.fn().mockImplementationOnce(() => oldRequest.promise).mockImplementationOnce(() => newRequest.promise));
    const { rerender } = render(<HospitalCapacitySummary session={session()} />);
    rerender(<HospitalCapacitySummary session={session('h-2', 'new-synthetic-token')} />);
    expect(screen.getByRole('status')).toHaveTextContent(/loading hospital capacity/i);
    oldRequest.resolve(response(capacity('h-1')));
    await waitFor(() => expect(screen.queryByText('h-1')).not.toBeInTheDocument());
    newRequest.resolve(response(capacity('h-2')));
    expect(await screen.findByText('Hospital: h-2')).toBeInTheDocument();
    expect(screen.queryByText('h-1')).not.toBeInTheDocument();
  });

  it('rejects a capacity response for a different hospital', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(response(capacity('foreign-hospital')))));
    render(<HospitalCapacitySummary session={session()} />);
    expect(await screen.findByRole('alert')).toHaveTextContent(/hospital capacity unavailable/i);
    expect(screen.queryByText('foreign-hospital')).not.toBeInTheDocument();
  });

  it('hides previously loaded totals synchronously when the acting context changes', async () => {
    const pending = deferred();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(capacity())).mockImplementationOnce(() => pending.promise));
    const { rerender } = render(<HospitalCapacitySummary session={session()} />);
    expect(await screen.findByText('Hospital: h-1')).toBeInTheDocument();
    rerender(<HospitalCapacitySummary session={session('h-2')} />);
    expect(screen.getByRole('status')).toHaveTextContent(/loading hospital capacity/i);
    expect(screen.queryByText('Hospital: h-1')).not.toBeInTheDocument();
    pending.resolve(response(capacity('h-2')));
    expect(await screen.findByText('Hospital: h-2')).toBeInTheDocument();
  });

  it('does not request a hospital when the session cannot identify one', () => {
    const fetcher = vi.fn();
    vi.stubGlobal('fetch', fetcher);
    render(<HospitalCapacitySummary session={session(null)} />);
    expect(screen.getByRole('status')).toHaveTextContent(/unavailable for this acting context/i);
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('notifies the shell on session expiry', async () => {
    const onSessionExpired = vi.fn();
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(response({}, 401))));
    render(<HospitalCapacitySummary session={session()} onSessionExpired={onSessionExpired} />);
    expect(await screen.findByRole('alert')).toHaveTextContent(/session has expired/i);
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
  });

  it('shows a denial for 403 without stale totals', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(response({}, 403))));
    render(<HospitalCapacitySummary session={session()} />);
    expect(await screen.findByRole('alert')).toHaveTextContent(/permission/i);
    expect(screen.queryByText('Beds available')).not.toBeInTheDocument();
  });
});
