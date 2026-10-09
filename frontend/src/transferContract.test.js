import { afterEach, describe, expect, it } from 'vitest';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import {
  acceptTransfer, cancelTransfer, completeTransfer, rejectTransfer,
  requestTransfer, startTransferTransit,
} from './generated/api/operations';

const script = resolve(import.meta.dirname, '../../scripts/phase4/generate-openapi-client.mjs');
const tempDirs = [];
afterEach(() => {
  for (const dir of tempDirs.splice(0)) rmSync(dir, { recursive: true, force: true });
});

const body = { reasonCode: 'BED_SHORTAGE', expectedVersion: 1 };
const createBody = {
  patientId: 'synthetic-patient', sourceAdmissionId: 'synthetic-admission',
  destinationHospitalId: 'synthetic-destination', reasonCode: 'BED_SHORTAGE',
};

describe('transfer write descriptors', () => {
  it.each([
    ['request', (key) => requestTransfer(createBody, key), '/api/transfers', createBody],
    ['accept', (key) => acceptTransfer('synthetic-id', { destinationBranchId: 'b', destinationBedId: 'bed', expectedVersion: 1 }, key), '/api/transfers/synthetic-id/accept'],
    ['reject', (key) => rejectTransfer('synthetic-id', body, key), '/api/transfers/synthetic-id/reject', body],
    ['cancel', (key) => cancelTransfer('synthetic-id', body, key), '/api/transfers/synthetic-id/cancel', body],
    ['start transit', (key) => startTransferTransit('synthetic-id', key), '/api/transfers/synthetic-id/start-transit'],
    ['complete', (key) => completeTransfer('synthetic-id', key), '/api/transfers/synthetic-id/complete'],
  ])('%s requires an idempotency key in its HTTP descriptor', (_name, build, path, requestBody) => {
    const descriptor = build('synthetic-key');
    expect(descriptor).toMatchObject({ method: 'POST', path, headers: { 'Idempotency-Key': 'synthetic-key' } });
    if (requestBody) expect(descriptor.body).toEqual(requestBody);
  });

  it('ships nullable transfer view fields in the checked-in client', () => {
    const schemas = readFileSync(resolve(import.meta.dirname, 'generated/api/schemas.ts'), 'utf8');
    for (const field of ['destinationBranchId', 'destinationBedId', 'acceptedAt',
      'transitStartedAt', 'completedAt', 'cancelledAt', 'rejectedAt']) {
      expect(schemas).toContain(`"${field}": string | null;`);
    }
  });

  it('emits a mandatory header argument and nullable response fields from a contract', () => {
    const dir = mkdtempSync(join(process.env.TMPDIR, 'medicore-client-test-'));
    tempDirs.push(dir);
    const document = {
      info: { title: 'MediCore Hospital API', version: 'v1' },
      components: { schemas: {
        CreateTransferRequest: { type: 'object', properties: { patientId: { type: 'string' } }, required: ['patientId'] },
        TransferView: { type: 'object', properties: {
          id: { type: 'string' }, destinationBedId: { type: 'string', nullable: true },
          acceptedAt: { type: ['string', 'null'], format: 'date-time' },
        }, required: ['id', 'destinationBedId', 'acceptedAt'] },
      } },
      paths: { '/api/transfers': { post: {
        operationId: 'requestTransfer', parameters: [{ name: 'Idempotency-Key', in: 'header', required: true, schema: { type: 'string' } }],
        requestBody: { required: true, content: { 'application/json': { schema: { $ref: '#/components/schemas/CreateTransferRequest' } } } },
        responses: { 201: { content: { 'application/json': { schema: { $ref: '#/components/schemas/TransferView' } } } } },
      } } },
    };
    const input = join(dir, 'openapi.json');
    writeFileSync(input, JSON.stringify(document));
    execFileSync(process.execPath, [script, input, join(dir, 'client')]);
    const operations = readFileSync(join(dir, 'client', 'operations.ts'), 'utf8');
    const schemas = readFileSync(join(dir, 'client', 'schemas.ts'), 'utf8');
    expect(operations).toContain('requestTransfer(request: CreateTransferRequest, idempotencyKey: string): HttpRequest');
    expect(operations).toContain('headers: { "Idempotency-Key": idempotencyKey }');
    expect(schemas).toContain('"destinationBedId": string | null;');
    expect(schemas).toContain('"acceptedAt": string | null;');
  });
});
