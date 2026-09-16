import { expect, test } from '@playwright/test';
import {
  ErrorGuard,
  MAIN_BRANCH_LINE,
  assertNoHorizontalOverflow,
  assertResponsiveLine,
  loginAsAdmin,
  runTag,
  selectByOptionText,
} from './helpers.mjs';

// Phase 4 container E2E — full synthetic workflow journey (T079 desktop,
// T080 mobile) over the disposable Compose stack: PostgreSQL + backend +
// frontend, all loopback-only, all credentials process-local (T077).
//
// One sequential journey per configured viewport:
//   login → acting context → patient registration → appointment (with one
//   deliberate, exactly-asserted 409 conflict) → admission without bed →
//   bed assignment → discharge → emergency visit → simulated invoice →
//   command center → audit evidence.
//
// Every mutation asserts the exact success message and the exact transport
// status. The ErrorGuard fails the run on any console/page error that is not
// an explicitly allowed, deliberately provoked non-2xx (T084). No trace,
// screenshot, or video is ever produced (T076): acceptance is assertion-only.
//
// Mobile additionally runs the one-line responsive check (no horizontal
// overflow, navigation usable) at every journey screen (T080).

const PATIENT_BASE = 'Demo E2E Patient';
const APPOINTMENT_DATE = '2031-03-02'; // inside the seeded modeled-availability window
const APPOINTMENT_TYPE_BASE = 'E2E consult';
const ADMITTED_AT = '2033-06-16T09:00';
const ADMISSION_REASON_BASE = 'E2E admission workflow';
const COMPLAINT_BASE = 'E2E chief complaint';
const INVOICE_BASE = 'E2E-INV';

async function openScreen(page, label, heading) {
  await page
    .getByRole('navigation', { name: 'Screens permitted for your roles' })
    .getByRole('button', { name: label })
    .click();
  await expect(page.locator('main header h2')).toHaveText(heading);
}

