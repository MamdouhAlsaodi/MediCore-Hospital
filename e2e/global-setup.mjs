// Phase 4 container E2E global setup (T078 re-proof inside the runner).
//
// The Compose stack is owned by scripts/phase4/container-e2e-stack.sh, which
// must already have proven service/port ownership before invoking Playwright.
// This setup never starts, restarts, or stops anything: it only re-verifies,
// from inside the browser runner, that the loopback origins handed over in
// the environment are really this run's healthy stack. A failure here stops
// the whole run before any test executes.

export default async function globalSetup() {
  const web = process.env.REVIEW_WEB_ORIGIN;
  const api = process.env.REVIEW_API_ORIGIN;
  for (const [label, url] of [
    ['frontend SPA', web],
    ['backend readiness', `${api}/actuator/health/readiness`],
    ['backend liveness', `${api}/actuator/health/liveness`],
  ]) {
    let response;
    try {
      response = await fetch(url, { redirect: 'manual' });
    } catch (error) {
      throw new Error(
        `container E2E global setup: ${label} at ${url} is not reachable `
          + `(${error.cause?.code ?? error.message}). The stack must be started and proven by `
          + 'scripts/phase4/container-e2e-stack.sh before the browser suite runs.'
      );
    }
    if (!response.ok && response.status !== 0) {
      throw new Error(
        `container E2E global setup: ${label} at ${url} answered ${response.status}; `
          + 'refusing to run a browser journey against an unhealthy stack.'
      );
    }
  }
}
