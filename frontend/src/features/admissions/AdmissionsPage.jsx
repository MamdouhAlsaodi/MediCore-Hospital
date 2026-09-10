import React, { useEffect, useState } from 'react';
import { ApiError } from '../../api.js';
import { can } from '../../authorization.js';
import { fetchPatients } from '../patients/patientApi.js';
import { createAdmission, dischargeAdmission, fetchAdmissions } from './admissionApi.js';

// Registration form (docs/plan2.md Task 2). Patients arrive as already-loaded
// domain records (the host screen's list or the single preselected record
// from the patient detail view); there is no free-typed reference anywhere.
// The submitted body mirrors CreateAdmissionRequest exactly — patientId,
// admittedAt, reason — because the server owns the lifecycle: it sets
// status=ADMITTED and stamps the discharge itself. Server errors render
// inline with zero field loss; 401 is ownership of the shell.
export function AdmissionForm({
  session,
  patients,
  preselectedPatientId = '',
  onCreated,
  onCancel,
  onSessionExpired,
}) {
  const [patientId, setPatientId] = useState(preselectedPatientId ?? '');
  const [admittedAt, setAdmittedAt] = useState('');
  const [reason, setReason] = useState('');
  const [pending, setPending] = useState(false);
  const [validationError, setValidationError] = useState('');
  const [serverError, setServerError] = useState('');

  async function handleSubmit(event) {
    event.preventDefault();
    if (pending) return;
    setValidationError('');
    setServerError('');
    const patient = patients.find((record) => record.id === patientId);
    const trimmedReason = reason.trim();
    if (!patient || !admittedAt || !trimmedReason) {
      setValidationError('Select a patient and provide the admission date and time and a reason.');
      return;
    }
    setPending(true);
    try {
      const created = await createAdmission({
        token: session.token,
        admission: { patientId: patient.id, admittedAt, reason: trimmedReason },
        onUnauthorized: onSessionExpired,
      });
      onCreated(created);
    } catch (error) {
      // 401 is ownership of the shell: no local error on top of it.
      if (error instanceof ApiError && error.status === 401) return;
      setServerError(
        error instanceof ApiError ? error.message : 'The admission could not be registered.'
      );
    } finally {
      setPending(false);
    }
  }

  return (
    <form
      className="panel admission-form"
      aria-label="Register an admission"
      aria-busy={pending}
      onSubmit={handleSubmit}
    >
      <h3>Register an admission</h3>
      <p className="panel-hint">
        Choose the patient from the loaded records, then pick the admission
        date and time and describe the reason. No identifiers are typed by
        hand, and the server owns the admission status and the discharge.
      </p>

      {validationError && (
        <p className="notice error" role="alert">{validationError}</p>
      )}
      {serverError && (
        <p className="notice error" role="alert">{serverError}</p>
      )}

      <div className="admission-form-grid">
        <div className="admission-field">
          <label htmlFor="admission-patient">Patient</label>
          <select
            id="admission-patient"
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

        <div className="admission-field">
          <label htmlFor="admission-admitted-at">Admitted at</label>
          <input
            id="admission-admitted-at"
            type="datetime-local"
            value={admittedAt}
            onChange={(event) => setAdmittedAt(event.target.value)}
          />
        </div>

        <div className="admission-field">
          <label htmlFor="admission-reason">Reason</label>
          <input
            id="admission-reason"
            type="text"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
        </div>
      </div>

      <div className="admission-form-actions">
        <button
          type="submit"
          className="admission-save"
          disabled={pending}
        >
          {pending ? 'Registering…' : 'Register admission'}
        </button>
        <button
          type="button"
          className="admission-cancel"
          onClick={onCancel}
          disabled={pending}
        >
          Cancel
        </button>
      </div>
    </form>
  );
}