test('container synthetic workflow journey', async ({ page }, testInfo) => {
  const isMobile = testInfo.project.name === 'mobile';
  // Every durable identifier carries the run tag AND the project name: the
  // stack is shared by the desktop and mobile runs, and duplicate MRNs,
  // invoice numbers, or booking slots are server-refused by design.
  const tag = `${runTag()}-${testInfo.project.name}`;
  const MRN = `E2E-${tag}`;
  const PATIENT_NAME = `${PATIENT_BASE} ${tag}`;
  const APPOINTMENT_AT = `${APPOINTMENT_DATE}T${isMobile ? '10:30' : '10:00'}`;
  const APPOINTMENT_TYPE = `${APPOINTMENT_TYPE_BASE} ${tag}`;
  const ADMISSION_REASON = `${ADMISSION_REASON_BASE} ${tag}`;
  const COMPLAINT = `${COMPLAINT_BASE} ${tag}`;
  const INVOICE_NUMBER = `${INVOICE_BASE}-${tag}`;
  const responsive = async (label) => {
    if (isMobile) await assertResponsiveLine(page, label);
    else await assertNoHorizontalOverflow(page, label);
  };

  const guard = new ErrorGuard(page);
  const conflictResponses = [];
  page.on('response', (response) => {
    if (response.status() === 409 && response.url().endsWith('/api/appointments')) {
      conflictResponses.push(response.status());
    }
  });

  // --- Login ---------------------------------------------------------------
  await loginAsAdmin(page);
  await responsive('login');
  await expect(page.locator('.whoami-name')).toHaveText('admin');
  await expect(page.locator('.whoami-roles')).toHaveText('ADMIN');
  await expect(page.locator('.whoami-context')).toHaveText(MAIN_BRANCH_LINE);
  const selector = page.getByRole('combobox', { name: 'Acting context' });
  for (const branchName of ['Demo Main Branch', 'Demo North Branch', 'Demo Harbor Branch']) {
    await expect(selector.locator('option', { hasText: branchName })).toHaveCount(1);
  }
  await responsive('acting context');

  // --- Patient registration ------------------------------------------------
  await openScreen(page, 'Patients', 'Patients');
  await page.locator('.patient-new', { hasText: 'New patient' }).click();
  const patientForm = page.locator('section[aria-label="Patient form"]');
  await expect(patientForm).toBeVisible();
  await patientForm.getByLabel('Medical record number').fill(MRN);
  await patientForm.getByLabel('Full name').fill(PATIENT_NAME);
  const patientResponse = page.waitForResponse(
    (response) => response.url().endsWith('/api/patients') && response.request().method() === 'POST'
  );
  await patientForm.getByRole('button', { name: 'Save patient' }).click();
  expect((await patientResponse).status(), 'patient creation must be exactly 200').toBe(200);
  await expect(page.getByText('Patient registered successfully.')).toBeVisible();
  // Registration opens the server-returned detail view; the detail panel is
  // the durable proof of the new record.
  const detailPanel = page.locator('section[aria-label="Selected patient"]');
  await expect(detailPanel).toBeVisible();
  await expect(detailPanel.getByText(PATIENT_NAME)).toBeVisible();
  await expect(detailPanel.getByText(MRN, { exact: true })).toBeVisible();
  await detailPanel.getByRole('button', { name: 'Back to patient list' }).click();
  const patientList = page.getByRole('list', { name: 'Patient results' });
  await expect(patientList).toBeVisible();
  await expect(patientList).toContainText(PATIENT_NAME);
  await responsive('patient registration');

  // --- Appointment (create + deliberate exactly-asserted 409) --------------
  await openScreen(page, 'Appointments', 'Appointments');
  const appointmentsTable = page.getByRole('table', { name: 'Scheduled appointments' });
  await expect(appointmentsTable).toBeVisible();
  await page.getByRole('button', { name: 'Schedule appointment' }).first().click();
  const appointmentForm = page.locator('form[aria-label="Schedule an appointment"]');
  await expect(appointmentForm).toBeVisible();
  await selectByOptionText(appointmentForm.getByLabel('Patient'), PATIENT_NAME);
  await selectByOptionText(appointmentForm.getByLabel('Professional'), 'Demo Physician Alpha');
  const availabilityResponse = page.waitForResponse(
    (response) =>
      response.url().includes('/api/staff/')
      && response.url().includes('/availability?')
      && response.request().method() === 'GET'
  );
  await appointmentForm.getByLabel('Date and time').fill(APPOINTMENT_AT);
  expect((await availabilityResponse).status(), 'modeled availability must load').toBe(200);
  // The modeled window must actually exist for the chosen day (the server
  // refuses bookings outside modeled intervals with 409).
  await expect(appointmentForm.getByText(/2031-03-02T08:00/)).toBeVisible();
  await appointmentForm.getByLabel('Duration (minutes)').fill('30');
  await appointmentForm.getByLabel('Type').fill(APPOINTMENT_TYPE);
  const appointmentResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith('/api/appointments') && response.request().method() === 'POST'
  );
  await appointmentForm.getByRole('button', { name: 'Schedule appointment' }).click();
  expect((await appointmentResponse).status(), 'appointment creation must be exactly 200').toBe(200);
  await expect(appointmentForm).not.toBeVisible();
  await expect(appointmentsTable).toContainText(APPOINTMENT_TYPE);
  await responsive('appointment creation');

  // Same professional, same instant, different patient: the server must
  // refuse with EXACTLY 409 and the form must keep its inline message.
  guard.allowNon2xx(409, '/api/appointments');
  await page.getByRole('button', { name: 'Schedule appointment' }).first().click();
  await expect(appointmentForm).toBeVisible();
  await selectByOptionText(appointmentForm.getByLabel('Patient'), 'Demo Patient Alpha');
  await selectByOptionText(appointmentForm.getByLabel('Professional'), 'Demo Physician Alpha');
  await appointmentForm.getByLabel('Date and time').fill(APPOINTMENT_AT);
  await appointmentForm.getByLabel('Duration (minutes)').fill('30');
  await appointmentForm.getByLabel('Type').fill(`${APPOINTMENT_TYPE} conflict`);
  const conflictResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith('/api/appointments') && response.request().method() === 'POST'
  );
  await appointmentForm.getByRole('button', { name: 'Schedule appointment' }).click();
  expect(
    (await conflictResponse).status(),
    'the deliberate double-booking must be refused with exactly 409'
  ).toBe(409);
  await expect(appointmentForm.getByRole('alert')).toHaveText(
    'The request failed (409). Please try again.'
  );
  await appointmentForm.getByRole('button', { name: 'Cancel' }).click();
  await expect(appointmentsTable).not.toContainText(`${APPOINTMENT_TYPE} conflict`);
  await responsive('appointment conflict');

  // --- Admission without bed, then bed assignment --------------------------
  await openScreen(page, 'Admissions', 'Admissions');
  const admissionsTable = page.getByRole('table', { name: 'Registered admissions' });
  await expect(admissionsTable).toBeVisible();
  await page.locator('.admission-new', { hasText: 'Register admission' }).click();
  const admissionForm = page.locator('form[aria-label="Register an admission"]');
  await expect(admissionForm).toBeVisible();
  await selectByOptionText(admissionForm.getByLabel('Patient'), PATIENT_NAME);
  await admissionForm.getByLabel('Admitted at').fill(ADMITTED_AT);
  await admissionForm.getByLabel('Reason').fill(ADMISSION_REASON);
  await admissionForm.getByLabel('Bed (optional)').selectOption({ label: 'No bed at registration' });
  const admissionResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith('/api/admissions') && response.request().method() === 'POST'
  );
  await admissionForm.getByRole('button', { name: 'Register admission' }).click();
  expect((await admissionResponse).status(), 'admission creation must be exactly 200').toBe(200);
  await expect(page.getByText('Admission registered.')).toBeVisible();
  const ownRow = admissionsTable.getByRole('row', { name: new RegExp(PATIENT_NAME) });
  await expect(ownRow).toContainText('ADMITTED');
  await expect(ownRow).toContainText('No bed assigned');
  await responsive('admission registration');

  await ownRow.getByRole('button', { name: 'Assign bed' }).click();
  const assignSelect = ownRow.getByLabel('Available bed');
  // Skip the empty placeholder option; take the first real bed offer.
  const bedOption = assignSelect.locator('option[value]:not([value=""])').first();
  await expect(bedOption, 'an available bed must be offered').toHaveCount(1);
  const bedLabel = (await bedOption.textContent())?.trim();
  await assignSelect.selectOption(await bedOption.getAttribute('value'));
  const assignResponse = page.waitForResponse(
    (response) =>
      response.url().includes('/api/admissions/')
      && response.url().endsWith('/bed')
      && response.request().method() === 'PUT'
  );
  await ownRow.getByRole('button', { name: 'Confirm assignment' }).click();
  expect((await assignResponse).status(), 'bed assignment must be exactly 200').toBe(200);
  await expect(page.getByText('Bed assigned.')).toBeVisible();
  await expect(admissionsTable.getByRole('row', { name: new RegExp(PATIENT_NAME) })).toContainText(
    String(bedLabel).trim()
  );
  await responsive('bed assignment');

  // --- Discharge -----------------------------------------------------------
  const dischargeRow = admissionsTable.getByRole('row', { name: new RegExp(PATIENT_NAME) });
  await dischargeRow.getByRole('button', { name: 'Discharge' }).click();
  const dischargeResponse = page.waitForResponse(
    (response) =>
      response.url().includes('/api/admissions/')
      && response.url().endsWith('/status')
      && response.request().method() === 'PUT'
  );
  await dischargeRow.getByRole('button', { name: 'Confirm discharge' }).click();
  expect((await dischargeResponse).status(), 'discharge must be exactly 200').toBe(200);
  await expect(page.getByText('Admission discharged.')).toBeVisible();
  await expect(admissionsTable.getByRole('row', { name: new RegExp(PATIENT_NAME) })).toContainText(
    'DISCHARGED'
  );
  await responsive('discharge');

  // --- Emergency visit -----------------------------------------------------
  await openScreen(page, 'Emergency Visits', 'Emergency Visits');
  await page.locator('.emergency-new', { hasText: 'Register visit' }).click();
  const emergencyForm = page.locator('form[aria-label="Register an emergency visit"]');
  await expect(emergencyForm).toBeVisible();
  await selectByOptionText(emergencyForm.getByLabel('Patient'), PATIENT_NAME);
  await emergencyForm.getByLabel('Arrival date and time').fill(ADMITTED_AT);
  await emergencyForm
    .getByLabel('Triage label (demo 1–5, no clinical meaning)')
    .selectOption({ label: '3' });
  await emergencyForm.getByLabel('Chief complaint').fill(COMPLAINT);
  const emergencyResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith('/api/emergency-visits') && response.request().method() === 'POST'
  );
  await emergencyForm.getByRole('button', { name: 'Register visit' }).click();
  expect((await emergencyResponse).status(), 'emergency visit creation must be exactly 200').toBe(200);
  await expect(page.getByText('Emergency visit registered.')).toBeVisible();
  await expect(page.getByRole('table', { name: 'Registered emergency visits' })).toContainText(
    PATIENT_NAME
  );
  await responsive('emergency visit');

  // --- Simulated invoice ---------------------------------------------------
  await openScreen(page, 'Invoices', 'Invoices');
  await page.locator('.invoice-new', { hasText: 'Create invoice' }).click();
  const invoiceForm = page.locator('form[aria-label="Create an invoice"]');
  await expect(invoiceForm).toBeVisible();
  await selectByOptionText(invoiceForm.getByLabel('Patient'), PATIENT_NAME);
  await invoiceForm.getByLabel('Invoice number').fill(INVOICE_NUMBER);
  await invoiceForm.getByLabel('Amount (demo)').fill('120.50');
  await invoiceForm.getByLabel('Currency (demo label)').fill('DEM');
  const invoiceResponse = page.waitForResponse(
    (response) => response.url().endsWith('/api/invoices') && response.request().method() === 'POST'
  );
  await invoiceForm.getByRole('button', { name: 'Create invoice' }).click();
  expect((await invoiceResponse).status(), 'invoice creation must be exactly 200').toBe(200);
  await expect(page.getByText('Invoice created.')).toBeVisible();
  await expect(page.getByRole('table', { name: 'Registered invoices' })).toContainText(
    INVOICE_NUMBER
  );
  await responsive('simulated invoice');

  // --- Command center ------------------------------------------------------
  await openScreen(page, 'Dashboard', 'Operations Dashboard');
  await expect(page.locator('p.dashboard-branch-line').filter({ hasText: 'DEMO-BR-001' })).toHaveText(
    'DEMO-BR-001 — Demo Main Branch'
  );
  await expect(page.getByText('Network comparison')).toBeVisible();
  const networkBranchList = page.getByRole('list', { name: 'Branches in the network' });
  for (const branchName of ['Demo Main Branch', 'Demo North Branch', 'Demo Harbor Branch']) {
    await expect(networkBranchList.getByRole('heading', { name: branchName })).toBeVisible();
  }
  await responsive('command center');
  await page.getByRole('button', { name: 'View patients' }).click();
  await expect(page.locator('main header h2')).toHaveText('Patients');
  await expect(page.getByRole('list', { name: 'Patient results' })).toContainText(PATIENT_NAME);

  // --- Audit evidence ------------------------------------------------------
  await openScreen(page, 'Audit', 'Audit Evidence');
  const auditTable = page.getByRole('table', { name: 'Audit events' });
  await expect(auditTable).toBeVisible();
  await page.getByLabel('Actor').fill('admin');
  await page.getByRole('button', { name: 'Apply filters' }).click();
  await expect(
    auditTable.getByRole('row', { name: /admin ADMIN ORGANIZATION Demo Main Branch CREATE Patient/ }).first()
  ).toBeVisible();
  // Credentials never appear anywhere in the evidence view.
  await expect(page.locator('body')).not.toContainText('eyJ');
  await expect(page.locator('body')).not.toContainText('Bearer ');
  await responsive('audit');

  // Exactly one deliberate conflict was exercised on this viewport.
  expect(conflictResponses, 'exactly one intentional 409 conflict must be observed').toEqual([409]);

  // No unrelated console/page errors anywhere on the journey (T084).
  const summary = guard.finish(`journey (${testInfo.project.name})`);
  console.log(
    `journey(${testInfo.project.name}): steps=10 conflict=409 consoleErrors=${summary.consoleErrors} allowedNon2xx=${summary.allowed}`
  );
});
