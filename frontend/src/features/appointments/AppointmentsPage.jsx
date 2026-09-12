import React, { useEffect, useState } from 'react';
import { ApiError } from '../../api.js';
import { actingContextKey } from '../../auth.js';
import { can } from '../../authorization.js';
import { fetchPatients } from '../patients/patientApi.js';
import { fetchStaff, staffDisplayName } from '../staff/staffApi.js';
import { fetchAppointments } from './appointmentApi.js';
import AppointmentForm from './AppointmentForm.jsx';

// Pure render-phase selector (the accepted Task 8 render-tag invariant):
// decides exactly what this screen may display for the current acting
// context. Branch-owned data is tagged with the actingContextKey it
// resolved under (loaded.contextKey) and is exposed only while that tag
// still equals the current contextKey. While the tags differ — i.e. from
// the moment the shell swaps in a new session until the new context's data
// publishes — the loading state is returned instead: never the previous
// branch's rows, and never a misleading empty-state result. The component
// renders through this selector on every pass, so the gate holds during the
// render itself rather than depending on when an effect happens to run.
// Exported as the deterministic seam that lets tests pin the render
// contract. This is display isolation only; the server's scope for each
// context-bound token stays the sole authority over what data exists.
export function appointmentsDisplayState({ contextKey, loaded }) {
  if (loaded.contextKey === contextKey) return loaded;
  return { contextKey, status: 'loading', loadError: '', patients: [], staff: [], appointments: [] };
}

// Everything one load produces, tagged with its context in a single state
// update so the tag and the data it describes can never drift apart.
const UNLOADED = { contextKey: null, status: 'loading', loadError: '', patients: [], staff: [], appointments: [] };

// Appointments screen (plan1.md Task 8, docs/plan3.md Tasks 5 and 9): the
// existing appointment list over GET /api/appointments plus the scheduling
// flow over POST /api/appointments. The list resolves patient/professional
// names against the loaded records and renders honest "Unknown record"
// placeholders for references it cannot resolve (e.g. pre-Task-4 rows with
// raw stored values) — raw reference values are never displayed. The
// professional directory is best-effort here (name resolution only); its
// failure never blocks the list. All transport goes through the feature
// adapters — components never call fetch directly.
export default function AppointmentsPage({ session, onSessionExpired }) {
  // Single tagged state for everything the load produces; see UNLOADED and
  // appointmentsDisplayState above.
  const [loaded, setLoaded] = useState(UNLOADED);
  // 'list' | 'form'
  const [view, setView] = useState('list');
  const [confirmation, setConfirmation] = useState('');
  // Bumped after a successful schedule so the list refetches instead of
  // showing a result set that cannot contain the new appointment.
  const [listRefresh, setListRefresh] = useState(0);
  // Identity of the acting context (assignment + bound branch). After a
  // successful context switch the shell swaps in a complete new session;
  // this screen stays the single owner of its list state and simply
  // refetches it when the context (or its context-bound token) changes.
  const contextKey = actingContextKey(session);

  useEffect(() => {
    let active = true;
    // Fresh load attempt for this context. The previous context's rows stay
    // tagged in state until this context's data publishes; the render-phase
    // selector already refuses to display them, so the screen shows loading
    // from the first render of the switch — no effect timing required.
    setLoaded({ contextKey, status: 'loading', loadError: '', patients: [], staff: [], appointments: [] });
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
        // Publish rows tagged with the context they resolved under, so the
        // selector can never show them under a different acting context.
        setLoaded({
          contextKey,
          status: 'ready',
          loadError: '',
          patients: Array.isArray(loadedPatients) ? loadedPatients : [],
          appointments: Array.isArray(loadedAppointments) ? loadedAppointments : [],
          staff: Array.isArray(loadedStaff) ? loadedStaff : [],
        });
      })
      .catch((error) => {
        if (!active) return;
        // 401 is ownership of the shell: the session-expiry callback returns
        // the app to Login, so no local error is raised on top of it.
        if (error instanceof ApiError && error.status === 401) return;
        // The load attempt for this context concluded with an error; tag the
        // error to this context so it is shown here and nowhere else.
        setLoaded({
          contextKey,
          status: 'ready',
          loadError: error instanceof ApiError ? error.message : 'Appointments could not be loaded.',
          patients: [],
          staff: [],
          appointments: [],
        });
      });
    return () => { active = false; };
  }, [session.token, contextKey, listRefresh, onSessionExpired]);

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

  // Render-phase display gate: rows, options, status, and load error are
  // only ever taken from data whose context tag matches the current acting
  // context (see appointmentsDisplayState).
  const { status, loadError, patients, staff, appointments } =
    appointmentsDisplayState({ contextKey, loaded });

  // UI convenience hint from the shared permission map; backend stays
  // authoritative for every request (the server refuses with 403 and the
  // page shows that state).
  const canSchedule = can(session, 'create', 'appointment');
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
