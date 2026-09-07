# API Quick Start

1. Start backend on port 8080.
2. POST `/api/auth/login` with `{"username":"admin","password":"ChangeMe123!"}`.
3. Send returned token as `Authorization: Bearer <token>`.
4. Core endpoints include `/api/patients`, `/api/appointments`, `/api/clinical-encounters`, `/api/admissions`, `/api/emergency-visits`, `/api/lab-orders`, `/api/radiology-orders`, `/api/drugs`, `/api/invoices`, `/api/insurance-claims`, `/api/inventory-items`, `/api/audit`, and `/api/dashboard`.

The development admin password exists only to make the training build immediately usable; change it before any shared environment.
