# NAHID AI Knowledge Server — Deployment & Operations Guide

- **Deployment Status in Current Environment:** `NOT_DEPLOYED` (No external Cloud SQL / PostgreSQL host credentials or Cloud Run service permissions are provisioned in this sandbox).
- **Architecture:** 100% Firebase-free Node.js HTTP REST API + PostgreSQL (`schema.sql`).
- **Local Verification:** Run `node --test server.test.js` inside `/knowledge-server/` (tested and verified).
- **Cloud Run / VPS Production Steps:**
  1. Provision PostgreSQL 15+ and apply `schema.sql`.
  2. Build container from `Dockerfile` and supply `KNOWLEDGE_API_TOKEN` + `DATABASE_URL` via environment secrets.
  3. Configure TLS/HTTPS endpoint and enter the endpoint + bearer token in Android `AiSettingsRepository`.
