import { ApiError, apiFetch } from '../../api.js';
import {
  getActingBranchCapacity,
  getActingHospitalCapacity,
  getAuthorizedNetworkCapacity,
} from '../../generated/api/index';

// Every route comes from the checked-in generated client; there is no
// fallback path that could silently bypass a missing or drifting operation.
const routes = {
  branch: getActingBranchCapacity,
  hospital: getActingHospitalCapacity,
  network: getAuthorizedNetworkCapacity,
};
const countKeys = ['bedsAvailable', 'bedsReservedForTransfer', 'bedsOccupied', 'appointmentsScheduled'];
const malformed = () => new ApiError(500, 'The server returned an unexpected capacity response.');
const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const isId = (value) => typeof value === 'string' && value.trim().length > 0;
const isCount = (value) => Number.isSafeInteger(value) && value >= 0;

function parseMetrics(payload, identityKey) {
  if (!isObject(payload) || !isId(payload[identityKey]) || !countKeys.every((key) => isCount(payload[key]))) {
    throw malformed();
  }
  return {
    [identityKey]: payload[identityKey],
    ...Object.fromEntries(countKeys.map((key) => [key, payload[key]])),
  };
}

export function parseBranchCapacity(payload) {
  return parseMetrics(payload, 'branchId');
}

export function parseHospitalCapacity(payload) {
  const summary = parseMetrics(payload, 'hospitalId');
  if (!Array.isArray(payload.branches)) throw malformed();
  const branches = payload.branches.map(parseBranchCapacity);
  if (new Set(branches.map((branch) => branch.branchId)).size !== branches.length) throw malformed();
  return { ...summary, branches };
}

export function parseNetworkCapacity(payload) {
  if (!Array.isArray(payload)) throw malformed();
  return payload.map(parseHospitalCapacity);
}

function fetchCapacity(route, parse, { token, onUnauthorized } = {}) {
  const request = routes[route]();
  return apiFetch(request.path, { method: request.method, token, onUnauthorized }).then(parse);
}

export function fetchBranchCapacity(options) {
  return fetchCapacity('branch', parseBranchCapacity, options);
}

export function fetchHospitalCapacity(options) {
  return fetchCapacity('hospital', parseHospitalCapacity, options);
}

export function fetchNetworkCapacity(options) {
  return fetchCapacity('network', parseNetworkCapacity, options);
}
