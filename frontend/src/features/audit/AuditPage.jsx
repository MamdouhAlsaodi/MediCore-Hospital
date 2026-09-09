import React, { useEffect, useState } from 'react';
import { ApiError } from '../../api.js';
import { fetchAuditEvents } from './auditApi.js';

// Audit evidence screen (plan1.md Task 10): a strictly read-only,
// data-minimized view of the mutation audit trail over GET /api/audit
// (ADMIN-only in SecurityConfig; navigation hides the destination from every
// other role and the server refuses direct reads with 403).
//
// Each event renders only the evidence the contract provides — timestamp,
// actor, action, entity type, identifier — as plain React text nodes
// (hostile-looking strings can only ever appear as literal text). The
// internal record metadata and the raw details payload are deliberately not
// rendered, and no tokens, credentials, or request bodies are shown. Events
// are sorted newest-first on the client because GET /api/audit takes no
// ordering parameters.
export default function AuditPage({ session, onSessionExpired }) {
  const [events, setEvents] = useState([]);
  const [status, setStatus] = useState('loading');
  const [loadError, setLoadError] = useState('');

  useEffect(() => {
    let active = true;
    setStatus('loading');
    setLoadError('');
    fetchAuditEvents({ token: session.token, onUnauthorized: onSessionExpired })
      .then((loaded) => {
        if (!active) return;
        setEvents(Array.isArray(loaded) ? loaded : []);
        setStatus('ready');
      })
      .catch((error) => {
        if (!active) return;
        // 401 is ownership of the shell: the session-expiry callback returns
        // the app to Login, so no local error is raised on top of it.
        if (error instanceof ApiError && error.status === 401) return;
        setLoadError(error instanceof ApiError ? error.message : 'Audit events could not be loaded.');
        setStatus('ready');
      });
    return () => { active = false; };
  }, [session.token, onSessionExpired]);

  // The endpoint returns rows in unspecified repository order; the demo
  // contract is reverse-chronological, so the page sorts defensively by the
  // contract timestamp (unparseable values sink to the end).
  const orderedEvents = [...events].sort(
    (a, b) => (Date.parse(b?.occurredAt ?? '') || 0) - (Date.parse(a?.occurredAt ?? '') || 0)
  );

  return (
    <section className="audit-screen" aria-label="Audit screen">
      {status === 'loading' && (
        <p className="notice" role="status">Loading audit events…</p>
      )}

      {loadError && (
        <p className="notice error" role="alert">{loadError}</p>
      )}

      {status === 'ready' && !loadError && (
        orderedEvents.length === 0 ? (
          <div className="panel audit-empty" role="status">
            <h3>No audit events recorded</h3>
            <p className="panel-hint">
              Mutation evidence appears here as users create, update, or delete records.
            </p>
          </div>
        ) : (
          <div className="audit-table-wrap">
            <table className="audit-table" aria-label="Audit events">
              <thead>
                <tr>
                  <th scope="col">Time</th>
                  <th scope="col">Actor</th>
                  <th scope="col">Action</th>
                  <th scope="col">Entity type</th>
                  <th scope="col">Identifier</th>
                </tr>
              </thead>
              <tbody>
                {orderedEvents.map((event) => (
                  <tr key={event.id}>
                    <td>{event.occurredAt || '—'}</td>
                    <td>{event.actor || '—'}</td>
                    <td>{event.action || '—'}</td>
                    <td>{event.resourceType || '—'}</td>
                    <td>{event.resourceId || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      )}
    </section>
  );
}
