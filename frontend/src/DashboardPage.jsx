import React, { useEffect, useState } from 'react';
import { ApiError } from './api.js';
import { actingContextKey } from './auth.js';
import { permittedDestinations } from './navigation.js';
import { fetchBranchSummary, fetchNetworkSummary } from './features/dashboard/dashboardApi.js';

// Human-readable labels for the keys the Task 10 backend contract guarantees
// (docs/plan3.md §4.7). Response values stay the single source of truth;
// unknown keys keep the generic fallback section and absent keys keep the
// honest '—' marker — the screen never fabricates a zero.
const CURRENT_ACTIVITY = {
  openAdmissions: 'Open admissions',
  activeEmergencyVisits: 'Active emergency visits',
  todayAppointments: "Today's appointments",
};
const TOTALS = {
  patients: 'Patients',
  appointments: 'Appointments',
  admissions: 'Admissions',
  emergencyVisits: 'Emergency visits',
  invoices: 'Invoices',
};
const BED_STATUSES = {
  bedsAvailable: 'Available',
  bedsOccupied: 'Occupied',
  bedsMaintenance: 'Maintenance',
  bedsOutOfService: 'Out of service',
};
const INVOICE_STATUSES = {
  invoicesDraft: 'Draft',
  invoicesIssued: 'Issued',
  invoicesPaid: 'Paid',
  invoicesVoid: 'Void',
};
const KNOWN_KEYS = new Set(
  Object.keys({ ...CURRENT_ACTIVITY, ...TOTALS, ...BED_STATUSES, ...INVOICE_STATUSES })
);

// Pure render-phase gate (the accepted Task 8 render-tag invariant): decides
// what this screen may display for the current acting context. Loaded state
// is tagged with the actingContextKey it resolved under and is exposed only
// while that tag still equals the current contextKey — so a branch or
// network response resolved under a switched context can never paint, and
// the screen shows loading instead: never the previous branch's numbers and
// never a misleading empty state. Exported as the deterministic seam that
// lets tests pin the render contract. This is display isolation only; the
// server's scope for each context-bound token stays the sole authority over
// what data exists.
export function branchDisplayState({ contextKey, loaded }) {
  if (!loaded || loaded.contextKey !== contextKey) {
    return { contextKey, status: 'loading', loadError: '', summary: null };
  }
  return loaded;
}

// The network section has one extra gate: the server-issued acting context
// must be ADMIN with ORGANIZATION scope. This is a display decision only —
// the server independently refuses the endpoint to every other context,
// and its denial renders inline.
export function networkDisplayState({ contextKey, enabled, loaded }) {
  if (!enabled) {
    return { contextKey, status: 'hidden', loadError: '', summary: null };
  }
  if (!loaded || loaded.contextKey !== contextKey) {
    return { contextKey, status: 'loading', loadError: '', summary: null };
  }
  return loaded;
}

function StatCard({ label, value }) {
  // '—' marks a contract key this response did not carry; a zero is only
  // ever rendered when the server actually reported one.
  return (
    <article>
      <small>{label}</small>
      <strong>{value === undefined ? '—' : value}</strong>
    </article>
  );
}

// A totals card with an already-implemented drill-down target renders its
// action as a real, keyboard-operable button whose accessible name names
// the destination — never a fabricated filter promise.
function DrillDownCard({ label, value, action, destination, onNavigate }) {
  return (
    <article className="stat-card">
      <small>{label}</small>
      <strong>{value === undefined ? '—' : value}</strong>
      <button
        type="button"
        className="stat-card-link"
        aria-label={action}
        onClick={() => onNavigate(destination)}
      >
        {action}
      </button>
    </article>
  );
}

function StatGroup({ name, entries, footer }) {
  return (
    <section className="dashboard-group" aria-label={name}>
      <h2>{name}</h2>
      <div className="cards">
        {entries.map((entry) => <StatCard key={entry.label} label={entry.label} value={entry.value} />)}
      </div>
      {footer}
    </section>
  );
}

