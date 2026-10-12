import React, { useEffect, useState } from 'react';
import { ApiError } from '../../api.js';
import { requestTransfer } from './transferApi.js';
import { fetchNetworkHierarchy } from '../network/networkApi.js';

const REASONS = [
  { code: 'BED_SHORTAGE', label: 'Bed shortage' },
  { code: 'SPECIALIST_CARE', label: 'Specialist care' },
  { code: 'EQUIPMENT_LIMITATION', label: 'Equipment limitation' },
  { code: 'ISOLATION_CAPACITY', label: 'Isolation capacity' },
  { code: 'PATIENT_PREFERENCE', label: 'Patient preference' },
];

function newKey() {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  let key = 'ui-';
  for (let i = 0; i < 24; i += 1) {
    key += alphabet[Math.floor(Math.random() * alphabet.length)];
  }
  return key;
}

export default function TransferForm({ session, onSessionExpired, onRequested }) {
  const [patients, setPatients] = useState([]);
  const [admissions, setAdmissions] = useState([]);
  const [hospitals, setHospitals] = useState([]);
  const [patientId, setPatientId] = useState('');
  const [admissionId, setAdmissionId] = useState('');
  const [destinationId, setDestinationId] = useState('');
  const [reasonCode, setReasonCode] = useState('');
  const [pending, setPending] = useState(false);
  const [validationError, setValidationError] = useState('');
  const [serverError, setServerError] = useState('');
  const [success, setSuccess] = useState(false);
  const [open, setOpen] = useState(false);

  const loadOptions = () => {
    let active = true;
    if (!open) return undefined;
    const headers = { Authorization: 'Bearer ' + session.token };
    fetch('/api/patients', { headers })
      .then((response) => (response.ok ? response.json() : []))
      .then((rows) => { if (active) setPatients(Array.isArray(rows) ? rows : []); })
      .catch(() => { if (active) setPatients([]); });
    fetch('/api/admissions', { headers })
      .then((response) => (response.ok ? response.json() : []))
      .then((rows) => {
        if (!active) return;
        setAdmissions((Array.isArray(rows) ? rows : []).filter((admission) => admission.status === 'ADMITTED'));
      })
      .catch(() => { if (active) setAdmissions([]); });
    fetchNetworkHierarchy({ token: session.token, onUnauthorized: onSessionExpired })
      .then((hierarchy) => {
        if (!active) return;
        setHospitals(hierarchy.hospitals.filter((hospital) => hospital.id !== session.actingContext?.hospitalId));
      })
      .catch(() => { if (active) setHospitals([]); });
    return () => { active = false; };
  };

  useEffect(loadOptions, [open, session.token]); // eslint-disable-line react-hooks/exhaustive-deps

  const submit = async (event) => {
    event.preventDefault();
    setValidationError('');
    setServerError('');
    setSuccess(false);
    if (!patientId || !admissionId || !destinationId || !reasonCode) {
      setValidationError('Select the patient, admission, destination hospital, and reason.');
      return;
    }
    setPending(true);
    try {
      await requestTransfer({
        token: session.token,
        idempotencyKey: newKey(),
        transfer: {
          patientId,
          sourceAdmissionId: admissionId,
          destinationHospitalId: destinationId,
          reasonCode,
        },
        onUnauthorized: onSessionExpired,
      });
      setSuccess(true);
      setPatientId(''); setAdmissionId(''); setDestinationId(''); setReasonCode('');
      onRequested();
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) return;
      setServerError(error instanceof ApiError ? error.message : 'The transfer could not be requested.');
    } finally {
      setPending(false);
    }
  };

  return (
    <section>
      <button type="button" className="transfer-new" onClick={() => setOpen((value) => !value)}>
        {open ? 'Close request form' : 'Request transfer'}
      </button>
      {open && (
        <form className="panel transfer-form" aria-label="Request a transfer" onSubmit={submit} aria-busy={pending}>
          <h3>Request a transfer</h3>
          <p className="panel-hint">
            The source hospital is this hospital: the server derives it from your
            acting context, never from this form. Pick the admitted patient, the
            holding admission, and the destination hospital.
          </p>
          {validationError && <p className="notice error" role="alert">{validationError}</p>}
          {serverError && <p className="notice error" role="alert">{serverError}</p>}
          {success && <p className="notice" role="status">Transfer requested.</p>}
          <div className="transfer-form-grid">
            <label htmlFor="transfer-patient">Patient</label>
            <select id="transfer-patient" value={patientId} onChange={(e) => setPatientId(e.target.value)}>
              <option value="">Select a patient</option>
              {patients.map((patient) => (
                <option key={patient.id} value={patient.id}>
                  {patient.fullName} (MRN {patient.medicalRecordNumber})
                </option>
              ))}
            </select>
            <label htmlFor="transfer-admission">Admission</label>
            <select id="transfer-admission" value={admissionId} onChange={(e) => setAdmissionId(e.target.value)}>
              <option value="">Select an open admission</option>
              {admissions.map((admission) => (
                <option key={admission.id} value={admission.id}>
                  Admitted {admission.admittedAt}
                </option>
              ))}
            </select>
            <label htmlFor="transfer-destination">Destination hospital</label>
            <select id="transfer-destination" value={destinationId} onChange={(e) => setDestinationId(e.target.value)}>
              <option value="">Select a destination</option>
              {hospitals.map((hospital) => (
                <option key={hospital.id} value={hospital.id}>{hospital.name}</option>
              ))}
            </select>
            <label htmlFor="transfer-reason">Reason</label>
            <select id="transfer-reason" value={reasonCode} onChange={(e) => setReasonCode(e.target.value)}>
              <option value="">Select a reason</option>
              {REASONS.map((reason) => (
                <option key={reason.code} value={reason.code}>{reason.label}</option>
              ))}
            </select>
          </div>
          <div className="transfer-form-actions">
            <button type="submit" className="transfer-save" disabled={pending}>Submit transfer request</button>
          </div>
        </form>
      )}
    </section>
  );
}
