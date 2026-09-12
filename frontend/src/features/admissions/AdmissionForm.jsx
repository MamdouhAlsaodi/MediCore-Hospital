import React, { useEffect, useState } from 'react';
import { ApiError } from '../../api.js';
import { fetchBeds } from '../beds/bedApi.js';
import { createAdmission } from './admissionApi.js';

// Human label for a branch bed DTO — ward, room, and bed number only; the
// raw bed id is never displayed.
export function bedLabel(bed) {
  return `Bed ${bed.bedNumber} — ${bed.ward} · ${bed.room}`;
}

// Only beds the server currently reports AVAILABLE may ever be offered as
// an assignment/transfer target; OCCUPIED, MAINTENANCE, and OUT_OF_SERVICE
// are never selectable, and a currently held bed is filtered by the caller.
export function availableBeds(beds, excludeBedId = null) {
  return (Array.isArray(beds) ? beds : [])
    .filter((bed) => bed.occupancyStatus === 'AVAILABLE' && bed.id !== excludeBedId);
}

// Registration form (docs/plan2.md Task 2, extended by docs/plan3.md Task 7).
// Patients arrive as already-loaded domain records (the host screen's list or
// the single preselected record from the patient detail view); there is no
// free-typed reference anywhere. The form loads the branch bed inventory
// itself through the feature adapter and offers ONLY the beds the server
// reports AVAILABLE; selecting none keeps the exact three-field create
// contract. The submitted body mirrors CreateAdmissionRequest exactly —
// patientId, admittedAt, reason, optional bedId — because the server owns
// the lifecycle: it sets status=ADMITTED, stamps the discharge, and validates
// bed availability. Server errors (including the 409 bed conflict) render
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
  const [bedId, setBedId] = useState('');
  const [beds, setBeds] = useState([]);
  // 'loading' | 'ready' | 'error'
  const [bedsState, setBedsState] = useState('loading');
  const [pending, setPending] = useState(false);
  const [validationError, setValidationError] = useState('');
  const [serverError, setServerError] = useState('');

  useEffect(() => {
    let active = true;
    setBedsState('loading');
    fetchBeds({ token: session.token, onUnauthorized: onSessionExpired })
      .then((loaded) => {
        if (!active) return;
        setBeds(Array.isArray(loaded) ? loaded : []);
        setBedsState('ready');
      })
      .catch((error) => {
        if (!active) return;
        // 401 is ownership of the shell: the session-expiry callback returns
        // the app to Login, so no local state is raised on top of it. Any
        // other failure only disables the OPTIONAL bed choice — the
        // registration itself stays possible without a bed.
        if (error instanceof ApiError && error.status === 401) return;
        setBedsState('error');
      });
    return () => { active = false; };
  }, [session.token, onSessionExpired]);

  const selectableBeds = availableBeds(beds);

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
      const admission = { patientId: patient.id, admittedAt, reason: trimmedReason };
      // bedId is added only when a bed was selected; with no selection the
      // body stays the exact three-field contract of Task 2.
      if (bedId) admission.bedId = bedId;
      const created = await createAdmission({
        token: session.token,
        admission,
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
        date and time and describe the reason. A bed is optional: only the
        beds this branch reports AVAILABLE are offered, and the server
        confirms the assignment. No identifiers are typed by hand, and the
        server owns the admission status and the discharge.
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

        <div className="admission-field">
          <label htmlFor="admission-bed">Bed (optional)</label>
          {bedsState === 'error' ? (
            <select id="admission-bed" value="" disabled aria-label="Bed (optional)">
              <option value="">Bed list unavailable — the admission can be registered without a bed</option>
            </select>
          ) : (
            <select
              id="admission-bed"
              value={bedId}
              disabled={bedsState === 'loading'}
              onChange={(event) => setBedId(event.target.value)}
            >
              <option value="">{bedsState === 'loading' ? 'Loading beds…' : 'No bed at registration'}</option>
              {selectableBeds.map((bed) => (
                <option key={bed.id} value={bed.id}>{bedLabel(bed)}</option>
              ))}
            </select>
          )}
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
