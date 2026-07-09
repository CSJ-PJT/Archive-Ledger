# Archive-Ledger

Archive-Ledger is the synthetic financial ledger backend in the Archive ecosystem.

It receives events from:

- Archive-Nexus: direct manufacturing and logistics-direct operations events
- Archive-Logitics: logistics cost confirmation events

and processes them into finance transactions, double-entry ledger entries, settlement workflow, approvals, and reconciliation summaries.

## Tech stack

- Java 21, Spring Boot 3
- Spring Web, Spring Validation, JdbcTemplate, Spring Batch, Spring Boot Actuator
- PostgreSQL, Flyway
- JUnit 5, AssertJ, (optionally Testcontainers in test profile)
- Docker Compose

## Run

```powershell
docker compose up --build -d
```

| Service | URL |
| --- | --- |
| Ledger API | `http://localhost:18080` |
| Health | `http://localhost:18080/actuator/health` |
| PostgreSQL | `localhost:56543` |

## API

- `POST /api/events/nexus` (existing direct event path)
- `POST /api/events/nexus/bulk`
- `POST /api/events/logistics`
- `POST /api/events/logistics/bulk`
- `GET /api/events/received?source=Archive-Logitics`
- `GET /api/transactions?source=Archive-Logitics`
- `GET /api/ledger/summary?source=Archive-Logitics`
- `POST /api/settlements/daily/run?date=YYYY-MM-DD`
- `GET /api/reconciliation/daily?date=YYYY-MM-DD`
- `POST /api/reconciliation/daily?date=YYYY-MM-DD`
- `GET /api/reconciliation/summary`
- `POST /api/approvals/callback`
- `GET /api/operations/summary`

## Safety rules

- `eventId` and `idempotencyKey` are required.
- Logistics amount resolution order: `totalCost`, `estimatedCost`, `amount`.
- Unknown/invalid logistics amount leads to failed status.
- `APPROVAL_REQUIRED` transactions are not included in settlement until approval callback.
- Each processed transaction emits debit/credit entries and keeps totals equal.
- Source filtering is supported for `events/received`, `transactions`, and `ledger/summary`.
- `Archive-Logitics` compatibility mode supports `eventType=LOGISTICS_DISPATCHED`.

## Commands

```powershell
.\gradlew.bat test --no-daemon --console=plain
.\gradlew.bat bootJar --no-daemon --console=plain
docker compose config --quiet
```

## Docs

- `docs/logistics-event-contract.md`
- `docs/ledger-transaction-mapping.md`
- `docs/demo-ledger-with-logitics.md`
- `docs/portfolio-bullets.md`

## Portfolio

Archive-Ledger · Java/Spring synthetic financial event processing backend.
