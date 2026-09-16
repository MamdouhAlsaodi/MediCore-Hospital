import { expect } from '@playwright/test';

// Shared Phase 4 container E2E helpers (T079–T084).
//
// Discipline carried from the Phase 3 runner, tightened for the container
// stack: credentials only from the process environment, exact status
// assertions for every deliberate non-2xx, and a strict guard that fails the
// run on any console/page error that was not explicitly allowed (T084).

export const MAIN_BRANCH_LINE = 'Demo Synthetic Hospital — Demo Main Branch';
export const NORTH_BRANCH_LINE = 'Demo Synthetic Hospital — Demo North Branch';

export function runTag() {
  return process.env.E2E_RUN_TAG ?? 'e2e';
}

// --- Console/page error guard (T084) ---------------------------------------

// Browsers log an error-level console message for EVERY non-2xx resource
// load. Deliberate non-2xx probes (exact-status assertions) therefore
// register an allowance here; at finish(), every collected console error
// must be covered by exactly one allowance, and any pageerror fails.
export class ErrorGuard {
  constructor(page) {
    this.page = page;
    this.consoleErrors = [];
    this.pageErrors = [];
    this.allowed = [];
    this.onConsole = (message) => {
      if (message.type() === 'error') {
        this.consoleErrors.push({ text: message.text(), url: message.location()?.url ?? '' });
      }
    };
    this.onPageError = (error) => this.pageErrors.push(String(error));
    page.on('console', this.onConsole);
    page.on('pageerror', this.onPageError);
  }

  // Registers an expected non-2xx (exact status, URL substring). status must
  // be an exact number — never a range or "any non-2xx".
  allowNon2xx(status, urlIncludes) {
    this.allowed.push({ status, urlIncludes });
  }

  finish(label = 'page') {
    const unexpected = [];
    for (const error of this.consoleErrors) {
      const network = /^Failed to load resource: the server responded with a status of (\d{3})/.exec(
        error.text
      );
      if (network) {
        const status = Number(network[1]);
        const covered = this.allowed.some(
          (allow) => allow.status === status && error.url.includes(allow.urlIncludes)
        );
        if (!covered) {
          unexpected.push(`${status} at ${error.url} (not an allowed non-2xx)`);
        }
        continue;
      }
      unexpected.push(error.text);
    }
    expect(this.pageErrors, `${label}: unexpected page errors`).toEqual([]);
    expect(unexpected, `${label}: unexpected console errors`).toEqual([]);
    return { consoleErrors: this.consoleErrors.length, allowed: this.allowed.length };
  }
}

// --- API helpers (exact status assertions, no browser needed) ---------------

// Authenticated API request context bound to a bearer token obtained through
// the real login endpoint. Session shape: {accessToken, roles, assignments:[{id,
// branchId, branchLabel, ...}], actingContext}. Token never leaves memory.
export async function apiLogin(request, username, password) {
  const response = await request.post(`${process.env.REVIEW_API_ORIGIN}/api/auth/login`, {
    data: { username, password },
  });
  expect(
    response.status(),
    `login as ${username} must succeed with exact status 200`
  ).toBe(200);
  const session = await response.json();
  expect(session.accessToken, 'login response must carry an access token').toBeTruthy();
  return session;
}

// Switches the acting context through the real /api/auth/context endpoint and
// returns the replacement session. Resolves the branch id from the server's
// branch list by the branch CODE (e.g. DEMO-BR-002) — assignments carry no
// branch labels at login (organization-scope admins get one assignment).
export async function switchContextToBranch(request, session, branchCode) {
  const branchesResponse = await request.get(
    `${process.env.REVIEW_API_ORIGIN}/api/branches`,
    { headers: bearer(session) }
  );
  expect(branchesResponse.status(), 'GET /api/branches must be exactly 200').toBe(200);
  const branches = await branchesResponse.json();
  const branch = branches.find((candidate) => candidate.code === branchCode);
  expect(branch, `server must list branch ${branchCode}`).toBeTruthy();
  const assignment = session.assignments.find((candidate) => candidate.enabled);
  expect(assignment, 'server must have issued an enabled assignment').toBeTruthy();
  const response = await request.post(`${process.env.REVIEW_API_ORIGIN}/api/auth/context`, {
    headers: bearer(session),
    data: { assignmentId: assignment.id, branchId: branch.id },
  });
  expect(response.status(), 'acting-context switch must succeed with exact status 200').toBe(200);
  return response.json();
}

export function bearer(session) {
  return { Authorization: `Bearer ${session.accessToken}` };
}

export async function apiGet(request, session, path, expectedStatus) {
  const response = await request.get(`${process.env.REVIEW_API_ORIGIN}${path}`, {
    headers: bearer(session),
  });
  if (expectedStatus !== undefined) {
    expect(
      response.status(),
      `GET ${path} must answer with exact status ${expectedStatus}`
    ).toBe(expectedStatus);
  }
  return response;
}

// --- Browser journey helpers ------------------------------------------------

export async function loginAsAdmin(page) {
  const password = process.env.HOSPITAL_ADMIN_PASSWORD;
  await page.goto('/');
  await expect(page.getByLabel('Username')).toBeVisible();
  await page.getByLabel('Username').fill('admin');
  const passwordInput = page.getByLabel('Password');
  await passwordInput.fill(password);
  const loginResponse = page.waitForResponse(
    (response) => response.url().endsWith('/api/auth/login') && response.request().method() === 'POST'
  );
  await page.getByRole('button', { name: 'Log in' }).click();
  // The request already owns its serialized body; clear the DOM field so no
  // failure artifact can disclose even a disposable value.
  await passwordInput.evaluate((input) => { input.value = ''; });
  expect((await loginResponse).status(), 'synthetic admin login must be exactly 200').toBe(200);
  await expect(
    page.getByRole('navigation', { name: 'Screens permitted for your roles' })
  ).toBeVisible();
}

export async function selectByOptionText(selectLocator, optionText) {
  const option = selectLocator.locator('option', { hasText: optionText });
  await expect(option).toHaveCount(1);
  await selectLocator.selectOption(await option.getAttribute('value'));
}

export async function selectBranchByLabel(page, branchLabelText) {
  const selector = page.getByRole('combobox', { name: 'Acting context' });
  const option = selector.locator('option', { hasText: branchLabelText });
  await expect(option).toHaveCount(1);
  await selector.selectOption(await option.getAttribute('value'));
}

export async function assertNoHorizontalOverflow(page, label) {
  const overflow = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    body: document.body.scrollWidth - document.body.clientWidth,
  }));
  expect(overflow.document, `${label}: horizontal page overflow`).toBeLessThanOrEqual(0);
  expect(overflow.body, `${label}: horizontal body overflow`).toBeLessThanOrEqual(0);
}

// One-line responsive check (T080): the screen must not overflow horizontally
// and the shell navigation must be usable in the current viewport.
export async function assertResponsiveLine(page, label) {
  await assertNoHorizontalOverflow(page, label);
  await expect(
    page.getByRole('navigation', { name: 'Screens permitted for your roles' }),
    `${label}: navigation must remain visible without overflow`
  ).toBeVisible();
}
