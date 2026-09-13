import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig } from '@playwright/test';
import { E2E_DATA_DIR, resetDisposableDbDir } from './e2e/disposable-db.mjs';

// Browser-evidence runner (docs/plan3.md Task 13).
//
// Scope and boundary:
//   - The run targets ONLY the disposable loopback review pair documented in
//     docs/runbook.md: the Vite review frontend on 127.0.0.1:5592 proxying
//     /api to the Spring Boot review backend on 127.0.0.1:5591. No other
//     host is ever contacted. These dedicated review ports are passed to
//     both servers explicitly below (SERVER_PORT for the backend,
//     REVIEW_FRONTEND_PORT/REVIEW_API_PROXY_TARGET for Vite), and
//     reuseExistingServer is unconditionally false: a previously running
//     service — even one this project started earlier on another port — is
//     never reused, so the journey can never reach a stale instance.
//   - The backend is started with a run-specific disposable H2 file database
//     outside the repository (SPRING_DATASOURCE_URL under /tmp) and
//     MEDICORE_DEMO_SEED=true, so every run proves the journey against a
//     fresh, idempotent, obviously synthetic three-branch cohort. The
//     directory is removed in globalTeardown.
//   - Secrets are never embedded here: HOSPITAL_ADMIN_PASSWORD and
//     HOSPITAL_JWT_SECRET must be exported in the invoking environment
//     (values only; see docs/runbook.md for the variable names). A missing
//     variable fails the run before anything starts — the backend would
//     refuse to boot without them anyway.
//   - Playwright artifacts live under node_modules (never committed). The
//     three evidence screenshots under docs/evidence/phase3/ are copied by
//     the journey's final (mobile) project, and only after every assertion
//     of both viewport runs has passed.

const frontendDir = path.dirname(fileURLToPath(import.meta.url));
const repoDir = path.resolve(frontendDir, '..');
const backendDir = path.join(repoDir, 'backend');
const evidenceDir = path.join(repoDir, 'docs', 'evidence', 'phase3');

// Dedicated, collision-free review ports for this runner. Nothing else in
// the repository may bind them; the values are also spelled out in
// docs/runbook.md.
export const REVIEW_BACKEND_PORT = 5591;
export const REVIEW_FRONTEND_PORT = 5592;
const REVIEW_BACKEND_ORIGIN = `http://127.0.0.1:${REVIEW_BACKEND_PORT}`;
const REVIEW_FRONTEND_ORIGIN = `http://127.0.0.1:${REVIEW_FRONTEND_PORT}`;

// Disposable per-run H2 home outside the repository: wiped before the
// backend starts and removed again in global teardown.
resetDisposableDbDir();

// Evidence discipline: any screenshot left under docs/evidence/phase3/ from
// a previous invocation is stale — wipe it once per run, before the servers
// start, so only the current fully passing run can recreate the set.
if (fs.existsSync(evidenceDir)) {
  for (const entry of fs.readdirSync(evidenceDir)) {
    if (entry.endsWith('.png')) fs.rmSync(path.join(evidenceDir, entry), { force: true });
  }
}

if (!process.env.HOSPITAL_ADMIN_PASSWORD || !process.env.HOSPITAL_JWT_SECRET) {
  throw new Error(
    'Missing environment: export HOSPITAL_ADMIN_PASSWORD and HOSPITAL_JWT_SECRET '
      + '(disposable values, never committed) before running npm run test:e2e. '
      + 'Variable names are documented in docs/runbook.md.'
  );
}

export default defineConfig({
  testDir: 'e2e',
  fullyParallel: false,
  workers: 1,
  forbidOnly: true,
  retries: 0,
  reporter: 'list',
  // The journey is a long sequential scenario on a single small workstation
  // that also hosts both servers; 30 s (the Playwright default) is not
  // truthful headroom for it under normal machine load.
  timeout: 120_000,
  // Keep all Playwright output inside the git-ignored node_modules tree;
  // evidence screenshots are copied into docs/evidence/phase3 only by the
  // passing journey itself.
  outputDir: path.join(frontendDir, 'node_modules', '.e2e-artifacts'),
  use: {
    baseURL: REVIEW_FRONTEND_ORIGIN,
    screenshot: 'off',
    video: 'off',
    // Authenticated network traces can persist the disposable login request
    // body. Keep them disabled; the list reporter and redacted DOM context are
    // sufficient diagnostics for this evidence runner.
    trace: 'off',
  },
  projects: [
    {
      name: 'desktop',
      use: { viewport: { width: 1280, height: 720 } },
    },
    {
      name: 'mobile',
      use: {
        viewport: { width: 375, height: 812 },
        isMobile: true,
        hasTouch: true,
      },
    },
  ],
  webServer: [
    {
      command: 'mvn -q spring-boot:run',
      cwd: backendDir,
      url: `${REVIEW_BACKEND_ORIGIN}/actuator/health`,
      // Never attach to an already-running service: the review backend on
      // the dedicated port must be this run's own disposable instance.
      reuseExistingServer: false,
      timeout: 300_000,
      stdout: 'pipe',
      stderr: 'pipe',
      env: {
        ...process.env,
        // The dedicated review backend port, passed explicitly so the
        // server never falls back to the shared default port.
        SERVER_PORT: String(REVIEW_BACKEND_PORT),
        // Bounded heap for the review backend so the browser always has
        // headroom on this single small workstation.
        JAVA_TOOL_OPTIONS: '-Xmx768m',
        MEDICORE_DEMO_SEED: 'true',
        // Fresh disposable H2 file database per run, outside the repository.
        SPRING_DATASOURCE_URL: `jdbc:h2:file:${E2E_DATA_DIR}/medicore;MODE=PostgreSQL`,
      },
    },
    {
      command: 'npm run dev',
      cwd: frontendDir,
      url: REVIEW_FRONTEND_ORIGIN,
      // Same rule: never reuse a frontend already listening on the port.
      reuseExistingServer: false,
      timeout: 120_000,
      stdout: 'pipe',
      stderr: 'pipe',
      env: {
        ...process.env,
        // Dedicated review ports, passed explicitly to the Vite dev server
        // (read by vite.config.js): bind port and /api proxy target.
        REVIEW_FRONTEND_PORT: String(REVIEW_FRONTEND_PORT),
        REVIEW_API_PROXY_TARGET: REVIEW_BACKEND_ORIGIN,
      },
    },
  ],
  globalTeardown: './e2e/global-teardown.mjs',
});
