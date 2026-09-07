# Product Design Requirements — MediCore Hospital

## Goal
A modular hospital-management training system that demonstrates realistic hospital workflows without pretending to be production-certified medical software.

## Architecture
- Backend: Java 21 + Spring Boot 3, modular monolith.
- API: REST/JSON.
- Persistence: H2 for lightweight local development; PostgreSQL profile for later production-like use.
- Frontend: React + Vite.
- Security: JWT bearer token + role model.
- Audit: application-level audit events for mutating operations.
- Docker: intentionally postponed.

## Important boundary
This repository is an educational implementation. Real clinical deployment requires validated workflows, regulatory review, threat modelling, migration discipline, backups, observability, integration standards, and substantially deeper testing.
