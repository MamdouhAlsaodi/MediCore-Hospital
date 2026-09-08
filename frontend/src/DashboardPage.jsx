import React, { useEffect, useState } from 'react';
import { apiFetch, ApiError } from './api.js';

const MODULES = [
  { name: 'Patients', roles: ['DOCTOR', 'NURSE', 'RECEPTIONIST'], status: 'API ready' },
  { name: 'Staff', roles: ['HR'], status: 'API ready' },
  { name: 'Departments', roles: ['HR'], status: 'API ready' },
  { name: 'Appointments', roles: ['DOCTOR', 'NURSE', 'RECEPTIONIST'], status: 'API ready' },
  { name: 'Clinical EHR', roles: ['DOCTOR', 'NURSE'], status: 'API ready' },
  { name: 'Nursing', roles: ['DOCTOR', 'NURSE'], status: 'API ready' },
  { name: 'Admissions', roles: ['DOCTOR', 'NURSE', 'RECEPTIONIST'], status: 'API ready' },
  { name: 'Rooms & Beds', roles: ['DOCTOR', 'NURSE', 'RECEPTIONIST'], status: 'API ready' },
  { name: 'Emergency', roles: ['DOCTOR', 'NURSE', 'RECEPTIONIST'], status: 'API ready' },
  { name: 'Laboratory', roles: ['DOCTOR', 'LAB_TECH'], status: 'API ready' },
  { name: 'Radiology', roles: ['DOCTOR', 'RADIOLOGY_TECH'], status: 'API ready' },
  { name: 'Pharmacy', roles: ['PHARMACIST', 'DOCTOR'], status: 'API ready' },
  { name: 'Medication', roles: ['DOCTOR', 'NURSE'], status: 'API ready' },
  { name: 'Surgery', roles: ['DOCTOR', 'NURSE'], status: 'API ready' },
  { name: 'Billing', roles: ['BILLING'], status: 'API ready' },
  { name: 'Insurance', roles: ['BILLING'], status: 'API ready' },
  { name: 'Inventory', roles: ['PHARMACIST', 'STAFF'], status: 'API ready' },
  { name: 'Blood Bank', roles: ['DOCTOR', 'NURSE', 'LAB_TECH'], status: 'API ready' },
  { name: 'Nutrition', roles: ['DOCTOR', 'NURSE', 'STAFF'], status: 'API ready' },
  { name: 'Facilities', roles: ['STAFF'], status: 'API ready' },
  { name: 'HR & Shifts', roles: ['HR'], status: 'API ready' },
  { name: 'Notifications', roles: 'ANY', status: 'API ready' },
  { name: 'Documents', roles: ['DOCTOR', 'NURSE', 'STAFF'], status: 'API ready' },
  { name: 'Audit', roles: [], status: 'API ready' },
  { name: 'Reports', roles: [], status: 'Planned' },
];

function canViewModule(module, roles) {
  if (roles.includes('ADMIN')) return true;
  if (module.roles === 'ANY') return roles.length > 0;
  return module.roles.some((role) => roles.includes(role));
}

export default function DashboardPage({ session, onLogout, onSessionExpired }) {
  const [stats, setStats] = useState(null);
  const [loadError, setLoadError] = useState('');

  useEffect(() => {
    let active = true;
    apiFetch('/api/dashboard', { token: session.token, onUnauthorized: onSessionExpired })
      .then((data) => { if (active) { setStats(data); setLoadError(''); } })
      .catch((err) => {
        if (!active) return;
        setLoadError(err instanceof ApiError ? err.message : 'The dashboard could not be loaded.');
      });
    return () => { active = false; };
  }, [session.token, onSessionExpired]);

  const roles = session.roles;
  const modules = MODULES.filter((module) => canViewModule(module, roles));

  return (
    <div className="app">
      <aside>
        <h1>MediCore</h1>
        <p>Hospital Management</p>
        <div className="whoami">
          <span className="whoami-name">{session.username}</span>
          <span className="whoami-roles">{roles.join(', ')}</span>
        </div>
        <nav aria-label="Modules permitted for your roles">
          {modules.map((module) => (
            <span key={module.name} className="nav-item" title={`${module.status} — ${module.name}`}>
              {module.name}
            </span>
          ))}
        </nav>
        <button type="button" className="logout" onClick={onLogout}>Log out</button>
      </aside>

      <main>
        <header>
          <div>
            <h2>Operations Dashboard</h2>
            <span>Training build</span>
          </div>
          <button type="button" className="logout compact" onClick={onLogout}>Log out</button>
        </header>

        {loadError && (
          <p className="notice error" role="alert" aria-live="assertive">{loadError}</p>
        )}

        <section className="cards" aria-label="Key statistics">
          {stats
            ? Object.entries(stats).map(([label, value]) => (
                <article key={label}>
                  <small>{label}</small>
                  <strong>{value}</strong>
                </article>
              ))
            : !loadError && <article><small>Status</small><strong>Loading…</strong></article>}
        </section>

        <section className="panel" aria-label="Modules permitted for your roles">
          <h3>Modules for your roles</h3>
          <p className="panel-hint">
            Only modules your roles may access are listed. Module pages open in a later phase.
          </p>
          <div className="grid">
            {modules.map((module) => (
              <div className="module" key={module.name}>
                <b aria-hidden="true">·</b>
                <span>{module.name}</span>
                <em className={'badge ' + (module.status === 'API ready' ? 'ok' : 'planned')}>
                  {module.status}
                </em>
              </div>
            ))}
            {!modules.length && <p className="notice">No modules are assigned to your roles yet.</p>}
          </div>
        </section>
      </main>
    </div>
  );
}
