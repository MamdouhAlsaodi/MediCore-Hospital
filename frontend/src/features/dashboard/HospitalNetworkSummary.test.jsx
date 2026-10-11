import { describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import HospitalNetworkSummary from './HospitalNetworkSummary.jsx';

const hospital = {
  hospitalId: 'hospital-1', hospitalCode: 'H1', hospitalName: 'Synthetic Hospital',
  patients: 4, appointments: 5, admissions: 2, emergencyVisits: 1, invoices: 3,
  openAdmissions: 2, activeEmergencyVisits: 1, todayAppointments: 1,
  bedsAvailable: 3, bedsOccupied: 2, bedsMaintenance: 1, bedsOutOfService: 0,
  bedsReservedForTransfer: 1, appointmentsScheduled: 2, activeTransfers: 1,
  invoicesDraft: 1, invoicesIssued: 1, invoicesPaid: 1, invoicesVoid: 0,
  branches: [],
};

describe('HospitalNetworkSummary', () => {
  it('shows server-issued operational and capacity metrics without a clinical interpretation', () => {
    render(<HospitalNetworkSummary hospital={hospital} />);
    const view = screen.getByRole('region', { name: 'Hospital command center' });
    expect(within(view).getByText(/Open admissions 2/)).toBeInTheDocument();
    expect(within(view).getByText(/Beds occupied 2/)).toBeInTheDocument();
    expect(within(view).getByText(/Emergency visits 1/)).toBeInTheDocument();
    expect(within(view).getByText(/Invoices 3/)).toBeInTheDocument();
    expect(within(view).queryByText(/risk|triage|diagnosis/i)).not.toBeInTheDocument();
  });
});
