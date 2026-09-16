import { apiFetch } from '../../api.js';
// Phase 4 (plan Task 10, T073; FR-017): request shapes come from the
// generated typed contract; this adapter keeps its role as the ONLY patient
// transport seam for the Patients screen and keeps every wire byte identical
// (paths, query encoding, Bearer envelope, JSON bodies).
import {
  createPatient as createPatientHttpRequest,
  listPatients as listPatientsHttpRequest,
  updatePatient as updatePatientHttpRequest,
} from '../../generated/api/index';

// The only patient transport adapter for the Patients screen (plan1.md
// Tasks 6-7). Every patient read and mutation goes through the shared
// apiFetch so session headers, JSON parsing, 401 expiry handling, and error
// mapping stay in one place. Components never call fetch directly.
// Plan 3 Task 5: every request rides the acting context's bound bearer
// token alone — the server derives the branch scope from that token, so
// this adapter never sends branch/assignment headers or parameters, and a
// successful context switch refreshes data simply because the shell hands
// the new session's token to these functions.
export function fetchPatients({ token, query = '', onUnauthorized } = {}) {
  const trimmed = typeof query === 'string' ? query.trim() : '';
  // T073: request shapes come from the generated contract (FR-017); the
  // screen's blank-search-means-no-filter policy stays right here.
  const request = listPatientsHttpRequest(trimmed ? { q: trimmed } : undefined);
  return apiFetch(request.path, { method: request.method, token, onUnauthorized });
}

// Task 7: register a patient. Body mirrors PatientDtos.CreatePatientRequest:
// required medicalRecordNumber/fullName, optional dateOfBirth/sex/phone/
// nationalId/address, and an optional email the server validates with @Email.
export function createPatient({ token, patient, onUnauthorized } = {}) {
  const request = createPatientHttpRequest(patient);
  return apiFetch(request.path, {
    method: request.method,
    token,
    body: request.body,
    onUnauthorized,
  });
}

// Task 7: edit an existing patient. Body mirrors PatientDtos.UpdatePatientRequest
// exactly (fullName, phone, email, address) — the medical record number,
// date of birth, sex, and national ID are deliberately not editable through
// this contract, and the adapter refuses to send them.
export function updatePatient({ token, id, changes, onUnauthorized } = {}) {
  // The adapter still refuses to send the non-editable contract fields
  // (medicalRecordNumber, dateOfBirth, sex, nationalId) — the generated
  // UpdatePatientRequest type documents the same four-field contract.
  const request = updatePatientHttpRequest(id, {
    fullName: changes?.fullName,
    phone: changes?.phone,
    email: changes?.email,
    address: changes?.address,
  });
  return apiFetch(request.path, {
    method: request.method,
    token,
    body: request.body,
    onUnauthorized,
  });
}
