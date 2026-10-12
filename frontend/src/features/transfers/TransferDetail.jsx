import React, { useEffect, useState } from 'react';
import { ApiError } from '../../api.js';
import {
  fetchTransfer, acceptTransfer, rejectTransfer, cancelTransfer, startTransit, completeTransfer,
} from './transferApi.js';
import { fetchNetworkHierarchy } from '../network/networkApi.js';

const STATUS_TRANSITIONS = {
  REQUESTED: ['cancel', 'startTransit'],
  ACCEPTED: ['accept', 'reject', 'cancel'],
  IN_TRANSIT: ['complete', 'cancel'],
  COMPLETED: [],
  REJECTED: [],
  CANCELLED: [],
};

function newKey() {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  let key = 'ui-';
  for (let i = 0; i < 24; i += 1) {
    key += alphabet[Math.floor(Math.random() * alphabet.length)];
  }
  return key;
}

/** Destination label resolver from the server-owned hierarchy; falls back to a generic form. */
export function useHospitalLabels(session, onSessionExpired) {
  const [labels, setLabels] = useState(() => new Map());
  const actingHospitalId = session.actingContext?.hospitalId ?? null;
  useEffect(() => {
    let active = true;
    fetchNetworkHierarchy({ token: session.token, onUnauthorized: onSessionExpired })
      .then((hierarchy) => {
        if (!active) return;
        const map = new Map();
        for (const hospital of hierarchy.hospitals) {
          map.set(hospital.id, hospital.name);
        }
        setLabels(map);
      })
      .catch(() => {
        if (!active) return;
        // Label resolution is display-only; failures degrade to generic forms.
        setLabels(new Map());
      });
    return () => { active = false; };
  }, [session.token, onSessionExpired]);
  const labelFor = (hospitalId) => {
    if (!hospitalId) return null;
    // Identity first: the acting hospital is always "this hospital", even
    // before the hierarchy labels arrive (or when they fail to load).
    if (hospitalId === actingHospitalId) return labels.get(hospitalId) ?? 'This hospital';
    if (labels.size === 0) return 'Another hospital';
    return labels.get(hospitalId) ?? 'Another hospital';
  };
  return { labelFor, actingHospitalId };
}

export function directionOf(transfer, labelFor, actingHospitalId) {
  const source = labelFor(transfer.sourceHospitalId) ?? 'Unknown hospital';
  const destination = labelFor(transfer.destinationHospitalId) ?? 'Unknown hospital';
  const side = transfer.sourceHospitalId === actingHospitalId
    ? 'outgoing'
    : (transfer.destinationHospitalId === actingHospitalId ? 'incoming' : null);
  return `${source} → ${destination}${side ? ` (${side})` : ''}`;
}

