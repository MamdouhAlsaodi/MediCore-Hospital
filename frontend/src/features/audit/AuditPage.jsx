import React, { useEffect, useMemo, useState } from 'react';
import { ApiError } from '../../api.js';
import { fetchAuditEvents } from './auditApi.js';
import { fetchOrganizationView } from '../branches/actingContextApi.js';

// Audit evidence screen (plan1.md Task 10; plan3.md Task 11): a strictly
// read-only, data-minimized view of the mutation audit trail over
// GET /api/audit (ADMIN-only in SecurityConfig; navigation hides the
// destination from every other role and the server refuses direct reads
// with 403).
//
// Task 11: the server decides the visible scope slice on every read — an
// ORGANIZATION-scope acting context inspects the whole organization plus
// rows marked legacy/unassigned (ownership never guessed), a BRANCH-scope
// context only its own branch, a DEPARTMENT-scope context only its
// department — so the four client filters (branch, entity type, actor,
// correlation id) can only narrow a view, never widen one. Branch options
// come only from the server-owned active-branch list (GET /api/organization)
// and only for ORGANIZATION-scope sessions; every other scope is already
// branch-bound server-side, so no branch filter is offered.
//
// Each event renders only the evidence the contract provides — timestamp,
// actor, acting role/scope, branch attribution (or the legacy/unassigned
// mark), action, entity type, identifier, and the bounded correlation id —
// as plain React text nodes (hostile-looking strings can only ever appear
// as literal text). Raw details payloads, assignment/organization/department
// pointers, persistence metadata, tokens, credentials, and request bodies
// are deliberately not rendered. Events are sorted newest-first on the
// client because ordering stays a server decision per response.
const LEGACY_ATTRIBUTION = 'legacy/unassigned';
const NO_VALUE = '—';

export default function AuditPage({ session, onSessionExpired }) {
  const [events, setEvents] = useState([]);
  const [status, setStatus] = useState('loading');
  const [loadError, setLoadError] = useState('');

  // The filter form state and the filter set actually issued as a query are
  // separate: nothing is sent until the operator applies the form, and
  // clearing returns to the unfiltered server view.
  const [filters, setFilters] = useState({ branchId: '', resourceType: '', actor: '', correlationId: '' });
  const [appliedFilters, setAppliedFilters] = useState({});

  const isOrganizationScope = session.actingContext?.scope === 'ORGANIZATION';
  const [branchOptions, setBranchOptions] = useState([]);
  const [branchOptionsError, setBranchOptionsError] = useState(false);

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
    if (!isOrganizationScope) return;
    let active = true;
    setBranchOptionsError(false);
    fetchOrganizationView({ token: session.token, onUnauthorized: onSessionExpired })
      .then((view) => {
        if (!active) return;
        setBranchOptions(Array.isArray(view?.activeBranches) ? view.activeBranches : []);
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
  }, [isOrganizationScope, session.token, onSessionExpired]);

  // The endpoint returns rows in unspecified repository order; the demo
  // contract is reverse-chronological, so the page sorts defensively by the
  // contract timestamp (unparseable values sink to the end).
  const orderedEvents = [...events].sort(
    (a, b) => (Date.parse(b?.occurredAt ?? '') || 0) - (Date.parse(a?.occurredAt ?? '') || 0)
  );

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

  return (
    <section className="audit-screen" aria-label="Audit screen">
      <form className="audit-filters" aria-label="Audit filters" onSubmit={applyFilters}>
        {isOrganizationScope && (
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

      {isOrganizationScope && branchOptionsError && (
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
                {orderedEvents.map((event) => (
                  <tr key={event.id}>
                    <td>{event.occurredAt || NO_VALUE}</td>
                    <td>{event.actor || NO_VALUE}</td>
                    <td>{event.role || NO_VALUE}</td>
                    <td>{event.scope || NO_VALUE}</td>
                    <td>{branchCell(event)}</td>
                    <td>{event.action || NO_VALUE}</td>
                    <td>{event.resourceType || NO_VALUE}</td>
                    <td>{event.resourceId || NO_VALUE}</td>
                    <td>{event.correlationId || NO_VALUE}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      )}
    </section>
  );
}
