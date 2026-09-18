import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import NetworkContextSelector, { hasSelectionScope, selectorTargets } from './NetworkContextSelector.jsx';
import { ApiError } from '../../api.js';

// Phase 5 US2 (tasks.md T056/T057; FR-007/FR-008): the hospital-aware
// acting-context selector. Every switch target comes from server-issued
// data only — the network hierarchy the server derives from the acting
// context for the selection scopes (ORGANIZATION, HOSPITAL), and the
// assignment view itself for the fixed scopes (BRANCH, DEPARTMENT). The
// client can never construct, widen, or free-form a target: a switch
// submits exactly one server-issued (assignment, hospital, branch) triple
// to POST /api/auth/context, and the server re-derives and revalidates the
// whole chain before issuing a replacement context.

const NETWORK_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const EAST_HOSPITAL_ID = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee01';
const WEST_HOSPITAL_ID = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee02';
const EAST_BRANCH_ID = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const EAST_WING_BRANCH_ID = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2';
const WEST_BRANCH_ID = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';
const EAST_DEPARTMENT_ID = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd';

// Parsed GET /api/network/hierarchy view (the strict shape networkApi.js
// returns): the server's authorized slice in its own deterministic order.
const HIERARCHY = {
  organizationId: NETWORK_ID,
  organizationCode: 'NET',
  organizationName: 'Synthetic Network',
  hospitals: [
    {
      id: EAST_HOSPITAL_ID,
      code: 'EAST-H',
      name: 'Demo East Hospital',
      regionLabel: 'East Region',
      timeZone: 'UTC',
      active: true,
      branches: [
        { id: EAST_BRANCH_ID, hospitalId: EAST_HOSPITAL_ID, code: 'EAST', name: 'East Clinic', timeZone: 'UTC', active: true },
        { id: EAST_WING_BRANCH_ID, hospitalId: EAST_HOSPITAL_ID, code: 'EAST-W', name: 'East Wing', timeZone: 'UTC', active: true },
      ],
    },
    {
      id: WEST_HOSPITAL_ID,
      code: 'WEST-H',
      name: 'Demo West Hospital',
      regionLabel: 'West Region',
      timeZone: 'America/New_York',
      active: true,
      branches: [
        { id: WEST_BRANCH_ID, hospitalId: WEST_HOSPITAL_ID, code: 'WEST', name: 'West Clinic', timeZone: 'America/New_York', active: true },
      ],
    },
  ],
};

