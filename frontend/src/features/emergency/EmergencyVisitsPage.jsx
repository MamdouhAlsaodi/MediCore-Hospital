import React, { useEffect, useState } from 'react';
import { ApiError } from '../../api.js';
import { can } from '../../authorization.js';
import { fetchPatients } from '../patients/patientApi.js';
import { createEmergencyVisit, fetchEmergencyVisits, transitionEmergencyVisit } from './emergencyApi.js';

// Neutral demo triage labels only (docs/plan2.md Task 3). These values are a
// non-clinical training simulation: they are NOT ATS, ESI, MTS, or any real
// triage protocol, carry no assessment or prioritization semantics, and the
// UI labels them as demo values everywhere they appear.
const TRIAGE_LEVELS = ['1', '2', '3', '4', '5'];
const TRIAGE_HINT = 'Neutral demo label (1–5) with no clinical meaning — not a real triage protocol.';

// Registration form (docs/plan2.md Task 3). Patients arrive as already-loaded
// domain records (the host screen's list); there is no free-typed reference
// anywhere. The submitted body mirrors CreateEmergencyVisitRequest exactly —
// patientId, arrivalAt, triageLevel, chiefComplaint — because the server owns
// the lifecycle: it sets status=WAITING and applies every transition itself.
// Server errors render inline with zero field loss; 401 is ownership of the
// shell.
export function EmergencyVisitForm({
  session,
  patients,
  onCreated,
  onCancel,
  onSessionExpired,
}) {
  const [patientId, setPatientId] = useState('');
  const [arrivalAt, setArrivalAt] = useState('');
  const [triageLevel, setTriageLevel] = useState('');
  const [chiefComplaint, setChiefComplaint] = useState('');
  const [pending, setPending] = useState(false);
  const [validationError, setValidationError] = useState('');
  const [serverError, setServerError] = useState('');

  async function handleSubmit(event) {
    event.preventDefault();
    if (pending) return;
    setValidationError('');
    setServerError('');
    const patient = patients.find((record) => record.id === patientId);
    const trimmedComplaint = chiefComplaint.trim();
    if (!patient || !arrivalAt || !TRIAGE_LEVELS.includes(triageLevel) || !trimmedComplaint) {
      setValidationError(
        'Select a patient and provide the arrival date and time, a triage label, and the chief complaint.'
      );
      return;
    }
    setPending(true);
    try {
      const created = await createEmergencyVisit({
        token: session.token,
        visit: { patientId: patient.id, arrivalAt, triageLevel, chiefComplaint: trimmedComplaint },
        onUnauthorized: onSessionExpired,
      });
      onCreated(created);
    } catch (error) {
      // 401 is ownership of the shell: no local error on top of it.
      if (error instanceof ApiError && error.status === 401) return;
      setServerError(
        error instanceof ApiError ? error.message : 'The emergency visit could not be registered.'
      );
    } finally {
      setPending(false);
    }
  }

  return (
    <form
      className="panel emergency-form"
      aria-label="Register an emergency visit"
      aria-busy={pending}
      onSubmit={handleSubmit}
    >
      <h3>Register an emergency visit</h3>
      <p className="panel-hint">
        Choose the patient from the loaded records, then pick the arrival date
        and time, a triage demo label, and describe the chief complaint. The
        triage label is a neutral training value with no clinical meaning, and
        the server owns the visit status.
      </p>

      {validationError && (
        <p className="notice error" role="alert">{validationError}</p>
      )}
      {serverError && (
        <p className="notice error" role="alert">{serverError}</p>
      )}

      <div className="emergency-form-grid">
        <div className="emergency-field">
          <label htmlFor="emergency-patient">Patient</label>
          <select
            id="emergency-patient"
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

        <div className="emergency-field">
          <label htmlFor="emergency-arrival-at">Arrival date and time</label>
          <input
            id="emergency-arrival-at"
            type="datetime-local"
            value={arrivalAt}
            onChange={(event) => setArrivalAt(event.target.value)}
          />
        </div>

        <div className="emergency-field">
          <label htmlFor="emergency-triage">Triage label (demo 1–5, no clinical meaning)</label>
          <select
            id="emergency-triage"
            value={triageLevel}
            onChange={(event) => setTriageLevel(event.target.value)}
          >
            <option value="">Select a demo label</option>
            {TRIAGE_LEVELS.map((level) => (
              <option key={level} value={level}>{level}</option>
            ))}
          </select>
          <span className="emergency-field-hint">{TRIAGE_HINT}</span>
        </div>

        <div className="emergency-field">
          <label htmlFor="emergency-complaint">Chief complaint</label>
          <input
            id="emergency-complaint"
            type="text"
            value={chiefComplaint}
            onChange={(event) => setChiefComplaint(event.target.value)}
          />
        </div>
      </div>

      <div className="emergency-form-actions">
        <button
          type="submit"
          className="emergency-save"
          disabled={pending}
        >
          {pending ? 'Registering…' : 'Register visit'}
        </button>
        <button
          type="button"
          className="emergency-cancel"
          onClick={onCancel}
          disabled={pending}
        >
          Cancel
        </button>
      </div>
    </form>
  );
}