// Drill-downs exist only for target screens that already expose their own
// explicit UI filters over branch-scoped data (the patients search and the
// beds filter). Every other card stays non-actionable rather than
// fabricating a client-side data filter, and a destination the acting roles
// cannot reach stays non-actionable too. The backend stays branch-scoped
// either way: navigation carries no filter or scope authority.
function drillDowns(reachable) {
  const actions = new Map();
  if (reachable.has('patients')) {
    actions.set('patients', { action: 'View patients', destination: 'patients' });
  }
  if (reachable.has('beds')) {
    actions.set('beds', { action: 'View beds', destination: 'beds' });
  }
  return actions;
}

function BranchSummary({ summary, drillDowns, onNavigate }) {
  const entries = (labels) =>
    Object.entries(labels).map(([key, label]) => ({ label, value: summary[key] }));
  const unknownEntries = Object.entries(summary)
    .filter(([key]) => !KNOWN_KEYS.has(key)
      && key !== 'branchId' && key !== 'branchCode' && key !== 'branchName')
    .map(([key, value]) => ({ label: key, value }));

  const patientsDrillDown = drillDowns.get('patients');
  return (
    <>
      {typeof summary.branchCode === 'string' && summary.branchCode.length > 0 && (
        <p className="dashboard-branch-line">
          {summary.branchCode} — {summary.branchName}
        </p>
      )}

      <StatGroup name="Current activity" entries={entries(CURRENT_ACTIVITY)} />

      <section className="dashboard-group" aria-label="Totals">
        <h2>Totals</h2>
        <div className="cards">
          {Object.entries(TOTALS).map(([key, label]) => {
            const drillDown = key === 'patients' ? patientsDrillDown : undefined;
            return drillDown ? (
              <DrillDownCard
                key={label}
                label={label}
                value={summary[key]}
                action={drillDown.action}
                destination={drillDown.destination}
                onNavigate={onNavigate}
              />
            ) : (
              <StatCard key={label} label={label} value={summary[key]} />
            );
          })}
        </div>
      </section>

      <StatGroup
        name="Beds by status"
        entries={entries(BED_STATUSES)}
        footer={drillDowns.get('beds') ? (
          <button
            type="button"
            className="stat-card-link"
            aria-label="View beds"
            onClick={() => onNavigate('beds')}
          >
            View beds
          </button>
        ) : undefined}
      />

      <StatGroup name="Invoices by status" entries={entries(INVOICE_STATUSES)} />

      {unknownEntries.length > 0 && (
        <StatGroup
          name="Other reported keys"
          entries={unknownEntries}
        />
      )}
    </>
  );
}

// Network comparison: rendered only while the server-issued acting context
// is ADMIN with ORGANIZATION scope (a display gate — the server re-checks
// every request), showing the organization totals and the per-branch
// summaries in the server's deterministic order. The screen never sorts,
// filters, or aggregates network data client-side.
function NetworkSummary({ summary }) {
  return (
    <section className="dashboard-group network-group" aria-label="Network comparison">
      <h2>Network comparison</h2>
      <p className="dashboard-branch-line">{summary.organizationName}</p>
      <div className="cards">
        {Object.entries(TOTALS).map(([key, label]) => (
          <StatCard key={label} label={label} value={summary[key]} />
        ))}
      </div>
      <ul className="network-branches" aria-label="Branches in the network">
        {summary.branches.map((branch) => (
          <li key={branch.branchId}>
            <h3>{branch.branchCode} — {branch.branchName}</h3>
            <small>
              {`Patients ${branch.patients ?? '—'} · Appointments ${branch.appointments ?? '—'} · `
                + `Admissions ${branch.admissions ?? '—'} · Emergency visits ${branch.emergencyVisits ?? '—'} · `
                + `Invoices ${branch.invoices ?? '—'}`}
            </small>
            <small>
              {`Open admissions ${branch.openAdmissions ?? '—'} · `
                + `Active emergency visits ${branch.activeEmergencyVisits ?? '—'} · `
                + `Beds available ${branch.bedsAvailable ?? '—'} · `
                + `Today's appointments ${branch.todayAppointments ?? '—'}`}
            </small>
          </li>
        ))}
      </ul>
    </section>
  );
}

