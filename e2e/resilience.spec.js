import { expect, test } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { apiGet, apiLogin, bearer, runTag, switchContextToBranch } from './helpers.mjs';

// Phase 4 container E2E — resilience rehearsals (T082 backend restart,
// T083 PostgreSQL loss + recovery) against the disposable Compose stack.
//
// Container control is NOT done with raw docker commands here: every
// destructive action routes through scripts/phase4/container-e2e-stack.sh,
// which refuses to act on any project it does not own (disposable-target
// guard for the container stack). The browser is not used in this spec —
// these are exact-transport assertions (T084 discipline: every non-2xx here
// is asserted by exact status code on a request context, never on a page).
//
// Desktop project only: the rehearsal mutates shared stack state, so it must
// run exactly once per suite.

function stack(action) {
  // Fails loudly on non-zero exit; the guard script prints its own refusals.
  const stackScript = process.env.E2E_STACK_SCRIPT;
  if (!stackScript) {
    throw new Error('E2E_STACK_SCRIPT must point at scripts/phase4/container-e2e-stack.sh');
  }
  execFileSync(stackScript, [action], { stdio: 'inherit', timeout: 300_000 });
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

test('backend restart preserves synthetic state with zero pending migrations @resilience', async ({ request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'resilience runs once, on desktop');
  test.setTimeout(420_000);

  const tag = runTag();
  const session = await apiLogin(request, 'admin', process.env.HOSPITAL_ADMIN_PASSWORD);
  const mrn = `E2E-RS-${tag}`;

  // Durable synthetic state created before the restart.
  const created = await request.post(`${process.env.REVIEW_API_ORIGIN}/api/patients`, {
    headers: bearer(session),
    data: { medicalRecordNumber: mrn, fullName: `Demo E2E Restart ${tag}` },
  });
  expect(created.status(), 'pre-restart patient creation must be exactly 200').toBe(200);
  const createdBody = await created.json();

  // --- Restart the backend container (guarded) ------------------------------
  stack('restart-backend');
  await waitForReadiness(request, true, 240_000);
  expect(await livenessStatus(request), 'liveness must be 200 after restart').toBe(200);

  // Zero pending migrations: the guarded script compares the applied
  // flyway_schema_history rows against the shipped migration files and
  // fails the run on any pending or failed migration.
  stack('assert-no-pending-migrations');

  // Preserved synthetic state: the pre-restart patient survives the restart
  // on an exact 200, with identical durable fields.
  const northSession = await switchContextToBranch(request, session, 'DEMO-BR-002');
  await apiGet(request, northSession, `/api/patients/${createdBody.id}`, 404);
  const reread = await apiGet(request, session, `/api/patients/${createdBody.id}`, 200);
  const rereadBody = await reread.json();
  expect(rereadBody.medicalRecordNumber, 'synthetic MRN must survive the restart').toBe(mrn);
  expect(rereadBody.fullName, 'synthetic name must survive the restart').toBe(
    `Demo E2E Restart ${tag}`
  );
});

test('PostgreSQL loss splits liveness/readiness; restore recovers on the same backend @resilience', async ({ request }, testInfo) => {
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

  // Liveness stays 200 (process-only); readiness turns EXACTLY 503
  // (process + db, Spring health Down mapping) while PostgreSQL is down.
  const lossReadiness = await waitForReadiness(request, false, 60_000);
  expect(
    lossReadiness,
    'readiness must be exactly 503 while PostgreSQL is down'
  ).toBe(503);
  expect(
    await livenessStatus(request),
    'liveness must stay exactly 200 during PostgreSQL loss'
  ).toBe(200);

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
  await apiGet(request, session, '/api/patients?', 200);
});

function stackCapture(what) {
  const stackScript = process.env.E2E_STACK_SCRIPT;
  if (!stackScript) {
    throw new Error('E2E_STACK_SCRIPT must point at scripts/phase4/container-e2e-stack.sh');
  }
  return execFileSync(stackScript, ['capture', what], { encoding: 'utf8', timeout: 30_000 }).trim();
}
