import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, apiFetch, authenticate, parseActingSessionPayload } from './api.js';
import { fetchOrganizationView, switchContext } from './features/branches/actingContextApi.js';
import { loadSession, saveSession } from './auth.js';

// Transport-contract tests at the browser `fetch` boundary only; the real
// exported adapter functions run. The Task 1 characterization that pinned the
// superseded global-role login mapping has been replaced by the Task 5
// acting-session and organization-view contracts; the still-accurate Task 1
// characterizations (plain session storage round-trip, apiFetch header
// contract, and the 401/403 callback boundary) are retained.
function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('multi-branch baseline contracts (Plan 3 Task 1 characterization)', () => {
  let fetchMock;

  beforeEach(() => {
    sessionStorage.clear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  describe('authenticate maps the complete acting-session response (Task 5 contract)', () => {
    // Supersedes the removed loginRequest pin: login no longer discards the
    // server-issued assignment list and acting context — it returns the full
    // normalized session the acting-context shell stores.
    it('maps a successful response to the complete session with assignments and acting context', async () => {
      fetchMock = vi.fn(() =>
        Promise.resolve(
          jsonResponse({
            accessToken: 'synthetic-access-token',
            tokenType: 'Bearer',
            username: 'receptionist',
            roles: ['RECEPTIONIST'],
            assignments: [
              {
                id: 'assignment-1', role: 'RECEPTIONIST', scope: 'BRANCH',
                organizationId: 'org-1', organizationLabel: 'Main Hospital Group',
                branchId: 'branch-1', branchLabel: 'East Clinic',
                departmentId: null, departmentLabel: null, enabled: true,
              },
            ],
            actingContext: {
              username: 'receptionist', assignmentId: 'assignment-1', role: 'RECEPTIONIST',
              scope: 'BRANCH', organizationId: 'org-1', branchId: 'branch-1', departmentId: null,
            },
          })
        )
      );
      vi.stubGlobal('fetch', fetchMock);

      const session = await authenticate('receptionist', 'synthetic-password');

      expect(session).toEqual({
        token: 'synthetic-access-token',
        username: 'receptionist',
        roles: ['RECEPTIONIST'],
        assignments: [
          {
            id: 'assignment-1', role: 'RECEPTIONIST', scope: 'BRANCH',
            organizationId: 'org-1', organizationLabel: 'Main Hospital Group',
            branchId: 'branch-1', branchLabel: 'East Clinic',
            departmentId: null, departmentLabel: null, enabled: true,
          },
        ],
        actingContext: {
          username: 'receptionist', assignmentId: 'assignment-1', role: 'RECEPTIONIST',
          scope: 'BRANCH', organizationId: 'org-1', branchId: 'branch-1', departmentId: null,
        },
      });
      expect(fetchMock.mock.calls[0]).toEqual([
        '/api/auth/login',
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ username: 'receptionist', password: 'synthetic-password' }),
        },
      ]);
    });

    it('rejects a global-role-only response instead of silently discarding the acting context', async () => {
      fetchMock = vi.fn(() =>
        Promise.resolve(
          jsonResponse({
            accessToken: 'synthetic-access-token',
            tokenType: 'Bearer',
            username: 'receptionist',
            roles: ['RECEPTIONIST'],
          })
        )
      );
      vi.stubGlobal('fetch', fetchMock);

      const error = await authenticate('receptionist', 'synthetic-password').then(
        () => null,
        (thrown) => thrown
      );

      expect(error).toBeInstanceOf(ApiError);
      expect(error.status).toBe(500);
    });

    it('rejects acting-session responses whose context contradicts the selected assignment', () => {
      const validPayload = () => ({
        accessToken: 'synthetic-access-token',
        tokenType: 'Bearer',
        username: 'receptionist',
        roles: ['RECEPTIONIST'],
        assignments: [{
          id: 'assignment-1', role: 'RECEPTIONIST', scope: 'BRANCH',
          organizationId: 'org-1', organizationLabel: 'Main Hospital Group',
          branchId: 'branch-1', branchLabel: 'East Clinic',
          departmentId: null, departmentLabel: null, enabled: true,
        }],
        actingContext: {
          username: 'receptionist', assignmentId: 'assignment-1', role: 'RECEPTIONIST',
          scope: 'BRANCH', organizationId: 'org-1', branchId: 'branch-1', departmentId: null,
        },
      });
      const contradictions = [
        (payload) => { payload.actingContext.role = 'NURSE'; },
        (payload) => { payload.actingContext.scope = 'ORGANIZATION'; },
        (payload) => { payload.actingContext.organizationId = 'other-organization'; },
        (payload) => { payload.actingContext.branchId = 'other-branch'; },
        (payload) => { payload.actingContext.departmentId = 'other-department'; },
        (payload) => { payload.actingContext.username = 'other-user'; },
      ];

      for (const contradict of contradictions) {
        const payload = validPayload();
        contradict(payload);
        expect(() => parseActingSessionPayload(payload)).toThrow(ApiError);
      }
    });
  });

  describe('context switch transport names the assignment and the selected branch (Task 5 contract)', () => {
    it('posts both ids to /api/auth/context with the bearer token and parses the replacement session', async () => {
      fetchMock = vi.fn(() =>
        Promise.resolve(
          jsonResponse({
            accessToken: 'switched-access-token',
            tokenType: 'Bearer',
            username: 'admin',
            roles: ['ADMIN'],
            assignments: [
              {
                id: 'assignment-2', role: 'ADMIN', scope: 'ORGANIZATION',
                organizationId: 'org-1', organizationLabel: 'Main Hospital Group',
                branchId: null, branchLabel: null,
                departmentId: null, departmentLabel: null, enabled: true,
              },
            ],
            actingContext: {
              username: 'admin', assignmentId: 'assignment-2', role: 'ADMIN',
              scope: 'ORGANIZATION', organizationId: 'org-1', branchId: 'branch-2', departmentId: null,
            },
          })
        )
      );
      vi.stubGlobal('fetch', fetchMock);
      const onUnauthorized = vi.fn();

      const session = await switchContext({
        token: 'current-token',
        assignmentId: 'assignment-2',
        branchId: 'branch-2',
        onUnauthorized,
      });

      expect(fetchMock.mock.calls[0]).toEqual([
        '/api/auth/context',
        {
          method: 'POST',
          headers: { Authorization: 'Bearer current-token', 'Content-Type': 'application/json' },
          body: JSON.stringify({ assignmentId: 'assignment-2', branchId: 'branch-2' }),
        },
      ]);
      expect(onUnauthorized).not.toHaveBeenCalled();
      expect(session.token).toBe('switched-access-token');
      expect(session.actingContext.branchId).toBe('branch-2');
    });
  });

  describe('organization view transport is the branch allowlist source (Task 5 contract)', () => {
    it('fetches GET /api/organization with the bearer token and returns the strict active-branch view', async () => {
      fetchMock = vi.fn(() =>
        Promise.resolve(
          jsonResponse({
            id: 'org-1',
            code: 'MHG',
            name: 'Main Hospital Group',
            activeBranches: [
              { id: 'branch-1', organizationId: 'org-1', code: 'EAST', name: 'East Clinic', locationLabel: '1 East Way', active: true },
              { id: 'branch-2', organizationId: 'org-1', code: 'WEST', name: 'West Clinic', locationLabel: '9 West Way', active: true },
            ],
          })
        )
      );
      vi.stubGlobal('fetch', fetchMock);

      const view = await fetchOrganizationView({ token: 'current-token' });

      expect(fetchMock.mock.calls[0]).toEqual([
        '/api/organization',
        { headers: { Authorization: 'Bearer current-token' } },
      ]);
      expect(view).toEqual({
        id: 'org-1',
        name: 'Main Hospital Group',
        activeBranches: [
          { id: 'branch-1', organizationId: 'org-1', code: 'EAST', name: 'East Clinic', locationLabel: '1 East Way', active: true },
          { id: 'branch-2', organizationId: 'org-1', code: 'WEST', name: 'West Clinic', locationLabel: '9 West Way', active: true },
        ],
      });
    });

    it('delegates 401 to the expiry callback and refuses a malformed allowlist with ApiError(500)', async () => {
      const onUnauthorized = vi.fn();
      fetchMock = vi.fn()
        .mockResolvedValueOnce(jsonResponse({ error: 'expired' }, 401))
        .mockResolvedValueOnce(jsonResponse({ id: 'org-1', name: 'Main Hospital Group', activeBranches: [{ id: 'branch-1' }] }));
      vi.stubGlobal('fetch', fetchMock);

      const expired = await fetchOrganizationView({ token: 'current-token', onUnauthorized }).then(
        () => null,
        (thrown) => thrown
      );
      expect(expired).toBeInstanceOf(ApiError);
      expect(expired.status).toBe(401);
      expect(onUnauthorized).toHaveBeenCalledTimes(1);

      const malformed = await fetchOrganizationView({ token: 'current-token' }).then(
        () => null,
        (thrown) => thrown
      );
      expect(malformed).toBeInstanceOf(ApiError);
      expect(malformed.status).toBe(500);
    });
  });

  describe('session storage holds no branch context', () => {
    // CURRENT BASELINE — Task 5 will store the acting context alongside the
    // token; today a token/username/roles session round-trips and malformed
    // payloads stay rejected.
    it('saves and restores a token/username/roles session through real sessionStorage', () => {
      const session = { token: 'synthetic-token', username: 'receptionist', roles: ['RECEPTIONIST'] };

      saveSession(session);

      expect(loadSession()).toEqual(session);
    });

    it('still rejects malformed sessions without a valid token, username, or roles array', () => {
      const malformed = [
        JSON.stringify({ username: 'receptionist', roles: ['RECEPTIONIST'] }),
        JSON.stringify({ token: '', username: 'receptionist', roles: ['RECEPTIONIST'] }),
        JSON.stringify({ token: 'synthetic-token', roles: ['RECEPTIONIST'] }),
        JSON.stringify({ token: 'synthetic-token', username: 'receptionist', roles: 'RECEPTIONIST' }),
        'not-json-at-all',
      ];

      for (const raw of malformed) {
        sessionStorage.setItem('medicore.session', raw);
        expect(loadSession(), `expected null for ${raw}`).toBeNull();
      }
      expect(loadSession()).toBeNull();
    });
  });

  describe('apiFetch sends no acting-context headers today', () => {
    // CURRENT BASELINE — Task 5 will add the acting-context contract; today
    // only the bearer token (and content type for bodies) is sent.
    it('sends the bearer token with GET requests and returns the parsed JSON body', async () => {
      fetchMock = vi.fn(() => Promise.resolve(jsonResponse({ ok: true })));
      vi.stubGlobal('fetch', fetchMock);

      const result = await apiFetch('/api/patients', { token: 'synthetic-token' });

      expect(fetchMock).toHaveBeenCalledTimes(1);
      const [path, options] = fetchMock.mock.calls[0];
      expect(path).toBe('/api/patients');
      expect(options.method).toBe('GET');
      expect(options.headers).toEqual({ Authorization: 'Bearer synthetic-token' });
      expect(result).toEqual({ ok: true });
    });

    it('sends the JSON content type with a body and never adds assignment or branch headers', async () => {
      fetchMock = vi.fn(() => Promise.resolve(jsonResponse({ id: 'created-id' }, 201)));
      vi.stubGlobal('fetch', fetchMock);
      const payload = { fullName: 'Synthetic Patient' };

      const result = await apiFetch('/api/patients', {
        method: 'POST',
        token: 'synthetic-token',
        body: payload,
      });

      const [, options] = fetchMock.mock.calls[0];
      expect(options.method).toBe('POST');
      expect(options.headers).toEqual({
        Authorization: 'Bearer synthetic-token',
        'Content-Type': 'application/json',
      });
      expect(options.body).toBe(JSON.stringify(payload));
      expect(Object.keys(options.headers)).toEqual(
        expect.arrayContaining(['Authorization', 'Content-Type'])
      );
      expect(Object.keys(options.headers)).toHaveLength(2);
      expect(result).toEqual({ id: 'created-id' });
    });
  });

  describe('unauthorized and forbidden status handling', () => {
    // The 401-invokes-callback half is already pinned at component level in
    // DashboardPage.test.jsx and PatientsPage.test.jsx; this file pins the
    // missing boundary-level pair so a 403 provably does NOT expire the
    // session today.
    it('invokes the unauthorized callback on 401 and throws an ApiError', async () => {
      fetchMock = vi.fn(() => Promise.resolve(jsonResponse({ error: 'expired' }, 401)));
      vi.stubGlobal('fetch', fetchMock);
      const onUnauthorized = vi.fn();

      const error = await apiFetch('/api/patients', { token: 'synthetic-token', onUnauthorized }).then(
        () => null,
        (thrown) => thrown
      );

      expect(onUnauthorized).toHaveBeenCalledTimes(1);
      expect(error).toBeInstanceOf(ApiError);
      expect(error.status).toBe(401);
    });

    it('does not invoke the unauthorized callback on 403', async () => {
      fetchMock = vi.fn(() => Promise.resolve(jsonResponse({ error: 'forbidden' }, 403)));
      vi.stubGlobal('fetch', fetchMock);
      const onUnauthorized = vi.fn();

      const error = await apiFetch('/api/patients', { token: 'synthetic-token', onUnauthorized }).then(
        () => null,
        (thrown) => thrown
      );

      expect(onUnauthorized).not.toHaveBeenCalled();
      expect(error).toBeInstanceOf(ApiError);
      expect(error.status).toBe(403);
    });
  });
});