// Command-center screen (docs/plan3.md Task 10): the branch summary loads
// for every permitted role through the shared transport adapter, and the
// network comparison loads only for a server-issued ADMIN + ORGANIZATION
// acting context. 401 stays shell-owned (the expiry callback returns the
// app to Login); every other failure renders inline and preserves the
// current session, context, and any already-loaded summary. Layout chrome
// and navigation live in AppShell.
export default function DashboardPage({ session, onSessionExpired, onNavigate }) {
  const contextKey = actingContextKey(session);
  const [branch, setBranch] = useState({ contextKey, status: 'loading', loadError: '', summary: null });
  // The network section is only ever fetched for the server-issued ADMIN +
  // ORGANIZATION context; UI role data is a display gate, never authority.
  const canSeeNetwork = session.actingContext?.role === 'ADMIN'
    && session.actingContext?.scope === 'ORGANIZATION';
  const [network, setNetwork] = useState({ contextKey, status: 'loading', loadError: '', summary: null });

  useEffect(() => {
    let active = true;
    setBranch({ contextKey, status: 'loading', loadError: '', summary: null });
    fetchBranchSummary({ token: session.token, onUnauthorized: onSessionExpired })
      .then((summary) => {
        if (!active) return;
        setBranch({ contextKey, status: 'ready', loadError: '', summary });
      })
      .catch((error) => {
        if (!active) return;
        // apiFetch already invoked onSessionExpired for a 401; showing a
        // local error on top would be misleading, so only non-401 failures
        // render here (same convention as the other screens).
        if (error instanceof ApiError && error.status === 401) return;
        setBranch({
          contextKey,
          status: 'error',
          loadError: error instanceof ApiError ? error.message : 'The dashboard could not be loaded.',
          summary: null,
        });
      });
    return () => { active = false; };
  }, [contextKey, session.token, onSessionExpired]);

  useEffect(() => {
    if (!canSeeNetwork) return;
    let active = true;
    setNetwork({ contextKey, status: 'loading', loadError: '', summary: null });
    fetchNetworkSummary({ token: session.token, onUnauthorized: onSessionExpired })
      .then((summary) => {
        if (!active) return;
        setNetwork({ contextKey, status: 'ready', loadError: '', summary });
      })
      .catch((error) => {
        if (!active) return;
        if (error instanceof ApiError && error.status === 401) return;
        // Inline, polite refusal: the branch summary and the acting context
        // stay exactly as they are.
        setNetwork({
          contextKey,
          status: 'error',
          loadError: error instanceof ApiError ? error.message : 'The network comparison could not be loaded.',
          summary: null,
        });
      });
    return () => { active = false; };
  }, [contextKey, canSeeNetwork, session.token, onSessionExpired]);

  const branchView = branchDisplayState({ contextKey, loaded: branch });
  const networkView = networkDisplayState({ contextKey, enabled: canSeeNetwork, loaded: network });
  const reachable = new Set(permittedDestinations(session.roles).map((destination) => destination.id));
  const actions = drillDowns(reachable);

  return (
    <>
      {branchView.status === 'error' && (
        <p className="notice error" role="alert" aria-live="assertive">{branchView.loadError}</p>
      )}

      {branchView.status === 'loading' && <p className="notice">Loading…</p>}

      {branchView.status === 'ready' && (
        <BranchSummary summary={branchView.summary} drillDowns={actions} onNavigate={onNavigate} />
      )}

      {networkView.status === 'loading' && (
        <p className="notice" role="status">Loading network comparison…</p>
      )}
      {networkView.status === 'error' && (
        <p className="notice error" aria-live="polite">{networkView.loadError}</p>
      )}
      {networkView.status === 'ready' && <NetworkSummary summary={networkView.summary} />}
    </>
  );
}
