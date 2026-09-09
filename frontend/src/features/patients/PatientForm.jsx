import React, { useState } from 'react';
import { ApiError } from '../../api.js';
import { createPatient, updatePatient } from './patientApi.js';

// Registration-permitted roles (UI convenience only; the backend
// authorization policy stays authoritative for every request).
const EDIT_ROLES = ['RECEPTIONIST', 'ADMIN'];

// Client-side mirror of PatientDtos: @NotBlank on medicalRecordNumber/fullName
// (create) and @Email on email. No other client constraints are invented.
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const EMPTY_DRAFT = {
  medicalRecordNumber: '',
  fullName: '',
  dateOfBirth: '',
  sex: '',
  phone: '',
  email: '',
  nationalId: '',
  address: '',
};

function hasAnyRole(roles, wanted) {
  return Array.isArray(roles) && wanted.some((role) => roles.includes(role));
}

function draftFor(requestedMode, patient) {
  if (!patient) return { ...EMPTY_DRAFT };
  if (requestedMode === 'edit') {
    return {
      ...EMPTY_DRAFT,
      // Display-only: the update contract never sends the MRN.
      medicalRecordNumber: patient.medicalRecordNumber ?? '',
      fullName: patient.fullName ?? '',
      phone: patient.phone ?? '',
      email: patient.email ?? '',
      address: patient.address ?? '',
    };
  }
  return {
    medicalRecordNumber: patient.medicalRecordNumber ?? '',
    fullName: patient.fullName ?? '',
    dateOfBirth: patient.dateOfBirth ?? '',
    sex: patient.sex ?? '',
    phone: patient.phone ?? '',
    email: patient.email ?? '',
    nationalId: patient.nationalId ?? '',
    address: patient.address ?? '',
  };
}

// mode: 'create' registers via POST /api/patients, 'edit' updates the existing
// record via PUT /api/patients/{id}, and 'view' renders the same form
// read-only (e.g. for roles that may inspect but not change records).
export default function PatientForm({
  session,
  mode = 'create',
  patient = null,
  onSuccess,
  onCancel,
  onSessionExpired,
}) {
  const requestedMode = mode === 'edit' || mode === 'view' ? mode : 'create';
  const canEdit = hasAnyRole(session?.roles, EDIT_ROLES);
  // 'edit' without an edit-permitted role degrades to the read-only view;
  // nothing is ever submitted from a read-only form.
  const readOnly = requestedMode === 'view' || (requestedMode === 'edit' && !canEdit);

  const [draft, setDraft] = useState(() => draftFor(requestedMode, patient));
  const [errors, setErrors] = useState({});
  const [serverError, setServerError] = useState('');
  const [pending, setPending] = useState(false);

  function setField(name, value) {
    setDraft((prev) => ({ ...prev, [name]: value }));
  }

  function validate() {
    const next = {};
    if (requestedMode === 'create' && !draft.medicalRecordNumber.trim()) {
      next.medicalRecordNumber = 'Medical record number is required.';
    }
    if (!draft.fullName.trim()) {
      next.fullName = 'Full name is required.';
    }
    const email = draft.email.trim();
    if (email && !EMAIL_PATTERN.test(email)) {
      next.email = 'Enter a valid email address.';
    }
    return next;
  }

  async function handleSubmit(event) {
    event.preventDefault();
    if (readOnly || pending) return;

    const nextErrors = validate();
    setErrors(nextErrors);
    setServerError('');
    if (Object.keys(nextErrors).length > 0) return;

    if (requestedMode === 'create') {
      setPending(true);
      try {
        const saved = await createPatient({
          token: session.token,
          onUnauthorized: onSessionExpired,
          patient: {
            medicalRecordNumber: draft.medicalRecordNumber.trim(),
            fullName: draft.fullName.trim(),
            dateOfBirth: draft.dateOfBirth || null,
            sex: draft.sex.trim(),
            phone: draft.phone.trim(),
            email: draft.email.trim(),
            nationalId: draft.nationalId.trim(),
            address: draft.address.trim(),
          },
        });
        onSuccess(saved);
      } catch (error) {
        handleRequestError(error);
      } finally {
        setPending(false);
      }
      return;
    }

    if (!patient?.id) return;
    setPending(true);
    try {
      const saved = await updatePatient({
        token: session.token,
        id: patient.id,
        onUnauthorized: onSessionExpired,
        changes: {
          fullName: draft.fullName.trim(),
          phone: draft.phone.trim(),
          email: draft.email.trim(),
          address: draft.address.trim(),
        },
      });
      onSuccess(saved);
    } catch (error) {
      handleRequestError(error);
    } finally {
      setPending(false);
    }
  }

  function handleRequestError(error) {
    // 401 is ownership of the shell: the session-expiry callback returns the
    // app to Login, so no local error is raised on top of it.
    if (error instanceof ApiError && error.status === 401) return;
    setServerError(
      error instanceof ApiError ? error.message : 'The request failed. Please try again.'
    );
  }

  const showIdentityFields = requestedMode !== 'edit';
  const heading =
    requestedMode === 'create'
      ? 'Register patient'
      : readOnly
        ? 'Patient record form (read-only)'
        : 'Edit patient record';

  function renderField(name, label, { type = 'text', disabled = false } = {}) {
    const error = errors[name];
    const inputId = `patient-${name}`;
    return (
      <div className={`patient-field${error ? ' has-error' : ''}`}>
        <label htmlFor={inputId}>{label}</label>
        <input
          id={inputId}
          name={name}
          type={type}
          value={draft[name]}
          disabled={disabled || readOnly}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? `${inputId}-error` : undefined}
          onChange={(event) => setField(name, event.target.value)}
        />
        {error && (
          <p className="field-error" id={`${inputId}-error`}>{error}</p>
        )}
      </div>
    );
  }

  return (
    <section className="panel patient-form" aria-label="Patient form">
      <h3>{heading}</h3>
      {readOnly && (
        <p className="panel-hint">
          Read-only view — your role can inspect this record form but not change it.
        </p>
      )}
      {serverError && (
        <p className="notice error" role="alert">{serverError}</p>
      )}
      <form noValidate onSubmit={handleSubmit}>
        <div className="patient-form-grid">
          {renderField('medicalRecordNumber', 'Medical record number', {
            disabled: requestedMode === 'edit',
          })}
          {renderField('fullName', 'Full name')}
          {showIdentityFields && renderField('dateOfBirth', 'Date of birth', { type: 'date' })}
          {showIdentityFields && renderField('sex', 'Sex')}
          {renderField('phone', 'Phone')}
          {renderField('email', 'Email', { type: 'email' })}
          {showIdentityFields && renderField('nationalId', 'National ID')}
          {renderField('address', 'Address')}
        </div>
        <div className="patient-form-actions">
          {!readOnly && (
            <button
              type="submit"
              className="patient-save"
              disabled={pending}
              aria-busy={pending}
            >
              {pending ? 'Saving…' : requestedMode === 'edit' ? 'Save changes' : 'Save patient'}
            </button>
          )}
          <button
            type="button"
            className="patient-cancel"
            onClick={onCancel}
            disabled={pending}
          >
            {readOnly ? 'Back to detail' : 'Cancel'}
          </button>
        </div>
      </form>
    </section>
  );
}
