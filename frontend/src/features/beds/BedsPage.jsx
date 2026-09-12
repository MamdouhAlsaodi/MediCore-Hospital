import React, { useEffect, useState } from 'react';
import { ApiError } from '../../api.js';
import { can } from '../../authorization.js';
import { createBed, fetchBeds, transitionBed } from './bedApi.js';

// Registration form (docs/plan3.md Task 6). The submitted body mirrors
// CreateBedRequest exactly — ward, room, bedNumber — because the server
// owns everything else: the owning branch derives from the acting context
// and the initial status is AVAILABLE. No branch, status, or patient input
// exists anywhere in this form. Server errors (including the 409 duplicate
// conflict) render inline with zero field loss; 401 is ownership of the
// shell.
export function BedForm({ session, onCreated, onCancel, onSessionExpired }) {
  const [ward, setWard] = useState('');
  const [room, setRoom] = useState('');
  const [bedNumber, setBedNumber] = useState('');
  const [pending, setPending] = useState(false);
  const [validationError, setValidationError] = useState('');
  const [serverError, setServerError] = useState('');

  async function handleSubmit(event) {
    event.preventDefault();
    if (pending) return;
    setValidationError('');
    setServerError('');
    if (!ward.trim() || !room.trim() || !bedNumber.trim()) {
      setValidationError('Provide the ward, the room, and the bed number.');
      return;
    }
    setPending(true);
    try {
      const created = await createBed({
        token: session.token,
        bed: { ward: ward.trim(), room: room.trim(), bedNumber: bedNumber.trim() },
        onUnauthorized: onSessionExpired,
      });
      onCreated(created);
    } catch (error) {
      // 401 is ownership of the shell: no local error on top of it.
      if (error instanceof ApiError && error.status === 401) return;
      setServerError(
        error instanceof ApiError ? error.message : 'The bed could not be added.'
      );
    } finally {
      setPending(false);
    }
  }

  return (
    <form className="panel beds-form" aria-label="Add a bed" aria-busy={pending} onSubmit={handleSubmit}>
      <h3>Add a bed</h3>
      <p className="panel-hint">
        The bed is registered for the branch you are acting on and starts as
        AVAILABLE. The ward, room, and bed number together must be unique
        within that branch.
      </p>

      {validationError && (
        <p className="notice error" role="alert">{validationError}</p>
      )}
      {serverError && (
        <p className="notice error" role="alert">{serverError}</p>
      )}

      <div className="beds-form-grid">
        <div className="beds-field">
          <label htmlFor="bed-ward">Ward</label>
          <input
            id="bed-ward"
            type="text"
            value={ward}
            onChange={(event) => setWard(event.target.value)}
          />
        </div>

        <div className="beds-field">
          <label htmlFor="bed-room">Room</label>
          <input
            id="bed-room"
            type="text"
            value={room}
            onChange={(event) => setRoom(event.target.value)}
          />
        </div>

        <div className="beds-field">
          <label htmlFor="bed-number">Bed number</label>
          <input
            id="bed-number"
            type="text"
            value={bedNumber}
            onChange={(event) => setBedNumber(event.target.value)}
          />
        </div>
      </div>

      <div className="beds-form-actions">
        <button type="submit" className="beds-save" disabled={pending}>
          {pending ? 'Saving…' : 'Save bed'}
        </button>
        <button type="button" className="beds-cancel" onClick={onCancel} disabled={pending}>
          Cancel
        </button>
      </div>
    </form>
  );
}

