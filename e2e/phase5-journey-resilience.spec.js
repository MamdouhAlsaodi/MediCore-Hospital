import { expect, test } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { apiGet, apiLogin, bearer, runTag } from './helpers.mjs';

// Phase 5 container E2E — journey resilience proofs (T153/T154) against the
// disposable Compose stack, routed through the OWNED control plane of
// scripts/phase5/container-network-journey.sh:
//
//   T153: a backend restart re-applies ZERO migrations (Flyway history stays
//         exactly the shipped file count) and the synthetic transfer state
//         created before the restart survives with identical durable fields.
//   T154: stopping PostgreSQL splits the probes truthfully — liveness stays
//         exactly 200 (process-only), readiness turns EXACTLY 503 — and the
//         same backend process (untouched container id) recovers to a real
//         database-backed read on restart.
//
// Desktop project only: these rehearsals mutate shared stack state.
// Every non-2xx here is asserted by exact status on a request context.

function stack(action) {
  const stackScript = process.env.E2E_STACK_SCRIPT;
  if (!stackScript || !stackScript.includes('phase5/container-network-journey.sh')) {
    throw new Error('E2E_STACK_SCRIPT must point at scripts/phase5/container-network-journey.sh');
  }
  execFileSync(stackScript, [action], { stdio: 'inherit', timeout: 300_000 });
}

function stackCapture(what) {
  const stackScript = process.env.E2E_STACK_SCRIPT;
  return execFileSync(stackScript, ['capture', what], { encoding: 'utf8', timeout: 30_000 }).trim();
}

async function waitForReadiness(request, expectedOk, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    try {
      const response = await request.get(`${process.env.REVIEW_API_ORIGIN}/actuator/health/readiness`);
      if (expectedOk && response.status() === 200) return 200;
      if (!expectedOk && response.status() !== 200) return response.status();
    } catch {
      // connection refused during restart: keep polling until the deadline
    }
    if (Date.now() > deadline) {
      throw new Error(
        `readiness did not ${expectedOk ? 'return to 200' : 'turn non-200'} within ${timeoutMs}ms`
      );
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
}

async function livenessStatus(request) {
  const response = await request.get(`${process.env.REVIEW_API_ORIGIN}/actuator/health/liveness`);
  return response.status();
}

test('backend restart keeps Flyway history exact and transfer state durable @resilience', async ({ request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'resilience runs once, on desktop');
  test.setTimeout(420_000);

  const tag = runTag();
  const session = await apiLogin(request, 'admin', process.env.HOSPITAL_ADMIN_PASSWORD);

  // Durable synthetic transfer state created before the restart.
  const patients = await apiGet(request, session, '/api/patients?q=Demo%20Patient%20Alpha', 200);
  const patient = (await patients.json())[0];
  expect(patient, 'the seeded transfer-source patient must exist').toBeTruthy();
  const admissions = await (await apiGet(request, session, '/api/admissions', 200)).json();
  const openAdmission = admissions.find(
    (admission) => admission.patientId === patient.id && admission.status === 'ADMITTED'
  );
  expect(openAdmission, 'an open admission must exist for the transfer source').toBeTruthy();
  const hierarchy = await (await apiGet(request, session, '/api/network/hierarchy', 200)).json();
  const destination = hierarchy.hospitals.find(
    (hospital) => hospital.id !== session.actingContext.hospitalId
  );
  expect(destination, 'another hospital must exist as the transfer destination').toBeTruthy();

  const created = await request.post(`${process.env.REVIEW_API_ORIGIN}/api/transfers`, {
    headers: { ...bearer(session), 'Idempotency-Key': `e2e-restart-${tag}` },
    data: {
      patientId: patient.id,
      sourceAdmissionId: openAdmission.id,
      destinationHospitalId: destination.id,
      reasonCode: 'SPECIALIST_CARE',
    },
  });
  expect(created.status(), 'the pre-restart transfer create must be exactly 201').toBe(201);
  const createdBody = await created.json();

  // --- Restart the backend container (guarded) ------------------------------
  stack('restart-backend');
  await waitForReadiness(request, true, 240_000);
  expect(await livenessStatus(request), 'liveness must be 200 after restart').toBe(200);

  // T153: the applied migration count is exactly the shipped file count —
  // the restart applied zero new migrations and none failed.
  stack('assert-no-pending-migrations');

  // The pre-restart transfer survives with identical durable fields.
  const reread = await apiGet(request, session, `/api/transfers/${createdBody.id}`, 200);
  const rereadBody = await reread.json();
  expect(rereadBody.transferNumber, 'the transfer number must survive the restart')
    .toBe(createdBody.transferNumber);
  expect(rereadBody.status, 'the transfer status must survive the restart').toBe('REQUESTED');
  expect(rereadBody.reasonCode, 'the reason code must survive the restart').toBe('SPECIALIST_CARE');
  expect(rereadBody.version, 'the optimistic version must survive the restart').toBe(0);
});

test('PostgreSQL loss splits liveness/readiness; the same backend recovers @resilience', async ({ request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'resilience runs once, on desktop');
  test.setTimeout(420_000);

  // Healthy baseline: both probes 200.
  expect(await livenessStatus(request), 'baseline liveness must be 200').toBe(200);
  const baselineReadiness = await request.get(
    `${process.env.REVIEW_API_ORIGIN}/actuator/health/readiness`
  );
  expect(baselineReadiness.status(), 'baseline readiness must be 200').toBe(200);

  const backendContainerBefore = stackCapture('backend-container-id');

  // --- Stop PostgreSQL (guarded) --------------------------------------------
  stack('stop-postgres');

  const lossReadiness = await waitForReadiness(request, false, 60_000);
  expect(lossReadiness, 'readiness must be exactly 503 while PostgreSQL is down').toBe(503);
  expect(await livenessStatus(request), 'liveness must stay exactly 200 during the loss').toBe(200);

  // --- Restore PostgreSQL (guarded); same backend process -------------------
  stack('start-postgres');
  const recoveredReadiness = await waitForReadiness(request, true, 240_000);
  expect(recoveredReadiness, 'readiness must recover to exactly 200').toBe(200);
  expect(await livenessStatus(request), 'liveness after recovery must be 200').toBe(200);

  const backendContainerAfter = stackCapture('backend-container-id');
  expect(
    backendContainerAfter,
    'the backend container must survive the database loss/recovery untouched'
  ).toBe(backendContainerBefore);

  // The same backend process serves real reads again (login through the
  // full auth path proves database-backed work end to end).
  const session = await apiLogin(request, 'admin', process.env.HOSPITAL_ADMIN_PASSWORD);
  await apiGet(request, session, '/api/transfers', 200);
});
