import { rmSync } from 'node:fs';
import { E2E_DATA_DIR } from './disposable-db.mjs';

// Global teardown (docs/plan3.md Task 13): the disposable H2 database of the
// e2e run never outlives the run.
export default function globalTeardown() {
  rmSync(E2E_DATA_DIR, { recursive: true, force: true });
}
