# Phase 5 Planning Traceability

**Status:** Planned mappings only. Evidence columns remain `NOT RUN` until separately authorized implementation occurs.

## Functional requirements

| Requirement | Planned tasks | Planned primary evidence | Current status |
|---|---|---|---|
| FR-001, FR-002, FR-003, FR-004 hierarchy/lifecycle | T011–T018, T027–T030, T031–T045 | V5 migration + hierarchy API/security tests | NOT RUN |
| FR-005, FR-006, FR-007, FR-008, FR-009 assignments/context/render isolation | T019–T025, T046–T061 | auth matrix, structural-claim tests, frontend render discrimination | NOT RUN |
| FR-010, FR-011, FR-012, FR-013 patient identity/access | T062–T077 | V6 migration, patient access API tests, PostgreSQL duplicate races | NOT RUN |
| FR-014, FR-015, FR-016, FR-017, FR-018, FR-019, FR-020, FR-021 transfer/reservation/idempotency/concurrency | T078–T103 | V7 migration, lifecycle/API/security tests, PostgreSQL race evidence | NOT RUN |
| FR-022, FR-023 capacity/scheduling | T104–T115 | capacity exact-sum tests and PostgreSQL conflict races | NOT RUN |
| FR-024, FR-025 command center/non-clinical metrics | T116–T126 | dashboard exact-sum/query-count/API/UI tests | NOT RUN |
| FR-026, FR-027, FR-028 audit/non-enumeration/no partial writes | T127–T138 | audit scope matrix, zero-success-audit assertions, telemetry scans | NOT RUN |
| FR-029 OpenAPI/generated client | T040–T042, T054, T075, T101–T102, T112, T122 | deterministic generation and drift checks | NOT RUN |
| FR-030, FR-031 migrations/PostgreSQL-real proof | T014–T016, T028–T030, T063–T064, T072, T081, T099–T100, T155–T158 | empty and Phase 4-shaped migrations, Testcontainers races | NOT RUN |
| FR-032 desktop/mobile browser evidence | T139–T154 | Playwright journeys and context discrimination | NOT RUN |
| FR-033 canonical acceptance | T157–T159, T168–T169 | 15-stage fail-fast command | NOT RUN |
| FR-034 synthetic/public-safe artifacts | T010, T160, T167 | candidate-path scanner and manual triage | NOT RUN |
| FR-035 no implied execution/publication | T001–T005, T170 | authorization record and final intact worktree | PLAN ENFORCED |

## Success criteria

| Criterion | Planned tasks | Pass evidence required | Current status |
|---|---|---|---|
| SC-001 synthetic multi-hospital cohort | T027, T038–T039 | deterministic 3-hospital/2-branch/2-zone fixture assertions | NOT RUN |
| SC-002 hierarchy authorization matrix | T032–T037, T044–T045 | exact allowed/foreign identifiers and counts | NOT RUN |
| SC-003 stale/tampered/disabled authority | T024–T025, T046–T053, T061 | request reload and refusal matrix | NOT RUN |
| SC-004 one patient identity + grants | T062–T077 | migration/API/concurrency evidence | NOT RUN |
| SC-005 complete transfer paths | T078–T103 | happy/reject/cancel lifecycle evidence | NOT RUN |
| SC-006 one race winner/no partial writes | T099–T100, T111, T156 | repeated PostgreSQL race output | NOT RUN |
| SC-007 idempotency replay/conflict | T080, T086–T087, T099–T100, T150 | identical replay/no duplicate + changed-payload `409` | NOT RUN |
| SC-008 exact capacity sums | T104–T115, T117–T121 | branch→hospital→network equality | NOT RUN |
| SC-009 desktop/mobile/no stale paint | T139–T154 | Playwright exact journey and render checks | NOT RUN |
| SC-010 deterministic contracts | T040–T042, T054, T075, T101–T102, T112, T122 | generation twice + zero drift + status matrix | NOT RUN |
| SC-011 Phase 4 non-regression | T005, T029, T044, T061, T077, T103, T115, T126, T138, T168 | fresh canonical/regression outputs | NOT RUN |
| SC-012 public-safe tracked artifacts | T010, T160, T167 | zero unresolved scanner findings | NOT RUN |
| SC-013 canonical pass and cleanup | T157–T159, T168–T170 | all 15 stages exit 0 + zero leftovers | NOT RUN |
| SC-014 complete traceability | T166, T169 | every FR/SC mapped to source/test/runtime/docs | NOT RUN |

## User-story acceptance map

| Story | Task range | Independent checkpoint |
|---|---:|---|
| US1 Multi-hospital hierarchy | T031–T045 | Authorized hierarchy only, deterministic and bounded |
| US2 Hierarchical acting context | T046–T061 | Full ancestor reload and no stale paint |
| US3 Network patient identity | T062–T077 | One identity, explicit hospital grants, no foreign discovery |
| US4 Inter-hospital transfer | T078–T103 | Transactional full lifecycle, reservation, idempotency, races |
| US5 Capacity/scheduling | T104–T115 | Exact authorized aggregates and one-winner conflicts |
| US6 Command center | T116–T126 | Exact network/hospital/branch sums and scoped UI |
| US7 Audit/isolation | T127–T138 | Structured traceability and zero leakage/false success |
| US8 Complete demonstration | T139–T154 | Clean-stack desktop/mobile journey and recovery |

## Planning gate

The planning artifact is complete when all documents exist, contain no unresolved placeholders, task IDs are sequential, requirements are mapped, and only planning files changed. That gate does **not** change any `NOT RUN` implementation status.