// A hierarchy slice the server never issues for an authorized actor: an
// inactive hospital, an inactive branch, and (below) a smuggled foreign
// branch. If a selector target ever carries one, the client expanded scope.
const EAST_WITH_DORMANT = {
  ...HIERARCHY.hospitals[0],
  branches: [
    ...HIERARCHY.hospitals[0].branches,
    { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb8', hospitalId: EAST_HOSPITAL_ID, code: 'DORMANT', name: 'Dormant Clinic', timeZone: 'UTC', active: false },
  ],
};
const INACTIVE_HIERARCHY = {
  ...HIERARCHY,
  hospitals: [
    EAST_WITH_DORMANT,
    HIERARCHY.hospitals[1],
    {
      id: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee09',
      code: 'OLD-H',
      name: 'Retired Hospital',
      regionLabel: 'Nowhere',
      timeZone: 'UTC',
      active: false,
      branches: [
        { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb9', hospitalId: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee09', code: 'OLD', name: 'Retired Clinic', timeZone: 'UTC', active: true },
      ],
    },
  ],
};

function assignmentFor(scope) {
  const shared = {
    organizationId: NETWORK_ID,
    organizationLabel: 'Synthetic Network',
    departmentId: null,
    departmentLabel: null,
    enabled: true,
  };
  if (scope === 'ORGANIZATION') {
    return {
      id: '11111111-1111-4111-8111-111111111111',
      role: 'ADMIN',
      scope,
      ...shared,
      hospitalId: null,
      hospitalLabel: null,
      branchId: null,
      branchLabel: null,
    };
  }
  if (scope === 'HOSPITAL') {
    return {
      id: '22222222-2222-4222-8222-222222222222',
      role: 'ADMIN',
      scope,
      ...shared,
      hospitalId: EAST_HOSPITAL_ID,
      hospitalLabel: 'Demo East Hospital',
      branchId: null,
      branchLabel: null,
    };
  }
  if (scope === 'DEPARTMENT') {
    return {
      id: '33333333-3333-4333-8333-333333333333',
      role: 'RECEPTIONIST',
      scope,
      ...shared,
      hospitalId: WEST_HOSPITAL_ID,
      hospitalLabel: 'Demo West Hospital',
      branchId: WEST_BRANCH_ID,
      branchLabel: 'West Clinic',
      departmentId: EAST_DEPARTMENT_ID,
      departmentLabel: 'Outpatient Clinic',
    };
  }
  return {
    id: '44444444-4444-4444-8444-444444444444',
    role: 'NURSE',
    scope: 'BRANCH',
    ...shared,
    hospitalId: EAST_HOSPITAL_ID,
    hospitalLabel: 'Demo East Hospital',
    branchId: EAST_BRANCH_ID,
    branchLabel: 'East Clinic',
  };
}

const ORG_ASSIGNMENT = assignmentFor('ORGANIZATION');
const HOSPITAL_ASSIGNMENT = assignmentFor('HOSPITAL');
const BRANCH_ASSIGNMENT = assignmentFor('BRANCH');
const DEPARTMENT_ASSIGNMENT = assignmentFor('DEPARTMENT');

function sessionFor(actingAssignment, extraAssignments = []) {
  return {
    token: 'synthetic-context-token',
    username: 'staffuser',
    roles: [actingAssignment.role],
    assignments: [actingAssignment, ...extraAssignments],
    actingContext: {
      username: 'staffuser',
      assignmentId: actingAssignment.id,
      role: actingAssignment.role,
      scope: actingAssignment.scope,
      organizationId: actingAssignment.organizationId,
      hospitalId: actingAssignment.hospitalId,
      branchId: actingAssignment.branchId ?? EAST_BRANCH_ID,
      departmentId: actingAssignment.departmentId ?? null,
    },
  };
}

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function renderSelector(session, { hierarchy = HIERARCHY, hierarchyLoading = false, hierarchyError = '', onContextSwitch = vi.fn(), onSessionExpired = vi.fn() } = {}) {
  return render(
    <NetworkContextSelector
      session={session}
      hierarchy={hierarchy}
      hierarchyLoading={hierarchyLoading}
      hierarchyError={hierarchyError}
      onContextSwitch={onContextSwitch}
      onSessionExpired={onSessionExpired}
    />
  );
}

function selectOptions(select) {
  return [...select.querySelectorAll('option')].map((option) => ({
    value: option.value,
    text: option.textContent,
    disabled: option.disabled,
  }));
}

describe('network selector target derivation (Phase 5 T056)', () => {
  it('offers an ORGANIZATION assignment exactly the server-issued active hospital/branch pairs', () => {
    const targets = selectorTargets([ORG_ASSIGNMENT], HIERARCHY);
    expect(targets.map((target) => target.key)).toEqual([
      `${ORG_ASSIGNMENT.id}|${EAST_HOSPITAL_ID}|${EAST_BRANCH_ID}`,
      `${ORG_ASSIGNMENT.id}|${EAST_HOSPITAL_ID}|${EAST_WING_BRANCH_ID}`,
      `${ORG_ASSIGNMENT.id}|${WEST_HOSPITAL_ID}|${WEST_BRANCH_ID}`,
    ]);
    expect(targets[0]).toMatchObject({
      assignmentId: ORG_ASSIGNMENT.id,
      hospitalId: EAST_HOSPITAL_ID,
      branchId: EAST_BRANCH_ID,
    });
    expect(targets[0].label).toBe('ADMIN — East Clinic · Demo East Hospital · Synthetic Network');
    expect(targets[2].label).toBe('ADMIN — West Clinic · Demo West Hospital · Synthetic Network');
  });

  it('never derives a target from inactive hospitals or branches', () => {
    const targets = selectorTargets([ORG_ASSIGNMENT], INACTIVE_HIERARCHY);
    expect(targets.map((target) => target.key)).toEqual([
      `${ORG_ASSIGNMENT.id}|${EAST_HOSPITAL_ID}|${EAST_BRANCH_ID}`,
      `${ORG_ASSIGNMENT.id}|${EAST_HOSPITAL_ID}|${EAST_WING_BRANCH_ID}`,
      `${ORG_ASSIGNMENT.id}|${WEST_HOSPITAL_ID}|${WEST_BRANCH_ID}`,
    ]);
  });

  it('constrains a HOSPITAL assignment to the branches of its own fixed facility', () => {
    const targets = selectorTargets([HOSPITAL_ASSIGNMENT], HIERARCHY);
    expect(targets.map((target) => target.key)).toEqual([
      `${HOSPITAL_ASSIGNMENT.id}|${EAST_HOSPITAL_ID}|${EAST_BRANCH_ID}`,
      `${HOSPITAL_ASSIGNMENT.id}|${EAST_HOSPITAL_ID}|${EAST_WING_BRANCH_ID}`,
    ]);
    // Even a malformed hierarchy row naming another hospital never leaks in.
    const hostile = {
      ...HIERARCHY,
      hospitals: [{
        ...HIERARCHY.hospitals[0],
        branches: [
          ...HIERARCHY.hospitals[0].branches,
          { id: WEST_BRANCH_ID, hospitalId: WEST_HOSPITAL_ID, code: 'SMUGGLED', name: 'Foreign Branch', timeZone: 'UTC', active: true },
        ],
      }],
    };
    expect(selectorTargets([HOSPITAL_ASSIGNMENT], hostile).map((target) => target.branchId))
      .toEqual([EAST_BRANCH_ID, EAST_WING_BRANCH_ID]);
  });

  it('offers a BRANCH assignment exactly its own fixed server-issued pair without needing the hierarchy', () => {
    const targets = selectorTargets([BRANCH_ASSIGNMENT], null);
    expect(targets).toHaveLength(1);
    expect(targets[0]).toMatchObject({
      assignmentId: BRANCH_ASSIGNMENT.id,
      hospitalId: EAST_HOSPITAL_ID,
      branchId: EAST_BRANCH_ID,
      key: `${BRANCH_ASSIGNMENT.id}|${EAST_HOSPITAL_ID}|${EAST_BRANCH_ID}`,
    });
    expect(targets[0].label).toBe('NURSE — East Clinic · Demo East Hospital · Synthetic Network');
  });

  it('offers a DEPARTMENT assignment its fixed pair and names the department in the label', () => {
    const targets = selectorTargets([DEPARTMENT_ASSIGNMENT], null);
    expect(targets).toHaveLength(1);
    expect(targets[0]).toMatchObject({
      assignmentId: DEPARTMENT_ASSIGNMENT.id,
      hospitalId: WEST_HOSPITAL_ID,
      branchId: WEST_BRANCH_ID,
    });
    expect(targets[0].label).toBe(
      'RECEPTIONIST — Outpatient Clinic · West Clinic · Demo West Hospital · Synthetic Network'
    );
  });

  it('offers nothing for a selection scope while the server hierarchy has not loaded', () => {
    expect(selectorTargets([ORG_ASSIGNMENT], null)).toEqual([]);
    expect(selectorTargets([HOSPITAL_ASSIGNMENT], null)).toEqual([]);
    expect(hasSelectionScope([ORG_ASSIGNMENT])).toBe(true);
    expect(hasSelectionScope([HOSPITAL_ASSIGNMENT])).toBe(true);
    expect(hasSelectionScope([BRANCH_ASSIGNMENT])).toBe(false);
    expect(hasSelectionScope([DEPARTMENT_ASSIGNMENT])).toBe(false);
    expect(hasSelectionScope([])).toBe(false);
  });

  it('recognizes the acting context as a current target even when its record omits the hospital', () => {
    // The frozen login parser stores the context without the hospital
    // field; recognition goes by the (assignment, branch) pair the server
    // bound, never by a client-invented hospital id.
    const session = sessionFor(BRANCH_ASSIGNMENT);
    delete session.actingContext.hospitalId;
    renderSelector(session, { hierarchy: null });
    const select = screen.getByRole('combobox', { name: 'Acting context' });
    expect(select).toHaveValue(`${BRANCH_ASSIGNMENT.id}|${EAST_HOSPITAL_ID}|${EAST_BRANCH_ID}`);
    expect(select.querySelectorAll('option')).toHaveLength(1);
  });
});

describe('network selector switching contract (Phase 5 T057)', () => {
  let fetchMock;

  beforeEach(() => {
    fetchMock = vi.fn(() =>
      Promise.resolve(jsonResponse({
        accessToken: 'switched-context-token',
        tokenType: 'Bearer',
        username: 'staffuser',
        roles: ['ADMIN'],
        assignments: [ORG_ASSIGNMENT],
        actingContext: {
          username: 'staffuser',
          assignmentId: ORG_ASSIGNMENT.id,
          role: 'ADMIN',
          scope: 'ORGANIZATION',
          organizationId: NETWORK_ID,
          hospitalId: WEST_HOSPITAL_ID,
          branchId: WEST_BRANCH_ID,
          departmentId: null,
        },
      }))
    );
    vi.stubGlobal('fetch', fetchMock);
  });

  it('submits the server-issued assignment, hospital, and branch triple to the switch endpoint', async () => {
    const user = userEvent.setup();
    const onContextSwitch = vi.fn();
    let resolveSwitch;
    fetchMock = vi.fn(() => new Promise((resolve) => { resolveSwitch = resolve; }));
    vi.stubGlobal('fetch', fetchMock);
    renderSelector(sessionFor(BRANCH_ASSIGNMENT, [ORG_ASSIGNMENT]), { onContextSwitch });

    const select = screen.getByRole('combobox', { name: 'Acting context' });
    await waitFor(() => expect(select.querySelectorAll('option')).toHaveLength(4));
    await user.selectOptions(
      select,
      `${ORG_ASSIGNMENT.id}|${WEST_HOSPITAL_ID}|${WEST_BRANCH_ID}`,
    );

    // The in-flight switch disables the control and announces itself.
    expect(screen.getByRole('status')).toHaveTextContent('Switching acting context…');
    expect(select).toBeDisabled();
    resolveSwitch(jsonResponse({
      accessToken: 'switched-context-token',
      tokenType: 'Bearer',
      username: 'staffuser',
      roles: ['ADMIN'],
      assignments: [ORG_ASSIGNMENT],
      actingContext: {
        username: 'staffuser',
        assignmentId: ORG_ASSIGNMENT.id,
        role: 'ADMIN',
        scope: 'ORGANIZATION',
        organizationId: NETWORK_ID,
        hospitalId: WEST_HOSPITAL_ID,
        branchId: WEST_BRANCH_ID,
        departmentId: null,
      },
    }));

    await waitFor(() => expect(onContextSwitch).toHaveBeenCalledTimes(1));
    expect(onContextSwitch.mock.calls[0][0].token).toBe('switched-context-token');
    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument());
    const [path, options] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/auth/context');
    expect(options.method).toBe('POST');
    expect(options.headers.Authorization).toBe('Bearer synthetic-context-token');
    expect(JSON.parse(options.body)).toEqual({
      assignmentId: ORG_ASSIGNMENT.id,
      hospitalId: WEST_HOSPITAL_ID,
      branchId: WEST_BRANCH_ID,
    });
  });

  it('keeps the prior context and shows an accessible in-place denial on a 403 refusal', async () => {
    const user = userEvent.setup();
    fetchMock = vi.fn(() =>
      Promise.resolve(jsonResponse({ error: 'forbidden' }, 403))
    );
    vi.stubGlobal('fetch', fetchMock);
    const onContextSwitch = vi.fn();
    const onSessionExpired = vi.fn();
    renderSelector(sessionFor(BRANCH_ASSIGNMENT, [ORG_ASSIGNMENT]), { onContextSwitch, onSessionExpired });

    const select = screen.getByRole('combobox', { name: 'Acting context' });
    await waitFor(() => expect(select.querySelectorAll('option')).toHaveLength(4));
    await user.selectOptions(select, `${ORG_ASSIGNMENT.id}|${WEST_HOSPITAL_ID}|${WEST_BRANCH_ID}`);

    expect(await screen.findByRole('alert')).toHaveTextContent(/refused this context switch/i);
    expect(onContextSwitch).not.toHaveBeenCalled();
    expect(onSessionExpired).not.toHaveBeenCalled();
    expect(select).toHaveValue(`${BRANCH_ASSIGNMENT.id}|${EAST_HOSPITAL_ID}|${EAST_BRANCH_ID}`);
    // Refused switches never reach the endpoint twice for the same choice.
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('delegates a 401 to the session-expiry handler without a local denial', async () => {
    const user = userEvent.setup();
    fetchMock = vi.fn(() =>
      Promise.resolve(jsonResponse({ error: 'expired' }, 401))
    );
    vi.stubGlobal('fetch', fetchMock);
    const onContextSwitch = vi.fn();
    const onSessionExpired = vi.fn();
    renderSelector(sessionFor(BRANCH_ASSIGNMENT, [ORG_ASSIGNMENT]), { onContextSwitch, onSessionExpired });

    const select = screen.getByRole('combobox', { name: 'Acting context' });
    await waitFor(() => expect(select.querySelectorAll('option')).toHaveLength(4));
    await user.selectOptions(select, `${ORG_ASSIGNMENT.id}|${WEST_HOSPITAL_ID}|${WEST_BRANCH_ID}`);

    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(1));
    expect(onContextSwitch).not.toHaveBeenCalled();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('ignores a selection that is not one of the server-issued targets', async () => {
    const user = userEvent.setup();
    renderSelector(sessionFor(BRANCH_ASSIGNMENT, [ORG_ASSIGNMENT]));
    const select = screen.getByRole('combobox', { name: 'Acting context' });
    await waitFor(() => expect(select.querySelectorAll('option')).toHaveLength(4));

    // A value that matches no server-issued triple — e.g. a hand-edited or
    // stale client key naming a branch under the wrong hospital — fires a
    // change event but never reaches the endpoint.
    fireEvent.change(select, {
      target: { value: `${ORG_ASSIGNMENT.id}|${WEST_HOSPITAL_ID}|${EAST_BRANCH_ID}` },
    });
    expect(fetchMock).not.toHaveBeenCalled();
    expect(user).toBeTruthy();
  });

  it('shows a neutral loading state for a selection scope until the hierarchy arrives, and the error when it fails', () => {
    // An ORGANIZATION acting context has no target until the server slice
    // arrives: the select holds one honest loading entry, never a guess.
    const orgSession = sessionFor(ORG_ASSIGNMENT);
    const { unmount } = renderSelector(orgSession, { hierarchy: null, hierarchyLoading: true });
    expect(screen.getByRole('status')).toHaveTextContent('Loading network options…');
    const options = selectOptions(screen.getByRole('combobox', { name: 'Acting context' }));
    expect(options).toHaveLength(1);
    expect(options[0].text).toBe('Loading branch…');
    expect(options[0].disabled).toBe(true);
    unmount();

    // A fixed-scope acting context keeps its own target visible while a
    // selection-scope assignment waits for the hierarchy.
    const mixed = sessionFor(BRANCH_ASSIGNMENT, [ORG_ASSIGNMENT]);
    const second = renderSelector(mixed, { hierarchy: null, hierarchyLoading: true });
    expect(screen.getByRole('status')).toHaveTextContent('Loading network options…');
    expect(selectOptions(screen.getByRole('combobox', { name: 'Acting context' }))).toHaveLength(1);
    second.unmount();

    renderSelector(mixed, { hierarchy: null, hierarchyError: 'Network options could not be loaded.' });
    expect(screen.getByRole('alert')).toHaveTextContent('Network options could not be loaded.');
  });

  it('reports a session without an acting assignment honestly instead of rendering a control', () => {
    const session = sessionFor(BRANCH_ASSIGNMENT);
    session.actingContext = null;
    renderSelector(session);
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent(
      'Acting context unavailable — the server session lists no acting assignment.'
    );
  });

  it('names the single-target case in the help line for a fixed-scope-only session', () => {
    renderSelector(sessionFor(BRANCH_ASSIGNMENT), { hierarchy: null });
    expect(screen.getByText('The server issued one acting hospital branch for your account.')).toBeInTheDocument();
  });
});
