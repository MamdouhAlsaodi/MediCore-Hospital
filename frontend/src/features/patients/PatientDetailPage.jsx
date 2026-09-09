import React, { useState } from 'react';
import AppointmentForm from '../appointments/AppointmentForm.jsx';

// Role contract for record actions (UI convenience only; backend
// authorization stays authoritative for every request):
//   - RECEPTIONIST / ADMIN may edit a record (editable form) and schedule
//     appointments for the selected patient.
//   - DOCTOR may inspect the record form, read-only.
//   - Other roles (e.g. NURSE) get the detail view without form actions.
const EDIT_ROLES = ['RECEPTIONIST', 'ADMIN'];
const FORM_VIEW_ROLES = [...EDIT_ROLES, 'DOCTOR'];
const SCHEDULE_ROLES = ['RECEPTIONIST', 'ADMIN'];

function hasAnyRole(roles, wanted) {
  return Array.isArray(roles) && wanted.some((role) => roles.includes(role));
}

// Read-only detail view of the selected patient (plan1.md Tasks 7-8). It
// reuses the Task 6 data-minimized summary fields: National ID stays in the
// backend DTO but is deliberately never rendered here. The Edit action is
// role-permitted and hands over to the PatientForm via onEdit('edit'|'view').
// The Schedule action (Task 8) is role-permitted and opens the appointment
// form in place with this patient preselected — no raw identifiers, and the
// host list does not need to change.
export default function PatientDetailPage({
  patient,
  session,
  confirmation = '',
  onEdit,
  onClose,
  onSessionExpired,
}) {
  const roles = session?.roles;
  const canEdit = hasAnyRole(roles, EDIT_ROLES);
  const canViewForm = hasAnyRole(roles, FORM_VIEW_ROLES);
  const canSchedule = hasAnyRole(roles, SCHEDULE_ROLES);
  const [scheduling, setScheduling] = useState(false);
  const [scheduleConfirmation, setScheduleConfirmation] = useState('');

  function handleScheduled() {
    setScheduling(false);
    setScheduleConfirmation('Appointment scheduled.');
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

      {scheduling ? (
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
          {canSchedule && (
            <button
              type="button"
              className="patient-action"
              onClick={() => {
                setScheduleConfirmation('');
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
