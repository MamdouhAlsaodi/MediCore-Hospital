import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import BranchSelector from './BranchSelector.jsx';
import { saveSession } from '../../auth.js';

// Only the browser `fetch` boundary is mocked; the real adapter
// (actingContextApi.switchContext) runs, so the HTTP contract —
// POST /api/auth/context with the assignment id AND the selected branch id —
// is exercised for real. The server-owned organization view arrives as a
// prop exactly as the shell hands it over after fetching GET
// /api/organization through the same adapter.

const ORG_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const EAST_BRANCH_ID = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const WEST_BRANCH_ID = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';

const ORG_ADMIN_ASSIGNMENT = {
  id: '11111111-1111-4111-8111-111111111111',
  role: 'ADMIN',
  scope: 'ORGANIZATION',
  organizationId: ORG_ID,
  organizationLabel: 'Main Hospital Group',
  branchId: null,
  branchLabel: null,
  departmentId: null,
  departmentLabel: null,
  enabled: true,
};

const NURSE_BRANCH_ASSIGNMENT = {
  id: '22222222-2222-4222-8222-222222222222',
  role: 'NURSE',
  scope: 'BRANCH',
  organizationId: ORG_ID,
  organizationLabel: 'Main Hospital Group',
  branchId: EAST_BRANCH_ID,
  branchLabel: 'East Clinic',
  departmentId: null,
  departmentLabel: null,
  enabled: true,
};

const RECEPTIONIST_DEPARTMENT_ASSIGNMENT = {
  id: '33333333-3333-4333-8333-333333333333',
  role: 'RECEPTIONIST',
  scope: 'DEPARTMENT',
  organizationId: ORG_ID,
  organizationLabel: 'Main Hospital Group',
  branchId: WEST_BRANCH_ID,
  branchLabel: 'West Clinic',
  departmentId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
  departmentLabel: 'Outpatient Clinic',
  enabled: true,
};

