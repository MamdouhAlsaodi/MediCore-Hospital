export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

function messageFor(status) {
  if (status === 401) return 'Your session has expired. Please log in again.';
  if (status === 403) return 'You do not have permission to view this data.';
  return 'The request failed (' + status + '). Please try again.';
}

export async function apiFetch(path, { method = 'GET', token, body, onUnauthorized } = {}) {
  const headers = {};
  if (token) headers.Authorization = 'Bearer ' + token;
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  let response;
  try {
    response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, 'Network error: the server is unreachable.');
  }

  if (response.status === 401 && onUnauthorized) onUnauthorized();
  if (!response.ok) throw new ApiError(response.status, messageFor(response.status));
  return response.json();
}

export async function loginRequest(username, password) {
  let response;
  try {
    response = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    });
  } catch {
    throw new ApiError(0, 'Network error: the server is unreachable.');
  }
  if (!response.ok) throw new ApiError(response.status, 'Invalid username or password.');
  const payload = await response.json();
  if (typeof payload.accessToken !== 'string' || !payload.accessToken
      || typeof payload.username !== 'string' || !Array.isArray(payload.roles)) {
    throw new ApiError(500, 'The server returned an unexpected login response.');
  }
  return { token: payload.accessToken, username: payload.username, roles: payload.roles };
}
