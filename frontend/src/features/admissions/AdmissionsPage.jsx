import React, { useEffect, useState } from 'react';
import { ApiError } from '../../api.js';
import { can } from '../../authorization.js';
import { fetchPatients } from '../patients/patientApi.js';
import { fetchBeds } from '../beds/bedApi.js';
import { assignAdmissionBed, dischargeAdmission, fetchAdmissions } from './admissionApi.js';
import { AdmissionForm } from './AdmissionForm.jsx';
import AdmissionsTable from './AdmissionsTable.jsx';

// Admissions screen (docs/plan2.md Task 2, extended by docs/plan3.md
// Task 7): the admissions list over GET /api/admissions, the registration
// flow over POST /api/admissions, the atomic bed assignment/transfer over
// PUT /api/admissions/{id}/bed, and the deliberate discharge flow over
// PUT /api/admissions/{id}/status (the server releases a held bed on that
// transition). The list resolves patient names against the loaded records
// and renders honest "Unknown record" placeholders for references it cannot
// resolve — raw reference values are never displayed. Every active admission
// shows its held bed or an explicit "No bed assigned"; assignment and
// transfer are two-step confirmations whose target choices contain only the
// beds the server reports AVAILABLE, never the currently held bed. Nothing
// is patched optimistically: after every successful create, assignment,
// transfer, or discharge the admissions list AND the branch bed inventory
// are refetched so both views reflect server truth; a failed command
// changes nothing on screen. All transport goes through the feature adapter
// — components never call fetch directly. The page owns the authoritative
// state and every mutation; the table (AdmissionsTable.jsx) and the
// registration form (AdmissionForm.jsx) are presentation only.
export default function AdmissionsPage({ session, onSessionExpired }) {
  const [patients, setPatients] = useState([]);
  const [admissions, setAdmissions] = useState([]);
  const [beds, setBeds] = useState([]);
  const [status, setStatus] = useState('loading');
  const [loadError, setLoadError] = useState('');
  // 'list' | 'form'
  const [view, setView] = useState('list');
  const [confirmation, setConfirmation] = useState('');
  // Bumped after a successful mutation so the admissions list, the loaded
  // patients, and the branch bed inventory all refetch instead of showing a
  // result set that cannot contain the new state.
  const [listRefresh, setListRefresh] = useState(0);
  // The id of the admission awaiting its discharge confirmation, or null.
  const [dischargePendingId, setDischargePendingId] = useState(null);
  // The id of the admission whose discharge request is in flight, or null.
  const [dischargingId, setDischargingId] = useState(null);
  const [dischargeError, setDischargeError] = useState('');
  // The id of the admission with an open bed command (assignment when it
  // holds no bed, transfer when it holds one), or null.
  const [bedCommandId, setBedCommandId] = useState(null);
  // The selected target bed id inside the open bed command.
  const [bedTarget, setBedTarget] = useState('');
  // Whether a bed command request is in flight.
  const [bedBusy, setBedBusy] = useState(false);
  const [bedValidationError, setBedValidationError] = useState('');
  const [bedCommandError, setBedCommandError] = useState('');

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
    const loadBeds = fetchBeds({
      token: session.token,
      onUnauthorized: onSessionExpired,
    });
    Promise.all([loadPatients, loadAdmissions, loadBeds])
      .then(([loadedPatients, loadedAdmissions, loadedBeds]) => {
        if (!active) return;
        setPatients(Array.isArray(loadedPatients) ? loadedPatients : []);
        setAdmissions(Array.isArray(loadedAdmissions) ? loadedAdmissions : []);
        setBeds(Array.isArray(loadedBeds) ? loadedBeds : []);
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
    setBedCommandError('');
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
    // Server-side state cannot be honestly patched locally: refetch the
    // admissions list and the bed inventory (the optional create bed may
    // now be OCCUPIED).
    setListRefresh((n) => n + 1);
  }

  function requestDischarge(admissionId) {
    setConfirmation('');
    setDischargeError('');
    setBedValidationError('');
    setBedCommandError('');
    setBedCommandId(null);
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
      // Server-side state cannot be honestly patched locally: refetch the
      // admissions list and the bed inventory (a released bed is AVAILABLE
      // again only there).
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

  function requestBedCommand(admissionId) {
    setConfirmation('');
    setDischargeError('');
    setBedValidationError('');
    setBedCommandError('');
    setBedTarget('');
    setDischargePendingId(null);
    setBedCommandId(admissionId);
  }

  function cancelBedCommand() {
    setBedCommandId(null);
    setBedTarget('');
    setBedValidationError('');
    setBedCommandError('');
  }

  // Selection inside the open bed command: remember the choice and clear
  // any stale validation error, exactly as before the extraction.
  function handleBedTargetChange(value) {
    setBedTarget(value);
    setBedValidationError('');
  }

  async function confirmBedCommand(admission) {
    if (bedBusy) return;
    if (!bedTarget) {
      setBedValidationError('Select an available bed.');
      return;
    }
    setBedBusy(true);
    setBedCommandError('');
    try {
      // The same narrow command performs assignment and transfer; the
      // response body is deliberately not patched into the list — the
      // refetch below is the only source of the new state.
      await assignAdmissionBed({
        token: session.token,
        id: admission.id,
        bedId: bedTarget,
        onUnauthorized: onSessionExpired,
      });
      const wasTransfer = Boolean(admission.currentBed);
      setBedCommandId(null);
      setBedTarget('');
      setConfirmation(wasTransfer ? 'Bed transferred.' : 'Bed assigned.');
      // Refetch admissions AND beds so both views show server truth (the
      // previous bed — on a transfer — is AVAILABLE again only there).
      setListRefresh((n) => n + 1);
    } catch (error) {
      // 401 is ownership of the shell: no local error on top of it. Any
      // other failure (403/404/409/network) keeps the confirmation open
      // with the selection intact and the row untouched.
      if (error instanceof ApiError && error.status === 401) return;
      setBedCommandError(
        error instanceof ApiError ? error.message : 'The bed could not be assigned.'
      );
    } finally {
      setBedBusy(false);
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

              {bedCommandError && (
                <p className="notice error" role="alert">{bedCommandError}</p>
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
                <AdmissionsTable
                  admissions={admissions}
                  beds={beds}
                  patientNameById={patientNameById}
                  canDischarge={canDischarge}
                  bedCommandId={bedCommandId}
                  bedTarget={bedTarget}
                  bedBusy={bedBusy}
                  bedValidationError={bedValidationError}
                  dischargePendingId={dischargePendingId}
                  dischargingId={dischargingId}
                  onRequestBedCommand={requestBedCommand}
                  onCancelBedCommand={cancelBedCommand}
                  onConfirmBedCommand={confirmBedCommand}
                  onBedTargetChange={handleBedTargetChange}
                  onRequestDischarge={requestDischarge}
                  onCancelDischarge={cancelDischarge}
                  onConfirmDischarge={confirmDischarge}
                />
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
