import { apiFetch } from '../../api.js';

// The only patient transport adapter for the Patients screen (plan1.md Task 6).
// Every patient read goes through the shared apiFetch so session headers, JSON
// parsing, 401 expiry handling, and error mapping stay in one place.
// Components never call fetch directly.
export function fetchPatients({ token, query = '', onUnauthorized } = {}) {
  const trimmed = typeof query === 'string' ? query.trim() : '';
  const path = trimmed
    ? `/api/patients?q=${encodeURIComponent(trimmed)}`
    : '/api/patients';
  return apiFetch(path, { method: 'GET', token, onUnauthorized });
}
