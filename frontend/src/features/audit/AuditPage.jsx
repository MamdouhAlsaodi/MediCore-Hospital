import React, { useEffect, useMemo, useState } from 'react';
import { ApiError } from '../../api.js';
import { fetchAuditEvents, fetchHospitalBranchOptions } from './auditApi.js';
import { fetchOrganizationView } from '../branches/actingContextApi.js';

// Audit evidence screen (plan1.md Task 10; plan3.md Task 11; Phase 5 US7
// T137): a strictly read-only, data-minimized view of the mutation audit
// trail over GET /api/audit (ADMIN-only in SecurityConfig; navigation hides
// the destination from every other role and the server refuses direct reads
// with 403).
//
// Task 11 + US7: the server decides the visible scope slice on every read —
// an ORGANIZATION-scope acting context inspects the whole organization plus
// rows marked legacy/unassigned (ownership never guessed), a HOSPITAL-scope
// context the hospital-wide slice of its acting hospital, a BRANCH-scope
// context only its own branch, a DEPARTMENT-scope context only its
// department — so the four client filters (branch, entity type, actor,
// correlation id) can only narrow a view, never widen one. Branch options
// come only from the server-owned active-branch list (GET /api/organization)
// for ORGANIZATION-scope sessions and only from the server-owned acting
// hospital's hierarchy slice (GET /api/network/hierarchy, filtered by the
// acting context's hospitalId) for HOSPITAL-scope sessions; every other
// scope is already branch-bound server-side, so no branch filter is offered.
//
// Each event renders only the evidence the contract provides — timestamp,
// actor, acting role/scope, branch attribution (or the legacy/unassigned
// mark), hospital attribution label, action, entity type, identifier, and
// the bounded correlation id — as plain React text nodes (hostile-looking
// strings can only ever appear as literal text). Transfer lifecycle rows add
// the bounded source → destination hospital direction, derived only from the
// allowlisted hospitalId/label fields. Raw details payloads,
// assignment/organization/department/hospital pointers, transfer pointers,
// persistence metadata, tokens, credentials, and request bodies are
// deliberately not rendered. Events are sorted newest-first on the client
// because ordering stays a server decision per response.
const LEGACY_ATTRIBUTION = 'legacy/unassigned';
const NO_VALUE = '—';

const TRANSFER_ACTION_LABELS = {
  TRANSFER_REQUEST: 'Transfer request',
  TRANSFER_ACCEPT: 'Transfer accept',
  TRANSFER_START_TRANSIT: 'Transfer transit start',
  TRANSFER_COMPLETE: 'Transfer complete',
  TRANSFER_REJECT: 'Transfer rejection',
  TRANSFER_CANCEL: 'Transfer cancellation',
};

