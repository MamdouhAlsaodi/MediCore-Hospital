import React, { useEffect, useState } from 'react';
import { ApiError } from '../../api.js';
import { fetchPatients } from '../patients/patientApi.js';
import { fetchStaff, staffDisplayName } from '../staff/staffApi.js';
import { fetchAppointments } from './appointmentApi.js';
import AppointmentForm from './AppointmentForm.jsx';

// Scheduling-permitted roles for the "Schedule appointment" action (UI
// convenience only; backend authorization stays authoritative for every
// request — the server refuses with 403 and the page shows that state).
const SCHEDULE_ROLES = ['RECEPTIONIST', 'ADMIN'];

function hasAnyRole(roles, wanted) {
  return Array.isArray(roles) && wanted.some((role) => roles.includes(role));
}

// Appointments screen (plan1.md Task 8): the existing appointment list over
// GET /api/appointments plus the scheduling flow over POST /api/appointments.
// The list resolves patient/professional names against the loaded records
// and renders honest "Unknown record" placeholders for references it cannot
// resolve (e.g. pre-Task-4 rows with raw stored values) — raw reference
// values are never displayed. The professional directory is best-effort here
// (name resolution only); its failure never blocks the list. All transport
// goes through the feature adapters — components never call fetch directly.
export default function AppointmentsPage({ session, onSessionExpired }) {
  const [patients, setPatients] = useState([]);
  const [staff, setStaff] = useState([]);
  const [appointments, setAppointments] = useState([]);
  const [status, setStatus] = useState('loading');
  const [loadError, setLoadError] = useState('');
  // 'list' | 'form'
  const [view, setView] = useState('list');
  const [confirmation, setConfirmation] = useState('');
  // Bumped after a successful schedule so the list refetches instead of
  // showing a result set that cannot contain the new appointment.
  const [listRefresh, setListRefresh] = useState(0);

  useEffect(() => {
    let active = true;
    setStatus('loading');
    setLoadError('');
    const loadPatients = fetchPatients({
      token: session.token,
      onUnauthorized: onSessionExpired,
    });
    const loadAppointments = fetchAppointments({
      token: session.token,
      onUnauthorized: onSessionExpired,
    });
    // Best-effort: only used to resolve professional names in the list.
    const loadStaff = fetchStaff({
      token: session.token,
      onUnauthorized: onSessionExpired,
    }).catch(() => null);
    Promise.all([loadPatients, loadAppointments, loadStaff])
      .then(([loadedPatients, loadedAppointments, loadedStaff]) => {
        if (!active) return;
        setPatients(Array.isArray(loadedPatients) ? loadedPatients : []);
        setAppointments(Array.isArray(loadedAppointments) ? loadedAppointments : []);
        setStaff(Array.isArray(loadedStaff) ? loadedStaff : []);
        setStatus('ready');
      })
      .catch((error) => {
        if (!active) return;
        // 401 is ownership of the shell: the session-expiry callback returns
        // the app to Login, so no local error is raised on top of it.
        if (error instanceof ApiError && error.status === 401) return;
        setLoadError(error instanceof ApiError ? error.message : 'Appointments could not be loaded.');
        setStatus('ready');
      });
    return () => { active = false; };
  }, [session.token, listRefresh, onSessionExpired]);

  function openForm() {
    setConfirmation('');
    setView('form');
  }

  function handleFormCancelled() {
    // Cancellation preserves nothing half-saved and returns to the list.
    setConfirmation('');
    setView('list');
  }

  function handleScheduled() {
    setConfirmation('Appointment scheduled.');
    setView('list');
    // Server-side search results cannot be honestly patched locally: refetch.
    setListRefresh((n) => n + 1);
  }

  const canSchedule = hasAnyRole(session?.roles, SCHEDULE_ROLES);
  const patientNameById = new Map(patients.map((patient) => [patient.id, patient.fullName]));
  const professionalNameById = new Map(staff.map((member) => [member.id, staffDisplayName(member)]));

  return (
    <section className="appointments-screen" aria-label="Appointments screen">
      {view === 'list' && (
        <>
          {status === 'loading' && (
            <p className="notice" role="status">Loading appointments…</p>
          )}

          {loadError && (
            <p className="notice error" role="alert">{loadError}</p>
          )}

          {status === 'ready' && !loadError && (
            <>
              {confirmation && (
                <p className="notice success" role="status">{confirmation}</p>
              )}

              {canSchedule && (
                <div className="appointments-actions">
                  <button
                    type="button"
                    className="appointment-new"
                    onClick={openForm}
                  >
                    Schedule appointment
                  </button>
                </div>
              )}

              {appointments.length === 0 ? (
                <div className="panel appointments-empty" role="status">
                  <h3>No appointments scheduled</h3>
                  <p className="panel-hint">
                    No appointments exist yet.{' '}
                    {canSchedule
                      ? 'Use the schedule action above to book one for an existing patient with an existing professional.'
                      : 'Appointments appear here once they are scheduled.'}
                  </p>
                </div>
              ) : (
                <div className="appointments-table-wrap">
                  <table className="appointments-table" aria-label="Scheduled appointments">
                    <thead>
                      <tr>
                        <th scope="col">Patient</th>
                        <th scope="col">Professional</th>
                        <th scope="col">Scheduled for</th>
                        <th scope="col">Type</th>
                        <th scope="col">Status</th>
                      </tr>
                    </thead>
                    <tbody>
                      {appointments.map((appointment) => (
                        <tr key={appointment.id}>
                          <td>{patientNameById.get(appointment.patientId) ?? 'Unknown record'}</td>
                          <td>{professionalNameById.get(appointment.professionalId) ?? 'Unknown record'}</td>
                          <td>{appointment.scheduledAt}</td>
                          <td>{appointment.type}</td>
                          <td>{appointment.status}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </>
          )}
        </>
      )}

      {view === 'form' && (
        <AppointmentForm
          session={session}
          patients={patients}
          onCreated={handleScheduled}
          onCancel={handleFormCancelled}
          onSessionExpired={onSessionExpired}
        />
      )}
    </section>
  );
}
