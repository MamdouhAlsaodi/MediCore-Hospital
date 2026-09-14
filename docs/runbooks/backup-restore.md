# Backup and Restore Runbook (Phase 4)

Training/portfolio operational rehearsal for the **synthetic disposable** PostgreSQL
review database. Nothing here is a production procedure, an SLA, an RPO, or an
RTO: durations recorded below are local training measurements only.

## Scope and safety model

- Every command routes through `scripts/phase4/postgres-target-guard.sh`, which
  accepts only loopback hosts (or the documented Compose service `postgres`)
  and database names matching `medicore_phase4_[a-z0-9_]+`. Anything else is
  refused **before any connection or statement** (fail closed).
- `restore-postgres.sh` additionally refuses: a missing archive, an archive
  that fails `pg_restore --list`, a target equal to the source database
  (`RESTORE_SOURCE_DB`), and an **existing** target database (no overwrite).
- Credentials travel only through the `PGPASSWORD` environment variable and are
  never echoed by any script.

## Commands (from the repository root)

```bash
# 1. Backup (custom format + checksum, restrictive permissions)
export PGPASSWORD   # runtime-only; never committed
scripts/phase4/backup-postgres.sh 127.0.0.1 55432 medicore_phase4_review medicore_review /tmp/medicore_phase4_backup
# -> /tmp/medicore_phase4_backup.dump(.sha256), mode 600

# 2. Verify the checksum
( cd /tmp && sha256sum -c medicore_phase4_backup.dump.sha256 )

# 3. Restore into a FRESH disposable target (refuses to overwrite)
RESTORE_SOURCE_DB=medicore_phase4_review \
scripts/phase4/restore-postgres.sh 127.0.0.1 55432 /tmp/medicore_phase4_backup.dump medicore_phase4_review_restored medicore_review

# 4. Compare allowlisted invariants (counts/references/uniqueness/lifecycle)
scripts/phase4/verify-database-invariants.sh 127.0.0.1 55432 medicore_phase4_review medicore_review
scripts/phase4/verify-database-invariants.sh 127.0.0.1 55432 medicore_phase4_review_restored medicore_review
```

## One-command rehearsal

`scripts/phase4/test-backup-restore.sh` starts one process-owned disposable
`postgres:16-alpine` container, applies the shipped migrations, seeds a tiny
synthetic cohort, and proves the positive path plus seven negative probes
(unsafe host, missing password, missing archive, corrupt archive,
target==source, non-disposable name, existing-target overwrite) with the
source verified unchanged afterwards. Measured local duration of one full
rehearsal on the development host: a few minutes (dominated by PostgreSQL
container init and the Flyway migration run) — training evidence only.

## Rollback limitation

Schema evolution is forward-only Flyway (constitution IV). There is no
automated downgrade; recovery from a bad migration state is restore-from-backup
into a fresh disposable database, exactly as rehearsed above.
