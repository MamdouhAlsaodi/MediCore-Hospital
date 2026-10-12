import { expect, test } from '@playwright/test';
import {
  ErrorGuard,
  MAIN_BRANCH_LINE,
  assertNoHorizontalOverflow,
  assertResponsiveLine,
  loginAsAdmin,
  runTag,
  selectBranchByLabel,
} from './helpers.mjs';

// Phase 5 container E2E — the full multi-hospital transfer journey
// (T148 desktop + mobile) over the disposable Compose stack.
//
// One sequential journey per configured viewport:
//   login → acting context → transfer request → scoped list → detail →
//   cancel with exact statuses. The journey runs on the legacy hospital's
//   main branch (the seeded Phase 4 workflow cohort); the destination-side
//   acceptance and the foreign-hospital non-enumeration are covered by the
//   concurrency spec on the request context.
//
// Every mutation asserts the exact transport status. The ErrorGuard fails
// the run on any console/page error that is not an explicitly allowed,
// deliberately provoked non-2xx. No trace, screenshot, or video is ever
// produced: acceptance is assertion-only.

const PATIENT_OPTION = 'Demo Patient Alpha (MRN DEMO-0001)';

async function openScreen(page, label, heading) {
  await page
    .getByRole('navigation', { name: 'Screens permitted for your roles' })
    .getByRole('button', { name: label })
    .click();
  await expect(page.locator('main header h2')).toHaveText(heading);
}

test('multi-hospital transfer journey', async ({ page }, testInfo) => {
  const isMobile = testInfo.project.name === 'mobile';
  const responsive = async (label) => {
    if (isMobile) await assertResponsiveLine(page, label);
    else await assertNoHorizontalOverflow(page, label);
  };

  const guard = new ErrorGuard(page);

  // --- Login ---------------------------------------------------------------
  await loginAsAdmin(page);
  await responsive('login');
  await expect(page.locator('.whoami-name')).toHaveText('admin');
  await expect(page.locator('.whoami-roles')).toHaveText('ADMIN');
  // The ORGANIZATION admin's context line resolves through the hierarchy
  // (organization — hospital — branch); wait for the full chain.
  await expect(page.locator('.whoami-context')).toHaveText(
    'Demo Synthetic Hospital — Demo Legacy Hospital — Demo Main Branch',
    { timeout: 20_000 }
  );

  // --- Transfers destination is reachable ------------------------------------
  await openScreen(page, 'Transfers', 'Transfers');
  const transfersNav = page
    .getByRole('navigation', { name: 'Screens permitted for your roles' })
    .getByRole('button', { name: 'Transfers' });
  await expect(transfersNav).toHaveAttribute('aria-current', 'page');
  await responsive('transfers screen');

  // --- Request a transfer from the source side ------------------------------
  const createResponse = page.waitForResponse(
    (response) => response.url().endsWith('/api/transfers') && response.request().method() === 'POST'
  );
  await page.getByRole('button', { name: 'Request transfer' }).click();
  const form = page.getByRole('form', { name: 'Request a transfer' });
  await expect(form.getByLabel('Patient').locator('option', { hasText: 'Demo Patient Alpha' })).toHaveCount(1, { timeout: 20_000 });
  await form.getByLabel('Patient').selectOption({ label: PATIENT_OPTION });
  await expect(form.getByLabel('Admission').locator('option').nth(1)).toBeAttached({ timeout: 20_000 });
  const admissionValue = await form.getByLabel('Admission').locator('option').nth(1).getAttribute('value');
  await form.getByLabel('Admission').selectOption(admissionValue);
  await expect(form.getByLabel('Destination hospital').locator('option').nth(1)).toBeAttached({ timeout: 20_000 });
  await form.getByLabel('Destination hospital').selectOption({ label: 'Demo Riverside Hospital' });
  await form.getByLabel('Reason').selectOption('BED_SHORTAGE');
  await form.getByRole('button', { name: 'Submit transfer request' }).click();
  const response = await createResponse;
  expect(response.status(), 'transfer creation must be exactly 201').toBe(201);
  const createdTransfer = await response.json();
  await expect(page.getByText('Transfer requested.')).toBeVisible();
  await responsive('transfer request');

  // --- The scoped list shows the new REQUESTED row --------------------------
  const table = page.getByRole('table', { name: 'Authorized transfers' });
  await expect(table).toBeVisible();
  const requestRow = table.getByRole('row', { name: new RegExp(createdTransfer.transferNumber) });
  await expect(requestRow).toBeVisible();
  await expect(requestRow).toContainText('REQUESTED');
  await expect(requestRow).toContainText('BED_SHORTAGE');
  await expect(requestRow).toContainText('Demo Legacy Hospital → Demo Riverside Hospital (outgoing)');

  // --- Detail: source-side controls only ------------------------------------
  await requestRow.getByRole('button', { name: /View transfer/ }).click();
  const detail = page.getByRole('complementary', { name: 'Transfer detail' });
  await expect(detail).toBeVisible();
  await expect(detail).toContainText('REQUESTED');
  await expect(detail.getByRole('button', { name: 'Cancel transfer' })).toBeVisible();
  await expect(detail.getByRole('button', { name: 'Start transit' })).toBeVisible();
  await expect(detail.getByRole('button', { name: 'Accept transfer' })).toHaveCount(0);
  await responsive('transfer detail');

  // --- Cancel with the two-step confirmation --------------------------------
  await detail.getByRole('button', { name: 'Cancel transfer' }).click();
  await detail.getByRole('button', { name: 'Confirm cancel' }).click();
  await expect(table.getByRole('row', { name: /CANCELLED/ }).first()).toBeVisible({
    timeout: 15_000,
  });
  await responsive('transfer cancelled');

  // --- The cancelled transfer is terminal: no transitions remain ------------
  await table.getByRole('row', { name: /CANCELLED/ }).first()
    .getByRole('button', { name: /View transfer/ }).click();
  await expect(detail.getByRole('button', { name: 'Cancel transfer' })).toHaveCount(0);
  await expect(detail.getByRole('button', { name: 'Start transit' })).toHaveCount(0);
  await expect(detail).toContainText('CANCELLED');

  // No unrelated console/page errors anywhere on the journey.
  const summary = guard.finish(`transfer journey (${testInfo.project.name})`);
  console.log(
    `transfer-journey(${testInfo.project.name}): request=201 cancel=200 consoleErrors=${summary.consoleErrors} allowedNon2xx=${summary.allowed}`
  );
});
