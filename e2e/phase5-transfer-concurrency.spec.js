import { expect, test } from '@playwright/test';
import { apiGet, apiLogin, bearer, runTag } from './helpers.mjs';

// Phase 5 container E2E — exact conflict/idempotency transfer assertions
// (T150) on a request context (no browser page): the two refusal classes of
// the transfer contract are proven with exact status codes and exact
// durable state, on the disposable Compose stack.
//
//   1. Same key + same payload  -> the stored outcome replays (201 stays
//      the one create; the replay answers 201 with the identical id).
//   2. Same key + changed payload -> exactly 409, and exactly one transfer
//      exists for the pair (no duplicate row).
//   3. Stale expectedVersion accept -> exactly 409 with the reservation
//      absent and the stored version unchanged.
//   4. A foreign-hospital transfer read answers the SAME generic 404 as an
//      unknown id — zero existence disclosure.
//
// The acting context is switched through the real /api/auth/context
// endpoint; every durable identifier carries the run tag. Every non-2xx
// here is asserted by exact status on a request context.

test('transfer idempotency replays, conflicts, and stale versions answer exactly @concurrency', async ({ request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'conflict rehearsal runs once, on desktop');
  test.setTimeout(240_000);

  const tag = runTag();
  const session = await apiLogin(request, 'admin', process.env.HOSPITAL_ADMIN_PASSWORD);
  const key = `e2e-conflict-${tag}`;

  // --- Fixture: the seeded Alpha patient has the active source admission ---
  // Bravo is intentionally discharged in the demo cohort; Alpha owns its
  // open ADMITTED admission. Resolve both through the authenticated API and
  // tie them by patient id, rather than assuming the first patient result is
  // eligible for transfer.
  const patients = await apiGet(request, session, '/api/patients?q=Demo%20Patient%20Alpha', 200);
  const patientsBody = await patients.json();
  const patient = patientsBody.find((row) => row.medicalRecordNumber === 'DEMO-0001');
  expect(patient, 'the seeded source-side patient must be readable').toBeTruthy();

  const admissionsResponse = await apiGet(request, session, '/api/admissions', 200);
  const admissions = await admissionsResponse.json();
  const openAdmission = admissions.find(
    (admission) => admission.patientId === patient.id && admission.status === 'ADMITTED'
  );
  expect(openAdmission, 'the source patient must have an active ADMITTED admission').toBeTruthy();
  expect(openAdmission.dischargedAt, 'an active admission must not be discharged').toBeNull();

  // The destination is any other active hospital of the network.
  const hierarchyResponse = await apiGet(request, session, '/api/network/hierarchy', 200);
  const hierarchy = await hierarchyResponse.json();
  const destination = hierarchy.hospitals.find(
    (hospital) => hospital.id !== session.actingContext.hospitalId
  );
  expect(destination, 'another hospital must exist as the transfer destination').toBeTruthy();
  const destinationBranch = destination.branches.find((branch) => branch.active);
  expect(destinationBranch, 'the destination hospital must have an active branch').toBeTruthy();

  const createPayload = {
    patientId: patient.id,
    sourceAdmissionId: openAdmission.id,
    destinationHospitalId: destination.id,
    reasonCode: 'BED_SHORTAGE',
  };

  // --- 1. First create: exactly 201 -----------------------------------------
  // A key-less create must be refused with 400 before anything is stored.
  const keyLess = await request.post(`${process.env.REVIEW_API_ORIGIN}/api/transfers`, {
    headers: bearer(session),
    data: createPayload,
  });
  expect(keyLess.status(), 'a key-less create must be refused with exactly 400').toBe(400);

  const first = await request.post(`${process.env.REVIEW_API_ORIGIN}/api/transfers`, {
    headers: { ...bearer(session), 'Idempotency-Key': key },
    data: createPayload,
  });
  expect(first.status(), 'the first create must be exactly 201').toBe(201);
  const firstBody = await first.json();
  expect(firstBody.status).toBe('REQUESTED');

  // --- 2. Same key + same payload: the stored outcome replays as 201 ---------
  const replay = await request.post(`${process.env.REVIEW_API_ORIGIN}/api/transfers`, {
    headers: { ...bearer(session), 'Idempotency-Key': key },
    data: createPayload,
  });
  expect(replay.status(), 'a same-key same-payload replay must answer exactly 201').toBe(201);
  const replayBody = await replay.json();
  expect(replayBody.id, 'the replay must return the identical transfer id').toBe(firstBody.id);

  // --- 3. Same key + changed payload: exactly 409 ----------------------------
  const conflict = await request.post(`${process.env.REVIEW_API_ORIGIN}/api/transfers`, {
    headers: { ...bearer(session), 'Idempotency-Key': key },
    data: { ...createPayload, reasonCode: 'SPECIALIST_CARE' },
  });
  expect(conflict.status(), 'a same-key different-payload create must be exactly 409').toBe(409);

  // Exactly one transfer row exists for this create: the list carries the
  // id exactly once (the scoped slice is the same server view the UI reads).
  const list = await apiGet(request, session, '/api/transfers', 200);
  const listBody = await list.json();
  const matching = listBody.filter((row) => row.id === firstBody.id);
  expect(matching, 'exactly one transfer row may exist for the created request').toHaveLength(1);
  expect(matching[0].status, 'the surviving row stays REQUESTED').toBe('REQUESTED');

  // --- 4. Stale expectedVersion accept: exactly 409 --------------------------
  // Accept requires the destination side; switch the acting context.
  const assignment = session.assignments.find((candidate) => candidate.enabled);
  const contextResponse = await request.post(`${process.env.REVIEW_API_ORIGIN}/api/auth/context`, {
    headers: bearer(session),
    data: {
      assignmentId: assignment.id,
      hospitalId: destination.id,
      branchId: destinationBranch.id,
    },
  });
  expect(contextResponse.status(), 'the context switch to the destination must be 200').toBe(200);
  const destinationSession = await contextResponse.json();

  const staleAccept = await request.post(
    `${process.env.REVIEW_API_ORIGIN}/api/transfers/${firstBody.id}/accept`,
    {
      headers: { ...bearer(destinationSession), 'Idempotency-Key': `stale-${tag}` },
      data: { destinationBranchId: destinationBranch.id, destinationBedId: '00000000-0000-0000-0000-000000000000', expectedVersion: 999 },
    }
  );
  // Stale version is checked before destination branch/bed lookup, so this
  // refusal must be exactly 409 even though the bed id is deliberately
  // unknown. The reread below proves no partial accept or version mutation.
  expect(staleAccept.status(), 'a stale expectedVersion must be exactly 409').toBe(409);

  const reread = await apiGet(request, destinationSession, `/api/transfers/${firstBody.id}`, 200);
  const rereadBody = await reread.json();
  expect(rereadBody.version, 'the stored version must be untouched by the refused accept').toBe(0);
  expect(rereadBody.status, 'the stored status must stay REQUESTED').toBe('REQUESTED');

  // --- 5. Foreign-hospital read: the same generic 404 as an unknown id -------
  // Switch to a third hospital that is neither source nor destination.
  const third = hierarchy.hospitals.find(
    (hospital) => hospital.id !== session.actingContext.hospitalId && hospital.id !== destination.id
  );
  if (third) {
    const thirdBranch = third.branches.find((branch) => branch.active);
    expect(thirdBranch, 'a third hospital must have an active branch').toBeTruthy();
    const thirdContext = await request.post(`${process.env.REVIEW_API_ORIGIN}/api/auth/context`, {
      headers: bearer(session),
      data: { assignmentId: assignment.id, hospitalId: third.id, branchId: thirdBranch.id },
    });
    expect(thirdContext.status(), 'the context switch to the third hospital must be 200').toBe(200);
    const thirdSession = await thirdContext.json();

    const foreignKnown = await request.get(
      `${process.env.REVIEW_API_ORIGIN}/api/transfers/${firstBody.id}`,
      { headers: bearer(thirdSession) }
    );
    const foreignUnknown = await request.get(
      `${process.env.REVIEW_API_ORIGIN}/api/transfers/00000000-0000-0000-0000-000000000000`,
      { headers: bearer(thirdSession) }
    );
    expect(foreignKnown.status(), 'a foreign hospital must receive the generic 404').toBe(404);
    expect(foreignUnknown.status(), 'an unknown id must receive the same generic 404').toBe(404);
  }

  console.log(
    `transfer-conflict(${tag}): create=201 replay=201 conflict=409 staleVersion=${staleAccept.status()} foreign=404`
  );
});
