# Implementation Status

## Implemented in code
- Patient registry with create/read/list/update and audit events.
- JWT authentication, BCrypt password hashing, roles, stateless Spring Security.
- CRUD API surfaces for staff, departments, appointments, clinical encounters, nursing, admissions, beds, emergency, laboratory, radiology, pharmacy, medication, surgery, billing, insurance, inventory, blood bank, nutrition, facilities, HR shifts, notifications and documents.
- Persistent audit-event store.
- Dashboard summary endpoint.
- H2 lightweight local profile and PostgreSQL profile.
- React/Vite frontend shell with module navigation and dashboard API integration.
- Global validation/error handling, optimistic-lock field in base entities, Actuator health/metrics exposure, smoke-test scaffold.

## Deliberately deferred
- Docker/containerization, per the development constraint.
- Production clinical certification and hospital-specific regulatory validation.
- External integrations such as HL7/FHIR, PACS/Orthanc, payment gateways and insurer networks.
- Production-grade MFA/SSO, fine-grained resource authorization and secret manager integration.

The repository is therefore complete as the requested **training/reference build**, not represented as deployable certified hospital software.
