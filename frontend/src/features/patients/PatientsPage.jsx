import React, { useEffect, useState } from 'react';
import { ApiError } from '../../api.js';
import { can } from '../../authorization.js';
import { fetchPatients } from './patientApi.js';
import PatientDetailPage from './PatientDetailPage.jsx';
import PatientForm from './PatientForm.jsx';

// Patients screen (plan1.md Tasks 6-7): patient list and search over
// GET /api/patients?q=..., plus the Task 7 record workflows — selecting a
// patient opens the read-only detail view, permitted roles register new
// records, and permitted roles edit the selected record. The screen never
// fabricates fallback data and never implies clinical decision support.
// All transport goes through the patientApi adapter — components never call
// fetch directly.
export default function PatientsPage({ session, onSessionExpired }) {
  const [inputValue, setInputValue] = useState('');
  const [submittedQuery, setSubmittedQuery] = useState('');
  const [status, setStatus] = useState('loading');
  const [patients, setPatients] = useState([]);
  const [loadError, setLoadError] = useState('');
  const [selected, setSelected] = useState(null);

  // 'list' | 'detail' | 'form' — the Task 7 record workflows replace the
  // Task 6 inline summary while the search/select/list behavior stays intact.
  const [view, setView] = useState('list');
  const [formMode, setFormMode] = useState('create');
  const [confirmation, setConfirmation] = useState('');
  // Bumped after a successful registration so the list refetches instead of
  // showing a stale result set that cannot contain the new patient.
  const [listRefresh, setListRefresh] = useState(0);

  useEffect(() => {
    let active = true;
    setStatus('loading');
    setLoadError('');
    setPatients([]);
    fetchPatients({
      token: session.token,
      query: submittedQuery,
      onUnauthorized: onSessionExpired,
    })
      .then((data) => {
        if (!active) return;
        setPatients(Array.isArray(data) ? data : []);
        setStatus('ready');
      })
      .catch((error) => {
        if (!active) return;
        // 401 is ownership of the shell: the session-expiry callback returns
        // the app to Login, so no local error is raised on top of it.
        if (error instanceof ApiError && error.status === 401) return;
        setLoadError(error instanceof ApiError ? error.message : 'Patients could not be loaded.');
        setStatus('ready');
      });
    return () => { active = false; };
  }, [session.token, submittedQuery, listRefresh, onSessionExpired]);

  function handleSearch(event) {
    event.preventDefault();
    // A different submitted query starts a new result context: close the
    // detail view and drop any selected-patient state first so stale identity
    // data cannot remain beside results it was not chosen from.
    if (inputValue !== submittedQuery) {
      setSelected(null);
      setView('list');
      setConfirmation('');
      setSubmittedQuery(inputValue);
    }
  }

  function handleSelect(patient) {
    setSelected(patient);
    setConfirmation('');
    setView('detail');
  }

  function openCreate() {
    setFormMode('create');
    setConfirmation('');
    setView('form');
  }

  function openForm(mode) {
    setFormMode(mode === 'view' ? 'view' : 'edit');
    setConfirmation('');
    setView('form');
  }

  function handleFormCancelled() {
    // Cancellation preserves nothing half-saved: a create returns to the
    // untouched list, an edit/view returns to the unchanged detail view.
    setConfirmation('');
    setView(formMode === 'create' ? 'list' : 'detail');
  }

  function handleSaved(saved) {
    setSelected(saved);
    setConfirmation(formMode === 'create' ? 'Patient registered successfully.' : 'Changes saved.');
    setView('detail');
    if (formMode === 'create') {
      setListRefresh((n) => n + 1);
    } else {
      // Edit: patch the loaded list in place so it reflects the saved record.
      setPatients((prev) => prev.map((p) => (p.id === saved.id ? saved : p)));
    }
  }

  function closeDetail() {
    setConfirmation('');
    setView('list');
  }

  // UI convenience hint from the shared permission map; backend stays
  // authoritative for every request.
  const canRegister = can(session, 'create', 'patient');
  const showList = view === 'list' && status === 'ready' && !loadError && patients.length > 0;
  const showEmpty = view === 'list' && status === 'ready' && !loadError && patients.length === 0;

  return (
    <section className="patients-screen" aria-label="Patients screen">
      {/* The search form (and the registration action) stay available beside
          the detail view so a new submitted search can always clear a stale
          selection; they hide only while a record form holds the screen so a
          draft cannot be discarded by accident. */}
      {view !== 'form' && (
        <>
          <form className="patient-search" role="search" onSubmit={handleSearch}>
            <div className="patient-search-field">
              <label htmlFor="patient-search-input">Search patients</label>
              <input
                id="patient-search-input"
                type="search"
                value={inputValue}
                onChange={(event) => setInputValue(event.target.value)}
              />
            </div>
            <button type="submit">Search</button>
          </form>

          {canRegister && (
            <div className="patients-actions">
              <button type="button" className="patient-new" onClick={openCreate}>
                New patient
              </button>
            </div>
          )}
        </>
      )}

      {view === 'list' && (
        <>
          {status === 'loading' && (
            <p className="notice" role="status">Loading patients…</p>
          )}

          {loadError && (
            <p className="notice error" role="alert">{loadError}</p>
          )}

          {showEmpty && (
            <div className="panel patients-empty" role="status">
              <h3>No patients found</h3>
              <p className="panel-hint">
                {submittedQuery
                  ? `No patients match “${submittedQuery}”. Try a different name or medical record number, or clear the search field and press Search to list all patients.`
                  : 'No patients are registered yet. Once records exist, use the search field above to find a patient by name or medical record number.'}
              </p>
            </div>
          )}

          {showList && (
            <ul className="patient-list" aria-label="Patient results">
              {patients.map((patient) => (
                <li key={patient.id}>
                  <button
                    type="button"
                    className="patient-row"
                    aria-pressed={selected?.id === patient.id}
                    onClick={() => handleSelect(patient)}
                  >
                    <span className="patient-name">{patient.fullName}</span>
                    <span className="patient-meta">
                      MRN {patient.medicalRecordNumber} · {patient.active ? 'Active' : 'Inactive'}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </>
      )}

      {view === 'detail' && selected && (
        <PatientDetailPage
          patient={selected}
          session={session}
          confirmation={confirmation}
          onEdit={openForm}
          onClose={closeDetail}
        />
      )}

      {view === 'form' && (
        <PatientForm
          session={session}
          mode={formMode}
          patient={formMode === 'create' ? null : selected}
          onSuccess={handleSaved}
          onCancel={handleFormCancelled}
          onSessionExpired={onSessionExpired}
        />
      )}
    </section>
  );
}
