import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../api.js';
import {
  requestTransfer, listTransfers, fetchTransfer, acceptTransfer, rejectTransfer,
  cancelTransfer, startTransit, completeTransfer,
} from './transferApi.js';

// Phase 5 US8 (T141/T142): the transfer transport adapter contract over the
// GENERATED typed descriptors (requestTransfer/acceptTransfer/… in
// frontend/src/generated/api — see scripts/phase4/generate-openapi-client.mjs).
// Every write carries the mandatory Idempotency-Key header, every request
// rides the shared apiFetch envelope (Bearer token, JSON, ApiError,
// onUnauthorized), and responses parse through the strict TransferView
// allowlist — the seventeen contract keys exactly, never the entity or its
// persistence metadata. A malformed payload refuses with ApiError 500
// instead of improvising a partial row.
const TRANSFER_ROW = {
  id: '88888888-8888-4888-8888-888888888888',
  transferNumber: 'TR-0001',
  patientId: '55555555-5555-4555-8555-555555555555',
  sourceHospitalId: '44444444-4444-4444-8444-444444444444',
  sourceBranchId: '22222222-2222-4222-8222-222222222222',
  destinationHospitalId: '66666666-6666-4666-8666-666666666666',
  destinationBranchId: null,
  destinationBedId: null,
  status: 'REQUESTED',
  reasonCode: 'BED_SHORTAGE',
  requestedAt: '2026-03-02T10:00:00Z',
  acceptedAt: null,
  transitStartedAt: null,
  completedAt: null,
  cancelledAt: null,
  rejectedAt: null,
  version: 0,
};

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('transferApi adapter', () => {
  let fetchMock;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('lists the authorized transfers through the generated descriptor', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse([TRANSFER_ROW])));
    const rows = await listTransfers({ token: 'tok' });
    expect(rows).toHaveLength(1);
    expect(rows[0]).toEqual(TRANSFER_ROW);
    const [path, options] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/transfers');
    expect(options.method).toBe('GET');
    expect(options.headers.Authorization).toBe('Bearer tok');
    expect(options.body).toBeUndefined();
  });

  it('forwards the optional status filter exactly as the descriptor builds it', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse([])));
    await listTransfers({ token: 'tok', status: 'REQUESTED' });
    expect(fetchMock.mock.calls[0][0]).toBe('/api/transfers?status=REQUESTED');
  });

  it('creates a transfer with the exact four-field body and the Idempotency-Key header', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(TRANSFER_ROW, 201)));
    const row = await requestTransfer({
      token: 'tok',
      idempotencyKey: 'key-create-1',
      transfer: {
        patientId: TRANSFER_ROW.patientId,
        sourceAdmissionId: '77777777-7777-4777-8777-777777777777',
        destinationHospitalId: TRANSFER_ROW.destinationHospitalId,
        reasonCode: 'BED_SHORTAGE',
      },
    });
    expect(row.status).toBe('REQUESTED');
    const [path, options] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/transfers');
    expect(options.method).toBe('POST');
    expect(options.headers.Authorization).toBe('Bearer tok');
    expect(options.headers['Idempotency-Key']).toBe('key-create-1');
    expect(options.headers['Content-Type']).toBe('application/json');
    expect(options.body).toBe(JSON.stringify({
      patientId: TRANSFER_ROW.patientId,
      sourceAdmissionId: '77777777-7777-4777-8777-777777777777',
      destinationHospitalId: TRANSFER_ROW.destinationHospitalId,
      reasonCode: 'BED_SHORTAGE',
    }));
  });

  it('refuses a create without a bounded idempotency key before any request', async () => {
    // The key validation is synchronous (the request is never even built),
    // so the refusal surfaces as a throw on the call itself.
    let caught = null;
    try {
      await requestTransfer({
        token: 'tok', idempotencyKey: '   ',
        transfer: {
          patientId: 'p', sourceAdmissionId: 'a',
          destinationHospitalId: 'd', reasonCode: 'BED_SHORTAGE',
        },
      });
    } catch (error) {
      caught = error;
    }
    expect(caught).toMatchObject({ status: 400 });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('performs each transition with its idempotency key and parses the strict view', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(
      { ...TRANSFER_ROW, status: 'ACCEPTED', destinationBranchId: 'b1', destinationBedId: 'bed1', version: 1 }
    )));
    const accepted = await acceptTransfer({
      token: 'tok', id: TRANSFER_ROW.id, idempotencyKey: 'key-acc-1',
      decision: { destinationBranchId: 'b1', destinationBedId: 'bed1', expectedVersion: 0 },
    });
    expect(accepted.status).toBe('ACCEPTED');
    let [path, options] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/transfers/88888888-8888-4888-8888-888888888888/accept');
    expect(options.headers['Idempotency-Key']).toBe('key-acc-1');
    expect(JSON.parse(options.body).expectedVersion).toBe(0);

    fetchMock.mockClear();
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(TRANSFER_ROW)));
    await rejectTransfer({ token: 'tok', id: TRANSFER_ROW.id, idempotencyKey: 'key-rej-1',
      decision: { reasonCode: 'BED_SHORTAGE', expectedVersion: 0 } });
    [path, options] = fetchMock.mock.calls[0];
    expect(path.endsWith('/reject')).toBe(true);
    expect(options.headers['Idempotency-Key']).toBe('key-rej-1');

    fetchMock.mockClear();
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(TRANSFER_ROW)));
    await cancelTransfer({ token: 'tok', id: TRANSFER_ROW.id, idempotencyKey: 'key-can-1',
      decision: { reasonCode: 'PATIENT_PREFERENCE', expectedVersion: 0 } });
    expect(fetchMock.mock.calls[0][0].endsWith('/cancel')).toBe(true);

    fetchMock.mockClear();
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(TRANSFER_ROW)));
    await startTransit({ token: 'tok', id: TRANSFER_ROW.id, idempotencyKey: 'key-tra-1' });
    [path, options] = fetchMock.mock.calls[0];
    expect(path.endsWith('/start-transit')).toBe(true);
    expect(options.headers['Idempotency-Key']).toBe('key-tra-1');
    expect(options.body).toBeUndefined();

    fetchMock.mockClear();
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(TRANSFER_ROW)));
    await completeTransfer({ token: 'tok', id: TRANSFER_ROW.id, idempotencyKey: 'key-com-1' });
    expect(fetchMock.mock.calls[0][0].endsWith('/complete')).toBe(true);
  });

  it('fetches one authorized transfer and refuses malformed payloads with the typed 500', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(TRANSFER_ROW)));
    const row = await fetchTransfer({ token: 'tok', id: TRANSFER_ROW.id });
    expect(row.transferNumber).toBe('TR-0001');
    expect(fetchMock.mock.calls[0][0]).toBe('/api/transfers/' + TRANSFER_ROW.id);

    const broken = { ...TRANSFER_ROW };
    delete broken.status;
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(broken)));
    await expect(fetchTransfer({ token: 'tok', id: TRANSFER_ROW.id }))
      .rejects.toMatchObject({ status: 500 });
  });

  it('keeps the shared error surface: typed ApiError statuses pass through untouched', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse({ error: 'x' }, 409)));
    await expect(requestTransfer({
      token: 'tok', idempotencyKey: 'k1',
      transfer: { patientId: 'p', sourceAdmissionId: 'a', destinationHospitalId: 'd', reasonCode: 'BED_SHORTAGE' },
    })).rejects.toMatchObject({ status: 409, message: 'The request failed (409). Please try again.' });
  });
});
