import React, { useEffect, useMemo, useState } from 'react';
import { ApiError } from '../../api.js';
import { actingContextKey } from '../../auth.js';
import { fetchStaff, staffDisplayName } from '../staff/staffApi.js';
import { fetchStaffAvailability, availabilityIntervalText } from '../staff/availabilityApi.js';
import { createAppointment } from './appointmentApi.js';

// Lowercase status contract from AppointmentDtos.CreateAppointmentRequest.
const STATUS_OPTIONS = ['scheduled', 'confirmed', 'completed', 'cancelled'];

// Bounded engineering demo range enforced by the server contract
// (docs/plan3.md Task 9). It is a validation bound for the synthetic
// demo only — never clinical, care, or staffing policy.
export const MIN_APPOINTMENT_DURATION = 5;
export const MAX_APPOINTMENT_DURATION = 480;

// Pure render-phase selector (the accepted Task 8 render-tag invariant):
// decides exactly which availability state this form may display for the
// current acting context. Availability is tagged with the actingContextKey
// it resolved under (loaded.contextKey) and is exposed only while that tag
// still equals the current contextKey; while the tags differ the loading
// state is returned instead — never the previous branch's modeled
// intervals, and never a misleading empty result. The component renders
// through this selector on every pass, so the gate holds during the render
// itself rather than depending on when an effect happens to run. Exported
// as the deterministic seam that lets tests pin the render contract.
// Display isolation only: the server's scope for each context-bound token
// stays the sole authority over what data exists.
export function availabilityDisplayState({ contextKey, loaded }) {
  if (loaded.contextKey === contextKey) return loaded;
  return { contextKey, phase: 'loading', intervals: [], errorText: '' };
}

// The half-open [from, to) UTC/ISO window covering the selected date. The
// datetime-local value carries no time-zone offset; both bounds are passed
// through as ISO strings exactly as the server contract expects — no
// branch-local conversion is invented anywhere.
function dayWindow(scheduledAt) {
  const date = typeof scheduledAt === 'string' ? scheduledAt.slice(0, 10) : '';
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return null;
  const [year, month, day] = date.split('-').map(Number);
  const next = new Date(Date.UTC(year, month - 1, day + 1));
  const nextDay = `${next.getUTCFullYear()}-${String(next.getUTCMonth() + 1).padStart(2, '0')}-${String(next.getUTCDate()).padStart(2, '0')}`;
  return { from: `${date}T00:00`, to: `${nextDay}T00:00` };
}

