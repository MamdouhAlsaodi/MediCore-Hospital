# API Quick Start

1. Start backend on loopback port 5501.
2. POST `/api/auth/login` with `{"username":"admin","password":"<HOSPITAL_ADMIN_PASSWORD value from the runtime environment>"}`.
3. Send the returned token as `Authorization: Bearer <token>`.
4. Core endpoints include `/api/patients`, `/api/appointments`, `/api/clinical-encounters`, `/api/admissions`, `/api/emergency-visits`, `/api/lab-orders`, `/api/radiology-orders`, `/api/drugs`, `/api/invoices`, `/api/insurance-claims`, `/api/inventory-items`, `/api/audit`, and `/api/dashboard`.

The admin password is never stored in this repository. Set `HOSPITAL_ADMIN_PASSWORD` in the runtime environment (at least 12 characters) before first start; the initial `admin` account is created only when it does not exist yet, and an existing account is never modified.