// Admissions screen (docs/plan2.md Task 2): the admissions list over
// GET /api/admissions, the registration flow over POST /api/admissions, and
// the deliberate discharge flow over PUT /api/admissions/{id}/status. The
// list resolves patient names against the loaded records and renders honest
// "Unknown record" placeholders for references it cannot resolve — raw
// reference values are never displayed. Discharge is a two-step confirmation:
// nothing is sent until Confirm is clicked. All transport goes through the
// feature adapter — components never call fetch directly.
export default function AdmissionsPage({ session, onSessionExpired }) {
  const [patients, setPatients] = useState([]);
  const [admissions, setAdmissions] = useState([]);
  const [status, setStatus] = useState('loading');
  const [loadError, setLoadError] = useState('');
  // 'list' | 'form'
  const [view, setView] = useState('list');
  const [confirmation, setConfirmation] = useState('');
  // Bumped after a successful mutation so the list refetches instead of
  // showing a result set that cannot contain the new state.
  const [listRefresh, setListRefresh] = useState(0);
  // The id of the admission awaiting its discharge confirmation, or null.
  const [dischargePendingId, setDischargePendingId] = useState(null);
  // The id of the admission whose discharge request is in flight, or null.
  const [dischargingId, setDischargingId] = useState(null);
  const [dischargeError, setDischargeError] = useState('');

  useEffect(() => {
    let active = true;
    setStatus('loading');
    setLoadError('');
    const loadPatients = fetchPatients({
      token: session.token,
      onUnauthorized: onSessionExpired,
    });
    const loadAdmissions = fetchAdmissions({
      token: session.token,
      onUnauthorized: onSessionExpired,
    });
    Promise.all([loadPatients, loadAdmissions])
      .then(([loadedPatients, loadedAdmissions]) => {
        if (!active) return;
        setPatients(Array.isArray(loadedPatients) ? loadedPatients : []);
        setAdmissions(Array.isArray(loadedAdmissions) ? loadedAdmissions : []);
        setStatus('ready');
      })
      .catch((error) => {
        if (!active) return;
        // 401 is ownership of the shell: the session-expiry callback returns
        // the app to Login, so no local error is raised on top of it.
        if (error instanceof ApiError && error.status === 401) return;
        setLoadError(error instanceof ApiError ? error.message : 'Admissions could not be loaded.');
        setStatus('ready');
      });
    return () => { active = false; };
  }, [session.token, listRefresh, onSessionExpired]);

  function openForm() {
    setConfirmation('');
    setDischargeError('');
    setView('form');
  }

  function handleFormCancelled() {
    // Cancellation preserves nothing half-saved and returns to the list.
    setConfirmation('');
    setView('list');
  }

  function handleRegistered() {
    setConfirmation('Admission registered.');
    setView('list');
    // Server-side state cannot be honestly patched locally: refetch.
    setListRefresh((n) => n + 1);
  }

  function requestDischarge(admissionId) {
    setConfirmation('');
    setDischargeError('');
    setDischargePendingId(admissionId);
  }

  function cancelDischarge() {
    setDischargePendingId(null);
  }

  async function confirmDischarge(admissionId) {
    if (dischargingId) return;
    setDischargingId(admissionId);
    setDischargeError('');
    try {
      await dischargeAdmission({
        token: session.token,
        id: admissionId,
        onUnauthorized: onSessionExpired,
      });
      setDischargePendingId(null);
      setConfirmation('Admission discharged.');
      // Server-side state cannot be honestly patched locally: refetch.
      setListRefresh((n) => n + 1);
    } catch (error) {
      setDischargePendingId(null);
      // 401 is ownership of the shell: no local error on top of it.
      if (error instanceof ApiError && error.status === 401) return;
      setDischargeError(
        error instanceof ApiError ? error.message : 'The discharge could not be recorded.'
      );
    } finally {
      setDischargingId(null);
    }
  }

  // UI convenience hints from the shared permission map; backend stays
  // authoritative for every request (the server refuses with 403 and the
  // page shows that state).
  const canRegister = can(session, 'create', 'admission');
  const canDischarge = can(session, 'transition', 'admission');
  const patientNameById = new Map(patients.map((patient) => [patient.id, patient.fullName]));

  return (
    <section className="admissions-screen" aria-label="Admissions screen">
      {view === 'list' && (
        <>
          {status === 'loading' && (
            <p className="notice" role="status">Loading admissions…</p>
          )}

          {loadError && (
            <p className="notice error" role="alert">{loadError}</p>
          )}

          {status === 'ready' && !loadError && (
            <>
              {confirmation && (
                <p className="notice success" role="status">{confirmation}</p>
              )}

              {dischargeError && (
                <p className="notice error" role="alert">{dischargeError}</p>
              )}

              {canRegister && (
                <div className="admissions-actions">
                  <button
                    type="button"
                    className="admission-new"
                    onClick={openForm}
                  >
                    Register admission
                  </button>
                </div>
              )}

              {admissions.length === 0 ? (
                <div className="panel admissions-empty" role="status">
                  <h3>No admissions registered</h3>
                  <p className="panel-hint">
                    No admissions exist yet.{' '}
                    {canRegister
                      ? 'Use the register action above to admit an existing patient.'
                      : 'Admissions appear here once they are registered.'}
                  </p>
                </div>
              ) : (
                <div className="admissions-table-wrap">
                  <table className="admissions-table" aria-label="Registered admissions">
                    <thead>
                      <tr>
                        <th scope="col">Patient</th>
                        <th scope="col">Admitted at</th>
                        <th scope="col">Discharged at</th>
                        <th scope="col">Reason</th>
                        <th scope="col">Status</th>
                        <th scope="col">Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {admissions.map((admission) => (
                        <tr key={admission.id}>
                          <td>{patientNameById.get(admission.patientId) ?? 'Unknown record'}</td>
                          <td>{admission.admittedAt}</td>
                          <td>{admission.dischargedAt || '—'}</td>
                          <td>{admission.reason}</td>
                          <td>
                            <span className={`status-badge status-${String(admission.status).toLowerCase()}`}>
                              {admission.status}
                            </span>
                          </td>
                          <td className="admission-row-actions">
                            {canDischarge
                              && admission.status === 'ADMITTED'
                              && dischargePendingId !== admission.id && (
                              <button
                                type="button"
                                className="admission-discharge"
                                onClick={() => requestDischarge(admission.id)}
                              >
                                Discharge
                              </button>
                            )}
                            {dischargePendingId === admission.id && (
                              <>
                                <button
                                  type="button"
                                  className="admission-discharge-confirm"
                                  disabled={dischargingId === admission.id}
                                  onClick={() => confirmDischarge(admission.id)}
                                >
                                  {dischargingId === admission.id ? 'Discharging…' : 'Confirm discharge'}
                                </button>
                                <button
                                  type="button"
                                  className="admission-discharge-cancel"
                                  disabled={dischargingId === admission.id}
                                  onClick={cancelDischarge}
                                >
                                  Cancel
                                </button>
                              </>
                            )}
                          </td>
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
        <AdmissionForm
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
