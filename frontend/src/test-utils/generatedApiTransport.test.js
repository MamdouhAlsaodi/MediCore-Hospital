import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, apiFetch, authenticate } from '../api.js';
import {
  assignAdmissionBed,
  createAdmission,
  dischargeAdmission,
  fetchAdmissions,
} from '../features/admissions/admissionApi.js';
import { fetchAuditEvents } from '../features/audit/auditApi.js';
import {
  getBranchSummaryAlias,
  getNetworkSummary,
  listAuditEvents,
} from '../generated/api/index';
import { fetchAppointments, createAppointment } from '../features/appointments/appointmentApi.js';
import { fetchPatients, createPatient, updatePatient } from '../features/patients/patientApi.js';
import { fetchBranchSummary, fetchNetworkSummary } from '../features/dashboard/dashboardApi.js';
import { switchContext } from '../features/branches/actingContextApi.js';

// T072 (plan Task 10 step 6; FR-016/FR-017): transport-contract tests at the
// browser `fetch` boundary. Every demonstrated route family (auth, patients,
// appointments, admissions, dashboard, audit) is exercised through the REAL
// exported adapters, which now build their requests from the generated typed
// contract and execute them through the one shared fetch boundary. These
// tests pin method, path (including query encoding), headers, JSON body
// serialization, and the 400/401/403/404/409 error mapping — so a contract
// or adapter regression cannot pass silently.
function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const SESSION = {
  accessToken: 'synthetic-access-token',
  tokenType: 'Bearer',
  username: 'admin',
  roles: ['ADMIN'],
  assignments: [
    {
      id: 'assignment-1', role: 'ADMIN', scope: 'ORGANIZATION',
      organizationId: 'org-1', branchId: null, branchLabel: null,
      departmentId: null, departmentLabel: null, enabled: true,
    },
  ],
  actingContext: {
    username: 'admin', assignmentId: 'assignment-1', role: 'ADMIN',
    scope: 'ORGANIZATION', organizationId: 'org-1', branchId: 'branch-1',
    departmentId: null,
  },
};

const BRANCH_SUMMARY = {
  branchId: 'branch-1', branchCode: 'DEMO-BR-001', branchName: 'Synthetic Branch',
};
for (const key of [
  'patients', 'appointments', 'admissions', 'emergencyVisits', 'invoices',
  'openAdmissions', 'activeEmergencyVisits', 'bedsAvailable', 'bedsOccupied',
  'bedsMaintenance', 'bedsOutOfService', 'todayAppointments',
  'invoicesDraft', 'invoicesIssued', 'invoicesPaid', 'invoicesVoid',
]) BRANCH_SUMMARY[key] = 0;