// Beds screen (docs/plan3.md Task 6): the branch-scoped inventory over
// GET /api/beds, the add flow over POST /api/beds, and the guarded status
// transitions over PUT /api/beds/{id}/status (AVAILABLE <-> MAINTENANCE <->
// OUT_OF_SERVICE). OCCUPIED is admission-owned: an occupied row offers no
// status actions at all. The text filter narrows the already-loaded list
// client-side against ward, room, and bed number — it never performs a
// second request. Every transition is a two-step confirmation and every
// transport goes through the feature adapter — components never call fetch
// directly, and no persistence metadata is rendered anywhere.
export default function BedsPage({ session, onSessionExpired }) {
  const [beds, setBeds] = useState([]);
  const [status, setStatus] = useState('loading');
  const [loadError, setLoadError] = useState('');
  // 'list' | 'form'
  const [view, setView] = useState('list');
  const [filterText, setFilterText] = useState('');
  const [confirmation, setConfirmation] = useState('');
  // Bumped after a successful mutation so the list refetches instead of
  // showing a result set that cannot contain the new state.
  const [listRefresh, setListRefresh] = useState(0);
  // The {id, target} awaiting its transition confirmation, or null.
  const [confirmPending, setConfirmPending] = useState(null);
  // The {id, target} whose transition request is in flight, or null.
  const [transitioning, setTransitioning] = useState(null);
  const [transitionError, setTransitionError] = useState('');

  useEffect(() => {
    let active = true;
    setStatus('loading');
    setLoadError('');
    fetchBeds({ token: session.token, onUnauthorized: onSessionExpired })
      .then((loaded) => {
        if (!active) return;
        setBeds(Array.isArray(loaded) ? loaded : []);
        setStatus('ready');
      })
      .catch((error) => {
        if (!active) return;
        // 401 is ownership of the shell: the session-expiry callback returns
        // the app to Login, so no local error is raised on top of it.
        if (error instanceof ApiError && error.status === 401) return;
        setLoadError(error instanceof ApiError ? error.message : 'Beds could not be loaded.');
        setStatus('ready');
      });
    return () => { active = false; };
  }, [session.token, listRefresh, onSessionExpired]);

  function openForm() {
    setConfirmation('');
    setTransitionError('');
    setView('form');
  }

  function handleFormCancelled() {
    setView('list');
  }

  function handleCreated() {
    setConfirmation('Bed added.');
    setView('list');
    // Server-side state cannot be honestly patched locally: refetch.
    setListRefresh((n) => n + 1);
  }

  function requestTransition(bedId, target) {
    setConfirmation('');
    setTransitionError('');
    setConfirmPending({ id: bedId, target });
  }

  function cancelTransition() {
    setConfirmPending(null);
  }

  async function confirmTransition(bedId, target) {
    if (transitioning) return;
    setTransitioning({ id: bedId, target });
    setTransitionError('');
    try {
      await transitionBed({
        token: session.token,
        id: bedId,
        status: target,
        onUnauthorized: onSessionExpired,
      });
      setConfirmPending(null);
      setConfirmation('Status updated.');
      setListRefresh((n) => n + 1);
    } catch (error) {
      setConfirmPending(null);
      // 401 is ownership of the shell: no local error on top of it.
      if (error instanceof ApiError && error.status === 401) return;
      setTransitionError(
        error instanceof ApiError ? error.message : 'The status change could not be recorded.'
      );
    } finally {
      setTransitioning(null);
    }
  }

  // UI convenience hints from the shared permission map; backend stays
  // authoritative for every request (the server refuses with 403 and the
  // page shows that state).
  const canCreate = can(session, 'create', 'bed');
  const canTransition = can(session, 'transition', 'bed');
  // The transitions each row's current status legally admits (mirrors the
  // server map; the server still validates and refuses anything else).
  // OCCUPIED and any unknown status admit no client transition.
  function transitionsFor(bedStatus) {
    if (bedStatus === 'AVAILABLE') return ['MAINTENANCE', 'OUT_OF_SERVICE'];
    if (bedStatus === 'MAINTENANCE') return ['AVAILABLE', 'OUT_OF_SERVICE'];
    if (bedStatus === 'OUT_OF_SERVICE') return ['AVAILABLE', 'MAINTENANCE'];
    return [];
  }

  const ACTION_LABELS = {
    AVAILABLE: 'Return to service',
    MAINTENANCE: 'Start maintenance',
    OUT_OF_SERVICE: 'Take out of service',
  };

  const trimmedFilter = filterText.trim().toLowerCase();
  const visibleBeds = trimmedFilter
    ? beds.filter((bed) =>
        [bed.ward, bed.room, bed.bedNumber]
          .filter((value) => typeof value === 'string')
          .some((value) => value.toLowerCase().includes(trimmedFilter)))
    : beds;

  return (
    <section className="beds-screen" aria-label="Beds screen">
      {status === 'loading' && (
        <p className="notice" role="status">Loading beds…</p>
      )}

      {loadError && (
        <p className="notice error" role="alert">{loadError}</p>
      )}

      {status === 'ready' && !loadError && (
        <>
          {confirmation && (
            <p className="notice success" role="status">{confirmation}</p>
          )}

          {transitionError && (
            <p className="notice error" role="alert">{transitionError}</p>
          )}

          <p className="panel-hint beds-boundary">
            This inventory shows only the beds of the branch you are acting
            on. Bed status is operational data: occupancy itself is controlled
            by admissions, not by this screen.
          </p>

          {canCreate && view === 'list' && (
            <div className="beds-actions">
              <button type="button" className="beds-new" onClick={openForm}>
                Add bed
              </button>
            </div>
          )}

          {view === 'list' && beds.length > 0 && (
            <div className="beds-filter">
              <label htmlFor="beds-filter-input">Filter beds</label>
              <input
                id="beds-filter-input"
                type="search"
                placeholder="Filter by ward, room, or bed number"
                value={filterText}
                onChange={(event) => setFilterText(event.target.value)}
              />
            </div>
          )}

          {view === 'list' && beds.length === 0 ? (
            <div className="panel beds-empty" role="status">
              <h3>No beds registered</h3>
              <p className="panel-hint">
                No beds exist for this branch yet.{' '}
                {canCreate
                  ? 'Use the add action above to register the first bed.'
                  : 'Beds appear here once they are registered.'}
              </p>
            </div>
          ) : view === 'list' && visibleBeds.length === 0 ? (
            <div className="panel beds-empty" role="status">
              <h3>No beds match the filter</h3>
              <p className="panel-hint">
                No loaded bed matches “{filterText}”. Clear the filter to see
                every bed of this branch.
              </p>
            </div>
          ) : view === 'list' ? (
            <div className="beds-table-wrap">
              <table className="beds-table" aria-label="Registered beds">
                <thead>
                  <tr>
                    <th scope="col">Ward</th>
                    <th scope="col">Room</th>
                    <th scope="col">Bed number</th>
                    <th scope="col">Status</th>
                    <th scope="col">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {visibleBeds.map((bed) => {
                    const targets = canTransition ? transitionsFor(bed.occupancyStatus) : [];
                    return (
                      <tr key={bed.id}>
                        <td>{bed.ward}</td>
                        <td>{bed.room}</td>
                        <td>{bed.bedNumber}</td>
                        <td>
                          <span
                            className={`status-badge status-${String(bed.occupancyStatus).toLowerCase()}`}
                          >
                            {bed.occupancyStatus}
                          </span>
                        </td>
                        <td className="beds-row-actions">
                          {targets.map((target) => {
                            const pendingHere =
                              confirmPending?.id === bed.id && confirmPending.target === target;
                            const busyHere =
                              transitioning?.id === bed.id && transitioning.target === target;
                            return pendingHere ? (
                              <React.Fragment key={target}>
                                <button
                                  type="button"
                                  className="beds-transition-confirm"
                                  disabled={Boolean(transitioning)}
                                  onClick={() => confirmTransition(bed.id, target)}
                                >
                                  {busyHere ? 'Recording…' : `Confirm ${ACTION_LABELS[target]}`}
                                </button>
                                <button
                                  type="button"
                                  className="beds-transition-cancel"
                                  disabled={Boolean(transitioning)}
                                  onClick={cancelTransition}
                                >
                                  Cancel
                                </button>
                              </React.Fragment>
                            ) : (
                              <button
                                key={target}
                                type="button"
                                className={`beds-transition beds-transition-${target.toLowerCase()}`}
                                onClick={() => requestTransition(bed.id, target)}
                              >
                                {ACTION_LABELS[target]}
                              </button>
                            );
                          })}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          ) : null}

          {view === 'form' && (
            <BedForm
              session={session}
              onCreated={handleCreated}
              onCancel={handleFormCancelled}
              onSessionExpired={onSessionExpired}
            />
          )}
        </>
      )}
    </section>
  );
}
