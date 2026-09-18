import React, { useState } from 'react';
import { ApiError } from '../../api.js';
import { actingAssignmentOf, actingContextIdentity } from '../../auth.js';
import { switchContext } from '../branches/actingContextApi.js';

// A switch target is one (assignment, hospital, branch) triple, keyed by
// all three ids: two assignments may share a branch, one selection-scope
// assignment (ORGANIZATION, HOSPITAL) spans several hospitals/branches,
// and Phase 5 authority is hospital-scoped — so no smaller identity selects
// unambiguously (Phase 5, FR-007/FR-008).
function targetKey(assignmentId, hospitalId, branchId) {
  return `${assignmentId}|${hospitalId}|${branchId}`;
}

/** True when any assignment lets its holder select among network targets. */
export function hasSelectionScope(assignments) {
  return (Array.isArray(assignments) ? assignments : []).some(
    (assignment) => (assignment?.scope === 'ORGANIZATION' || assignment?.scope === 'HOSPITAL')
      && assignment?.id
  );
}

// Targets come ONLY from server-issued data. For the selection scopes that
// is the authorized network hierarchy the server derives from the acting
// context (GET /api/network/hierarchy, fetched by the shell): every ACTIVE
// hospital/branch pair of that slice — and, for a HOSPITAL assignment,
// only the pairs inside the facility the assignment itself fixes. For the
// fixed scopes (BRANCH, DEPARTMENT) it is the assignment view's own pair.
// A hierarchy that has not loaded yet yields no selection targets — never
// a placeholder that lets the server pick a target the UI did not choose —
// and no id from storage, a request body, or a header is ever one.
export function selectorTargets(assignments, hierarchy) {
  const hospitals = Array.isArray(hierarchy?.hospitals) ? hierarchy.hospitals : [];
  const targets = [];
  for (const assignment of Array.isArray(assignments) ? assignments : []) {
    if (!assignment?.id || !assignment?.role) continue;
    const organizationName = assignment.organizationLabel ?? 'organization';
    if (assignment.scope === 'ORGANIZATION' || assignment.scope === 'HOSPITAL') {
      for (const hospital of hospitals) {
        if (hospital?.active !== true || !hospital.id) continue;
        if (assignment.scope === 'HOSPITAL' && hospital.id !== assignment.hospitalId) continue;
        for (const branch of Array.isArray(hospital.branches) ? hospital.branches : []) {
          if (branch?.active !== true || !branch.id || branch.hospitalId !== hospital.id) continue;
          targets.push({
            key: targetKey(assignment.id, hospital.id, branch.id),
            assignmentId: assignment.id,
            hospitalId: hospital.id,
            branchId: branch.id,
            label: `${assignment.role} — ${branch.name} · ${hospital.name} · ${organizationName}`,
          });
        }
      }
      continue;
    }
    if (!assignment.branchId) continue;
    const location = [
      assignment.scope === 'DEPARTMENT' ? assignment.departmentLabel ?? 'department' : assignment.branchLabel ?? 'branch',
      ...(assignment.scope === 'DEPARTMENT' ? [assignment.branchLabel ?? 'branch'] : []),
      ...(assignment.hospitalLabel ? [assignment.hospitalLabel] : []),
    ];
    targets.push({
      key: targetKey(assignment.id, assignment.hospitalId ?? '', assignment.branchId),
      assignmentId: assignment.id,
      hospitalId: assignment.hospitalId ?? null,
      branchId: assignment.branchId,
      label: `${assignment.role} — ${location.join(' · ')} · ${organizationName}`,
    });
  }
  return targets;
}

