import React, { useState } from 'react';
import DashboardPage from './DashboardPage.jsx';
import PatientsPage from './features/patients/PatientsPage.jsx';
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

function Screen({ destination, session, onSessionExpired }) {
  if (!destination.implemented) {
    return <ScreenBoundary destination={destination} />;
  }
  if (destination.id === 'patients') {
    return <PatientsPage session={session} onSessionExpired={onSessionExpired} />;
  }
  return <DashboardPage session={session} onSessionExpired={onSessionExpired} />;
}

export default function AppShell({ session, onLogout, onSessionExpired }) {
  // In-memory selection: a fresh mount or refresh always falls back to the
  // dashboard because the selection is intentionally not persisted.
  const [selectedId, setSelectedId] = useState(defaultDestination().id);

  const destinations = permittedDestinations(session.roles);
  const selected =
    destinations.find((destination) => destination.id === selectedId) ?? defaultDestination();

  return (
    <div className="app">
      <aside>
        <h1>MediCore</h1>
        <p>Hospital Management</p>
        <div className="whoami">
          <span className="whoami-name">{session.username}</span>
          <span className="whoami-roles">{session.roles.join(', ')}</span>
        </div>
        <nav aria-label="Screens permitted for your roles">
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
            <h2>{selected.heading}</h2>
            <span>Training build</span>
          </div>
        </header>
        <Screen destination={selected} session={session} onSessionExpired={onSessionExpired} />
      </main>
    </div>
  );
}
