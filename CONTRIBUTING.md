# Contributing

MediCore Hospital is a completed training/portfolio exercise, not a clinical product roadmap. Small, well-scoped corrections to documentation, accessibility, tests, or security are welcome for review. New clinical workflows, real-data integrations, deployment, or production-readiness claims are outside the current project scope and should be discussed before implementation.

1. Open an issue or describe the specific problem and expected behavior in a pull request. Do not attach real patient data, credentials, database dumps, or private infrastructure details.
2. Work from the current `main` on a focused branch. Keep synthetic fixtures and server-enforced hospital/branch isolation intact; do not bypass migration, authorization, or concurrency tests to make a check green.
3. Run the relevant tests. The full local portfolio gate is `scripts/phase5/acceptance.sh` (requires Docker, JDK 21/Maven, Node/npm, and PostgreSQL client tools); see [the runbook](docs/runbook.md) for guarded setup. For a docs-only correction, validate links, `git diff --check`, and the public-artifact scan instead of claiming the full gate was rerun.
4. In the PR, state the changed files, the exact checks you ran and their outcomes, and what you did not verify. GitHub's PostgreSQL/OpenAPI workflow is narrower than the local acceptance gate.
5. Report security findings through [private vulnerability reporting](SECURITY.md), not a public issue.

By contributing, you agree that your contributions are made available under the repository's [MIT License](LICENSE). No clinical suitability, certification, or production support is implied.
