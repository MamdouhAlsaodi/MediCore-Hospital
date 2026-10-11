import { apiFetch, ApiError } from '../../api.js';
// Phase 4 (plan Task 10, T073; FR-017): the two read shapes come from the
// generated typed contract. The screen keeps consuming the retained
// '/api/dashboard' compatibility alias (its body is server-pinned to be
// exactly the typed branch summary); the generated client marks that
// operation deprecated so new callers can see the explicit branch path.
import {
  getActingHospitalDashboard as getActingHospitalDashboardHttpRequest,
  getBranchSummaryAlias as getBranchSummaryAliasHttpRequest,
  getNetworkSummary as getNetworkSummaryHttpRequest,
} from '../../generated/api/index';

// Command-center transport (docs/plan3.md Task 10, §4.7). Two server-owned
// reads, both through the shared apiFetch adapter — components never call
// fetch directly:
//
//   fetchBranchSummary  GET /api/dashboard — the retained Phase 3
//       compatibility alias, which this task pinned server-side to be
//       exactly the typed branch summary of the acting context (never
//       whole-table counts). The shell consumes this alias until a later
//       packet migrates the screen to /api/dashboard/branch.
//   fetchNetworkSummary GET /api/dashboard/network — the organization
//       comparison the server issues only to enabled ADMIN contexts with
//       ORGANIZATION scope; every other context receives the ordinary 403.
//
// The branch parse keeps the accepted Task 5 display contract of this
// screen: every known contract key the server sent is kept, absent keys
// render the honest '—' marker later, and unknown keys stay visible in the
// generic section — the screen never fabricates a zero. The network parse
// is a strict allowlist because it is a brand-new Task 10 contract with no
// legacy shape: a malformed response refuses to render rather than
// improvising. Neither parser ever derives authority — the server stays
// the sole owner of scope and values.

/** The numeric contract keys of the typed branch summary (docs/plan3.md §4.7). */
const BRANCH_METRIC_KEYS = [
  'patients', 'appointments', 'admissions', 'emergencyVisits', 'invoices',
  'openAdmissions', 'activeEmergencyVisits',
  'bedsAvailable', 'bedsOccupied', 'bedsMaintenance', 'bedsOutOfService',
  'todayAppointments',
  'invoicesDraft', 'invoicesIssued', 'invoicesPaid', 'invoicesVoid',
];
const HIERARCHY_METRIC_KEYS = [...BRANCH_METRIC_KEYS, 'bedsReservedForTransfer', 'appointmentsScheduled', 'activeTransfers'];

function isNonEmptyString(value) {
  return typeof value === 'string' && value.length > 0;
}

function isNonNegativeCount(value) {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

// Lenient display allowlist: payload must be an object and every PRESENT
// metric key must be a non-negative integer count, but absent keys are kept
// as undefined so the screen can render the honest missing marker, and
// unknown keys are preserved for the generic fallback section.
export function parseBranchSummary(payload) {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
    throw new ApiError(500, 'The server returned an unexpected dashboard response.');
  }
  const summary = { ...payload };
  for (const key of BRANCH_METRIC_KEYS) {
    if (summary[key] !== undefined && !isNonNegativeCount(summary[key])) {
      throw new ApiError(500, 'The server returned an unexpected dashboard response.');
    }
  }
  return summary;
}

export async function fetchBranchSummary({ token, onUnauthorized } = {}) {
  const request = getBranchSummaryAliasHttpRequest();
  return apiFetch(request.path, { method: request.method, token, onUnauthorized })
    .then((payload) => parseBranchSummary(payload));
}

function parseBranchSummaryStrict(payload, malformed = () => new ApiError(500, 'The server returned an unexpected network response.')) {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) throw malformed();
  if (!isNonEmptyString(payload.branchId) || !isNonEmptyString(payload.branchCode)
      || !isNonEmptyString(payload.branchName)) throw malformed();
  const summary = { branchId: payload.branchId, branchCode: payload.branchCode, branchName: payload.branchName };
  for (const key of BRANCH_METRIC_KEYS) {
    if (!isNonNegativeCount(payload[key])) throw malformed();
    summary[key] = payload[key];
  }
  for (const key of ['bedsReservedForTransfer', 'appointmentsScheduled', 'activeTransfers']) {
    if (payload[key] !== undefined) {
      if (!isNonNegativeCount(payload[key])) throw malformed();
      summary[key] = payload[key];
    }
  }
  return summary;
}

function parseHospital(payload, malformed) {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)
      || !isNonEmptyString(payload.hospitalId) || !isNonEmptyString(payload.hospitalCode)
      || !isNonEmptyString(payload.hospitalName) || !Array.isArray(payload.branches)) throw malformed();
  const branches = payload.branches.map((branch) => parseBranchSummaryStrict(branch, malformed));
  if (new Set(branches.map((branch) => branch.branchId)).size !== branches.length) throw malformed();
  const hospital = {
    hospitalId: payload.hospitalId, hospitalCode: payload.hospitalCode,
    hospitalName: payload.hospitalName, branches,
  };
  for (const key of HIERARCHY_METRIC_KEYS) {
    if (!isNonNegativeCount(payload[key])) throw malformed();
    hospital[key] = payload[key];
  }
  return hospital;
}

export function parseHospitalSummary(payload) {
  return parseHospital(payload, () => new ApiError(500, 'The server returned an unexpected hospital response.'));
}

export async function fetchHospitalSummary({ token, onUnauthorized } = {}) {
  // The route comes from the regenerated OpenAPI client; a missing operation
  // is a build/contract error, never an invented path.
  const request = getActingHospitalDashboardHttpRequest();
  return apiFetch(request.path, { method: request.method, token, onUnauthorized })
    .then(parseHospitalSummary);
}

// Strict allowlist of the network summary: organization identity, every
// metric key, and the per-branch list in the server's deterministic order.
// Nothing here aggregates or sorts — the server owns totals and order.
export function parseNetworkSummary(payload) {
  const malformed = () => new ApiError(500, 'The server returned an unexpected network response.');
  if (!payload || typeof payload !== 'object') throw malformed();
  if (!isNonEmptyString(payload.organizationId) || !isNonEmptyString(payload.organizationName)) {
    throw malformed();
  }
  if (!Array.isArray(payload.branches)) throw malformed();
  const network = {
    organizationId: payload.organizationId,
    organizationName: payload.organizationName,
    branches: payload.branches.map((branch) => parseBranchSummaryStrict(branch)),
  };
  for (const key of BRANCH_METRIC_KEYS) {
    if (!isNonNegativeCount(payload[key])) throw malformed();
    network[key] = payload[key];
  }
  if (payload.hospitals !== undefined) {
    if (!Array.isArray(payload.hospitals)) throw malformed();
    network.hospitals = payload.hospitals.map((hospital) => parseHospital(hospital, malformed));
    if (new Set(network.hospitals.map((hospital) => hospital.hospitalId)).size !== network.hospitals.length) throw malformed();
    for (const key of ['bedsReservedForTransfer', 'appointmentsScheduled', 'activeTransfers']) {
      if (!isNonNegativeCount(payload[key])) throw malformed();
      network[key] = payload[key];
    }
  }
  return network;
}

export async function fetchNetworkSummary({ token, onUnauthorized } = {}) {
  const request = getNetworkSummaryHttpRequest();
  return apiFetch(request.path, { method: request.method, token, onUnauthorized })
    .then((payload) => parseNetworkSummary(payload));
}
