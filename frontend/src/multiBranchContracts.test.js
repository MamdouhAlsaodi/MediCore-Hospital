import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, apiFetch, loginRequest } from './api.js';
import { loadSession, saveSession } from './auth.js';

// Plan 3 Task 1 characterization (docs/plan3.md Task 1): these tests pin
// TODAY'S frontend contract — token/username/roles sessions with no branch
// context, an apiFetch that sends no acting-assignment headers — as the
// baseline that Plan 3 Task 5 will deliberately replace. Only the browser
// `fetch` boundary is mocked; the real exported adapter functions run.
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

  describe('loginRequest maps the global-role response only', () => {
    // CURRENT BASELINE — Task 5 will replace this adapter so the shell can
    // receive server-issued assignments and an acting context.
    it('maps a successful response to {token, username, roles} and discards future-looking assignments and actingContext', async () => {
      fetchMock = vi.fn(() =>
        Promise.resolve(
          jsonResponse({
            accessToken: 'synthetic-access-token',
            tokenType: 'Bearer',
            username: 'receptionist',
            roles: ['RECEPTIONIST'],
            assignments: [
              { id: 'assignment-1', role: 'RECEPTIONIST', scope: 'BRANCH', branchId: 'branch-1', enabled: true },
            ],
            actingContext: { assignmentId: 'assignment-1', branchId: 'branch-1' },
          })
        )
      );
      vi.stubGlobal('fetch', fetchMock);

      const session = await loginRequest('receptionist', 'synthetic-password');

      expect(session).toEqual({
        token: 'synthetic-access-token',
        username: 'receptionist',
        roles: ['RECEPTIONIST'],
      });
      expect(Object.keys(session).sort()).toEqual(['roles', 'token', 'username']);
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