export default function TransferDetail({ session, id, onSessionExpired, onChanged, onClose }) {
  const [transfer, setTransfer] = useState(null);
  const [loadError, setLoadError] = useState('');
  const [commandError, setCommandError] = useState('');
  const [pendingCommand, setPendingCommand] = useState(null);
  const [confirming, setConfirming] = useState(null);
  const { labelFor, actingHospitalId } = useHospitalLabels(session, onSessionExpired);

  useEffect(() => {
    let active = true;
    setTransfer(null);
    setLoadError('');
    setCommandError('');
    setConfirming(null);
    fetchTransfer({ token: session.token, id, onUnauthorized: onSessionExpired })
      .then((row) => { if (active) setTransfer(row); })
      .catch((error) => {
        if (!active) return;
        if (error instanceof ApiError && error.status === 401) return;
        setLoadError(error instanceof ApiError ? error.message : 'The transfer could not be loaded.');
      });
    return () => { active = false; };
  }, [id, session.token, onSessionExpired]);

  const run = async (command) => {
    if (!transfer) return;
    setCommandError('');
    setPendingCommand(command);
    const idempotencyKey = newKey();
    try {
      let updated;
      if (command === 'cancel') {
        updated = await cancelTransfer({
          token: session.token, id: transfer.id, idempotencyKey,
          decision: { reasonCode: transfer.reasonCode, expectedVersion: transfer.version },
          onUnauthorized: onSessionExpired,
        });
      } else if (command === 'startTransit') {
        updated = await startTransit({ token: session.token, id: transfer.id, idempotencyKey, onUnauthorized: onSessionExpired });
      } else if (command === 'complete') {
        updated = await completeTransfer({ token: session.token, id: transfer.id, idempotencyKey, onUnauthorized: onSessionExpired });
      } else if (command === 'reject') {
        updated = await rejectTransfer({
          token: session.token, id: transfer.id, idempotencyKey,
          decision: { reasonCode: transfer.reasonCode, expectedVersion: transfer.version },
          onUnauthorized: onSessionExpired,
        });
      } else if (command === 'accept') {
        // Accept needs a destination branch/bed; the destination-side screen
        // pre-fills them through the detail form (see the bed inputs below).
        return; // handled by the dedicated accept form
      }
      if (updated) {
        setTransfer(updated);
        setConfirming(null);
        onChanged();
      }
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) return;
      setCommandError(error instanceof ApiError ? error.message : 'The command was refused.');
      // The stored transfer (with its version) stays untouched for the retry.
    } finally {
      setPendingCommand(null);
    }
  };

  if (loadError) {
    return (
      <aside className="panel transfer-detail" aria-label="Transfer detail">
        <h3>Transfer detail</h3>
        <p className="notice error" role="alert">{loadError}</p>
        <button type="button" onClick={onClose}>Close</button>
      </aside>
    );
  }
  if (!transfer) {
    return (
      <aside className="panel transfer-detail" aria-label="Transfer detail">
        <h3>Transfer detail</h3>
        <p role="status">Loading transfer…</p>
      </aside>
    );
  }

  const permitted = STATUS_TRANSITIONS[transfer.status] ?? [];
  const isSourceSide = transfer.sourceHospitalId === actingHospitalId;
  const isDestinationSide = transfer.destinationHospitalId === actingHospitalId;
  const canSee = (command) => {
    if (!permitted.includes(command)) return false;
    // Server matrix mirror (T089): destination decisions are ADMIN/DOCTOR
    // only; the server re-checks hospital AND role on every call.
    if ((command === 'accept' || command === 'reject' || command === 'complete')
      && !['ADMIN', 'DOCTOR'].includes(session.actingContext?.role)) return false;
    if (command === 'accept') return isDestinationSide;
    if (command === 'reject') return isDestinationSide;
    if (command === 'complete') return isDestinationSide;
    return isSourceSide;
  };

  return (
    <aside className="panel transfer-detail" aria-label="Transfer detail">
      <h3>Transfer detail</h3>
      <p className="transfer-detail-line">
        <strong>{transfer.transferNumber}</strong> — {transfer.status} · {transfer.reasonCode}
      </p>
      <p className="transfer-detail-line">
        {directionOf(transfer, labelFor, actingHospitalId)}
      </p>
      <p className="transfer-detail-line">
        Requested {transfer.requestedAt}
        {transfer.acceptedAt ? ` · Accepted ${transfer.acceptedAt}` : ''}
        {transfer.transitStartedAt ? ` · Transit ${transfer.transitStartedAt}` : ''}
        {transfer.completedAt ? ` · Completed ${transfer.completedAt}` : ''}
        {transfer.rejectedAt ? ` · Rejected ${transfer.rejectedAt}` : ''}
        {transfer.cancelledAt ? ` · Cancelled ${transfer.cancelledAt}` : ''}
      </p>
      <p className="transfer-detail-line">Stored version {transfer.version}</p>

      {commandError && <p className="notice error" role="alert">{commandError}</p>}

      {canSee('cancel') && (
        confirming === 'cancel' ? (
          <>
            <button type="button" className="transfer-save" disabled={pendingCommand === 'cancel'}
              onClick={() => run('cancel')}>Confirm cancel</button>
            <button type="button" onClick={() => setConfirming(null)}>Keep transfer</button>
          </>
        ) : (
          <button type="button" onClick={() => setConfirming('cancel')}>Cancel transfer</button>
        )
      )}
      {canSee('startTransit') && (
        <button type="button" className="transfer-save" disabled={Boolean(pendingCommand)}
          onClick={() => run('startTransit')}>Start transit</button>
      )}
      {canSee('complete') && (
        <button type="button" className="transfer-save" disabled={Boolean(pendingCommand)}
          onClick={() => run('complete')}>Complete transfer</button>
      )}
      {canSee('reject') && (
        confirming === 'reject' ? (
          <>
            <button type="button" className="transfer-save" disabled={pendingCommand === 'reject'}
              onClick={() => run('reject')}>Confirm rejection</button>
            <button type="button" onClick={() => setConfirming(null)}>Keep transfer</button>
          </>
        ) : (
          <button type="button" onClick={() => setConfirming('reject')}>Reject transfer</button>
        )
      )}
      {canSee('accept') && (
        <AcceptForm session={session} transfer={transfer} onSessionExpired={onSessionExpired}
          onAccepted={(updated) => { setTransfer(updated); onChanged(); }} />
      )}

      <button type="button" className="transfer-close" onClick={onClose}>Close</button>
    </aside>
  );
}

