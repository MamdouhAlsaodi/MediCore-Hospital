import React, { useCallback, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './style.css';
import { clearSession, loadSession, purgeLegacyStorage, saveSession } from './auth.js';
import AppShell from './AppShell.jsx';
import LoginPage from './LoginPage.jsx';

function App() {
  const [session, setSession] = useState(() => {
    purgeLegacyStorage();
    return loadSession();
  });

  const handleLogin = useCallback((newSession) => {
    saveSession(newSession);
    setSession(newSession);
  }, []);

  // Successful acting-context switch (docs/plan3.md Task 5): the complete
  // server-issued session — new context-bound token, acting context, and
  // assignment list — is written to storage and swapped into state in one
  // atomic replacement. A refused switch (403) never reaches this handler,
  // so neither storage nor UI can be partially updated.
  const handleContextSwitch = useCallback((nextSession) => {
    saveSession(nextSession);
    setSession(nextSession);
  }, []);

  const handleLogout = useCallback(() => {
    clearSession();
    setSession(null);
  }, []);

  return session
    ? (
      <AppShell
        session={session}
        onLogout={handleLogout}
        onSessionExpired={handleLogout}
        onContextSwitch={handleContextSwitch}
      />
    )
    : <LoginPage onLogin={handleLogin} />;
}

createRoot(document.getElementById('root')).render(<App />);
