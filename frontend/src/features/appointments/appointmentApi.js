import { apiFetch } from '../../api.js';

// The only appointment transport adapter (plan1.md Task 8). Bodies mirror
// AppointmentDtos exactly:
//   - list:  GET /api/appointments -> AppointmentResponse[]
//            (id, patientId, professionalId, scheduledAt, type, status)
//   - create: POST /api/appointments with CreateAppointmentRequest
//            { patientId: UUID, professionalId: UUID,
//              scheduledAt: ISO LocalDateTime, type, status } where status is
//              bound to the lowercase contract
//              scheduled|confirmed|completed|cancelled.
// patientId/professionalId are typed UUID references validated server-side;
// unknown references are rejected with 404 and malformed bodies with 400 by
// the shared GlobalExceptionHandler. The adapter deliberately sends exactly
// the five contract fields and nothing else. All transport goes through the
// shared apiFetch (Bearer token, JSON, ApiError, onUnauthorized); components
// never call fetch directly. Plan 3 Task 5: branch scoping rides the acting
// context's bound bearer token alone — no branch/assignment headers or
// parameters are ever sent, and a successful context switch refreshes data
// because the shell hands the new session's token to these functions.
export function fetchAppointments({ token, onUnauthorized } = {}) {
  return apiFetch('/api/appointments', { method: 'GET', token, onUnauthorized });
}

export function createAppointment({ token, appointment, onUnauthorized } = {}) {
  return apiFetch('/api/appointments', {
    method: 'POST',
    token,
    body: {
      patientId: appointment?.patientId,
      professionalId: appointment?.professionalId,
      scheduledAt: appointment?.scheduledAt,
      type: appointment?.type,
      status: appointment?.status,
    },
    onUnauthorized,
  });
}