// Scheduling form (plan1.md Task 8, docs/plan3.md Task 9). Patients arrive
// as already-loaded domain records (the host screen's list or the single
// preselected record from the patient detail view); professionals load here
// from the staff directory adapter. The submitted IDs are always the
// selected domain records — there is no free-typed reference anywhere. The
// required duration (bounded 5–480 demo range) rides the single appointment
// transport seam, and the modeled availability for the selected
// professional and date is loaded, displayed, and dropped on a context
// switch through the render-tag invariant above. Server errors render
// inline with zero field loss; 401 is ownership of the shell
// (session-expiry callback only).
export default function AppointmentForm({
  session,
  patients,
  preselectedPatientId = '',
  onCreated,
  onCancel,
  onSessionExpired,
}) {
  const [patientId, setPatientId] = useState(preselectedPatientId ?? '');
  const [professionalId, setProfessionalId] = useState('');
  const [scheduledAt, setScheduledAt] = useState('');
  const [duration, setDuration] = useState('');
  const [type, setType] = useState('');
  const [status, setStatus] = useState('scheduled');
  // null while the professional directory request is in flight.
  const [professionals, setProfessionals] = useState(null);
  const [professionalsError, setProfessionalsError] = useState('');
  // Single tagged state for the availability load; see the selector above.
  const [availability, setAvailability] = useState({ contextKey: null, phase: 'idle', intervals: [], errorText: '' });
  const [availabilityRetry, setAvailabilityRetry] = useState(0);
  const [pending, setPending] = useState(false);
  const [validationError, setValidationError] = useState('');
  const [serverError, setServerError] = useState('');

  const contextKey = actingContextKey(session);
  const availabilityWindow = useMemo(() => dayWindow(scheduledAt), [scheduledAt]);

  useEffect(() => {
    let active = true;
    setProfessionals(null);
    setProfessionalsError('');
    fetchStaff({
      token: session.token,
      onUnauthorized: onSessionExpired,
    })
      .then((data) => {
        if (active) setProfessionals(Array.isArray(data) ? data : []);
      })
      .catch((error) => {
        if (!active) return;
        // 401 is ownership of the shell: no local error on top of it.
        if (error instanceof ApiError && error.status === 401) return;
        setProfessionalsError(
          error instanceof ApiError ? error.message : 'Professionals could not be loaded.'
        );
      });
    return () => { active = false; };
  }, [session.token, onSessionExpired]);

  useEffect(() => {
    if (!professionalId || !availabilityWindow) {
      setAvailability({ contextKey, phase: 'idle', intervals: [], errorText: '' });
      return;
    }
    let active = true;
    // Fresh load attempt for this context; the render-phase selector already
    // refuses to display a previous context's intervals from the first
    // render of the switch — no effect timing required.
    setAvailability({ contextKey, phase: 'loading', intervals: [], errorText: '' });
    fetchStaffAvailability({
      token: session.token,
      staffId: professionalId,
      from: availabilityWindow.from,
      to: availabilityWindow.to,
      onUnauthorized: onSessionExpired,
    })
      .then((data) => {
        if (active) setAvailability({ contextKey, phase: 'ready', intervals: Array.isArray(data) ? data : [], errorText: '' });
      })
      .catch((error) => {
        if (!active) return;
        // 401 is ownership of the shell: no local error on top of it.
        if (error instanceof ApiError && error.status === 401) return;
        setAvailability({
          contextKey,
          phase: 'error',
          intervals: [],
          errorText: error instanceof ApiError ? error.message : 'Availability could not be loaded.',
        });
      });
    return () => { active = false; };
  }, [session.token, contextKey, professionalId, availabilityWindow, availabilityRetry, onSessionExpired]);

  const directoryUnavailable = Boolean(professionalsError);
  // Display gate: intervals and errors are only ever taken from availability
  // state whose context tag matches the current acting context.
  const { phase: availabilityPhase, intervals, errorText: availabilityError } =
    availabilityDisplayState({ contextKey, loaded: availability });

  async function handleSubmit(event) {
    event.preventDefault();
    if (pending) return;
    setValidationError('');
    setServerError('');
    const patient = patients.find((record) => record.id === patientId);
    const professional = (professionals ?? []).find((record) => record.id === professionalId);
    const trimmedType = type.trim();
    const parsedDuration = Number(duration.trim());
    const durationValid = Number.isInteger(parsedDuration)
      && parsedDuration >= MIN_APPOINTMENT_DURATION
      && parsedDuration <= MAX_APPOINTMENT_DURATION;
    if (!patient || !professional || !scheduledAt || !durationValid || !trimmedType
        || !STATUS_OPTIONS.includes(status)) {
      setValidationError(!durationValid
        ? `Provide a duration between ${MIN_APPOINTMENT_DURATION} and ${MAX_APPOINTMENT_DURATION} minutes.`
        : 'Select a patient and a professional, and provide a date and time, a type, and a status.');
      return;
    }
    setPending(true);
    try {
      const created = await createAppointment({
        token: session.token,
        appointment: {
          patientId: patient.id,
          professionalId: professional.id,
          scheduledAt,
          durationMinutes: parsedDuration,
          type: trimmedType,
          status,
        },
        onUnauthorized: onSessionExpired,
      });
      onCreated(created);
    } catch (error) {
      // 401 is ownership of the shell: no local error on top of it.
      if (error instanceof ApiError && error.status === 401) return;
      setServerError(
        error instanceof ApiError ? error.message : 'The appointment could not be scheduled.'
      );
    } finally {
      setPending(false);
    }
  }

  return (
    <form
      className="panel appointment-form"
      aria-label="Schedule an appointment"
      aria-busy={pending}
      onSubmit={handleSubmit}
    >
      <h3>Schedule an appointment</h3>
      <p className="panel-hint">
        Choose the patient and the professional from the loaded records, then
        pick the date, time, duration, type, and status. Only the times the
        server models as available can be scheduled; the server refuses
        anything else. No identifiers are typed by hand.
      </p>

      {validationError && (
        <p className="notice error" role="alert">{validationError}</p>
      )}
      {serverError && (
        <p className="notice error" role="alert">{serverError}</p>
      )}
      {directoryUnavailable && (
        <>
          <p className="notice error" role="alert">{professionalsError}</p>
          <p className="panel-hint">
            Scheduling requires the professional directory, which the server
            refused or could not provide. No appointment can be submitted.
          </p>
        </>
      )}

      <div className="appointment-form-grid">
        <div className="appointment-field">
          <label htmlFor="appointment-patient">Patient</label>
          <select
            id="appointment-patient"
            value={patientId}
            onChange={(event) => setPatientId(event.target.value)}
          >
            <option value="">Select a patient</option>
            {patients.map((patient) => (
              <option key={patient.id} value={patient.id}>
                {patient.fullName} (MRN {patient.medicalRecordNumber})
              </option>
            ))}
          </select>
        </div>

        <div className="appointment-field">
          <label htmlFor="appointment-professional">Professional</label>
          <select
            id="appointment-professional"
            value={professionalId}
            disabled={directoryUnavailable}
            onChange={(event) => setProfessionalId(event.target.value)}
          >
            <option value="">
              {directoryUnavailable ? 'Professionals not available' : 'Select a professional'}
            </option>
            {(professionals ?? []).map((member) => (
              <option key={member.id} value={member.id}>
                {staffDisplayName(member)}
              </option>
            ))}
          </select>
        </div>

        <div className="appointment-field">
          <label htmlFor="appointment-scheduled-at">Date and time</label>
          <input
            id="appointment-scheduled-at"
            type="datetime-local"
            value={scheduledAt}
            onChange={(event) => setScheduledAt(event.target.value)}
          />
        </div>

        <div className="appointment-field">
          <label htmlFor="appointment-duration">Duration (minutes)</label>
          <input
            id="appointment-duration"
            type="number"
            value={duration}
            onChange={(event) => setDuration(event.target.value)}
          />
          <p className="appointment-field-hint">
            Demo validation range ({MIN_APPOINTMENT_DURATION}–{MAX_APPOINTMENT_DURATION} minutes)
            for this synthetic scheduling exercise — not a real-world duration policy.
          </p>
        </div>

        <div className="appointment-field">
          <label htmlFor="appointment-type">Type</label>
          <input
            id="appointment-type"
            type="text"
            value={type}
            onChange={(event) => setType(event.target.value)}
          />
        </div>

        <div className="appointment-field">
          <label htmlFor="appointment-status">Status</label>
          <select
            id="appointment-status"
            value={status}
            onChange={(event) => setStatus(event.target.value)}
          >
            {STATUS_OPTIONS.map((value) => (
              <option key={value} value={value}>{value}</option>
            ))}
          </select>
        </div>
      </div>

      {professionalId && availabilityWindow && (
        <div className="availability-panel" aria-label="Modeled availability">
          <h4>Modeled availability</h4>
          {availabilityPhase === 'loading' && (
            <p className="notice" role="status">Loading availability…</p>
          )}
          {availabilityPhase === 'ready' && intervals.length === 0 && (
            <p className="notice" role="status">
              No modeled availability exists for this professional in the
              selected day. The server schedules only inside modeled intervals.
            </p>
          )}
          {availabilityPhase === 'ready' && intervals.length > 0 && (
            <>
              <ul className="availability-list">
                {intervals.map((interval) => (
                  <li key={interval.id}>{availabilityIntervalText(interval)}</li>
                ))}
              </ul>
              <p className="appointment-field-hint">
                Intervals are the server&apos;s ISO (UTC) timestamps exactly as
                modeled — no branch-local time zone is applied. The appointment
                must fit inside one interval; the server refuses overlaps and
                outside-availability times.
              </p>
            </>
          )}
          {availabilityPhase === 'error' && (
            <>
              <p className="notice error" role="alert">{availabilityError}</p>
              <p className="panel-hint">
                The modeled availability could not be loaded. You can retry;
                the server stays the authority over what can be scheduled.
              </p>
              <button
                type="button"
                className="availability-retry"
                onClick={() => setAvailabilityRetry((n) => n + 1)}
              >
                Retry
              </button>
            </>
          )}
        </div>
      )}

      <div className="appointment-form-actions">
        <button
          type="submit"
          className="appointment-save"
          disabled={pending || directoryUnavailable}
        >
          {pending ? 'Scheduling…' : 'Schedule appointment'}
        </button>
        <button
          type="button"
          className="appointment-cancel"
          onClick={onCancel}
          disabled={pending}
        >
          Cancel
        </button>
      </div>
    </form>
  );
}
