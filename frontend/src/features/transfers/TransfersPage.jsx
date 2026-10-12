import React, { useEffect, useState } from 'react';
import { ApiError } from '../../api.js';
import { listTransfers } from './transferApi.js';
import { fetchNetworkHierarchy } from '../network/networkApi.js';
import TransferForm from './TransferForm.jsx';
import TransferDetail, { directionOf, useHospitalLabels } from './TransferDetail.jsx';

// Transfers screen (Phase 5 US8, T145/T146): the strictly read-scope list,
// the request form, and the per-status detail transitions over the adapter
// in ./transferApi.js (the generated contract + idempotency envelope).
//
// Scope: the list is exactly what GET /api/transfers returns — the
// server-derived source-or-destination slice of the acting hospital. No
// client filter or parameter can widen it; the wrong hospital's detail read
// answers the same generic 404 as an unknown id, and the screen renders
// that as an honest inline message.
//
// Permissions: the per-command buttons mirror the server matrix (T089) —
// source-side cancel/start-transit for ADMIN/DOCTOR/NURSE, destination-side
// accept/reject/complete for ADMIN/DOCTOR — but the buttons only *offer*
// commands: the server re-checks role AND hospital on every call, and any
// refusal (403 wrong role, 404 foreign hospital, 409 conflict or stale
// version) renders inline with the stored state preserved for the next
// attempt. Idempotency keys are generated per command attempt.
//
// Data minimization: rows render contract labels only — transfer number,
// status, reason, direction (labels resolved from the server-owned
// hierarchy), and timestamps. Patient/hospital/bed UUIDs never render.
export default function TransfersPage({ session, onSessionExpired }) {
  const [rows, setRows] = useState([]);
  const [status, setStatus] = useState('loading');
  const [loadError, setLoadError] = useState('');
  const [selectedId, setSelectedId] = useState(null);
  const [reloadTick, setReloadTick] = useState(0);
  const { labelFor, actingHospitalId } = useHospitalLabels(session, onSessionExpired);

  useEffect(() => {
    let active = true;
    setStatus('loading');
    setLoadError('');
    listTransfers({ token: session.token, onUnauthorized: onSessionExpired })
      .then((loaded) => {
        if (!active) return;
        setRows(loaded);
        setStatus('ready');
      })
      .catch((error) => {
        if (!active) return;
        if (error instanceof ApiError && error.status === 401) return;
        setLoadError(error instanceof ApiError ? error.message : 'Transfers could not be loaded.');
        setStatus('ready');
      });
    return () => { active = false; };
  }, [session.token, onSessionExpired, reloadTick]);

  const reload = () => setReloadTick((tick) => tick + 1);

  return (
    <section className="transfers-screen" aria-label="Transfers screen">
      <TransferForm session={session} onSessionExpired={onSessionExpired} onRequested={reload} />

      {status === 'loading' && <p className="notice" role="status">Loading transfers…</p>}
      {loadError && <p className="notice error" role="alert">{loadError}</p>}

      {status === 'ready' && !loadError && (
        rows.length === 0 ? (
          <div className="panel transfer-empty" role="status">
            <h3>No transfers involve this hospital yet.</h3>
            <p className="panel-hint">
              Transfer requests that leave or reach this hospital appear here as
              the source or destination side.
            </p>
          </div>
        ) : (
          <div className="transfer-table-wrap">
            <table className="transfer-table" aria-label="Authorized transfers">
              <thead>
                <tr>
                  <th scope="col">Number</th>
                  <th scope="col">Status</th>
                  <th scope="col">Reason</th>
                  <th scope="col">Direction</th>
                  <th scope="col">Requested</th>
                  <th scope="col">Actions</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((transfer) => (
                  <tr key={transfer.id}>
                    <td>{transfer.transferNumber}</td>
                    <td>{transfer.status}</td>
                    <td>{transfer.reasonCode}</td>
                    <td>{directionOf(transfer, labelFor, actingHospitalId)}</td>
                    <td>{transfer.requestedAt}</td>
                    <td>
                      <button
                        type="button"
                        aria-label={`View transfer ${transfer.transferNumber}`}
                        onClick={() => setSelectedId(transfer.id)}
                      >
                        View
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      )}

      {selectedId && (
        <TransferDetail
          session={session}
          id={selectedId}
          onSessionExpired={onSessionExpired}
          onChanged={reload}
          onClose={() => setSelectedId(null)}
        />
      )}
    </section>
  );
}