describe('generated-contract transport (T072)', () => {
  let fetchMock;

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function stubFetch(handler) {
    fetchMock = vi.fn((path, options) => Promise.resolve(handler(path, options ?? {})));
    vi.stubGlobal('fetch', fetchMock);
  }

  function calls() {
    return fetchMock.mock.calls.map(([path, options]) => ({
      path,
      options,
      body: options?.body === undefined ? undefined : JSON.parse(options.body),
    }));
  }

  // ------------------------------------------------------------------
  // auth — login and context switch ride the generated descriptors.
  // ------------------------------------------------------------------

  it('login sends the generated POST /api/auth/login contract', async () => {
    stubFetch(() => jsonResponse(SESSION));
    const session = await authenticate('admin', 'synthetic-password');
    expect(session.token).toBe('synthetic-access-token');
    const [call] = calls();
    expect(call.path).toBe('/api/auth/login');
    expect(call.options.method).toBe('POST');
    expect(call.options.headers['Content-Type']).toBe('application/json');
    expect(call.options.headers.Authorization).toBeUndefined();
    expect(call.body).toEqual({ username: 'admin', password: 'synthetic-password' });
  });

  it('context switch sends the generated POST /api/auth/context contract', async () => {
    stubFetch(() => jsonResponse(SESSION));
    await switchContext({ token: 'tok', assignmentId: 'assignment-1', branchId: 'branch-9' });
    const [call] = calls();
    expect(call.path).toBe('/api/auth/context');
    expect(call.options.method).toBe('POST');
    expect(call.options.headers.Authorization).toBe('Bearer tok');
    expect(call.options.headers['Content-Type']).toBe('application/json');
    expect(call.body).toEqual({ assignmentId: 'assignment-1', branchId: 'branch-9' });
  });

  // ------------------------------------------------------------------
  // patients
  // ------------------------------------------------------------------

  it('patients list/create/update follow the generated contract', async () => {
    stubFetch(() => jsonResponse({ id: 'p1', active: true }));
    await fetchPatients({ token: 'tok', query: '  Jo hn &?= ' });
    await fetchPatients({ token: 'tok', query: '   ' });
    await createPatient({ token: 'tok', patient: { medicalRecordNumber: 'MRN-1', fullName: 'Synthetic A' } });
    await updatePatient({ token: 'tok', id: '11111111-1111-4111-8111-111111111111', changes: { fullName: 'Renamed' } });

    const all = calls();
    expect(all[0].path).toBe('/api/patients?q=Jo%20hn%20%26%3F%3D');
    expect(all[0].options.method).toBe('GET');
    expect(all[1].path).toBe('/api/patients');
    expect(all[1].options.method).toBe('GET');
    expect(all[2].path).toBe('/api/patients');
    expect(all[2].options.method).toBe('POST');
    expect(all[2].body).toEqual({ medicalRecordNumber: 'MRN-1', fullName: 'Synthetic A' });
    expect(all[3].path).toBe('/api/patients/11111111-1111-4111-8111-111111111111');
    expect(all[3].options.method).toBe('PUT');
    expect(all[3].body).toEqual({ fullName: 'Renamed', phone: undefined, email: undefined, address: undefined });
    for (const call of all) {
      expect(call.options.headers.Authorization).toBe('Bearer tok');
    }
  });

  // ------------------------------------------------------------------
  // appointments
  // ------------------------------------------------------------------

  it('appointments list/create follow the generated contract', async () => {
    stubFetch(() => jsonResponse([]));
    await fetchAppointments({ token: 'tok' });
    await createAppointment({
      token: 'tok',
      appointment: {
        patientId: 'p-1', professionalId: 's-1', scheduledAt: '2031-04-04T14:00:00',
        durationMinutes: 60, type: 'consultation', status: 'scheduled',
      },
    });
    const all = calls();
    expect(all[0]).toMatchObject({ path: '/api/appointments', options: expect.objectContaining({ method: 'GET' }) });
    expect(all[1].path).toBe('/api/appointments');
    expect(all[1].options.method).toBe('POST');
    expect(all[1].body).toEqual({
      patientId: 'p-1', professionalId: 's-1', scheduledAt: '2031-04-04T14:00:00',
      durationMinutes: 60, type: 'consultation', status: 'scheduled',
    });
  });

  // ------------------------------------------------------------------
  // admissions
  // ------------------------------------------------------------------

  it('admissions list/create/bed/status follow the generated contract', async () => {
    stubFetch(() => jsonResponse([]));
    await fetchAdmissions({ token: 'tok' });
    await createAdmission({ token: 'tok', admission: { patientId: 'p-1', admittedAt: '2031-04-04T08:00:00', reason: 'synthetic' } });
    await createAdmission({ token: 'tok', admission: { patientId: 'p-1', admittedAt: '2031-04-04T08:00:00', reason: 'synthetic', bedId: 'b-1' } });
    await assignAdmissionBed({ token: 'tok', id: 'a-1', bedId: 'b-2' });
    await dischargeAdmission({ token: 'tok', id: 'a-1' });

    const all = calls();
    expect(all[0].path).toBe('/api/admissions');
    expect(all[0].options.method).toBe('GET');
    expect(all[1].options.method).toBe('POST');
    expect(all[1].body).toEqual({ patientId: 'p-1', admittedAt: '2031-04-04T08:00:00', reason: 'synthetic' });
    expect(all[2].body).toEqual({ patientId: 'p-1', admittedAt: '2031-04-04T08:00:00', reason: 'synthetic', bedId: 'b-1' });
    expect(all[3].path).toBe('/api/admissions/a-1/bed');
    expect(all[3].options.method).toBe('PUT');
    expect(all[3].body).toEqual({ bedId: 'b-2' });
    expect(all[4].path).toBe('/api/admissions/a-1/status');
    expect(all[4].options.method).toBe('PUT');
    expect(all[4].body).toEqual({ status: 'DISCHARGED' });
  });

  // ------------------------------------------------------------------
  // dashboard
  // ------------------------------------------------------------------

  it('dashboard reads follow the generated contract (alias stays on /api/dashboard)', async () => {
    const network = { organizationId: 'org-1', organizationName: 'Synthetic Org', branches: [] };
    for (const key of [
      'patients', 'appointments', 'admissions', 'emergencyVisits', 'invoices',
      'openAdmissions', 'activeEmergencyVisits', 'bedsAvailable', 'bedsOccupied',
      'bedsMaintenance', 'bedsOutOfService', 'todayAppointments',
      'invoicesDraft', 'invoicesIssued', 'invoicesPaid', 'invoicesVoid',
    ]) network[key] = 0;
    stubFetch((path) => {
      if (path === '/api/dashboard') return jsonResponse(BRANCH_SUMMARY);
      return jsonResponse(network);
    });
    const branch = await fetchBranchSummary({ token: 'tok' });
    expect(branch.branchCode).toBe('DEMO-BR-001');
    await fetchNetworkSummary({ token: 'tok' });
    const generatedAlias = getBranchSummaryAlias();
    const generatedNetwork = getNetworkSummary();
    expect(generatedAlias).toEqual({ method: 'GET', path: '/api/dashboard' });
    expect(generatedNetwork).toEqual({ method: 'GET', path: '/api/dashboard/network' });
    const all = calls();
    expect(all[0].path).toBe('/api/dashboard');
    expect(all[1].path).toBe('/api/dashboard/network');
  });

  // ------------------------------------------------------------------
  // audit — descriptor contract + the adapter through the shared boundary.
  // ------------------------------------------------------------------

  it('audit descriptor and adapter follow the generated contract', async () => {
    stubFetch(() => jsonResponse([]));
    expect(listAuditEvents()).toEqual({ method: 'GET', path: '/api/audit' });
    await fetchAuditEvents({ token: 'tok', filters: { resourceType: 'Patient', actor: 'admin' } });
    const [call] = calls();
    expect(call.path).toBe('/api/audit?resourceType=Patient&actor=admin');
    expect(call.options.method).toBe('GET');
  });

  // ------------------------------------------------------------------
  // Shared-boundary status contract: 400/401/403/404/409 map to typed
  // ApiError values with the exact status; 401 additionally notifies.
  // ------------------------------------------------------------------

  it.each([
    [400, 'The request failed (400). Please try again.'],
    [403, 'You do not have permission to view this data.'],
    [404, 'The request failed (404). Please try again.'],
    [409, 'The request failed (409). Please try again.'],
  ])('status %i maps to ApiError with the pinned message', async (status, message) => {
    stubFetch(() => jsonResponse({ error: 'synthetic' }, status));
    await expect(apiFetch('/api/patients', { token: 'tok' })).rejects.toMatchObject({
      name: 'ApiError',
      status,
      message,
    });
  });

  it('status 401 maps to the expiry message and fires onUnauthorized exactly once', async () => {
    stubFetch(() => jsonResponse({ error: 'authentication required' }, 401));
    const onUnauthorized = vi.fn();
    await expect(apiFetch('/api/patients', { token: 'tok', onUnauthorized }))
      .rejects.toMatchObject({ name: 'ApiError', status: 401, message: 'Your session has expired. Please log in again.' });
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it('a network failure maps to the unreachable ApiError(0)', async () => {
    fetchMock = vi.fn(() => Promise.reject(new TypeError('network down')));
    vi.stubGlobal('fetch', fetchMock);
    await expect(apiFetch('/api/patients', { token: 'tok' }))
      .rejects.toMatchObject({ name: 'ApiError', status: 0 });
  });

  it('non-JSON error bodies still map through the shared boundary', async () => {
    stubFetch(() => new Response('gateway timeout', { status: 504 }));
    const error = await apiFetch('/api/patients', { token: 'tok' }).catch((e) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(504);
  });
});