// Emergency-visits screen (docs/plan2.md Task 3): the visits list over
// GET /api/emergency-visits, the registration flow over POST
// /api/emergency-visits, and the guarded transitions over PUT
// /api/emergency-visits/{id}/status (WAITING -> IN_TREATMENT | CLOSED,
// IN_TREATMENT -> CLOSED; CLOSED is terminal and offers no actions). The
// list resolves patient names against the loaded records and renders honest
// "Unknown record" placeholders for references it cannot resolve — raw
// reference values are never displayed. Every transition is a two-step
// confirmation: nothing is sent until its Confirm button is clicked. All
// transport goes through the feature adapter — components never call fetch
// directly.
export default function EmergencyVisitsPage({ session, onSessionExpired }) {
  const [patients, setPatients] = useState([]);
  const [visits, setVisits] = useState([]);
  const [status, setStatus] = useState('loading');
  const [loadError, setLoadError] = useState('');
  // 'list' | 'form'
  const [view, setView] = useState('list');
  const [confirmation, setConfirmation] = useState('');
  // Bumped after a successful mutation so the list refetches instead of
  // showing a result set that cannot contain the new state.
  const [listRefresh, setListRefresh] = useState(0);
  // The {id, target} awaiting its transition confirmation, or null.
  const [confirmPending, setConfirmPending] = useState(null);
  // The {id, target} whose transition request is in flight, or null.
  const [transitioning, setTransitioning] = useState(null);
  const [transitionError, setTransitionError] = useState('');

  useEffect(() => {
    let active = true;
    setStatus('loading');
    setLoadError('');
    const loadPatients = fetchPatients({
      token: session.token,
      onUnauthorized: onSessionExpired,
    });
    const loadVisits = fetchEmergencyVisits({
      token: session.token,
      onUnauthorized: onSessionExpired,
    });
    Promise.all([loadPatients, loadVisits])
      .then(([loadedPatients, loadedVisits]) => {
        if (!active) return;
        setPatients(Array.isArray(loadedPatients) ? loadedPatients : []);
        setVisits(Array.isArray(loadedVisits) ? loadedVisits : []);
        setStatus('ready');
      })
      .catch((error) => {
        if (!active) return;
        // 401 is ownership of the shell: the session-expiry callback returns
        // the app to Login, so no local error is raised on top of it.
        if (error instanceof ApiError && error.status === 401) return;
        setLoadError(error instanceof ApiError ? error.message : 'Emergency visits could not be loaded.');
        setStatus('ready');
      });
    return () => { active = false; };
  }, [session.token, listRefresh, onSessionExpired]);

  function openForm() {
    setConfirmation('');
    setTransitionError('');
    setView('form');
  }

  function handleFormCancelled() {
    // Cancellation preserves nothing half-saved and returns to the list.
    setConfirmation('');
    setView('list');
  }

  function handleRegistered() {
    setConfirmation('Emergency visit registered.');
    setView('list');
    // Server-side state cannot be honestly patched locally: refetch.
    setListRefresh((n) => n + 1);
  }

  function requestTransition(visitId, target) {
    setConfirmation('');
    setTransitionError('');
    setConfirmPending({ id: visitId, target });
  }

  function cancelTransition() {
    setConfirmPending(null);
  }

  async function confirmTransition(visitId, target) {
    if (transitioning) return;
    setTransitioning({ id: visitId, target });
    setTransitionError('');
    try {
      await transitionEmergencyVisit({
        token: session.token,
        id: visitId,
        status: target,
        onUnauthorized: onSessionExpired,
      });
      setConfirmPending(null);
      setConfirmation(target === 'CLOSED' ? 'Visit closed.' : 'Treatment started.');
      // Server-side state cannot be honestly patched locally: refetch.
      setListRefresh((n) => n + 1);
    } catch (error) {
      setConfirmPending(null);
      // 401 is ownership of the shell: no local error on top of it.
      if (error instanceof ApiError && error.status === 401) return;
      setTransitionError(
        error instanceof ApiError ? error.message : 'The status change could not be recorded.'
      );
    } finally {
      setTransitioning(null);
    }
  }

  // UI convenience hints from the shared permission map; backend stays
  // authoritative for every request (the server refuses with 403 and the
  // page shows that state).
  const canRegister = can(session, 'create', 'emergencyVisit');
  const canTransition = can(session, 'transition', 'emergencyVisit');
  const patientNameById = new Map(patients.map((patient) => [patient.id, patient.fullName]));
  // The transitions each row's current status legally admits (mirrors the
  // server map; the server still validates and refuses anything else).
  function transitionsFor(visitStatus) {
    if (visitStatus === 'WAITING') return ['IN_TREATMENT', 'CLOSED'];
    if (visitStatus === 'IN_TREATMENT') return ['CLOSED'];
    return [];
  }

  return (
    <section className="emergency-screen" aria-label="Emergency Visits screen">
      {view === 'list' && (
        <>
          {status === 'loading' && (
            <p className="notice" role="status">Loading emergency visits…</p>
          )}

          {loadError && (
            <p className="notice error" role="alert">{loadError}</p>
          )}

          {status === 'ready' && !loadError && (
            <>
              {confirmation && (
                <p className="notice success" role="status">{confirmation}</p>
              )}

              {transitionError && (
                <p className="notice error" role="alert">{transitionError}</p>
              )}

              <p className="panel-hint emergency-boundary">
                Emergency visits are an administrative training simulation. The
                triage column shows neutral demo labels (1–5) with no clinical
                meaning — not a real triage protocol.
              </p>

              {canRegister && (
                <div className="emergency-actions">
                  <button
                    type="button"
                    className="emergency-new"
                    onClick={openForm}
                  >
                    Register visit
                  </button>
                </div>
              )}

              {visits.length === 0 ? (
                <div className="panel emergency-empty" role="status">
                  <h3>No emergency visits registered</h3>
                  <p className="panel-hint">
                    No emergency visits exist yet.{' '}
                    {canRegister
                      ? 'Use the register action above to record one for an existing patient.'
                      : 'Emergency visits appear here once they are registered.'}
                  </p>
                </div>
              ) : (
                <div className="emergency-table-wrap">
                  <table className="emergency-table" aria-label="Registered emergency visits">
                    <thead>
                      <tr>
                        <th scope="col">Patient</th>
                        <th scope="col">Arrival</th>
                        <th scope="col">Triage (demo label)</th>
                        <th scope="col">Chief complaint</th>
                        <th scope="col">Status</th>
                        <th scope="col">Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {visits.map((visit) => {
                        const targets = transitionsFor(visit.status);
                        return (
                          <tr key={visit.id}>
                            <td>{patientNameById.get(visit.patientId) ?? 'Unknown record'}</td>
                            <td>{visit.arrivalAt}</td>
                            <td>
                              <span
                                className="triage-badge"
                                title="Neutral demo triage label — no clinical meaning"
                              >
                                {visit.triageLevel}
                              </span>
                            </td>
                            <td>{visit.chiefComplaint}</td>
                            <td>
                              <span className={`status-badge status-${String(visit.status).toLowerCase()}`}>
                                {visit.status}
                              </span>
                            </td>
                            <td className="emergency-row-actions">
                              {canTransition
                                && targets.map((target) => {
                                  const pendingHere = confirmPending?.id === visit.id && confirmPending.target === target;
                                  const busyHere = transitioning?.id === visit.id && transitioning.target === target;
                                  const actionLabel = target === 'CLOSED' ? 'Close visit' : 'Start treatment';
                                  const confirmLabel = target === 'CLOSED' ? 'Confirm close' : 'Confirm start treatment';
                                  return pendingHere ? (
                                    <React.Fragment key={target}>
                                      <button
                                        type="button"
                                        className="emergency-transition-confirm"
                                        disabled={Boolean(transitioning)}
                                        onClick={() => confirmTransition(visit.id, target)}
                                      >
                                        {busyHere ? 'Recording…' : confirmLabel}
                                      </button>
                                      <button
                                        type="button"
                                        className="emergency-transition-cancel"
                                        disabled={Boolean(transitioning)}
                                        onClick={cancelTransition}
                                      >
                                        Cancel
                                      </button>
                                    </React.Fragment>
                                  ) : (
                                    <button
                                      key={target}
                                      type="button"
                                      className={`emergency-transition emergency-transition-${target.toLowerCase()}`}
                                      onClick={() => requestTransition(visit.id, target)}
                                    >
                                      {actionLabel}
                                    </button>
                                  );
                                })}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </>
          )}
        </>
      )}

      {view === 'form' && (
        <EmergencyVisitForm
          session={session}
          patients={patients}
          onCreated={handleRegistered}
          onCancel={handleFormCancelled}
          onSessionExpired={onSessionExpired}
        />
      )}
    </section>
  );
}
