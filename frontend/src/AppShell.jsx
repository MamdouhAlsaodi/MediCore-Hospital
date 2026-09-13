import React, { useEffect, useRef, useState } from 'react';
import { ApiError } from './api.js';
import DashboardPage from './DashboardPage.jsx';
import AdmissionsPage from './features/admissions/AdmissionsPage.jsx';
import EmergencyVisitsPage from './features/emergency/EmergencyVisitsPage.jsx';
import InvoicesPage from './features/billing/InvoicesPage.jsx';
import BedsPage from './features/beds/BedsPage.jsx';
import AppointmentsPage from './features/appointments/AppointmentsPage.jsx';
import AuditPage from './features/audit/AuditPage.jsx';
import PatientsPage from './features/patients/PatientsPage.jsx';
import BranchSelector from './features/branches/BranchSelector.jsx';
import { fetchOrganizationView } from './features/branches/actingContextApi.js';
import { actingAssignmentOf, actingContextKey } from './auth.js';
import { defaultDestination, permittedDestinations } from './navigation.js';

function ScreenBoundary({ destination }) {
  return (
    <section className="panel screen-boundary" aria-label={`${destination.label} screen`}>
      <p className="panel-hint">
        Navigation is ready, but the {destination.label.toLowerCase()} workflow is not
        implemented in this build. It arrives in {destination.arrivesIn}; no data is
        fetched or shown from this screen until then.
      </p>
    </section>
  );
}

function Screen({ destination, session, onSessionExpired, onNavigate }) {
  if (!destination.implemented) {
    return <ScreenBoundary destination={destination} />;
  }
  if (destination.id === 'patients') {
    return <PatientsPage session={session} onSessionExpired={onSessionExpired} />;
  }
  if (destination.id === 'appointments') {
    return <AppointmentsPage session={session} onSessionExpired={onSessionExpired} />;
  }
  if (destination.id === 'admissions') {
    return <AdmissionsPage session={session} onSessionExpired={onSessionExpired} />;
  }
  if (destination.id === 'emergency-visits') {
    return <EmergencyVisitsPage session={session} onSessionExpired={onSessionExpired} />;
  }
  if (destination.id === 'beds') {
    return <BedsPage session={session} onSessionExpired={onSessionExpired} />;
  }
  if (destination.id === 'invoices') {
    return <InvoicesPage session={session} onSessionExpired={onSessionExpired} />;
  }
  if (destination.id === 'audit') {
    return <AuditPage session={session} onSessionExpired={onSessionExpired} />;
  }
  return (
    <DashboardPage
      session={session}
      onSessionExpired={onSessionExpired}
      onNavigate={onNavigate}
    />
  );
}

// Branch lines come only from the server-issued session. A branch-bound
// acting context (every scope the server issues binds one concrete branch)
// is never described as "organization-wide": for an ORGANIZATION-scope
// context the bound branch is named from the server's active-branch list,
// with a neutral loading state while that list is in flight and a neutral
// id fallback when the branch is absent from it.
function actingContextLines(session, organizationView, organizationLoading) {
  const context = session.actingContext;
  const assignment = actingAssignmentOf(session);
  if (!context || !assignment) return [];

  const organizationName = assignment.organizationLabel ?? 'Unknown organization';
  if (context.scope === 'ORGANIZATION') {
    const activeBranches = Array.isArray(organizationView?.activeBranches)
      ? organizationView.activeBranches
      : [];
    const current = activeBranches.find((branch) => branch?.id === context.branchId);
    if (current) return [`${organizationName} — ${current.name}`];
    if (organizationLoading) return [`${organizationName} — loading branch…`];
    return [`${organizationName} — branch ${context.branchId}`];
  }
  const lines = [assignment.branchLabel ?? 'Unknown branch'];
  if (assignment.departmentLabel) lines.push(assignment.departmentLabel);
  return lines;
}

