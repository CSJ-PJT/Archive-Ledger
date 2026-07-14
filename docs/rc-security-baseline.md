# RC Security Baseline

Archive-Ledger uses only Synthetic Runtime Data, but its write paths still require a service boundary in RC environments.

## Exposure Audit

| Port/API | RC exposure | Required caller | Control |
| --- | --- | --- | --- |
| Ledger HTTP `18080` | `127.0.0.1` only | local ArchiveOS gateway or operator | host loopback bind |
| PostgreSQL `5432` | Docker network only | Archive-Ledger container | `expose`, no host `ports` mapping |
| `POST /api/events/market*` | internal write | Archive-Market | `ledger:ingest` + Market token |
| `POST /api/events/nexus*` | internal write | Archive-Nexus | `ledger:ingest` + Nexus token |
| `POST /api/events/logistics*` | internal write | Archive-Logistics | `ledger:ingest` + Logistics token |
| `POST /api/approvals/callback` | internal write | ArchiveOS | `ledger:approval-callback` + callback token |
| settlement, batch, reconciliation, workforce, simulation writes | admin write | ArchiveOS | `admin:operate` + admin token |
| health probes | public loopback health | Docker healthcheck | `/actuator/health` only |
| financial/runtime/workforce detail reads | authenticated read | ArchiveOS | `ledger:read` + read token |
| operations/runtime status/reconciliation summary | synthetic public summary | browser or ArchiveOS | read-only, no raw payload detail |

`Archive-Logitics` remains an accepted body-source alias only for existing compatibility events. Its authenticated header identity is always `Archive-Logistics`.

## Request Contract

Protected requests require all headers below:

```http
Authorization: Bearer <service-token>
X-Archive-Source-System: Archive-Market
X-Archive-Service-Scope: ledger:ingest
```

The source header must match the endpoint identity and the event body source. Token values are compared in constant time and are never logged.

## Controls

- write payloads larger than 1 MiB are rejected with `413`;
- protected write routes use a per-source/path 60-second in-memory rate window (`429` and `Retry-After: 60`);
- bulk event APIs are limited to 1,000 events;
- health is the only web-exposed actuator endpoint in RC;
- CORS accepts explicit configured origins only. Wildcards and credentialed wildcard CORS are not used;
- missing, invalid, or wrong-scope credentials return `401`/`403` and callers must classify them as non-retryable configuration failures.

## Compose

The default `docker-compose.yml` is RC-oriented: it requires DB and token environment variables, maps Ledger to host loopback while binding inside the container for Docker routing, and keeps PostgreSQL internal. Direct `bootRun` binds to loopback by default. Use `docker-compose.dev-db.yml` only when a local database tool needs a loopback port:

```powershell
docker compose -f docker-compose.yml -f docker-compose.dev-db.yml up -d
```

Copy `.env.example` to an untracked `.env` and populate values through local secret management. No token or password value belongs in Git.

## Residual Production Work

This baseline is not mTLS, centralized identity, distributed rate limiting, a WAF, or a secret manager. A production deployment should add those controls at the gateway/platform boundary and rotate service credentials through managed secret storage.
