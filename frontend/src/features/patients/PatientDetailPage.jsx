import React from 'react';

// Role contract for record actions (UI convenience only; backend
// authorization stays authoritative for every request):
//   - RECEPTIONIST / ADMIN may edit a record (editable form).
//   - DOCTOR may inspect the record form, read-only.
//   - Other roles (e.g. NURSE) get the detail view without a form action.
const EDIT_ROLES = ['RECEPTIONIST', 'ADMIN'];
const FORM_VIEW_ROLES = [...EDIT_ROLES, 'DOCTOR'];

function hasAnyRole(roles, wanted) {
  return Array.isArray(roles) && wanted.some((role) => roles.includes(role));
}

// Read-only detail view of the selected patient (plan1.md Task 7). It reuses
// the Task 6 data-minimized summary fields: National ID stays in the backend
// DTO but is deliberately never rendered here. The Edit action is
// role-permitted and hands over to the PatientForm via onEdit('edit'|'view').
export default function PatientDetailPage({
  patient,
  session,
  confirmation = '',
  onEdit,
  onClose,
}) {
  const roles = session?.roles;
  const canEdit = hasAnyRole(roles, EDIT_ROLES);
  const canViewForm = hasAnyRole(roles, FORM_VIEW_ROLES);

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
      <div className="patient-detail-actions">
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
    </section>
  );
}
