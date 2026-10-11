import { describe, expect, it } from 'vitest';
import { parseHospitalSummary, parseNetworkSummary } from './dashboardApi.js';

const metrics = {
  patients: 2, appointments: 0, admissions: 0, emergencyVisits: 0, invoices: 0,
  openAdmissions: 0, activeEmergencyVisits: 0, bedsAvailable: 1, bedsOccupied: 0,
  bedsMaintenance: 0, bedsOutOfService: 0, todayAppointments: 0,
  invoicesDraft: 0, invoicesIssued: 0, invoicesPaid: 0, invoicesVoid: 0,
  bedsReservedForTransfer: 1, appointmentsScheduled: 3, activeTransfers: 2,
};
const branch = { branchId: 'branch-1', branchCode: 'BR', branchName: 'Branch', ...metrics };
const hospital = { hospitalId: 'hospital-1', hospitalCode: 'H', hospitalName: 'Hospital', ...metrics, branches: [branch] };
const network = { organizationId: 'org-1', organizationName: 'Network', ...metrics, branches: [branch], hospitals: [hospital] };

describe('command-center response parsing', () => {
  it('retains server totals and bounded operational metrics, without client recomputation', () => {
    expect(parseHospitalSummary(hospital)).toMatchObject({ hospitalId: 'hospital-1', patients: 2, appointmentsScheduled: 3, activeTransfers: 2 });
    expect(parseNetworkSummary(network)).toMatchObject({ hospitals: [{ hospitalId: 'hospital-1', bedsReservedForTransfer: 1 }], appointmentsScheduled: 3, patients: 2 });
  });

  it('refuses malformed or duplicate hospital identity and negative counts', () => {
    expect(() => parseHospitalSummary({ ...hospital, activeTransfers: -1 })).toThrow(/unexpected hospital response/i);
    expect(() => parseNetworkSummary({ ...network, hospitals: [hospital, hospital] })).toThrow(/unexpected network response/i);
  });

  it('keeps the prior branch-only network shape compatible', () => {
    const legacy = { ...network };
    delete legacy.hospitals;
    delete legacy.activeTransfers;
    delete legacy.bedsReservedForTransfer;
    expect(parseNetworkSummary(legacy).branches).toHaveLength(1);
  });
});
