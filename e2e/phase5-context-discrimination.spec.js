import { expect, test } from '@playwright/test';
import {
  ErrorGuard,
  loginAsAdmin,
  selectBranchByLabel,
} from './helpers.mjs';

// Phase 5 container E2E — transfer scope follows the acting hospital
// (T149). A transfer created at the legacy source must remain visible as an
// incoming transfer at Riverside, then be absent from unrelated Harborview.
// Verify each scope both immediately and after reload. Context selection
// uses server-issued options through the shared helper.

test('stale transfer rows never render after a hospital context switch @context', async ({ page }, testInfo) => {
  const guard = new ErrorGuard(page);

  await loginAsAdmin(page);
  await expect(page.locator('.whoami-context')).toHaveText(
    'Demo Synthetic Hospital — Demo Legacy Hospital — Demo Main Branch',
    { timeout: 20_000 }
  );

  // --- Create a transfer under the MAIN context (source side) ---------------
  await page
    .getByRole('navigation', { name: 'Screens permitted for your roles' })
    .getByRole('button', { name: 'Transfers' })
    .click();
  const createResponse = page.waitForResponse(
    (response) => response.url().endsWith('/api/transfers') && response.request().method() === 'POST'
  );
  await page.getByRole('button', { name: 'Request transfer' }).click();
  const form = page.getByRole('form', { name: 'Request a transfer' });
  await expect(form.getByLabel('Patient').locator('option', { hasText: 'Demo Patient Alpha' })).toHaveCount(1, { timeout: 20_000 });
  await form.getByLabel('Patient').selectOption({ label: 'Demo Patient Alpha (MRN DEMO-0001)' });
  await expect(form.getByLabel('Admission').locator('option').nth(1)).toBeAttached({ timeout: 20_000 });
  const admissionValue = await form.getByLabel('Admission').locator('option').nth(1).getAttribute('value');
  await form.getByLabel('Admission').selectOption(admissionValue);
  await expect(form.getByLabel('Destination hospital').locator('option', { hasText: 'Demo Riverside' })).toHaveCount(1, { timeout: 20_000 });
  await form.getByLabel('Destination hospital').selectOption({ label: 'Demo Riverside Hospital' });
  await form.getByLabel('Reason').selectOption('SPECIALIST_CARE');
  const listResponse = page.waitForResponse(
    (response) => response.url().endsWith('/api/transfers') && response.request().method() === 'GET'
  );
  await form.getByRole('button', { name: 'Submit transfer request' }).click();
  const response = await createResponse;
  expect(response.status(), 'pre-switch transfer creation must be 201').toBe(201);
  const createdTransfer = await response.json();
  await expect(page.getByRole('table', { name: 'Authorized transfers' })).toContainText('SPECIALIST_CARE');
  expect((await listResponse).status(), 'the scoped list must reload as 200').toBe(200);

  // --- Switch the acting hospital (legacy → Riverside) ----------------------
  await selectBranchByLabel(page, 'Demo Riverside East Branch');
  await expect(page.locator('.whoami-context')).toContainText('Demo Riverside', { timeout: 20_000 });

  // --- Immediately: the authorized destination sees this as incoming --------
  await page
    .getByRole('navigation', { name: 'Screens permitted for your roles' })
    .getByRole('button', { name: 'Transfers' })
    .click();
  const screenBody = page.locator('main');
  const createdRow = page.getByRole('table', { name: 'Authorized transfers' })
    .getByRole('row', { name: new RegExp(createdTransfer.transferNumber) });
  await expect(createdRow).toContainText('SPECIALIST_CARE');
  await expect(createdRow).toContainText('Demo Legacy Hospital → Demo Riverside Hospital (incoming)');

  // --- After reload: destination scope still includes the same transfer -----
  await page.reload();
  await page
    .getByRole('navigation', { name: 'Screens permitted for your roles' })
    .getByRole('button', { name: 'Transfers' })
    .click();
  const reloadedRow = page.getByRole('table', { name: 'Authorized transfers' })
    .getByRole('row', { name: new RegExp(createdTransfer.transferNumber) });
  await expect(reloadedRow).toContainText('Demo Legacy Hospital → Demo Riverside Hospital (incoming)', { timeout: 15_000 });

  // --- Unrelated Harborview is isolated from both ends of the transfer -----
  await selectBranchByLabel(page, 'Demo Harborview Pier Branch');
  await expect(page.locator('.whoami-context')).toContainText('Demo Harborview', { timeout: 20_000 });
  await page
    .getByRole('navigation', { name: 'Screens permitted for your roles' })
    .getByRole('button', { name: 'Transfers' })
    .click();
  await expect(screenBody.getByText('No transfers involve this hospital yet.')).toBeVisible();
  await expect(screenBody.getByRole('table', { name: 'Authorized transfers' })).toHaveCount(0);

  // --- Reload cannot leak the transfer into the unrelated hospital ---------
  await page.reload();
  await page
    .getByRole('navigation', { name: 'Screens permitted for your roles' })
    .getByRole('button', { name: 'Transfers' })
    .click();
  await expect(screenBody.getByText('No transfers involve this hospital yet.')).toBeVisible({ timeout: 15_000 });
  await expect(screenBody.getByRole('table', { name: 'Authorized transfers' })).toHaveCount(0);

  // The unrelated scope's request form remains interactive.
  await page.getByRole('button', { name: 'Request transfer' }).click();
  await expect(page.getByRole('form', { name: 'Request a transfer' })).toBeVisible();

  const summary = guard.finish(`context discrimination (${testInfo.project.name})`);
  console.log(
    `transfer-context(${testInfo.project.name}): staleRows=0 reloads=2 consoleErrors=${summary.consoleErrors}`
  );
});
