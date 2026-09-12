import React, { useState } from 'react';
import { ApiError } from '../../api.js';
import { actingAssignmentOf } from '../../auth.js';
import { switchContext } from './actingContextApi.js';

// A switch target is one (assignment, branch) pair, keyed by both ids: two
// assignments may share a branch and one ORGANIZATION assignment spans
// several branches, so the assignment id alone is never a selection
// identity.
function targetKey(assignmentId, branchId) {
  return `${assignmentId}|${branchId}`;
}

// Targets come ONLY from server-issued data: the assignment view for
// BRANCH/DEPARTMENT scopes (each offers exactly its fixed branch) and, for
// ORGANIZATION scopes, the server's active-branch allowlist filtered to the
// assignment's own organization. An organization view that has not loaded
// yet (or failed to load) yields no organization targets — never a
// placeholder that lets the server pick a branch the UI did not choose.
function selectorTargets(assignments, organization) {
  const activeBranches = Array.isArray(organization?.activeBranches)
    ? organization.activeBranches
    : [];
  const targets = [];
  for (const assignment of assignments) {
    if (!assignment?.id || !assignment?.role) continue;
    const organizationName = assignment.organizationLabel ?? 'organization';
    if (assignment.scope === 'ORGANIZATION') {
      for (const branch of activeBranches) {
        if (!branch?.id || branch.organizationId !== assignment.organizationId) continue;
        targets.push({
          key: targetKey(assignment.id, branch.id),
          assignmentId: assignment.id,
          branchId: branch.id,
          label: `${assignment.role} — ${branch.name} · ${organizationName}`,
        });
      }
      continue;
    }
    if (!assignment.branchId) continue;
    targets.push({
      key: targetKey(assignment.id, assignment.branchId),
      assignmentId: assignment.id,
      branchId: assignment.branchId,
      label: assignment.scope === 'DEPARTMENT'
        ? `${assignment.role} — ${assignment.departmentLabel ?? 'department'} · ${assignment.branchLabel ?? 'branch'} · ${organizationName}`
        : `${assignment.role} — ${assignment.branchLabel ?? 'branch'} · ${organizationName}`,
    });
  }
  return targets;
}

// Branch-aware acting-context selector (docs/plan3.md Task 5). Lists only
// the (assignment, branch) pairs the server issued for this session and
// switches by calling POST /api/auth/context through the actingContextApi
// adapter. The current selection is the pair matching the acting context's
// assignment AND bound branch, so the same organization assignment can be
// switched from one branch to another.
//   - Success: the parsed complete session goes up via onContextSwitch; the
//     app atomically replaces the stored token + context and reloads the
//     branch-scoped view. This component keeps no local copy of the session,
//     so it can never half-apply a switch.
//   - 401: reported to onSessionExpired — the shell owns session expiry.
//   - 403 (or any other failure): the prior session is untouched, the
//     select stays bound to the still-current context, and an in-place
//     text denial (role="alert", never color-only) explains the refusal.
// Accessibility: a labeled native select (keyboard-operable by default),
// explicit busy and loading statuses (role="status"), and a help line that
// names the single-target case instead of leaving a silent control. Every
// branch label is honest: a branch-bound token is never described as
// "organization-wide"; while branch options load a neutral loading state
// shows instead.
export default function BranchSelector({
  session,
  organization,
  organizationLoading = false,
  organizationError = '',
  onContextSwitch,
  onSessionExpired,
}) {
  const [busy, setBusy] = useState(false);
  const [denial, setDenial] = useState('');

  const assignments = Array.isArray(session?.assignments) ? session.assignments : [];
  const actingAssignment = actingAssignmentOf(session);
  const actingBranchId = session?.actingContext?.branchId ?? '';
  const currentKey = actingAssignment && actingBranchId
    ? targetKey(actingAssignment.id, actingBranchId)
    : '';

  const targets = selectorTargets(assignments, organization);
  const hasOrganizationAssignment = assignments.some(
    (assignment) => assignment?.scope === 'ORGANIZATION' && assignment?.id
  );
  const branchOptionsPending = hasOrganizationAssignment && !organization && !organizationError;
  const actingTargeted = targets.some((target) => target.key === currentKey);

  // Without a server-issued acting assignment there is nothing selectable;
  // show that honestly rather than a control that implies client authority.
  if (!actingAssignment) {
    return (
      <div className="branch-selector">
        <p className="branch-selector-empty" role="status">
          Branch context unavailable — the server session lists no acting assignment.
        </p>
      </div>
    );
  }

  async function handleChange(event) {
    const key = event.target.value;
    // Same (assignment, branch) selection and busy states are no-ops;
    // unknown targets are ignored — only server-issued pairs are switchable.
    if (busy || !key || key === currentKey) return;
    const target = targets.find((candidate) => candidate.key === key);
    if (!target) return;

    setBusy(true);
    setDenial('');
    try {
      const nextSession = await switchContext({
        token: session.token,
        assignmentId: target.assignmentId,
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
        {!actingTargeted && (
          // The current (assignment, branch) pair is never dropped from the
          // select: while the server branch list loads a neutral loading
          // entry holds the selection, and if the acting branch is absent
          // from the active list an honest unavailable entry does — never an
          // invented "organization-wide" claim.
          <option value={currentKey} disabled>
            {branchOptionsPending ? 'Loading branch…' : 'Current branch unavailable'}
          </option>
        )}
      </select>
      <p id="acting-context-help" className="branch-selector-help">
        {branchOptionsPending
          ? 'Loading the branch options the server issued for your account.'
          : targets.length === 1
            ? 'The server issued one acting branch for your account.'
            : 'Switch between the acting assignment and branch pairs the server issued to you.'}
      </p>
      {branchOptionsPending && (
        <p className="branch-selector-status" role="status">Loading branch options…</p>
      )}
      {busy && (
        <p className="branch-selector-status" role="status">Switching acting context…</p>
      )}
      {organizationError && (
        <p className="branch-selector-denial" role="alert">{organizationError}</p>
      )}
      {denial && (
        <p className="branch-selector-denial" role="alert">{denial}</p>
      )}
    </div>
  );
}
