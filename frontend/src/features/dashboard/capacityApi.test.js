import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchBranchCapacity, fetchHospitalCapacity, fetchNetworkCapacity, parseBranchCapacity, parseHospitalCapacity, parseNetworkCapacity } from './capacityApi.js';

const branch = { branchId: 'br-1', bedsAvailable: 3, bedsReservedForTransfer: 1, bedsOccupied: 2, appointmentsScheduled: 4 };
const hospital = { hospitalId: 'h-1', bedsAvailable: 3, bedsReservedForTransfer: 1, bedsOccupied: 2, appointmentsScheduled: 4, branches: [branch] };
const response = (data, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } });

afterEach(() => vi.unstubAllGlobals());

describe('capacity transport (T113)', () => {
  it('reads all three server-scoped routes through the shared bearer transport', async () => {
    const fetcher = vi.fn((path) => Promise.resolve(response(path.endsWith('/branch') ? branch : path.endsWith('/hospital') ? hospital : [hospital])));
    vi.stubGlobal('fetch', fetcher);
    expect(await fetchBranchCapacity({ token: 'synthetic-token' })).toEqual(branch);
    expect(await fetchHospitalCapacity({ token: 'synthetic-token' })).toEqual(hospital);
    expect(await fetchNetworkCapacity({ token: 'synthetic-token' })).toEqual([hospital]);
    expect(fetcher.mock.calls.map(([path, init]) => [path, init.method, init.headers.Authorization, init.body])).toEqual([
      ['/api/capacity/branch', 'GET', 'Bearer synthetic-token', undefined],
      ['/api/capacity/hospital', 'GET', 'Bearer synthetic-token', undefined],
      ['/api/capacity/network', 'GET', 'Bearer synthetic-token', undefined],
    ]);
  });

  it('rejects duplicate branch identities rather than rendering ambiguous breakdowns', () => {
    expect(() => parseHospitalCapacity({ ...hospital, branches: [branch, branch] }))
      .toThrowError(/unexpected capacity response/i);
  });

  it('keeps server totals and ordering unchanged, including empty arrays and zero counts', () => {
    const second = { ...hospital, hospitalId: 'h-2', bedsAvailable: 0, branches: [] };
    expect(parseNetworkCapacity([{ ...second, internal: 'never render' }, hospital])).toEqual([second, hospital]);
    expect(parseNetworkCapacity([])).toEqual([]);
    expect(parseHospitalCapacity({ ...hospital, bedsAvailable: 99 }).bedsAvailable).toBe(99);
    expect(parseBranchCapacity({ ...branch, secret: 'never render' })).toEqual(branch);
  });

  it.each([
    ['missing hospital total', () => parseHospitalCapacity({ ...hospital, bedsAvailable: undefined })],
    ['negative branch count', () => parseHospitalCapacity({ ...hospital, branches: [{ ...branch, bedsOccupied: -1 }] })],
    ['fractional hospital count', () => parseHospitalCapacity({ ...hospital, appointmentsScheduled: 0.5 })],
    ['unsafe count', () => parseBranchCapacity({ ...branch, bedsAvailable: Number.MAX_SAFE_INTEGER + 1 })],
    ['missing branch id', () => parseBranchCapacity({ ...branch, branchId: null })],
    ['missing branches', () => parseHospitalCapacity({ ...hospital, branches: undefined })],
    ['object instead of network list', () => parseNetworkCapacity(hospital)],
    ['malformed entry in network list', () => parseNetworkCapacity([hospital, {}])],
  ])('rejects %s as a malformed capacity response', (_name, parse) => {
    expect(parse).toThrowError(/unexpected capacity response/i);
  });

  it('preserves the shared 401 notification and 403 denial', async () => {
    const onUnauthorized = vi.fn();
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(response({}, 401))));
    await expect(fetchHospitalCapacity({ token: 'synthetic', onUnauthorized })).rejects.toMatchObject({ status: 401 });
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(response({}, 403))));
    await expect(fetchNetworkCapacity({ token: 'synthetic' })).rejects.toMatchObject({ status: 403 });
  });
});