/** Destination-side accept: branch + available-bed selection from the server lists. */
function AcceptForm({ session, transfer, onSessionExpired, onAccepted }) {
  const [branches, setBranches] = useState([]);
  const [beds, setBeds] = useState([]);
  const [branchId, setBranchId] = useState('');
  const [bedId, setBedId] = useState('');
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);

  useEffect(() => {
    let active = true;
    const headers = { Authorization: 'Bearer ' + session.token };
    fetch('/api/branches', { headers })
      .then((response) => (response.ok ? response.json() : []))
      .then((rows) => { if (active) setBranches(Array.isArray(rows) ? rows.filter((b) => b.active) : []); })
      .catch(() => { if (active) setBranches([]); });
    fetch('/api/beds', { headers })
      .then((response) => (response.ok ? response.json() : []))
      .then((rows) => {
        if (!active) return;
        setBeds((Array.isArray(rows) ? rows : []).filter((bed) => bed.occupancyStatus === 'AVAILABLE'));
      })
      .catch(() => { if (active) setBeds([]); });
    return () => { active = false; };
  }, [session.token]);

  const accept = async () => {
    setError('');
    if (!branchId || !bedId) {
      setError('Select the destination branch and an available bed.');
      return;
    }
    setPending(true);
    try {
      const updated = await acceptTransfer({
        token: session.token, id: transfer.id, idempotencyKey: newKey(),
        decision: { destinationBranchId: branchId, destinationBedId: bedId, expectedVersion: transfer.version },
        onUnauthorized: onSessionExpired,
      });
      onAccepted(updated);
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) return;
      setError(failure instanceof ApiError ? failure.message : 'The accept was refused.');
    } finally {
      setPending(false);
    }
  };

  return (
    <div className="transfer-accept-form">
      <label htmlFor="transfer-accept-branch">Destination branch</label>
      <select id="transfer-accept-branch" value={branchId} onChange={(e) => setBranchId(e.target.value)}>
        <option value="">Select a branch</option>
        {branches.map((branch) => (
          <option key={branch.id} value={branch.id}>{branch.name}</option>
        ))}
      </select>
      <label htmlFor="transfer-accept-bed">Destination bed</label>
      <select id="transfer-accept-bed" value={bedId} onChange={(e) => setBedId(e.target.value)}>
        <option value="">Select an available bed</option>
        {beds.map((bed) => (
          <option key={bed.id} value={bed.id}>{bed.ward} {bed.room} / {bed.bedNumber}</option>
        ))}
      </select>
      {error && <p className="notice error" role="alert">{error}</p>}
      <button type="button" className="transfer-save" disabled={pending} onClick={accept}>Accept transfer</button>
    </div>
  );
}
