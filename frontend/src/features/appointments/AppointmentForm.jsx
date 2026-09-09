import React, { useEffect, useState } from 'react';
import { ApiError } from '../../api.js';
import { fetchStaff, staffDisplayName } from '../staff/staffApi.js';
import { createAppointment } from './appointmentApi.js';

// Lowercase status contract from AppointmentDtos.CreateAppointmentRequest.
const STATUS_OPTIONS = ['scheduled', 'confirmed', 'completed', 'cancelled'];

// Scheduling form (plan1.md Task 8). Patients arrive as already-loaded domain
// records (the host screen's list or the single preselected record from the
// patient detail view); professionals load here from the staff directory
// adapter. The submitted IDs are always the selected domain records — there
// is no free-typed reference anywhere. Server errors render inline with zero
// field loss; 401 is ownership of the shell (session-expiry callback only).
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
  const [type, setType] = useState('');
  const [status, setStatus] = useState('scheduled');
  // null while the professional directory request is in flight.
  const [professionals, setProfessionals] = useState(null);
  const [professionalsError, setProfessionalsError] = useState('');
  const [pending, setPending] = useState(false);
  const [validationError, setValidationError] = useState('');
  const [serverError, setServerError] = useState('');

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

  const directoryUnavailable = Boolean(professionalsError);

  async function handleSubmit(event) {
    event.preventDefault();
    if (pending) return;
    setValidationError('');
    setServerError('');
    const patient = patients.find((record) => record.id === patientId);
    const professional = (professionals ?? []).find((record) => record.id === professionalId);
    const trimmedType = type.trim();
    if (!patient || !professional || !scheduledAt || !trimmedType
        || !STATUS_OPTIONS.includes(status)) {
      setValidationError(
        'Select a patient and a professional, and provide a date and time, a type, and a status.'
      );
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
        pick the date, time, type, and status. No identifiers are typed by hand.
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
