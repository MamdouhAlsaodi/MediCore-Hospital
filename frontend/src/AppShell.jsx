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
import NetworkContextSelector from './features/network/NetworkContextSelector.jsx';
import { fetchNetworkHierarchy } from './features/network/networkApi.js';
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

// Context lines come only from server-issued data (Phase 5, FR-008). A
// branch-bound acting context is never described as "organization-wide":
// selection scopes (ORGANIZATION, HOSPITAL) name the acting hospital and
// branch from the server-derived network hierarchy loaded under the current
// acting context, with a neutral loading state while it is in flight and a
// neutral id fallback when the branch is absent from it. Fixed scopes
// (BRANCH, DEPARTMENT) carry their own server-issued labels, now including
// the hospital.
function locatedHierarchyBranch(hierarchy, branchId) {
  if (!hierarchy || !branchId) return null;
  for (const hospital of Array.isArray(hierarchy.hospitals) ? hierarchy.hospitals : []) {
    for (const branch of Array.isArray(hospital?.branches) ? hospital.branches : []) {
      if (branch?.id === branchId) return { hospitalName: hospital.name, branchName: branch.name };
    }
  }
  return null;
}

function actingContextLines(session, hierarchy, hierarchyLoading) {
  const context = session.actingContext;
  const assignment = actingAssignmentOf(session);
  if (!context || !assignment) return [];

  const organizationName = assignment.organizationLabel ?? 'Unknown organization';
  const located = locatedHierarchyBranch(hierarchy, context.branchId);
  if (context.scope === 'ORGANIZATION' || context.scope === 'HOSPITAL') {
    const scopeName = context.scope === 'ORGANIZATION'
      ? organizationName
      : assignment.hospitalLabel ?? 'Unknown hospital';
    if (located) {
      return [context.scope === 'ORGANIZATION'
        ? `${organizationName} — ${located.hospitalName} — ${located.branchName}`
        : `${scopeName} — ${located.branchName}`];
    }
    if (hierarchyLoading) return [`${scopeName} — loading branch…`];
    return [`${scopeName} — branch ${context.branchId}`];
  }
  const lines = [];
  if (assignment.hospitalLabel) lines.push(assignment.hospitalLabel);
  lines.push(assignment.branchLabel ?? 'Unknown branch');
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
  // The server-derived network hierarchy is needed exactly when the session
  // carries a selection-scope assignment (ORGANIZATION, HOSPITAL): it names
  // the acting hospital/branch of the bound token and provides the only
  // switch targets the selector may offer. Fixed-scope-only sessions never
  // fetch it. The state is tagged with the acting-context key it loaded
  // under and gated at render phase, so a hierarchy response that resolves
  // after a context switch can never paint for the new context (Phase 5,
  // FR-009 — not even for one render).
  const needsHierarchy = assignments.some(
    (assignment) => (assignment?.scope === 'ORGANIZATION' || assignment?.scope === 'HOSPITAL')
      && assignment?.id
  );
  const contextKeyValue = actingContextKey(session);
  const [hierarchy, setHierarchy] = useState(
    { contextKey: contextKeyValue, view: null, loading: false, error: '' }
  );

  useEffect(() => {
    if (!needsHierarchy) {
      setHierarchy({ contextKey: contextKeyValue, view: null, loading: false, error: '' });
      return;
    }
    let active = true;
    setHierarchy({ contextKey: contextKeyValue, view: null, loading: true, error: '' });
    fetchNetworkHierarchy({ token: session.token, onUnauthorized: onSessionExpired })
      .then((view) => {
        if (!active) return;
        setHierarchy({ contextKey: contextKeyValue, view, loading: false, error: '' });
      })
      .catch((error) => {
        if (!active) return;
        // 401 is ownership of the shell: the session-expiry callback returns
        // the app to Login, so no local error is raised on top of it. Every
        // other failure keeps the prior token/context/selection and surfaces
        // as text in the selector.
        if (error instanceof ApiError && error.status === 401) return;
        setHierarchy({
          contextKey: contextKeyValue,
          view: null,
          loading: false,
          error: error instanceof ApiError ? error.message : 'Network options could not be loaded.',
        });
      });
    return () => { active = false; };
  }, [contextKeyValue, session.token, needsHierarchy, onSessionExpired]);

  // Render-phase gate: only hierarchy state tagged with the CURRENT acting
  // context key is ever exposed to the whoami lines or the selector. A late
  // response resolved under a switched context is discarded here — before
  // paint — regardless of any effect timing.
  const currentHierarchy = hierarchy.contextKey === contextKeyValue
    ? hierarchy
    : { view: null, loading: true, error: '' };

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
  const contextLines = actingContextLines(session, currentHierarchy.view, currentHierarchy.loading);

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
        <NetworkContextSelector
          session={session}
          hierarchy={currentHierarchy.view}
          hierarchyLoading={currentHierarchy.loading}
          hierarchyError={currentHierarchy.error}
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
        {/* The acting-context key (complete assignment + hospital + branch
            + department identity) remounts the selected screen after a
            successful switch, so the context-bound view reloads from the
            server with the new context-bound token and no stale state from
            the previous context survives. */}
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
