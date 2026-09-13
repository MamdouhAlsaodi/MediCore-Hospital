import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test } from '@playwright/test';

// Multi-branch synthetic browser journey (docs/plan3.md Task 13).
//
// One sequential journey per configured viewport (desktop 1280x720, mobile
// 375x812) against the disposable loopback review pair from docs/runbook.md
// with the opt-in, idempotent, obviously synthetic three-branch demo cohort:
//
//   desktop — proves assignment/branch visibility, the branch + network
//     command center (screenshot), command-center drill-down, cross-branch
//     isolation on patients and beds, appointment conflict messaging (409 on
//     overlap and on outside-availability times, no partial write), audit
//     evidence, keyboard operability with visible focus, predictable focus
//     return after a form closes, accessible control names, and zero
//     horizontal page overflow.
//
//   mobile — additionally switches the acting context to Demo North Branch,
//     proves isolation from the previous branch with no stale rows, runs the
//     full bed lifecycle (register admission without a bed -> assign ->
//     transfer -> discharge -> released bed) with screenshots, and verifies
//     the branch-attributed audit trail of those commands.
//
// Evidence discipline: screenshots are captured into a scratch directory.
// The runner wipes any pre-existing images under docs/evidence/phase3/ once
// per run before the servers start (playwright.config.js), and the final
// (mobile) project copies the complete three-image set into docs ONLY after
// every assertion of BOTH viewport runs has passed — a failed or partial run
// ships no images. Only synthetic demo data appears in the captured screens
// (Demo*/DEMO-* fixtures; the review account is the generic `admin`;
// credentials come only from the run environment and are never written
// anywhere).

const specDir = path.dirname(fileURLToPath(import.meta.url));
const capturesDir = path.resolve(specDir, '..', 'node_modules', '.e2e-captures');
const evidenceDir = path.resolve(specDir, '..', '..', 'docs', 'evidence', 'phase3');

const EVIDENCE_DESKTOP_COMMAND_CENTER = 'branch-command-center-desktop.png';
const EVIDENCE_MOBILE_COMMAND_CENTER = 'branch-command-center-mobile.png';
const EVIDENCE_MOBILE_BED_TRANSFER = 'bed-transfer-mobile.png';
const ALL_EVIDENCE = [
  EVIDENCE_DESKTOP_COMMAND_CENTER,
  EVIDENCE_MOBILE_COMMAND_CENTER,
  EVIDENCE_MOBILE_BED_TRANSFER,
];

const MAIN_BRANCH_LINE = 'Demo Synthetic Hospital — Demo Main Branch';
const NORTH_BRANCH_LINE = 'Demo Synthetic Hospital — Demo North Branch';
const BRANCH_SUMMARY_MAIN = 'DEMO-BR-001 — Demo Main Branch';
const BRANCH_SUMMARY_NORTH = 'DEMO-BR-002 — Demo North Branch';
const CONFLICT_MESSAGE = 'The request failed (409). Please try again.';

const ADMIN_NAV = [
  'Dashboard',
  'Patients',
  'Appointments',
  'Admissions',
  'Emergency Visits',
  'Beds',
  'Invoices',
  'Audit',
];

function capturePath(name) {
  return path.join(capturesDir, name);
}

function evidencePath(name) {
  return path.join(evidenceDir, name);
}

async function assertNoHorizontalOverflow(page, label) {
  const overflow = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    body: document.body.scrollWidth - document.body.clientWidth,
  }));
  expect(overflow.document, `${label}: horizontal page overflow`).toBeLessThanOrEqual(0);
  expect(overflow.body, `${label}: horizontal body overflow`).toBeLessThanOrEqual(0);
}

