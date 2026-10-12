import { ApiError, apiFetch } from '../../api.js';
// Phase 5 US8 (T142): the request descriptors come from the generated typed
// contract (scripts/phase4/generate-openapi-client.mjs); this module owns
// the strict TransferView parse and the mandatory idempotency-key envelope.
import {
  acceptTransfer as acceptTransferHttpRequest,
  cancelTransfer as cancelTransferHttpRequest,
  completeTransfer as completeTransferHttpRequest,
  getAuthorizedTransfer as getAuthorizedTransferHttpRequest,
  listAuthorizedTransfers as listAuthorizedTransfersHttpRequest,
  rejectTransfer as rejectTransferHttpRequest,
  requestTransfer as requestTransferHttpRequest,
  startTransferTransit as startTransferTransitHttpRequest,
} from '../../generated/api/index';

// The only transfer transport adapter (Phase 5 US8, T142; FR-014–FR-020).
// Strictly what the generated contract demonstrates:
//
//   list:      GET  /api/transfers[?status]     -> TransferView[] (the
//               server-derived source-or-destination slice of the acting
//               hospital; the client never sends a hospital parameter)
//   read:      GET  /api/transfers/{id}         -> TransferView (a foreign
//               hospital receives the same generic 404 as an unknown id)
//   request:   POST /api/transfers            { patientId, sourceAdmissionId,
//               destinationHospitalId, reasonCode } + Idempotency-Key (201)
//   accept:    POST /api/transfers/{id}/accept          { destinationBranchId,
//               destinationBedId, expectedVersion }      + Idempotency-Key
//   reject:    POST /api/transfers/{id}/reject          { reasonCode,
//               expectedVersion }                        + Idempotency-Key
//   cancel:    POST /api/transfers/{id}/cancel          { reasonCode,
//               expectedVersion }                        + Idempotency-Key
//   transit:   POST /api/transfers/{id}/start-transit   + Idempotency-Key
//   complete:  POST /api/transfers/{id}/complete        + Idempotency-Key
//
// The source hospital/branch of a request is NEVER client input: the server
// derives it from the acting context. Idempotency keys are bounded,
// client-chosen command identities — reuse with a different payload is the
// server's 409. The adapter sends exactly the shared apiFetch envelope
// (Bearer token, JSON, ApiError, onUnauthorized) plus the generated
// Idempotency-Key header; components never call fetch directly. Responses
// parse through the strict seventeen-key TransferView allowlist — a
// malformed payload refuses with ApiError 500 instead of improvising.

const malformed = () => new ApiError(500, 'The server returned an unexpected transfer.');

const STATUSES = new Set(['REQUESTED', 'ACCEPTED', 'IN_TRANSIT', 'COMPLETED', 'REJECTED', 'CANCELLED']);
const REASON_CODES = new Set(['BED_SHORTAGE', 'SPECIALIST_CARE', 'EQUIPMENT_LIMITATION',
  'ISOLATION_CAPACITY', 'PATIENT_PREFERENCE']);

function isNonEmptyString(value) {
  return typeof value === 'string' && value.length > 0;
}

function isOptionalIsoInstant(value) {
  return value === null || value === undefined || (isNonEmptyString(value) && !Number.isNaN(Date.parse(value)));
}

/** Strict seventeen-key TransferView allowlist; unknown keys are dropped, missing keys refuse. */
function parseTransferView(payload) {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) throw malformed();
  if (!isNonEmptyString(payload.id) || !isNonEmptyString(payload.transferNumber)
    || !isNonEmptyString(payload.patientId)
    || !isNonEmptyString(payload.sourceHospitalId) || !isNonEmptyString(payload.sourceBranchId)
    || !isNonEmptyString(payload.destinationHospitalId)
    || !STATUSES.has(payload.status)
    || !REASON_CODES.has(payload.reasonCode)
    || !isNonEmptyString(payload.requestedAt)
    || typeof payload.version !== 'number' || !Number.isFinite(payload.version)) {
    throw malformed();
  }
  for (const instant of [payload.acceptedAt, payload.transitStartedAt, payload.completedAt,
    payload.cancelledAt, payload.rejectedAt]) {
    if (!isOptionalIsoInstant(instant)) throw malformed();
  }
  for (const reference of [payload.destinationBranchId, payload.destinationBedId]) {
    if (reference !== null && reference !== undefined && !isNonEmptyString(reference)) throw malformed();
  }
  return {
    id: payload.id,
    transferNumber: payload.transferNumber,
    patientId: payload.patientId,
    sourceHospitalId: payload.sourceHospitalId,
    sourceBranchId: payload.sourceBranchId,
    destinationHospitalId: payload.destinationHospitalId,
    destinationBranchId: payload.destinationBranchId ?? null,
    destinationBedId: payload.destinationBedId ?? null,
    status: payload.status,
    reasonCode: payload.reasonCode,
    requestedAt: payload.requestedAt,
    acceptedAt: payload.acceptedAt ?? null,
    transitStartedAt: payload.transitStartedAt ?? null,
    completedAt: payload.completedAt ?? null,
    cancelledAt: payload.cancelledAt ?? null,
    rejectedAt: payload.rejectedAt ?? null,
    version: payload.version,
  };
}

