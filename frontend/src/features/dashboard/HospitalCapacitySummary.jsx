import React, { useEffect, useState } from 'react';
import { actingContextIdentity, actingContextKey } from '../../auth.js';
import { fetchHospitalCapacity } from './capacityApi.js';

const metrics = [
  ['bedsAvailable', 'Beds available'],
  ['bedsReservedForTransfer', 'Beds reserved for transfer'],
  ['bedsOccupied', 'Beds occupied'],
  ['appointmentsScheduled', 'Appointments scheduled'],
];

function MetricList({ summary }) {
  return (
    <dl>
      {metrics.map(([key, label]) => (
        <div key={key}><dt>{label}</dt><dd>{summary[key]}</dd></div>
      ))}
    </dl>
  );
}

// This view deliberately reads only the acting hospital. Never choose a
// hospital from a selector or aggregate branch totals in the browser.
export default function HospitalCapacitySummary({ session, onSessionExpired }) {
  const identity = actingContextIdentity(session);
  const hospitalId = identity?.resolvedHospitalId;
  const token = session?.token;
  const requestKey = token && hospitalId ? JSON.stringify([token, actingContextKey(session)]) : null;
  const [result, setResult] = useState(null);

  useEffect(() => {
    if (!requestKey) return undefined;
    let current = true;
    fetchHospitalCapacity({ token, onUnauthorized: onSessionExpired }).then(
      (summary) => {
        if (!current) return;
        if (summary.hospitalId !== hospitalId) {
          setResult({ key: requestKey, error: 'Hospital capacity unavailable: the server returned another hospital.' });
        } else {
          setResult({ key: requestKey, summary });
        }
      },
      (error) => {
        if (current) setResult({ key: requestKey, error: error?.message || 'Hospital capacity unavailable.' });
      }
    );
    return () => { current = false; };
  }, [requestKey, token, hospitalId, onSessionExpired]);

  if (!requestKey) return <p role="status">Hospital capacity unavailable for this acting context.</p>;
  const current = result?.key === requestKey ? result : null;
  if (!current) return <p role="status">Loading hospital capacity…</p>;
  if (current.error) return <p role="alert">{current.error}</p>;

  const { summary } = current;
  return (
    <section aria-label="Hospital capacity">
      <h2>Hospital capacity</h2>
      <p>Hospital: {summary.hospitalId}</p>
      <MetricList summary={summary} />
      <h3>Branches</h3>
      {summary.branches.length === 0 ? (
        <p role="status">No branches in this hospital capacity response.</p>
      ) : (
        <ul>
          {summary.branches.map((branch) => (
            <li key={branch.branchId}>
              <h4>{branch.branchId}</h4>
              <MetricList summary={branch} />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
