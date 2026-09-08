# MediCore Hospital

MediCore is an educational, non-clinical Java/Spring Boot + React training implementation of a modular hospital-management system. It is not certified clinical software and must not be used for patient care or real clinical decisions.

## Start here
- `docs/plan.md` — the implementation roadmap.
- `docs/pdr.md` — product and architecture boundaries.
- `docs/api.md` — API quick start.
- `docs/runbook.md` — safe local review instructions.

## Public-safe local review
1. Set `HOSPITAL_JWT_SECRET` outside the repository.
2. Start the backend on loopback port `5502`.
3. For private review, have the supervisor start Vite with `REVIEW_BIND_HOST` set to the narrow Tailnet interface address.
4. Never expose or enable the H2 console. Do not use this project as clinical software.

Docker and public deployment remain outside this phase.