export default function AuditPage({ session, onSessionExpired }) {
  const [events, setEvents] = useState([]);
  const [status, setStatus] = useState('loading');
  const [loadError, setLoadError] = useState('');

  // The filter form state and the filter set actually issued as a query are
  // separate: nothing is sent until the operator applies the form, and
  // clearing returns to the unfiltered server view.
  const [filters, setFilters] = useState({ branchId: '', resourceType: '', actor: '', correlationId: '' });
  const [appliedFilters, setAppliedFilters] = useState({});

  const scope = session.actingContext?.scope;
  const isOrganizationScope = scope === 'ORGANIZATION';
  const isHospitalScope = scope === 'HOSPITAL';
  const [branchOptions, setBranchOptions] = useState([]);
  const [branchOptionsError, setBranchOptionsError] = useState(false);
  const [hospitalLabels, setHospitalLabels] = useState(() => new Map());

  useEffect(() => {
    let active = true;
    setStatus('loading');
    setLoadError('');
    fetchAuditEvents({ token: session.token, onUnauthorized: onSessionExpired, filters: appliedFilters })
      .then((loaded) => {
        if (!active) return;
        setEvents(Array.isArray(loaded) ? loaded : []);
        setStatus('ready');
      })
      .catch((error) => {
        if (!active) return;
        // 401 is ownership of the shell: the session-expiry callback returns
        // the app to Login, so no local error is raised on top of it.
        if (error instanceof ApiError && error.status === 401) return;
        setLoadError(error instanceof ApiError ? error.message : 'Audit events could not be loaded.');
        setStatus('ready');
      });
    return () => { active = false; };
  }, [session.token, onSessionExpired, appliedFilters]);

  useEffect(() => {
    if (!isOrganizationScope && !isHospitalScope) return;
    let active = true;
    setBranchOptionsError(false);
    const options = isHospitalScope
      ? fetchHospitalBranchOptions({
          token: session.token,
          hospitalId: session.actingContext?.hospitalId,
          onUnauthorized: onSessionExpired,
        })
      : fetchOrganizationView({ token: session.token, onUnauthorized: onSessionExpired })
          .then((view) => (Array.isArray(view?.activeBranches) ? view.activeBranches : []));
    options
      .then((loaded) => {
        if (!active) return;
        setBranchOptions(Array.isArray(loaded) ? loaded : []);
      })
      .catch((error) => {
        if (!active) return;
        // 401 is ownership of the shell; every other failure only degrades
        // the branch filter — the evidence list itself stays usable.
        if (error instanceof ApiError && error.status === 401) return;
        setBranchOptions([]);
        setBranchOptionsError(true);
      });
    return () => { active = false; };
  }, [isOrganizationScope, isHospitalScope, session.token, onSessionExpired, session.actingContext?.hospitalId]);

  // The endpoint returns rows in unspecified repository order; the demo
  // contract is reverse-chronological, so the page sorts defensively by the
  // contract timestamp (unparseable values sink to the end).
  const orderedEvents = [...events].sort(
    (a, b) => (Date.parse(b?.occurredAt ?? '') || 0) - (Date.parse(a?.occurredAt ?? '') || 0)
  );

  // The acting hospital's own display label, when the slice names it.
  const actingHospitalId = session.actingContext?.hospitalId ?? null;

  const branchLabels = useMemo(
    () => new Map(branchOptions.map((branch) => [branch.id, branch.name])),
    [branchOptions]
  );

  const setFilter = (key) => (event) => {
    const value = event.target.value;
    setFilters((current) => ({ ...current, [key]: value }));
  };

  const applyFilters = (domEvent) => {
    domEvent.preventDefault();
    setAppliedFilters({ ...filters });
  };

  const clearFilters = () => {
    const empty = { branchId: '', resourceType: '', actor: '', correlationId: '' };
    setFilters(empty);
    setAppliedFilters(empty);
  };

  const hasAppliedFilters = Object.values(appliedFilters).some((value) => value !== '');

  function branchCell(event) {
    if (event.branchAttribution === LEGACY_ATTRIBUTION) return LEGACY_ATTRIBUTION;
    if (!event.branchId) return NO_VALUE;
    return branchLabels.get(event.branchId) ?? event.branchId;
  }

  // The bounded transfer direction: label form only, from the server-owned
  // hierarchy labels — raw hospital/transfer pointers never render.
  function transferDirection(event) {
    if (!event.transferId) return null;
    const sourceLabel = hospitalLabelFor(event.sourceHospitalId);
    const destinationLabel = hospitalLabelFor(event.destinationHospitalId);
    if (sourceLabel === null && destinationLabel === null) return null;
    return (sourceLabel ?? 'Unknown hospital') + ' → ' + (destinationLabel ?? 'Unknown hospital');
  }

  function hospitalLabelFor(hospitalId) {
    if (!hospitalId) return null;
    if (hospitalId === actingHospitalId && session.actingContext) {
      // The acting hospital is the session's own context; its label is not
      // carried in the evidence row, so the stable display form is the
      // server's hierarchy name when loaded, else the bounded id form used
      // by no other cell — the raw pointer itself is never shown.
      return hospitalLabels.get(hospitalId) ?? 'This hospital';
    }
    return hospitalLabels.get(hospitalId) ?? 'Another hospital';
  }

  return (
    <section className="audit-screen" aria-label="Audit screen">
      <form className="audit-filters" aria-label="Audit filters" onSubmit={applyFilters}>
        {(isOrganizationScope || isHospitalScope) && (
          <label>
            Branch
            <select value={filters.branchId} onChange={setFilter('branchId')}>
              <option value="">All branches</option>
              {branchOptions.map((branch) => (
                <option key={branch.id} value={branch.id}>{branch.name}</option>
              ))}
            </select>
          </label>
        )}
        <label>
          Entity type
          <input type="text" value={filters.resourceType} onChange={setFilter('resourceType')} />
        </label>
        <label>
          Actor
          <input type="text" value={filters.actor} onChange={setFilter('actor')} />
        </label>
        <label>
          Correlation ID
          <input type="text" value={filters.correlationId} onChange={setFilter('correlationId')} />
        </label>
        <button type="submit">Apply filters</button>
        <button type="button" onClick={clearFilters}>Clear</button>
      </form>

      {(isOrganizationScope || isHospitalScope) && branchOptionsError && (
        <p className="notice" role="status">
          Branch filter options could not be loaded; showing every branch the server allows.
        </p>
      )}

      {status === 'loading' && (
        <p className="notice" role="status">Loading audit events…</p>
      )}

      {loadError && (
        <p className="notice error" role="alert">{loadError}</p>
      )}

      {status === 'ready' && !loadError && (
        orderedEvents.length === 0 ? (
          <div className="panel audit-empty" role="status">
            <h3>{hasAppliedFilters ? 'No audit events match the current filters' : 'No audit events recorded'}</h3>
            <p className="panel-hint">
              Mutation evidence appears here as users create, update, or delete records.
            </p>
          </div>
        ) : (
          <div className="audit-table-wrap">
            <table className="audit-table" aria-label="Audit events">
              <thead>
                <tr>
                  <th scope="col">Time</th>
                  <th scope="col">Actor</th>
                  <th scope="col">Role</th>
                  <th scope="col">Scope</th>
                  <th scope="col">Branch</th>
                  <th scope="col">Action</th>
                  <th scope="col">Entity type</th>
                  <th scope="col">Identifier</th>
                  <th scope="col">Correlation ID</th>
                </tr>
              </thead>
              <tbody>
                {orderedEvents.map((event) => {
                  const direction = transferDirection(event);
                  return (
                    <tr key={event.id}>
                      <td>{event.occurredAt || NO_VALUE}</td>
                      <td>{event.actor || NO_VALUE}</td>
                      <td>{event.role || NO_VALUE}</td>
                      <td>{event.scope || NO_VALUE}</td>
                      <td>{branchCell(event)}</td>
                      <td>
                        {TRANSFER_ACTION_LABELS[event.action] ? (
                          <>
                            {TRANSFER_ACTION_LABELS[event.action]}
                            {direction && (
                              <span className="audit-transfer-direction"> ({direction})</span>
                            )}
                          </>
                        ) : (
                          event.action || NO_VALUE
                        )}
                      </td>
                      <td>{event.resourceType || NO_VALUE}</td>
                      <td>{event.resourceId || NO_VALUE}</td>
                      <td>{event.correlationId || NO_VALUE}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )
      )}
    </section>
  );
}