export default function AppShell({ session, onLogout, onSessionExpired, onContextSwitch }) {
  // In-memory selection: a fresh mount or refresh always falls back to the
  // dashboard because the selection is intentionally not persisted.
  const [selectedId, setSelectedId] = useState(defaultDestination().id);

  // Shell focus management (docs/plan3.md Task 13 accessibility contract):
  // the shell owns the persistent chrome, so it owns predictable focus.
  const screenHeadingRef = useRef(null);
  const screenNavRef = useRef(null);

  const assignments = Array.isArray(session.assignments) ? session.assignments : [];
  // The server-owned active-branch list is needed exactly when the session
  // carries an ORGANIZATION-scope assignment: it names the current branch of
  // a branch-bound organization token and provides the switch targets for
  // the selector. Branch/department-only sessions never fetch it.
  const needsOrganization = assignments.some(
    (assignment) => assignment?.scope === 'ORGANIZATION' && assignment?.id
  );
  const [organization, setOrganization] = useState(null);
  const [organizationLoading, setOrganizationLoading] = useState(false);
  const [organizationError, setOrganizationError] = useState('');

  useEffect(() => {
    if (!needsOrganization) return;
    let active = true;
    setOrganization(null);
    setOrganizationError('');
    setOrganizationLoading(true);
    fetchOrganizationView({ token: session.token, onUnauthorized: onSessionExpired })
      .then((view) => {
        if (!active) return;
        setOrganization(view);
        setOrganizationLoading(false);
      })
      .catch((error) => {
        if (!active) return;
        setOrganizationLoading(false);
        // 401 is ownership of the shell: the session-expiry callback returns
        // the app to Login, so no local error is raised on top of it. Every
        // other failure (403 included — the endpoint is ADMIN-authorized)
        // keeps the prior token/context/selection and surfaces as text.
        if (error instanceof ApiError && error.status === 401) return;
        setOrganizationError(
          error instanceof ApiError ? error.message : 'Branch options could not be loaded.'
        );
      });
    return () => { active = false; };
  }, [session.token, needsOrganization, onSessionExpired]);

  const contextKeyValue = actingContextKey(session);

  // Predictable focus after a screen change: navigation and a successful
  // acting-context switch both remount the selected screen, which would
  // otherwise leave keyboard focus on a stale control or dropped on <body>.
  // The shell moves focus to the selected screen's heading — a visible,
  // labeled, non-interactive landmark (no focus ring; see style.css) from
  // which the next Tab continues inside the freshly loaded screen.
  useEffect(() => {
    screenHeadingRef.current?.focus();
  }, [selectedId, contextKeyValue]);

  // Predictable focus return after a dialog/form closes: transient forms and
  // two-step confirmations unmount their controls after a cancel or submit,
  // which would leave keyboard focus on a detached node or <body> with no
  // visible location. When the shell observes focus on a lost node right
  // after such a removal, it returns focus to the current screen's
  // navigation control — a stable, visible, labeled location. Ordinary focus
  // changes inside still-mounted UI (clicks, Tab order, page interactions)
  // are never redirected.
  useEffect(() => {
    let lastFocused = null;
    const rememberFocus = (event) => {
      const target = event.target;
      lastFocused = target instanceof Element && target.isConnected ? target : null;
    };
    const observer = new MutationObserver(() => {
      const active = document.activeElement;
      const focusLost = active === null
        || active === document.body
        || active === document.documentElement
        || (active instanceof Element && !active.isConnected);
      if (focusLost && lastFocused && !lastFocused.isConnected) {
        const nav = screenNavRef.current;
        const current = nav?.querySelector('button[aria-current="page"]')
          ?? nav?.querySelector('button');
        current?.focus();
      }
    });
    document.addEventListener('focusin', rememberFocus, true);
    observer.observe(document.body, { childList: true, subtree: true });
    return () => {
      document.removeEventListener('focusin', rememberFocus, true);
      observer.disconnect();
    };
  }, []);

  const destinations = permittedDestinations(session.roles);
  const selected =
    destinations.find((destination) => destination.id === selectedId) ?? defaultDestination();

  const context = session.actingContext ?? null;
  const actingRole = context?.role ?? session.roles.join(', ');
  const contextLines = actingContextLines(session, organization, organizationLoading);

  return (
    <div className="app">
      <aside>
        <h1>MediCore</h1>
        <p>Hospital Management</p>
        <div className="whoami">
          <span className="whoami-name">{session.username}</span>
          <span className="whoami-roles">{actingRole}</span>
          {contextLines.map((line) => (
            <span key={line} className="whoami-context">{line}</span>
          ))}
        </div>
        <BranchSelector
          session={session}
          organization={organization}
          organizationLoading={organizationLoading}
          organizationError={organizationError}
          onContextSwitch={onContextSwitch}
          onSessionExpired={onSessionExpired}
        />
        <nav ref={screenNavRef} aria-label="Screens permitted for your roles">
          {destinations.map((destination) => (
            <button
              key={destination.id}
              type="button"
              className="nav-link"
              aria-current={destination.id === selected.id ? 'page' : undefined}
              onClick={() => setSelectedId(destination.id)}
            >
              {destination.label}
            </button>
          ))}
        </nav>
        <button type="button" className="logout" onClick={onLogout}>Log out</button>
      </aside>

      <main>
        <header>
          <div>
            {/* Non-interactive landmark: receives programmatic focus after
                navigation/context switches (no focus ring; see style.css). */}
            <h2 ref={screenHeadingRef} tabIndex={-1}>{selected.heading}</h2>
            <span>Training build</span>
          </div>
        </header>
        {/* The acting-context key remounts the selected screen after a
            successful switch, so the branch-scoped view reloads from the
            server with the new context-bound token and no stale state from
            the previous branch survives. */}
        <Screen
          key={actingContextKey(session)}
          destination={selected}
          session={session}
          onSessionExpired={onSessionExpired}
          onNavigate={setSelectedId}
        />
      </main>
    </div>
  );
}