// Hospital-aware acting-context selector (Phase 5 US2, tasks.md T057).
// Lists only the (assignment, hospital, branch) triples the server issued
// for this session and switches by calling POST /api/auth/context through
// the actingContextApi adapter with all three ids. The current selection is
// the triple matching the acting context's assignment AND bound branch —
// recognized even while the context record omits the hospital field (the
// frozen login parser), because a branch belongs to exactly one hospital.
//   - Success: the parsed complete session goes up via onContextSwitch; the
//     app atomically replaces the stored token + context and remounts the
//     context-bound views. This component keeps no local copy of the
//     session, so it can never half-apply a switch.
//   - 401: reported to onSessionExpired — the shell owns session expiry.
//   - 403 (or any other failure): the prior session is untouched, the
//     select stays bound to the still-current context, and an in-place
//     text denial (role="alert", never color-only) explains the refusal.
// Accessibility: a labeled native select (keyboard-operable by default),
// explicit busy and loading statuses (role="status"), and a help line that
// names the single-target case instead of leaving a silent control.
export default function NetworkContextSelector({
  session,
  hierarchy = null,
  hierarchyLoading = false,
  hierarchyError = '',
  onContextSwitch,
  onSessionExpired,
}) {
  const [busy, setBusy] = useState(false);
  const [denial, setDenial] = useState('');

  const assignments = Array.isArray(session?.assignments) ? session.assignments : [];
  const actingAssignment = actingAssignmentOf(session);
  const identity = actingContextIdentity(session);
  const targets = selectorTargets(assignments, hierarchy);
  // Current-target recognition goes by the server-bound (assignment,
  // branch) pair; the hospital slot of the target key is server data.
  const currentKey = identity
    ? targets.find(
        (target) => target.assignmentId === identity.assignmentId
          && target.branchId === identity.branchId
      )?.key ?? ''
    : '';
  const networkPending = hasSelectionScope(assignments) && !hierarchy && !hierarchyError;

  // Without a server-issued acting assignment there is nothing selectable;
  // show that honestly rather than a control that implies client authority.
  if (!actingAssignment) {
    return (
      <div className="branch-selector">
        <p className="branch-selector-empty" role="status">
          Acting context unavailable — the server session lists no acting assignment.
        </p>
      </div>
    );
  }

  async function handleChange(event) {
    const key = event.target.value;
    // Same selection and busy states are no-ops; unknown keys are ignored —
    // only server-issued triples are switchable.
    if (busy || !key || key === currentKey) return;
    const target = targets.find((candidate) => candidate.key === key);
    if (!target) return;

    setBusy(true);
    setDenial('');
    try {
      const nextSession = await switchContext({
        token: session.token,
        assignmentId: target.assignmentId,
        hospitalId: target.hospitalId,
        branchId: target.branchId,
        onUnauthorized: onSessionExpired,
      });
      onContextSwitch(nextSession);
    } catch (error) {
      // 401 is ownership of the shell: the session-expiry callback returns
      // the app to Login, so no local denial is raised on top of it.
      if (!(error instanceof ApiError && error.status === 401)) {
        setDenial(
          error && error.message
            ? error.message
            : 'The context switch failed. Your current role and branch are unchanged.'
        );
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="branch-selector">
      <label htmlFor="acting-context-select">Acting context</label>
      <select
        id="acting-context-select"
        name="acting-context"
        value={currentKey}
        onChange={handleChange}
        disabled={busy}
        aria-describedby="acting-context-help"
      >
        {targets.map((target) => (
          <option key={target.key} value={target.key}>
            {target.label}
          </option>
        ))}
        {!currentKey && (
          // The current context is never dropped from the select: while the
          // server hierarchy loads a neutral loading entry holds the
          // selection, and if the acting pair is absent from the authorized
          // slice an honest unavailable entry does — never an invented
          // "organization-wide" claim.
          <option value="" disabled>
            {networkPending || hierarchyLoading ? 'Loading branch…' : 'Current branch unavailable'}
          </option>
        )}
      </select>
      <p id="acting-context-help" className="branch-selector-help">
        {networkPending
          ? 'Loading the hospital and branch options the server issued for your account.'
          : targets.length === 1
            ? 'The server issued one acting hospital branch for your account.'
            : 'Switch between the hospital and branch pairs the server issued to you.'}
      </p>
      {networkPending && (
        <p className="branch-selector-status" role="status">Loading network options…</p>
      )}
      {busy && (
        <p className="branch-selector-status" role="status">Switching acting context…</p>
      )}
      {hierarchyError && (
        <p className="branch-selector-denial" role="alert">{hierarchyError}</p>
      )}
      {denial && (
        <p className="branch-selector-denial" role="alert">{denial}</p>
      )}
    </div>
  );
}