// Server-owned organization view (GET /api/organization shape): the ACTIVE
// branches in server order. The last entry belongs to another organization
// and must never become a target of this organization's assignment.
const ORGANIZATION_VIEW = {
  id: ORG_ID,
  code: 'MHG',
  name: 'Main Hospital Group',
  activeBranches: [
    { id: EAST_BRANCH_ID, organizationId: ORG_ID, code: 'EAST', name: 'East Clinic', locationLabel: '1 East Way', active: true },
    { id: WEST_BRANCH_ID, organizationId: ORG_ID, code: 'WEST', name: 'West Clinic', locationLabel: '9 West Way', active: true },
    { id: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', organizationId: '99999999-9999-4999-8999-999999999999', code: 'OTHER', name: 'Foreign Org Branch', locationLabel: 'Elsewhere', active: true },
  ],
};

function sessionFor(actingAssignment, extraAssignments = [], actingBranchId = actingAssignment.branchId) {
  return {
    token: 'current-context-token',
    username: 'staffuser',
    roles: [actingAssignment.role],
    assignments: [actingAssignment, ...extraAssignments],
    actingContext: {
      username: 'staffuser',
      assignmentId: actingAssignment.id,
      role: actingAssignment.role,
      scope: actingAssignment.scope,
      organizationId: actingAssignment.organizationId,
      branchId: actingBranchId,
      departmentId: actingAssignment.departmentId ?? null,
    },
  };
}

function switchResponse(targetAssignment, targetBranchId) {
  return {
    accessToken: 'switched-context-token',
    tokenType: 'Bearer',
    username: 'staffuser',
    roles: [targetAssignment.role],
    assignments: [ORG_ADMIN_ASSIGNMENT, NURSE_BRANCH_ASSIGNMENT, RECEPTIONIST_DEPARTMENT_ASSIGNMENT],
    actingContext: {
      username: 'staffuser',
      assignmentId: targetAssignment.id,
      role: targetAssignment.role,
      scope: targetAssignment.scope,
      organizationId: targetAssignment.organizationId,
      branchId: targetBranchId,
      departmentId: targetAssignment.departmentId ?? null,
    },
  };
}

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function renderSelector(session, handlers = {}, organization = ORGANIZATION_VIEW, extraProps = {}) {
  const onContextSwitch = handlers.onContextSwitch ?? vi.fn();
  const onSessionExpired = handlers.onSessionExpired ?? vi.fn();
  render(
    <BranchSelector
      session={session}
      organization={organization}
      onContextSwitch={onContextSwitch}
      onSessionExpired={onSessionExpired}
      {...extraProps}
    />
  );
  return { onContextSwitch, onSessionExpired };
}

describe('BranchSelector', () => {
  let fetchMock;

  beforeEach(() => {
    sessionStorage.clear();
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  it('labels the select accessibly and offers exactly the fixed branch of a single BRANCH assignment', async () => {
    const session = sessionFor(NURSE_BRANCH_ASSIGNMENT);
    renderSelector(session);

    const select = screen.getByRole('combobox', { name: 'Acting context' });
    expect(select).toBeInTheDocument();
    expect(select).toHaveValue(`${NURSE_BRANCH_ASSIGNMENT.id}|${EAST_BRANCH_ID}`);
    const options = [...select.querySelectorAll('option')];
    expect(options).toHaveLength(1);
    expect(options[0]).toHaveTextContent('NURSE — East Clinic · Main Hospital Group');
    // The single-target case is named instead of leaving a silent control.
    expect(screen.getByText('The server issued one acting branch for your account.')).toBeInTheDocument();
  });

  it('derives ORGANIZATION targets only from the server active-branch allowlist of the assignment organization', async () => {
    // The acting organization context is branch-bound (East), as every
    // server-issued organization context is.
    renderSelector(sessionFor(ORG_ADMIN_ASSIGNMENT, [], EAST_BRANCH_ID));

    const options = [...screen.getByRole('combobox', { name: 'Acting context' }).querySelectorAll('option')];
    // Exactly the active branches of the assignment's own organization, in
    // server order — the foreign-organization entry is never offered.
    expect(options.map((option) => option.value)).toEqual([
      `${ORG_ADMIN_ASSIGNMENT.id}|${EAST_BRANCH_ID}`,
      `${ORG_ADMIN_ASSIGNMENT.id}|${WEST_BRANCH_ID}`,
    ]);
    expect(options[0]).toHaveTextContent('ADMIN — East Clinic · Main Hospital Group');
    expect(options[1]).toHaveTextContent('ADMIN — West Clinic · Main Hospital Group');
    // A branch-bound organization token is never labeled organization-wide.
    expect(screen.queryByText(/organization-wide/i)).not.toBeInTheDocument();
  });

  it('offers a fixed assignment exactly its server-issued branch even while other branches are active', () => {
    renderSelector(
      sessionFor(NURSE_BRANCH_ASSIGNMENT, [ORG_ADMIN_ASSIGNMENT, RECEPTIONIST_DEPARTMENT_ASSIGNMENT]),
    );

    const options = [...screen.getByRole('combobox', { name: 'Acting context' }).querySelectorAll('option')];
    expect(options.map((option) => option.value)).toEqual([
      `${NURSE_BRANCH_ASSIGNMENT.id}|${EAST_BRANCH_ID}`,
      `${ORG_ADMIN_ASSIGNMENT.id}|${EAST_BRANCH_ID}`,
      `${ORG_ADMIN_ASSIGNMENT.id}|${WEST_BRANCH_ID}`,
      `${RECEPTIONIST_DEPARTMENT_ASSIGNMENT.id}|${WEST_BRANCH_ID}`,
    ]);
    expect(options[3]).toHaveTextContent('RECEPTIONIST — Outpatient Clinic · West Clinic · Main Hospital Group');
    // The nurse cannot leave her fixed branch: no second NURSE option exists.
    expect(options.filter((option) => option.value.startsWith(NURSE_BRANCH_ASSIGNMENT.id))).toHaveLength(1);
  });

  it('switches the same organization assignment from branch A to branch B and sends both ids', async () => {
    const user = userEvent.setup();
    // Acting context: the ORGANIZATION assignment bound to East (branch A).
    fetchMock.mockResolvedValueOnce(jsonResponse(switchResponse(ORG_ADMIN_ASSIGNMENT, WEST_BRANCH_ID)));
    const { onContextSwitch, onSessionExpired } = renderSelector(
      sessionFor(ORG_ADMIN_ASSIGNMENT, [], EAST_BRANCH_ID),
    );
    const select = screen.getByRole('combobox', { name: 'Acting context' });
    expect(select).toHaveValue(`${ORG_ADMIN_ASSIGNMENT.id}|${EAST_BRANCH_ID}`);

    await user.selectOptions(select, `${ORG_ADMIN_ASSIGNMENT.id}|${WEST_BRANCH_ID}`);

    await waitFor(() => expect(onContextSwitch).toHaveBeenCalledTimes(1));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [path, options] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/auth/context');
    expect(options.method).toBe('POST');
    expect(options.headers.Authorization).toBe('Bearer current-context-token');
    // Same assignment, new branch: both ids travel to the server.
    expect(JSON.parse(options.body)).toEqual({
      assignmentId: ORG_ADMIN_ASSIGNMENT.id,
      branchId: WEST_BRANCH_ID,
    });
    // The replacement session is the complete parsed server structure.
    const switched = onContextSwitch.mock.calls[0][0];
    expect(switched.token).toBe('switched-context-token');
    expect(switched.actingContext).toMatchObject({
      assignmentId: ORG_ADMIN_ASSIGNMENT.id,
      branchId: WEST_BRANCH_ID,
      scope: 'ORGANIZATION',
    });
    expect(onSessionExpired).not.toHaveBeenCalled();
    expect(sessionStorage.getItem('medicore.session')).toBeNull();
  });

  it('rolls back cleanly on a 403 refusal: prior selection kept, denial shown, storage untouched', async () => {
    const user = userEvent.setup();
    fetchMock.mockResolvedValueOnce(jsonResponse({ error: 'Forbidden' }, 403));
    const { onContextSwitch, onSessionExpired } = renderSelector(
      sessionFor(ORG_ADMIN_ASSIGNMENT, [NURSE_BRANCH_ASSIGNMENT], EAST_BRANCH_ID),
    );
    const select = screen.getByRole('combobox', { name: 'Acting context' });

    await user.selectOptions(select, `${ORG_ADMIN_ASSIGNMENT.id}|${WEST_BRANCH_ID}`);

    const denial = await screen.findByRole('alert');
    expect(denial).toHaveTextContent(/refused this context switch/i);
    expect(denial).toHaveTextContent(/current role and branch are unchanged/i);
    // Prior login/token/context preserved: no switch handoff, no expiry.
    expect(onContextSwitch).not.toHaveBeenCalled();
    expect(onSessionExpired).not.toHaveBeenCalled();
    expect(select).toHaveValue(`${ORG_ADMIN_ASSIGNMENT.id}|${EAST_BRANCH_ID}`);
    expect(select).toBeEnabled();
    // Storage boundary: a failed switch never partially updates storage.
    expect(sessionStorage.getItem('medicore.session')).toBeNull();
  });

  it('reports network and other failures in place while preserving the current context', async () => {
    const user = userEvent.setup();
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    const { onContextSwitch, onSessionExpired } = renderSelector(
      sessionFor(NURSE_BRANCH_ASSIGNMENT, [ORG_ADMIN_ASSIGNMENT]),
    );

    await user.selectOptions(
      screen.getByRole('combobox', { name: 'Acting context' }),
      `${ORG_ADMIN_ASSIGNMENT.id}|${WEST_BRANCH_ID}`,
    );

    expect(await screen.findByRole('alert')).toHaveTextContent(/network error/i);
    expect(onContextSwitch).not.toHaveBeenCalled();
    expect(onSessionExpired).not.toHaveBeenCalled();
    expect(screen.getByRole('combobox', { name: 'Acting context' })).toHaveValue(
      `${NURSE_BRANCH_ASSIGNMENT.id}|${EAST_BRANCH_ID}`,
    );
  });

  it('clears the session path on 401 by invoking the expiry handler instead of showing a denial', async () => {
    const user = userEvent.setup();
    fetchMock.mockResolvedValueOnce(jsonResponse({ error: 'expired' }, 401));
    const { onContextSwitch, onSessionExpired } = renderSelector(
      sessionFor(NURSE_BRANCH_ASSIGNMENT, [ORG_ADMIN_ASSIGNMENT]),
    );

    await user.selectOptions(
      screen.getByRole('combobox', { name: 'Acting context' }),
      `${ORG_ADMIN_ASSIGNMENT.id}|${WEST_BRANCH_ID}`,
    );

    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(1));
    expect(onContextSwitch).not.toHaveBeenCalled();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows a neutral loading state while branch options are pending, never an organization-wide claim', async () => {
    const user = userEvent.setup();
    renderSelector(
      sessionFor(ORG_ADMIN_ASSIGNMENT, [], EAST_BRANCH_ID),
      {},
      null,
      { organizationLoading: true },
    );
    const select = screen.getByRole('combobox', { name: 'Acting context' });

    // The still-current pair is held open by a neutral loading entry; no
    // branch target exists yet and nothing implies organization-wide scope.
    expect(select).toHaveValue(`${ORG_ADMIN_ASSIGNMENT.id}|${EAST_BRANCH_ID}`);
    expect([...select.querySelectorAll('option')].map((option) => option.textContent))
      .toEqual(['Loading branch…']);
    expect(screen.getByRole('status')).toHaveTextContent(/loading branch options/i);
    expect(screen.queryByText(/organization-wide/i)).not.toBeInTheDocument();

    // Selecting the loading entry fires nothing — it is the current pair.
    await user.selectOptions(select, `${ORG_ADMIN_ASSIGNMENT.id}|${EAST_BRANCH_ID}`);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('shows accessible text and keeps the current context when branch options fail to load', () => {
    renderSelector(
      sessionFor(ORG_ADMIN_ASSIGNMENT, [NURSE_BRANCH_ASSIGNMENT], EAST_BRANCH_ID),
      {},
      null,
      { organizationError: 'Branch options could not be loaded (403). Your current role and branch are unchanged.' },
    );

    // The refusal text is on screen (role="alert"), the current context is
    // retained, and only fixed targets are offered — no invented org target.
    expect(screen.getByRole('alert')).toHaveTextContent(/branch options could not be loaded/i);
    const options = [...screen.getByRole('combobox', { name: 'Acting context' }).querySelectorAll('option')];
    expect(options.map((option) => option.value)).toEqual([
      `${NURSE_BRANCH_ASSIGNMENT.id}|${EAST_BRANCH_ID}`,
      `${ORG_ADMIN_ASSIGNMENT.id}|${EAST_BRANCH_ID}`,
    ]);
    // The acting organization pair itself is absent from the active list, so
    // an honest unavailable entry holds the selection — still not org-wide.
    const select = screen.getByRole('combobox', { name: 'Acting context' });
    expect(select).toHaveValue(`${ORG_ADMIN_ASSIGNMENT.id}|${EAST_BRANCH_ID}`);
    expect(select).toHaveTextContent('Current branch unavailable');
  });

  it('never reads prior sessions from storage to build options: the session prop is the only source', () => {
    saveSession(sessionFor(ORG_ADMIN_ASSIGNMENT));
    renderSelector(sessionFor(NURSE_BRANCH_ASSIGNMENT));

    const options = [...screen.getByRole('combobox', { name: 'Acting context' }).querySelectorAll('option')];
    expect(options.map((option) => option.value)).toEqual([`${NURSE_BRANCH_ASSIGNMENT.id}|${EAST_BRANCH_ID}`]);
  });

  it('reaches the select and switches from the keyboard', async () => {
    const user = userEvent.setup();
    fetchMock.mockResolvedValueOnce(jsonResponse(switchResponse(ORG_ADMIN_ASSIGNMENT, WEST_BRANCH_ID)));
    renderSelector(sessionFor(NURSE_BRANCH_ASSIGNMENT, [ORG_ADMIN_ASSIGNMENT]));
    const select = screen.getByRole('combobox', { name: 'Acting context' });

    // Keyboard: focus the labeled native select, then change the selection.
    select.focus();
    expect(select).toHaveFocus();
    await user.selectOptions(select, `${ORG_ADMIN_ASSIGNMENT.id}|${WEST_BRANCH_ID}`);

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
      assignmentId: ORG_ADMIN_ASSIGNMENT.id,
      branchId: WEST_BRANCH_ID,
    });
  });

  it('shows an understandable busy state while the switch is in flight', async () => {
    const user = userEvent.setup();
    let resolveSwitch;
    fetchMock.mockReturnValueOnce(new Promise((resolve) => { resolveSwitch = resolve; }));
    renderSelector(sessionFor(NURSE_BRANCH_ASSIGNMENT, [ORG_ADMIN_ASSIGNMENT]));

    await user.selectOptions(
      screen.getByRole('combobox', { name: 'Acting context' }),
      `${ORG_ADMIN_ASSIGNMENT.id}|${WEST_BRANCH_ID}`,
    );

    const select = screen.getByRole('combobox', { name: 'Acting context' });
    expect(select).toBeDisabled();
    expect(screen.getByRole('status')).toHaveTextContent(/switching acting context/i);

    resolveSwitch(jsonResponse(switchResponse(ORG_ADMIN_ASSIGNMENT, WEST_BRANCH_ID)));
    await waitFor(() => expect(select).toBeEnabled());
  });

  it('shows an honest status when the session carries no usable acting assignment', () => {
    renderSelector({
      token: 'orphan-token',
      username: 'staffuser',
      roles: ['NURSE'],
      assignments: [],
      actingContext: null,
    }, {}, ORGANIZATION_VIEW);

    expect(screen.getByRole('status')).toHaveTextContent(/branch context unavailable/i);
    expect(screen.queryByRole('combobox', { name: 'Acting context' })).not.toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
