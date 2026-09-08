const SESSION_KEY = 'medicore.session';
const LEGACY_TOKEN_KEY = 'token';

export function purgeLegacyStorage() {
  try {
    localStorage.removeItem(LEGACY_TOKEN_KEY);
  } catch {
    // Storage unavailable (private mode etc.) — nothing to purge.
  }
}

export function loadSession() {
  try {
    const raw = sessionStorage.getItem(SESSION_KEY);
    if (!raw) return null;
    const parsedSession = JSON.parse(raw);
    if (!parsedSession || typeof parsedSession.token !== 'string' || !parsedSession.token) return null;
    if (typeof parsedSession.username !== 'string' || !parsedSession.username) return null;
    if (!Array.isArray(parsedSession.roles)) return null;
    return parsedSession;
  } catch {
    return null;
  }
}

export function saveSession(session) {
  sessionStorage.setItem(SESSION_KEY, JSON.stringify(session));
}

export function clearSession() {
  sessionStorage.removeItem(SESSION_KEY);
}
