import React from 'react';
import { availableBeds, bedLabel } from './AdmissionForm.jsx';

// The visible current-bed text for one admission: the held bed's location
// for an active admission, an explicit "No bed assigned" when it holds
// none, and an em dash for closed admissions. Raw bed ids never render.
function currentBedText(admission) {
  if (admission.status !== 'ADMITTED') return '—';
  if (!admission.currentBed) return 'No bed assigned';
  return bedLabel(admission.currentBed);
}

// Presentation of the registered-admissions table (docs/plan2.md Task 2,
// extended by docs/plan3.md Task 7): one row per admission, the resolved
// patient name, the current bed text, and the row actions — the two-step
// discharge confirmation and the two-step bed assignment/transfer command.
// This component is deliberately presentational only: every value arrives
// as an explicit state prop and every intent is reported through an
// explicit callback prop, while AdmissionsPage keeps the transport calls,
// the mutation handlers, and the authoritative refetch state.
export default function AdmissionsTable({
  admissions,
  beds,
  patientNameById,
  canDischarge,
  bedCommandId,
  bedTarget,
  bedBusy,
  bedValidationError,
  dischargePendingId,
  dischargingId,
  onRequestBedCommand,
  onCancelBedCommand,
  onConfirmBedCommand,
  onBedTargetChange,
  onRequestDischarge,
  onCancelDischarge,
  onConfirmDischarge,
}) {
  return (
    <div className="admissions-table-wrap">
      <table className="admissions-table" aria-label="Registered admissions">
        <thead>
          <tr>
            <th scope="col">Patient</th>
            <th scope="col">Admitted at</th>
            <th scope="col">Discharged at</th>
            <th scope="col">Reason</th>
            <th scope="col">Current bed</th>
            <th scope="col">Status</th>
            <th scope="col">Actions</th>
          </tr>
        </thead>
        <tbody>
          {admissions.map((admission) => {
            // Target choices for THIS row: only the beds the
            // server reports AVAILABLE, never the bed this
            // admission already holds.
            const rowBedChoices = availableBeds(beds, admission.currentBed?.bedId);
            const bedCommandOpen = bedCommandId === admission.id;
            return (
              <tr key={admission.id}>
                <td>{patientNameById.get(admission.patientId) ?? 'Unknown record'}</td>
                <td>{admission.admittedAt}</td>
                <td>{admission.dischargedAt || '—'}</td>
                <td>{admission.reason}</td>
                <td className="admission-bed-cell">{currentBedText(admission)}</td>
                <td>
                  <span className={`status-badge status-${String(admission.status).toLowerCase()}`}>
                    {admission.status}
                  </span>
                </td>
                <td className="admission-row-actions">
                  {bedCommandOpen ? (
                    <div className="admission-bed-command" aria-busy={bedBusy}>
                      {bedValidationError && (
                        <p className="notice error" role="alert">{bedValidationError}</p>
                      )}
                      <label htmlFor={`admission-bed-target-${admission.id}`}>
                        {admission.currentBed ? 'Transfer to available bed' : 'Available bed'}
                      </label>
                      <select
                        id={`admission-bed-target-${admission.id}`}
                        className="admission-bed-target"
                        value={bedTarget}
                        disabled={bedBusy}
                        onChange={(event) => onBedTargetChange(event.target.value)}
                      >
                        <option value="">Select a bed</option>
                        {rowBedChoices.map((bed) => (
                          <option key={bed.id} value={bed.id}>{bedLabel(bed)}</option>
                        ))}
                      </select>
                      {rowBedChoices.length === 0 && (
                        <p className="panel-hint">No available bed in this branch right now.</p>
                      )}
                      <div className="admission-bed-command-actions">
                        <button
                          type="button"
                          className="admission-bed-confirm"
                          disabled={bedBusy || rowBedChoices.length === 0}
                          onClick={() => onConfirmBedCommand(admission)}
                        >
                          {bedBusy
                            ? (admission.currentBed ? 'Transferring…' : 'Assigning…')
                            : (admission.currentBed ? 'Confirm transfer' : 'Confirm assignment')}
                        </button>
                        <button
                          type="button"
                          className="admission-bed-cancel"
                          disabled={bedBusy}
                          onClick={() => onCancelBedCommand()}
                        >
                          Cancel
                        </button>
                      </div>
                    </div>
                  ) : (
                    <>
                      {canDischarge && admission.status === 'ADMITTED' && (
                        <button
                          type="button"
                          className="admission-bed-open"
                          onClick={() => onRequestBedCommand(admission.id)}
                        >
                          {admission.currentBed ? 'Transfer bed' : 'Assign bed'}
                        </button>
                      )}
                      {canDischarge
                        && admission.status === 'ADMITTED'
                        && dischargePendingId !== admission.id && (
                        <button
                          type="button"
                          className="admission-discharge"
                          onClick={() => onRequestDischarge(admission.id)}
                        >
                          Discharge
                        </button>
                      )}
                      {dischargePendingId === admission.id && (
                        <>
                          <button
                            type="button"
                            className="admission-discharge-confirm"
                            disabled={dischargingId === admission.id}
                            onClick={() => onConfirmDischarge(admission.id)}
                          >
                            {dischargingId === admission.id ? 'Discharging…' : 'Confirm discharge'}
                          </button>
                          <button
                            type="button"
                            className="admission-discharge-cancel"
                            disabled={dischargingId === admission.id}
                            onClick={() => onCancelDischarge()}
                          >
                            Cancel
                          </button>
                        </>
                      )}
                    </>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
