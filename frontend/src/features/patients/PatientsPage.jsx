import React, { useEffect, useState } from 'react';
import { ApiError } from '../../api.js';
import { fetchPatients } from './patientApi.js';

// Patients screen (plan1.md Task 6): read-only patient list and search over
// GET /api/patients?q=... . This screen never creates or edits records, never
// fabricates fallback data, and never implies clinical decision support;
// registration, detail, and editing arrive in Task 7. All transport goes
// through the patientApi adapter — components never call fetch.
export default function PatientsPage({ session, onSessionExpired }) {
  const [inputValue, setInputValue] = useState('');
  const [submittedQuery, setSubmittedQuery] = useState('');
  const [status, setStatus] = useState('loading');
  const [patients, setPatients] = useState([]);
  const [loadError, setLoadError] = useState('');
  const [selected, setSelected] = useState(null);

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
  }, [session.token, submittedQuery, onSessionExpired]);

  function handleSearch(event) {
    event.preventDefault();
    // A different submitted query starts a new result context: drop any
    // selected-patient summary first so stale identity data cannot remain
    // beside results it was not chosen from.
    if (inputValue !== submittedQuery) {
      setSelected(null);
      setSubmittedQuery(inputValue);
    }
  }

  const showList = status === 'ready' && !loadError && patients.length > 0;
  const showEmpty = status === 'ready' && !loadError && patients.length === 0;

  return (
    <section className="patients-screen" aria-label="Patients screen">
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
                onClick={() => setSelected(patient)}
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

      {selected && (
        <section className="panel patient-summary" aria-label="Selected patient">
          <h3>Selected patient</h3>
          <dl className="patient-facts">
            <dt>Medical record number</dt><dd>{selected.medicalRecordNumber}</dd>
            <dt>Full name</dt><dd>{selected.fullName}</dd>
            <dt>Date of birth</dt><dd>{selected.dateOfBirth || '—'}</dd>
            <dt>Sex</dt><dd>{selected.sex || '—'}</dd>
            <dt>Phone</dt><dd>{selected.phone || '—'}</dd>
            <dt>Email</dt><dd>{selected.email || '—'}</dd>
            <dt>Address</dt><dd>{selected.address || '—'}</dd>
            <dt>Status</dt><dd>{selected.active ? 'Active' : 'Inactive'}</dd>
          </dl>
          <p className="panel-hint">Read-only view — registration and editing arrive in Task 7.</p>
        </section>
      )}
    </section>
  );
}
