import React, { useCallback, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './style.css';
import { clearSession, loadSession, purgeLegacyStorage, saveSession } from './auth.js';
import DashboardPage from './DashboardPage.jsx';
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

  const handleLogout = useCallback(() => {
    clearSession();
    setSession(null);
  }, []);

  return session
    ? <DashboardPage session={session} onLogout={handleLogout} onSessionExpired={handleLogout} />
    : <LoginPage onLogin={handleLogin} />;
}

createRoot(document.getElementById('root')).render(<App />);
