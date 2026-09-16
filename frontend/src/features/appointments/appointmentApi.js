import { apiFetch } from '../../api.js';
// Phase 4 (plan Task 10, T073; FR-017): request shapes come from the
// generated typed contract; the six-field create allowlist (with the
// server-computed window end never sent) stays in this adapter.
import {
  createAppointment as createAppointmentHttpRequest,
  listAppointments as listAppointmentsHttpRequest,
} from '../../generated/api/index';

// The only appointment transport adapter (plan1.md Task 8). Bodies mirror
// AppointmentDtos exactly:
//   - list:  GET /api/appointments -> AppointmentResponse[]
//            (id, patientId, professionalId, scheduledAt, durationMinutes,
//             endsAt, type, status)
//   - create: POST /api/appointments with CreateAppointmentRequest
//            { patientId: UUID, professionalId: UUID,
//              scheduledAt: ISO LocalDateTime, durationMinutes: 5..480,
//              type, status } where status is bound to the lowercase
//              contract scheduled|confirmed|completed|cancelled.
// patientId/professionalId are typed UUID references validated server-side;
// unknown references are rejected with 404 and malformed bodies with 400 by
// the shared GlobalExceptionHandler. Task 9 (docs/plan3.md): the required
// bounded durationMinutes rides this single existing seam — the server
// computes the window end itself, so the adapter deliberately sends exactly
// the six contract fields and nothing else (no client-computed end). All
// transport goes through the shared apiFetch (Bearer token, JSON, ApiError,
// onUnauthorized); components never call fetch directly. Plan 3 Task 5:
// branch scoping rides the acting context's bound bearer token alone — no
// branch/assignment headers or parameters are ever sent, and a successful
// context switch refreshes data because the shell hands the new session's
// token to these functions.
export function fetchAppointments({ token, onUnauthorized } = {}) {
  const request = listAppointmentsHttpRequest();
  return apiFetch(request.path, { method: request.method, token, onUnauthorized });
}

export function createAppointment({ token, appointment, onUnauthorized } = {}) {
  const request = createAppointmentHttpRequest({
    patientId: appointment?.patientId,
    professionalId: appointment?.professionalId,
    scheduledAt: appointment?.scheduledAt,
    durationMinutes: appointment?.durationMinutes,
    type: appointment?.type,
    status: appointment?.status,
  });
  return apiFetch(request.path, {
    method: request.method,
    token,
    body: request.body,
    onUnauthorized,
  });
}
