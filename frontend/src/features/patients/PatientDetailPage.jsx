import React, { useState } from 'react';
import { can } from '../../authorization.js';
import { AdmissionForm } from '../admissions/AdmissionForm.jsx';
import AppointmentForm from '../appointments/AppointmentForm.jsx';

// Read-only detail view of the selected patient (plan1.md Tasks 7-8). It
// reuses the Task 6 data-minimized summary fields: National ID stays in the
// backend DTO but is deliberately never rendered here. The Edit action is
// role-permitted and hands over to the PatientForm via onEdit('edit'|'view').
// The Schedule action (Task 8) and the Register admission action (plan2.md
// Task 2) are role-permitted and open their form in place with this patient
// preselected — no raw identifiers, and the host list does not need to
// change.
export default function PatientDetailPage({
  patient,
  session,
  confirmation = '',
  onEdit,
  onClose,
  onSessionExpired,
}) {
  // UI convenience hints from the shared permission map; backend stays
  // authoritative for every request.
  const canEdit = can(session, 'update', 'patient');
  const canViewForm = can(session, 'formView', 'patient');
  const canSchedule = can(session, 'create', 'appointment');
  const canAdmit = can(session, 'create', 'admission');
  const [scheduling, setScheduling] = useState(false);
  const [scheduleConfirmation, setScheduleConfirmation] = useState('');
  const [admitting, setAdmitting] = useState(false);
  const [admissionConfirmation, setAdmissionConfirmation] = useState('');

  function handleScheduled() {
    setScheduling(false);
    setScheduleConfirmation('Appointment scheduled.');
  }

  function handleAdmitted() {
    setAdmitting(false);
    setAdmissionConfirmation('Admission registered.');
  }

  const hint = canEdit
    ? 'Read-only detail view — choose Edit record to update contact details.'
    : canViewForm
      ? 'Read-only detail view — you can inspect the record form but not change it.'
      : 'Read-only detail view — your role does not include record changes.';

  return (
    <section className="panel patient-detail" aria-label="Selected patient">
      <h3>Selected patient</h3>
      {confirmation && (
        <p className="notice success" role="status">{confirmation}</p>
      )}
      {scheduleConfirmation && !confirmation && (
        <p className="notice success" role="status">{scheduleConfirmation}</p>
      )}
      {admissionConfirmation && !confirmation && (
        <p className="notice success" role="status">{admissionConfirmation}</p>
      )}
      <dl className="patient-facts">
        <dt>Medical record number</dt><dd>{patient.medicalRecordNumber}</dd>
        <dt>Full name</dt><dd>{patient.fullName}</dd>
        <dt>Date of birth</dt><dd>{patient.dateOfBirth || '—'}</dd>
        <dt>Sex</dt><dd>{patient.sex || '—'}</dd>
        <dt>Phone</dt><dd>{patient.phone || '—'}</dd>
        <dt>Email</dt><dd>{patient.email || '—'}</dd>
        <dt>Address</dt><dd>{patient.address || '—'}</dd>
        <dt>Status</dt><dd>{patient.active ? 'Active' : 'Inactive'}</dd>
      </dl>
      <p className="panel-hint">{hint}</p>

      {admitting ? (
        <AdmissionForm
          session={session}
          patients={[patient]}
          preselectedPatientId={patient.id}
          onCreated={handleAdmitted}
          onCancel={() => setAdmitting(false)}
          onSessionExpired={onSessionExpired}
        />
      ) : scheduling ? (
        <AppointmentForm
          session={session}
          patients={[patient]}
          preselectedPatientId={patient.id}
          onCreated={handleScheduled}
          onCancel={() => setScheduling(false)}
          onSessionExpired={onSessionExpired}
        />
      ) : (
        <div className="patient-detail-actions">
          {canAdmit && (
            <button
              type="button"
              className="patient-action"
              onClick={() => {
                setAdmissionConfirmation('');
                setScheduleConfirmation('');
                setAdmitting(true);
              }}
            >
              Register admission
            </button>
          )}
          {canSchedule && (
            <button
              type="button"
              className="patient-action"
              onClick={() => {
                setScheduleConfirmation('');
                setAdmissionConfirmation('');
                setScheduling(true);
              }}
            >
              Schedule appointment
            </button>
          )}
          {canEdit && (
            <button
              type="button"
              className="patient-action"
              onClick={() => onEdit('edit')}
            >
              Edit record
            </button>
          )}
          {canViewForm && !canEdit && (
            <button
              type="button"
              className="patient-action"
              onClick={() => onEdit('view')}
            >
              View record form
            </button>
          )}
          <button type="button" className="patient-back" onClick={onClose}>
            Back to patient list
          </button>
        </div>
      )}
    </section>
  );
}