// Keyboard focus must be visible wherever Tab lands: the focused control has
// a non-none outline (custom :focus-visible rules or the UA focus ring).
async function assertVisibleKeyboardFocus(page, label) {
  await page.keyboard.press('Tab');
  const focus = await page.evaluate(() => {
    const element = document.activeElement;
    if (!element || element === document.body || element === document.documentElement) return null;
    const style = window.getComputedStyle(element);
    return {
      name: (element.getAttribute('aria-label') || element.textContent || '').trim().slice(0, 60),
      outlineStyle: style.outlineStyle,
      outlineWidth: style.outlineWidth,
    };
  });
  expect(focus, `${label}: Tab must land on a focusable control`).not.toBeNull();
  expect(
    focus.outlineStyle === 'none' || focus.outlineWidth === '0px',
    `${label}: focused control “${focus.name}” must show a visible focus indicator`
  ).toBe(false);
}

// Core controls must expose accessible names resolvable through the
// accessibility tree (no unnamed controls on the journey path).
async function assertCoreAccessibleNames(page) {
  const nav = page.getByRole('navigation', { name: 'Screens permitted for your roles' });
  await expect(nav).toBeVisible();
  for (const label of ADMIN_NAV) {
    await expect(nav.getByRole('button', { name: label })).toBeVisible();
  }
  await expect(page.getByRole('combobox', { name: 'Acting context' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Log out' })).toBeVisible();
}

// The dashboard renders a `p.dashboard-branch-line` in two places: the
// acting branch summary (`CODE — Name`) and the network comparison group
// (organization name). Scoping by the branch code pins the summary line.
function branchSummaryLine(branchCode) {
  return page => page.locator('p.dashboard-branch-line').filter({ hasText: branchCode });
}

async function loginAsAdmin(page) {
  const password = process.env.HOSPITAL_ADMIN_PASSWORD;
  if (!password) {
    throw new Error('HOSPITAL_ADMIN_PASSWORD must be exported for the e2e run (see docs/runbook.md).');
  }
  await page.goto('/');
  await expect(page.getByLabel('Username')).toBeVisible();
  await assertNoHorizontalOverflow(page, 'login');
  await page.getByLabel('Username').fill('admin');
  const passwordInput = page.getByLabel('Password');
  await passwordInput.fill(password);
  const loginResponse = page.waitForResponse((response) =>
    response.url().endsWith('/api/auth/login') && response.request().method() === 'POST'
  );
  // The dashboard is the shell's default screen: registering the wait for
  // its two summary reads before the click means the mount's requests can
  // never be missed, however fast the login round-trip resolves.
  const dashboardLoaded = dashboardSummariesLoaded(page);
  await page.getByRole('button', { name: 'Log in' }).click();
  // The request already owns its serialized body; clear the DOM field so a
  // later Playwright failure artifact cannot disclose even a disposable value.
  await passwordInput.evaluate((input) => { input.value = ''; });
  expect((await loginResponse).status(), 'synthetic admin login must succeed').toBe(200);
  await expect(
    page.getByRole('navigation', { name: 'Screens permitted for your roles' })
  ).toBeVisible();
  // The command center paints only after both reads resolve, so the
  // caller's dashboard assertions are deterministic from here on.
  await dashboardLoaded;
}

// Picks a select option by (substring) visible text — labels render resolved
// display names, never raw identifiers, so the journey targets those names.
async function selectByOptionText(selectLocator, optionText) {
  const option = selectLocator.locator('option', { hasText: optionText });
  await expect(option).toHaveCount(1);
  await selectLocator.selectOption(await option.getAttribute('value'));
}

// Switches the acting context through the shell selector: resolves the
// (assignment, branch) option the server issued for the named branch and
// selects it, which issues a replacement context-bound token.
async function selectBranchByLabel(page, branchLabelText) {
  const selector = page.getByRole('combobox', { name: 'Acting context' });
  const option = selector.locator('option', { hasText: branchLabelText });
  await expect(option).toHaveCount(1);
  await selector.selectOption(await option.getAttribute('value'));
}

// Dashboard data contract (DashboardPage.jsx): every mount — after login,
// on navigation return, or after an acting-context switch — issues exactly
// one GET /api/dashboard and, for this ADMIN + ORGANIZATION context, one
// GET /api/dashboard/network, and paints the branch line and network
// comparison only after both resolve. Registering both responses before the
// action that mounts the screen and awaiting them here pins every dashboard
// assertion to that contract, so even the cold first query of a freshly
// seeded review backend cannot race a short auto-wait.
function dashboardSummariesLoaded(page) {
  const succeededRead = (response) =>
    response.request().method() === 'GET' && response.status() === 200;
  return Promise.all([
    page.waitForResponse(
      (response) => response.url().endsWith('/api/dashboard') && succeededRead(response)
    ),
    page.waitForResponse(
      (response) => response.url().endsWith('/api/dashboard/network') && succeededRead(response)
    ),
  ]);
}

test('multi-branch synthetic browser journey', async ({ page }, testInfo) => {
  const project = testInfo.project.name;
  const isDesktop = project === 'desktop';
  const ownCaptures = isDesktop
    ? [EVIDENCE_DESKTOP_COMMAND_CENTER]
    : [EVIDENCE_MOBILE_COMMAND_CENTER, EVIDENCE_MOBILE_BED_TRANSFER];

  // Evidence discipline: stale evidence images were already wiped once per
  // run before the servers started (playwright.config.js); this project
  // starts from a clean scratch and publishes nothing itself — the final
  // (mobile) project copies the complete verified set into docs.
  fs.mkdirSync(capturesDir, { recursive: true });
  for (const name of ownCaptures) {
    fs.rmSync(capturePath(name), { force: true });
  }

  const consoleErrors = [];
  const conflictResponses = [];
  page.on('response', (response) => {
    if (response.status() === 409 && response.url().includes('/api/appointments')) {
      conflictResponses.push(response.url());
    }
  });
  page.on('console', (message) => {
    const text = message.text();
    const expectedConflictNoise = message.type() === 'error'
      && text === 'Failed to load resource: the server responded with a status of 409 (Conflict)';
    if (message.type() === 'error' && !expectedConflictNoise) consoleErrors.push(text);
  });
  page.on('pageerror', (error) => consoleErrors.push(String(error)));

  // --- Sign in with the synthetic review account ---------------------------
  await loginAsAdmin(page);

  const nav = page.getByRole('navigation', { name: 'Screens permitted for your roles' });

  // The shell displays identity, acting role, and the server-named branch.
  await expect(page.locator('.whoami-name')).toHaveText('admin');
  await expect(page.locator('.whoami-roles')).toHaveText('ADMIN');
  await expect(page.locator('.whoami-context')).toHaveText(MAIN_BRANCH_LINE);

  // The selector lists exactly the server-issued (assignment, branch) pairs.
  const selector = page.getByRole('combobox', { name: 'Acting context' });
  await expect(selector.locator('option', { hasText: 'Demo Main Branch' })).toHaveCount(1);
  await expect(selector.locator('option', { hasText: 'Demo North Branch' })).toHaveCount(1);
  await expect(selector.locator('option', { hasText: 'Demo Harbor Branch' })).toHaveCount(1);

  // --- Branch command center on the default branch -------------------------
  // The branch summary renders twice by design (context line + network
  // comparison heading); the context line is asserted exactly.
  await expect(branchSummaryLine('DEMO-BR-001')(page)).toHaveText(BRANCH_SUMMARY_MAIN);
  await expect(page.getByText('Network comparison')).toBeVisible();
  await expect(page.getByText('Demo Synthetic Hospital').first()).toBeVisible();
  // The network comparison lists all three branches as headings; branch
  // names also occur in the acting-context selector's options (hidden by
  // definition), so the check targets the network list specifically.
  const networkBranchList = page.getByRole('list', { name: 'Branches in the network' });
  for (const branchName of ['Demo Main Branch', 'Demo North Branch', 'Demo Harbor Branch']) {
    await expect(networkBranchList.getByRole('heading', { name: branchName })).toBeVisible();
  }
  await assertNoHorizontalOverflow(page, 'dashboard (main branch)');
  await assertCoreAccessibleNames(page);

  // Keyboard operability: Tab from a clicked control shows a visible focus
  // indicator, Enter activates the navigation, and the shell then moves focus
  // to the new screen's heading.
  await nav.getByRole('button', { name: 'Dashboard' }).click();
  await assertVisibleKeyboardFocus(page, 'navigation focus indicator');
  await page.keyboard.press('Tab');
  await page.keyboard.press('Enter');
  await expect(page.locator('main header h2')).toHaveText('Appointments');
  await expect(nav.getByRole('button', { name: 'Appointments' })).toHaveAttribute(
    'aria-current',
    'page'
  );
  await expect
    .poll(() => page.evaluate(() => document.activeElement?.textContent?.trim()))
    .toBe('Appointments');

  // --- Cross-branch isolation on patients (main branch) --------------------
  await nav.getByRole('button', { name: 'Patients' }).click();
  const patientList = page.getByRole('list', { name: 'Patient results' });
  await expect(patientList).toBeVisible();
  await expect(patientList).toContainText('Demo Patient Alpha');
  await expect(patientList).toContainText('Demo Patient Bravo');
  await expect(patientList).toContainText('Demo Patient Charlie');
  await expect(patientList).not.toContainText('Demo Patient Delta');
  await expect(patientList).not.toContainText('Demo Patient Echo');
  await expect(patientList).not.toContainText('Demo Patient Foxtrot');

  // A northern MRN cannot be found from the main branch either.
  await page.getByRole('searchbox', { name: 'Search patients' }).fill('DEMO-0004');
  const crossBranchSearch = page.waitForResponse((response) =>
    response.url().includes('/api/patients?') && response.status() === 200
  );
  await page.getByRole('button', { name: 'Search' }).click();
  await crossBranchSearch;
  await expect(page.getByRole('heading', { name: 'No patients found' })).toBeVisible();
  await expect(page.getByRole('list', { name: 'Patient results' })).toHaveCount(0);

  // --- Cross-branch isolation on beds (main branch) ------------------------
  await nav.getByRole('button', { name: 'Beds' }).click();
  const bedsTable = page.getByRole('table', { name: 'Registered beds' });
  await expect(bedsTable).toBeVisible();
  await expect(bedsTable).toContainText('Demo Ward A');
  await expect(bedsTable).not.toContainText('Demo Ward N');
  await expect(bedsTable).not.toContainText('Demo Ward H');
  // Status is text, never color-only: every operational status is readable.
  for (const statusText of ['AVAILABLE', 'OCCUPIED', 'MAINTENANCE', 'OUT_OF_SERVICE']) {
    await expect(bedsTable).toContainText(statusText);
  }
  await assertNoHorizontalOverflow(page, 'beds (main branch)');

  // --- Appointment conflict messaging (main branch) ------------------------
  await nav.getByRole('button', { name: 'Appointments' }).click();
  await expect(page.locator('main header h2')).toHaveText('Appointments');
  const appointmentsTable = page.getByRole('table', { name: 'Scheduled appointments' });
  await expect(appointmentsTable).toBeVisible();
  const mainRowsBefore = await appointmentsTable.getByRole('row').count();
  expect(mainRowsBefore).toBeGreaterThanOrEqual(2); // header + seeded cohort rows

  await page.getByRole('button', { name: 'Schedule appointment' }).click();
  const form = page.locator('form[aria-label="Schedule an appointment"]');
  await expect(form).toBeVisible();

  await selectByOptionText(form.getByLabel('Patient'), 'Demo Patient Alpha');
  await selectByOptionText(form.getByLabel('Professional'), 'Demo Physician Alpha');
  const availabilityResponse = page.waitForResponse((response) =>
    response.url().includes('/api/staff/')
      && response.url().includes('/availability?')
      && response.request().method() === 'GET'
  );
  await form.getByLabel('Date and time').fill('2031-03-02T09:00');
  expect((await availabilityResponse).status(), 'modeled availability must load').toBe(200);

  async function attemptConflict(dateTimeLabel) {
    await form.getByLabel('Date and time').fill(dateTimeLabel);
    // The modeled availability for the chosen day is shown before submitting.
    await expect(form.getByText('Modeled availability')).toBeVisible();
    await expect(form.getByText(/2031-03-02T08:00/)).toBeVisible();
    await form.getByLabel('Duration (minutes)').fill('30');
    await form.getByLabel('Type').fill('Demo visit');
    const conflictResponse = page.waitForResponse((response) =>
      response.url().endsWith('/api/appointments') && response.request().method() === 'POST'
    );
    await form.getByRole('button', { name: 'Schedule appointment' }).click();
    expect((await conflictResponse).status(), 'intentional appointment conflict must be explicit').toBe(409);
    await expect(form.getByRole('alert')).toHaveText(CONFLICT_MESSAGE);
  }

  // Overlaps the seeded 2031-03-02T09:00 appointment: the server refuses and
  // the form keeps every field with an inline 409 message (zero field loss).
  await attemptConflict('2031-03-02T09:00');
  // Outside the modeled 08:00–16:00 availability: refused as well.
  await attemptConflict('2031-03-02T07:00');

  // Closing the form returns keyboard focus predictably to the shell
  // navigation of the current screen, and no partial write happened.
  await form.getByRole('button', { name: 'Cancel' }).click();
  await expect(appointmentsTable).toBeVisible();
  await expect(appointmentsTable).not.toContainText('Demo visit');
  expect(await appointmentsTable.getByRole('row').count()).toBe(mainRowsBefore);
  await expect
    .poll(() => page.evaluate(() => document.activeElement?.textContent?.trim()))
    .toBe('Appointments');

  // --- Audit evidence (organization scope on the default branch) -----------
  await nav.getByRole('button', { name: 'Audit' }).click();
  await expect(page.locator('main header h2')).toHaveText('Audit Evidence');
  const auditTable = page.getByRole('table', { name: 'Audit events' });
  await expect(auditTable).toBeVisible();
  // Seeded cohort events are attributed to the `system` actor and the branch.
  await expect(auditTable.getByRole('row', { name: /system/ }).first()).toBeVisible();
  await expect(auditTable).toContainText('Demo Main Branch');
  // Credentials never appear anywhere in the evidence view.
  await expect(page.locator('body')).not.toContainText('eyJ');
  await expect(page.locator('body')).not.toContainText('Bearer ');
  await assertNoHorizontalOverflow(page, 'audit (main branch)');

  if (isDesktop) {
    // --- Desktop evidence: the command center with network comparison ------
    // Returning to the dashboard remounts the screen and refetches both
    // summaries (the same contract as the login mount); await them again.
    const commandCenterLoaded = dashboardSummariesLoaded(page);
    await nav.getByRole('button', { name: 'Dashboard' }).click();
    await commandCenterLoaded;
    await expect(branchSummaryLine('DEMO-BR-001')(page)).toHaveText(BRANCH_SUMMARY_MAIN);
    await expect(page.getByText('Network comparison')).toBeVisible();
    await page.screenshot({ path: capturePath(EVIDENCE_DESKTOP_COMMAND_CENTER) });

    // Command-center drill-down: the patients card navigates to the
    // branch-scoped patients screen.
    await page.getByRole('button', { name: 'View patients' }).click();
    await expect(page.locator('main header h2')).toHaveText('Patients');
    await expect(page.getByRole('list', { name: 'Patient results' })).toContainText(
      'Demo Patient Alpha'
    );
  }

  if (!isDesktop) {
    // --- Mobile: switch the acting context to the north branch -------------
    await selectBranchByLabel(page, 'Demo North Branch');

    // The shell immediately reflects the server-issued context, and the
    // branch context stays visible in the narrow layout.
    await expect(page.locator('.whoami-context')).toHaveText(NORTH_BRANCH_LINE);
    await expect(page.locator('.whoami-roles')).toHaveText('ADMIN');
    await expect(selector).toBeVisible();

    // The beds screen remounts under the new context-bound token: only the
    // north branch's beds exist here — no stale main-branch rows survive.
    // Navigate explicitly after the context switch; the shell preserves the
    // current screen by design rather than forcing a redirect.
    await nav.getByRole('button', { name: 'Beds' }).click();
    const northBeds = page.getByRole('table', { name: 'Registered beds' });
    await expect(northBeds).toBeVisible();
    await expect(northBeds).toContainText('Demo Ward N');
    await expect(northBeds).not.toContainText('Demo Ward A');
    await expect(northBeds).not.toContainText('Demo Ward H');
    await assertNoHorizontalOverflow(page, 'beds (north branch)');

    // --- Mobile evidence: the command center after the switch --------------
    const northCommandCenterLoaded = dashboardSummariesLoaded(page);
    await nav.getByRole('button', { name: 'Dashboard' }).click();
    await northCommandCenterLoaded;
    await expect(branchSummaryLine('DEMO-BR-002')(page)).toHaveText(BRANCH_SUMMARY_NORTH);
    await expect(page.getByText('Network comparison')).toBeVisible();
    await page.screenshot({ path: capturePath(EVIDENCE_MOBILE_COMMAND_CENTER) });

    // Command-center drill-down over beds.
    await page.getByRole('button', { name: 'View beds' }).click();
    await expect(page.locator('main header h2')).toHaveText('Beds');

    // --- Cross-branch isolation on patients (north branch) -----------------
    await nav.getByRole('button', { name: 'Patients' }).click();
    const northPatients = page.getByRole('list', { name: 'Patient results' });
    await expect(northPatients).toContainText('Demo Patient Delta');
    await expect(northPatients).toContainText('Demo Patient Echo');
    await expect(northPatients).not.toContainText('Demo Patient Alpha');

    // --- Appointment conflict messaging (north branch) ---------------------
    await nav.getByRole('button', { name: 'Appointments' }).click();
    const northAppointmentsTable = page.getByRole('table', { name: 'Scheduled appointments' });
    await expect(northAppointmentsTable).toBeVisible();
    const northRowsBefore = await northAppointmentsTable.getByRole('row').count();
    await page.getByRole('button', { name: 'Schedule appointment' }).click();
    const northForm = page.locator('form[aria-label="Schedule an appointment"]');
    await expect(northForm).toBeVisible();
    await selectByOptionText(northForm.getByLabel('Patient'), 'Demo Patient Delta');
    await selectByOptionText(northForm.getByLabel('Professional'), 'Demo Physician Delta');
    await northForm.getByLabel('Date and time').fill('2031-03-03T11:00');
    await expect(northForm.getByText(/2031-03-03T09:00/)).toBeVisible();
    await northForm.getByLabel('Duration (minutes)').fill('30');
    await northForm.getByLabel('Type').fill('Demo visit');
    await northForm.getByRole('button', { name: 'Schedule appointment' }).click();
    await expect(northForm.getByRole('alert')).toHaveText(CONFLICT_MESSAGE);
    await northForm.getByRole('button', { name: 'Cancel' }).click();
    await expect(northAppointmentsTable).toBeVisible();
    expect(await northAppointmentsTable.getByRole('row').count()).toBe(northRowsBefore);

    // --- Bed assignment / transfer / discharge lifecycle (north branch) ----
    const admissionsLoaded = page.waitForResponse((response) =>
      response.url().endsWith('/api/admissions')
        && response.request().method() === 'GET'
        && response.status() === 200
    );
    await nav.getByRole('button', { name: 'Admissions' }).click();
    await admissionsLoaded;
    const admissionsTable = page.getByRole('table', { name: 'Registered admissions' });
    await expect(admissionsTable).toBeVisible();
    const deltaRow = admissionsTable.getByRole('row', { name: /Demo Patient Delta/ });
    await expect(deltaRow).toContainText('ADMITTED');
    await expect(deltaRow).toContainText('Bed 02 — Demo Ward N · 201');

    // Register a new synthetic admission without a bed.
    await page.getByRole('button', { name: 'Register admission' }).click();
    const admissionForm = page.locator('form[aria-label="Register an admission"]');
    await expect(admissionForm).toBeVisible();
    await selectByOptionText(admissionForm.getByLabel('Patient'), 'Demo Patient Echo');
    await admissionForm.getByLabel('Admitted at').fill('2031-03-05T09:00');
    await admissionForm.getByLabel('Reason').fill('Demo bed assignment workflow');
    await admissionForm
      .getByLabel('Bed (optional)')
      .selectOption({ label: 'No bed at registration' });
    await admissionForm.getByRole('button', { name: 'Register admission' }).click();
    await expect(page.getByText('Admission registered.')).toBeVisible();
    const echoRow = admissionsTable.getByRole('row', { name: /Demo Patient Echo/ });
    await expect(echoRow).toContainText('ADMITTED');
    await expect(echoRow).toContainText('No bed assigned');

    // Assign the only available bed.
    await echoRow.getByRole('button', { name: 'Assign bed' }).click();
    const assignSelect = echoRow.getByLabel('Available bed');
    const bed01Value = await assignSelect
      .locator('option', { hasText: 'Bed 01 — Demo Ward N' })
      .getAttribute('value');
    await assignSelect.selectOption(bed01Value);
    await echoRow.getByRole('button', { name: 'Confirm assignment' }).click();
    await expect(page.getByText('Bed assigned.')).toBeVisible();
    await expect(admissionsTable.getByRole('row', { name: /Demo Patient Echo/ })).toContainText(
      'Bed 01 — Demo Ward N · 201'
    );

    // Discharge the northern seeded admission: its bed must be released.
    await deltaRow.getByRole('button', { name: 'Discharge' }).click();
    const deltaDischargeResponse = page.waitForResponse((response) =>
      response.url().includes('/api/admissions/')
        && response.url().endsWith('/status')
        && response.request().method() === 'PUT'
    );
    await deltaRow.getByRole('button', { name: 'Confirm discharge' }).click();
    expect((await deltaDischargeResponse).status(), 'north admission discharge must succeed').toBe(200);
    await expect(page.getByText('Admission discharged.')).toBeVisible();
    await expect(
      admissionsTable.getByRole('row', { name: /Demo Patient Delta/ })
    ).toContainText('DISCHARGED');

    // Transfer the remaining admission to the released bed.
    const echoRowAfterAssign = admissionsTable.getByRole('row', { name: /Demo Patient Echo/ });
    await echoRowAfterAssign.getByRole('button', { name: 'Transfer bed' }).click();
    const transferSelect = echoRowAfterAssign.getByLabel('Transfer to available bed');
    const bed02Value = await transferSelect
      .locator('option', { hasText: 'Bed 02 — Demo Ward N' })
      .getAttribute('value');
    await transferSelect.selectOption(bed02Value);
    await echoRowAfterAssign.getByRole('button', { name: 'Confirm transfer' }).click();
    await expect(page.getByText('Bed transferred.')).toBeVisible();
    const echoRowAfterTransfer = admissionsTable.getByRole('row', { name: /Demo Patient Echo/ });
    await expect(echoRowAfterTransfer).toContainText('Bed 02 — Demo Ward N · 201');

    // Mobile evidence: the transferred admission is rendered as a readable
    // labelled card, not seven columns compressed into vertical fragments.
    const mobileCells = echoRowAfterTransfer.locator('td');
    const mobileCellWidths = await mobileCells.evaluateAll((cells) =>
      cells.map((cell) => cell.getBoundingClientRect().width)
    );
    expect(Math.min(...mobileCellWidths)).toBeGreaterThan(250);
    await expect(page.locator('.whoami-context')).toHaveText(NORTH_BRANCH_LINE);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: capturePath(EVIDENCE_MOBILE_BED_TRANSFER), fullPage: true });

    // The bed inventory mirrors the server truth: 01 released, 02 occupied.
    await nav.getByRole('button', { name: 'Beds' }).click();
    const bedsAfterTransfer = page.getByRole('table', { name: 'Registered beds' });
    await expect(bedsAfterTransfer.getByRole('row', { name: /Demo Ward N 201 01/ })).toContainText(
      'AVAILABLE'
    );
    await expect(bedsAfterTransfer.getByRole('row', { name: /Demo Ward N 201 02/ })).toContainText(
      'OCCUPIED'
    );

    // Discharge the last admission: both northern beds are available again.
    await nav.getByRole('button', { name: 'Admissions' }).click();
    const echoRowFinal = admissionsTable.getByRole('row', { name: /Demo Patient Echo/ });
    await echoRowFinal.getByRole('button', { name: 'Discharge' }).click();
    const echoDischargeResponse = page.waitForResponse((response) =>
      response.url().includes('/api/admissions/')
        && response.url().endsWith('/status')
        && response.request().method() === 'PUT'
    );
    await echoRowFinal.getByRole('button', { name: 'Confirm discharge' }).click();
    expect((await echoDischargeResponse).status(), 'transferred admission discharge must succeed').toBe(200);
    await expect(page.getByText('Admission discharged.')).toBeVisible();
    await nav.getByRole('button', { name: 'Beds' }).click();
    const bedsAfterDischarge = page.getByRole('table', { name: 'Registered beds' });
    await expect(
      bedsAfterDischarge.getByRole('row', { name: /Demo Ward N 201 01/ })
    ).toContainText('AVAILABLE');
    await expect(
      bedsAfterDischarge.getByRole('row', { name: /Demo Ward N 201 02/ })
    ).toContainText('AVAILABLE');

    // --- Audit evidence of the executed commands ---------------------------
    await nav.getByRole('button', { name: 'Audit' }).click();
    await expect(page.locator('main header h2')).toHaveText('Audit Evidence');
    const mobileAuditTable = page.getByRole('table', { name: 'Audit events' });
    await expect(mobileAuditTable).toBeVisible();
    await page.getByLabel('Actor').fill('admin');
    await page.getByRole('button', { name: 'Apply filters' }).click();
    const mutationRow = mobileAuditTable
      .getByRole('row', {
        name: /admin ADMIN ORGANIZATION Demo North Branch UPDATE Admission/,
      })
      .first();
    await expect(mutationRow).toBeVisible();
    await expect(mutationRow).not.toContainText('—'); // correlation id present
    await expect(mobileAuditTable).not.toContainText('system'); // filter applied
    await assertNoHorizontalOverflow(page, 'audit (north branch)');
    await assertVisibleKeyboardFocus(page, 'audit focus indicator (north branch)');
  }

  const expectedConflictCount = isDesktop ? 2 : 3;
  expect(
    conflictResponses,
    `the journey must observe exactly ${expectedConflictCount} intentional appointment conflicts`
  ).toHaveLength(expectedConflictCount);
  expect(consoleErrors, 'the journey must run without unexpected console errors').toEqual([]);

  // A fully passing run is the only writer of the evidence set: the final
  // (mobile) project verifies that every capture from BOTH viewports exists
  // in scratch, and only then copies the complete set into docs. The desktop
  // project stops after its scratch capture; if the mobile run fails or is
  // skipped, no image ships.
  if (!isDesktop) {
    for (const name of ALL_EVIDENCE) {
      if (!fs.existsSync(capturePath(name))) {
        throw new Error(
          `Missing capture ${name}: the complete evidence set is only produced by a `
            + 'full desktop+mobile run of npm run test:e2e.'
        );
      }
    }
    fs.mkdirSync(evidenceDir, { recursive: true });
    for (const name of ALL_EVIDENCE) {
      fs.copyFileSync(capturePath(name), evidencePath(name));
    }
  }
});
