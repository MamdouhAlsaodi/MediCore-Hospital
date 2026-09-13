import { mkdirSync, rmSync } from 'node:fs';
import path from 'node:path';

// The disposable H2 home for one e2e run: a fixed, loopback-machine-local
// temp directory outside the repository. It is wiped before the backend
// starts (so every run seeds a fresh synthetic cohort) and removed again in
// global teardown. Sequential runs share the path safely because each run
// resets it at startup; concurrent runs on one workstation are out of scope
// for this single-workstation review runner.
export const E2E_DATA_DIR = path.join(path.sep, 'tmp', 'medicore-e2e-h2');

export function resetDisposableDbDir() {
  rmSync(E2E_DATA_DIR, { recursive: true, force: true });
  mkdirSync(E2E_DATA_DIR, { recursive: true });
}