/** One strict TransferView row or a malformed-payload refusal; arrays must be arrays. */
function parseTransferList(payload) {
  if (!Array.isArray(payload)) throw malformed();
  return payload.map(parseTransferView);
}

/**
 * A bounded idempotency key is mandatory on every state change: blank,
 * whitespace-only, over-long, or absent keys refuse locally (ApiError 400)
 * before any request is issued, mirroring the server's 400 for a missing
 * or blank header. The trimmed key is what travels on the wire.
 */
function requireIdempotencyKey(idempotencyKey) {
  if (typeof idempotencyKey !== 'string' || idempotencyKey.trim() === '' || idempotencyKey.length > 128) {
    throw new ApiError(400, 'A bounded idempotency key is required for this command.');
  }
  return idempotencyKey.trim();
}

async function send(request, { token, onUnauthorized }) {
  return apiFetch(request.path, {
    method: request.method,
    token,
    body: request.body,
    headers: request.headers,
    onUnauthorized,
  }).then((payload) => parseTransferView(payload));
}

export function listTransfers({ token, status, onUnauthorized } = {}) {
  const request = listAuthorizedTransfersHttpRequest(
    STATUSES.has(status) ? { status } : undefined
  );
  return apiFetch(request.path, { method: request.method, token, onUnauthorized })
    .then(parseTransferList);
}

export function fetchTransfer({ token, id, onUnauthorized } = {}) {
  if (!isNonEmptyString(id)) throw malformed();
  const request = getAuthorizedTransferHttpRequest(id);
  return apiFetch(request.path, { method: request.method, token, onUnauthorized })
    .then(parseTransferView);
}

export function requestTransfer({ token, idempotencyKey, transfer, onUnauthorized } = {}) {
  requireIdempotencyKey(idempotencyKey);
  if (!transfer || !isNonEmptyString(transfer.patientId) || !isNonEmptyString(transfer.sourceAdmissionId)
    || !isNonEmptyString(transfer.destinationHospitalId) || !REASON_CODES.has(transfer.reasonCode)) {
    throw new ApiError(400, 'The transfer request is incomplete.');
  }
  const request = requestTransferHttpRequest({
    patientId: transfer.patientId,
    sourceAdmissionId: transfer.sourceAdmissionId,
    destinationHospitalId: transfer.destinationHospitalId,
    reasonCode: transfer.reasonCode,
  }, idempotencyKey);
  return send(request, { token, onUnauthorized });
}

export function acceptTransfer({ token, id, idempotencyKey, decision, onUnauthorized } = {}) {
  requireIdempotencyKey(idempotencyKey);
  if (!isNonEmptyString(id) || !decision
    || !isNonEmptyString(decision.destinationBranchId) || !isNonEmptyString(decision.destinationBedId)
    || typeof decision.expectedVersion !== 'number') {
    throw new ApiError(400, 'The accept decision is incomplete.');
  }
  return send(acceptTransferHttpRequest(id, {
    destinationBranchId: decision.destinationBranchId,
    destinationBedId: decision.destinationBedId,
    expectedVersion: decision.expectedVersion,
  }, idempotencyKey), { token, onUnauthorized });
}

export function rejectTransfer({ token, id, idempotencyKey, decision, onUnauthorized } = {}) {
  requireIdempotencyKey(idempotencyKey);
  if (!isNonEmptyString(id) || !decision
    || !REASON_CODES.has(decision.reasonCode) || typeof decision.expectedVersion !== 'number') {
    throw new ApiError(400, 'The reject decision is incomplete.');
  }
  return send(rejectTransferHttpRequest(id, {
    reasonCode: decision.reasonCode,
    expectedVersion: decision.expectedVersion,
  }, idempotencyKey), { token, onUnauthorized });
}

export function cancelTransfer({ token, id, idempotencyKey, decision, onUnauthorized } = {}) {
  requireIdempotencyKey(idempotencyKey);
  if (!isNonEmptyString(id) || !decision
    || !REASON_CODES.has(decision.reasonCode) || typeof decision.expectedVersion !== 'number') {
    throw new ApiError(400, 'The cancel decision is incomplete.');
  }
  return send(cancelTransferHttpRequest(id, {
    reasonCode: decision.reasonCode,
    expectedVersion: decision.expectedVersion,
  }, idempotencyKey), { token, onUnauthorized });
}

export function startTransit({ token, id, idempotencyKey, onUnauthorized } = {}) {
  requireIdempotencyKey(idempotencyKey);
  if (!isNonEmptyString(id)) throw malformed();
  return send(startTransferTransitHttpRequest(id, idempotencyKey), { token, onUnauthorized });
}

export function completeTransfer({ token, id, idempotencyKey, onUnauthorized } = {}) {
  requireIdempotencyKey(idempotencyKey);
  if (!isNonEmptyString(id)) throw malformed();
  return send(completeTransferHttpRequest(id, idempotencyKey), { token, onUnauthorized });
}
