import { apiFetch } from '../../api.js';

// The only patient transport adapter for the Patients screen (plan1.md
// Tasks 6-7). Every patient read and mutation goes through the shared
// apiFetch so session headers, JSON parsing, 401 expiry handling, and error
// mapping stay in one place. Components never call fetch directly.
export function fetchPatients({ token, query = '', onUnauthorized } = {}) {
  const trimmed = typeof query === 'string' ? query.trim() : '';
  const path = trimmed
    ? `/api/patients?q=${encodeURIComponent(trimmed)}`
    : '/api/patients';
  return apiFetch(path, { method: 'GET', token, onUnauthorized });
}

// Task 7: register a patient. Body mirrors PatientDtos.CreatePatientRequest:
// required medicalRecordNumber/fullName, optional dateOfBirth/sex/phone/
// nationalId/address, and an optional email the server validates with @Email.
export function createPatient({ token, patient, onUnauthorized } = {}) {
  return apiFetch('/api/patients', {
    method: 'POST',
    token,
    body: patient,
    onUnauthorized,
  });
}

// Task 7: edit an existing patient. Body mirrors PatientDtos.UpdatePatientRequest
// exactly (fullName, phone, email, address) — the medical record number,
// date of birth, sex, and national ID are deliberately not editable through
// this contract, and the adapter refuses to send them.
export function updatePatient({ token, id, changes, onUnauthorized } = {}) {
  const safeId = encodeURIComponent(String(id ?? ''));
  return apiFetch(`/api/patients/${safeId}`, {
    method: 'PUT',
    token,
    body: {
      fullName: changes?.fullName,
      phone: changes?.phone,
      email: changes?.email,
      address: changes?.address,
    },
    onUnauthorized,
  });
}
