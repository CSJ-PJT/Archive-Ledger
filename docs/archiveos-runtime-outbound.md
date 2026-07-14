# ArchiveOS Runtime Outbound

Archive-Ledger keeps the Runtime Mesh pull endpoints and can additionally publish selected Ledger runtime events to ArchiveOS Live Flow.

## Boundary

- Endpoint: `POST ${ARCHIVEOS_BASE_URL}/api/live-flow/events/ingest`
- Headers: `Authorization: Bearer ${ARCHIVE_TOKEN_LEDGER_TO_OS}`, `X-Archive-Source-System: archive-ledger`, and `X-Archive-Service-Scope: runtime:ingest`.
- Approval dispatch (`ARCHIVEOS_APPROVAL_ENABLED`) and runtime ingest (`ARCHIVEOS_RUNTIME_INGEST_ENABLED`) are independent settings.
- The outbound outbox is persisted after projection capture. HTTP delivery happens later and never participates in Ledger business transactions.

## Delivery state

`PENDING` and `RETRY` are eligible for delivery. Any 2xx response, including `duplicate=true`, becomes `PUBLISHED`. HTTP 401/403 becomes `CONFIG_ERROR`; other 4xx becomes `NON_RETRYABLE_ERROR`; timeout, 408, 429, and 5xx use bounded backoff and eventually become `FAILED`.

## Safe envelope

The payload has canonical `sourceSystem=archive-ledger` and `targetSystem=archiveos`, while preserving event ID, idempotency key, correlation, causation, simulation run, settlement cycle, and workday context. Ledger forwards `orderId` only when it exists in inbound metadata, the source transaction event, or a unique correlation context. It never substitutes `entityId`, `transactionId`, or `settlementCycleId`, and independent settlement/reconciliation events retain `orderId=null`.

ArchiveOS currently validates `orderId` as required in `InternalRuntimeIngestService`. Ledger therefore correctly queues an independent event with null order lineage, but ArchiveOS will classify that delivery as a non-retryable 400 until its ingest DTO permits nullable `orderId` for `sourceSystem=archive-ledger` non-order lifecycle events. The minimal ArchiveOS correction is to keep `eventId`, `correlationId`, `causationId`, and `entityId` mandatory while allowing `orderId` to be null; no placeholder should be introduced.

## Read-only inspection

- `GET /api/runtime-outbound/summary`
- `GET /api/runtime-outbound/events?status=FAILED`
- `GET /api/runtime-outbound/correlation/{correlationId}/preview`

These endpoints neither generate nor publish events.
