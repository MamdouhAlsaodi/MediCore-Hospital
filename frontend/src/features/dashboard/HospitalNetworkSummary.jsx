import React, { useState } from 'react';

// Present only summaries the server returned. Expanding a hospital never
// chooses an API scope or issues a client-supplied hospital identifier.
function HospitalRow({ hospital }) {
  const [expanded, setExpanded] = useState(false);
  return (
    <li>
      <h3>{hospital.hospitalCode} — {hospital.hospitalName}</h3>
      <p>
        {`Patients ${hospital.patients} · Appointments ${hospital.appointments} · `
          + `Admissions ${hospital.admissions} · Emergency visits ${hospital.emergencyVisits} · `
          + `Invoices ${hospital.invoices} · Beds available ${hospital.bedsAvailable} · `
          + `Beds reserved for transfer ${hospital.bedsReservedForTransfer} · `
          + `Appointments scheduled ${hospital.appointmentsScheduled} · `
          + `Active transfers ${hospital.activeTransfers}`}
      </p>
      <p>{`Open admissions ${hospital.openAdmissions} · Active emergency visits ${hospital.activeEmergencyVisits} · `
        + `Today's appointments ${hospital.todayAppointments} · Beds occupied ${hospital.bedsOccupied} · `
        + `Beds maintenance ${hospital.bedsMaintenance} · Beds out of service ${hospital.bedsOutOfService}`}</p>
      <p>{`Invoices draft ${hospital.invoicesDraft} · Issued ${hospital.invoicesIssued} · `
        + `Paid ${hospital.invoicesPaid} · Void ${hospital.invoicesVoid}`}</p>
      <button type="button" aria-expanded={expanded}
        aria-label={`${expanded ? 'Hide' : 'Show'} branches for ${hospital.hospitalName}`}
        onClick={() => setExpanded((value) => !value)}>
        {expanded ? 'Hide branches' : 'Show branches'}
      </button>
      {expanded && (
        <ul aria-label={`Branches of ${hospital.hospitalName}`}>
          {hospital.branches.map((branch) => (
            <li key={branch.branchId}>
              {`${branch.branchCode} — ${branch.branchName} · Patients ${branch.patients} · `
                + `Beds available ${branch.bedsAvailable} · `
                + `Beds reserved for transfer ${branch.bedsReservedForTransfer ?? '—'} · `
                + `Active transfers ${branch.activeTransfers ?? '—'}`}
            </li>
          ))}
        </ul>
      )}
    </li>
  );
}

export default function HospitalNetworkSummary({ hospital, network }) {
  if (hospital) {
    return (
      <section className="dashboard-group network-group" aria-label="Hospital command center">
        <h2>Hospital command center</h2>
        <ul className="network-branches"><HospitalRow hospital={hospital} /></ul>
      </section>
    );
  }
  if (!network?.hospitals) return null;
  return (
    <section className="dashboard-group network-group" aria-label="Network hospitals">
      <h2>Network hospitals</h2>
      <p>{network.organizationName}</p>
      <p>{`Beds reserved for transfer ${network.bedsReservedForTransfer} · Appointments scheduled ${network.appointmentsScheduled} · Active transfers ${network.activeTransfers}`}</p>
      <ul className="network-branches">
        {network.hospitals.map((entry) => <HospitalRow key={entry.hospitalId} hospital={entry} />)}
      </ul>
    </section>
  );
}
