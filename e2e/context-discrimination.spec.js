import { expect, test } from '@playwright/test';
import {
  ErrorGuard,
  MAIN_BRANCH_LINE,
  NORTH_BRANCH_LINE,
  loginAsAdmin,
  runTag,
  selectBranchByLabel,
} from './helpers.mjs';

// Phase 4 container E2E — tagged context-switch discrimination (T081).
//
// After switching between branches of the same hospital, patient grants
// remain hospital-wide while beds remain branch-owned. Then switching to an
// unrelated hospital must remove every previously authorized patient row.
// The screen is intentionally re-visited twice (immediately, and after a
// full reload) so both the in-memory SPA state and any persisted client
// state are discriminated.

test('context-switch discrimination leaves no stale branch rows @context', async ({ page }, testInfo) => {
  const guard = new ErrorGuard(page);
  // Project-suffixed: desktop and mobile share the stack in one run.
  const tag = `${runTag()}-${testInfo.project.name}`;

  await loginAsAdmin(page);
  await expect(page.locator('.whoami-context')).toHaveText(MAIN_BRANCH_LINE);

  // Marker rows that only exist in the MAIN branch scope: the seeded
  // cohort's main-branch patient and a freshly created main-branch patient.
  const mainMarker = `Demo E2E Ctx ${tag}`;

  // beds: main-branch ward rows
  await page
    .getByRole('navigation', { name: 'Screens permitted for your roles' })
    .getByRole('button', { name: 'Beds' })
    .click();
  const bedsTable = page.getByRole('table', { name: 'Registered beds' });
  await expect(bedsTable).toBeVisible();
  await expect(bedsTable).toContainText('Demo Ward A');
  await expect(bedsTable).not.toContainText('Demo Ward N');

  // patients: seeded main cohort + create a unique marker patient
  await page
    .getByRole('navigation', { name: 'Screens permitted for your roles' })
    .getByRole('button', { name: 'Patients' })
    .click();
  const patientList = page.getByRole('list', { name: 'Patient results' });
  await expect(patientList).toContainText('Demo Patient Alpha');
  await page.locator('.patient-new', { hasText: 'New patient' }).click();
  const patientForm = page.locator('section[aria-label="Patient form"]');
  await patientForm.getByLabel('Medical record number').fill(`E2E-CTX-${tag}`);
  await patientForm.getByLabel('Full name').fill(mainMarker);
  await patientForm.getByRole('button', { name: 'Save patient' }).click();
  await expect(page.getByText('Patient registered successfully.')).toBeVisible();
  const detailPanel = page.locator('section[aria-label="Selected patient"]');
  await expect(detailPanel).toContainText(mainMarker);
  await detailPanel.getByRole('button', { name: 'Back to patient list' }).click();
  await expect(patientList).toBeVisible();
  await expect(patientList).toContainText(mainMarker);

  // --- Switch the acting context to the north branch -----------------------
  await selectBranchByLabel(page, 'Demo North Branch');
  await expect(page.locator('.whoami-context')).toHaveText(NORTH_BRANCH_LINE);

  // V6 patient visibility is hospital-wide: the main patient is still
  // authorized in the north sibling branch, not a stale-context leak.
  await page
    .getByRole('navigation', { name: 'Screens permitted for your roles' })
    .getByRole('button', { name: 'Patients' })
    .click();
  const northPatients = page.getByRole('list', { name: 'Patient results' });
  await expect(northPatients).toBeVisible();
  await expect(northPatients).toContainText('Demo Patient Delta');
  await expect(northPatients).toContainText('Demo Patient Alpha');
  await expect(northPatients).toContainText(mainMarker);
  await expect(northPatients).toContainText(`E2E-CTX-${tag}`);

  // …and again after a full reload: valid same-hospital access persists.
  await page.reload();
  await expect(page.locator('.whoami-context')).toHaveText(NORTH_BRANCH_LINE);
  await page
    .getByRole('navigation', { name: 'Screens permitted for your roles' })
    .getByRole('button', { name: 'Patients' })
    .click();
  const northPatientsReloaded = page.getByRole('list', { name: 'Patient results' });
  await expect(northPatientsReloaded).toBeVisible();
  await expect(northPatientsReloaded).toContainText('Demo Patient Delta');
  await expect(northPatientsReloaded).toContainText('Demo Patient Alpha');
  await expect(northPatientsReloaded).toContainText(mainMarker);

  // Beds discriminate the same way, both directions.
  await page
    .getByRole('navigation', { name: 'Screens permitted for your roles' })
    .getByRole('button', { name: 'Beds' })
    .click();
  const northBeds = page.getByRole('table', { name: 'Registered beds' });
  await expect(northBeds).toBeVisible();
  await expect(northBeds).toContainText('Demo Ward N');
  await expect(northBeds).not.toContainText('Demo Ward A');

  // The same-hospital search finds the patient through the ACTIVE grant.
  await page
    .getByRole('navigation', { name: 'Screens permitted for your roles' })
    .getByRole('button', { name: 'Patients' })
    .click();
  await page.getByRole('searchbox', { name: 'Search patients' }).fill('Demo Patient Alpha');
  await page.getByRole('button', { name: 'Search' }).click();
  await expect(page.getByRole('list', { name: 'Patient results' })).toContainText('Demo Patient Alpha');

  // Now cross the real security boundary: Harborview has no grant to any
  // legacy-hospital patient. Neither immediate state nor reload may leak it.
  await selectBranchByLabel(page, 'Demo Harborview Pier Branch');
  await expect(page.locator('.whoami-context')).toContainText('Demo Harborview Hospital');
  await page.getByRole('navigation', { name: 'Screens permitted for your roles' })
    .getByRole('button', { name: 'Patients' }).click();
  await expect(page.getByRole('heading', { name: 'No patients found' })).toBeVisible();
  await page.reload();
  await expect(page.locator('.whoami-context')).toContainText('Demo Harborview Hospital');
  await page.getByRole('navigation', { name: 'Screens permitted for your roles' })
    .getByRole('button', { name: 'Patients' }).click();
  await expect(page.getByRole('heading', { name: 'No patients found' })).toBeVisible();

  guard.finish('context discrimination');
});
