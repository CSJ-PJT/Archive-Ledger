# Workforce Event Contract

Archive-Ledger workforce APIs are designed for future ArchiveOS orchestration.

## Allocation API

```http
POST /api/workforce/allocations
Content-Type: application/json
```

```json
{
  "workdayId": "LEDGER-WORKDAY-20260710",
  "workDate": "2026-07-10",
  "sourceService": "ArchiveOS",
  "targetService": "Archive-Ledger",
  "roleType": "SETTLEMENT_OPERATOR",
  "allocatedHeadcount": 2,
  "capacityPerPersonPerDay": 120,
  "productivityScore": 1.0,
  "wagePerDay": 120000,
  "enabled": true,
  "simulationRunId": "SIM-WF-001",
  "settlementCycleId": "CYCLE-WF-001",
  "correlationId": "CORR-WF-001",
  "causationId": "CAUSE-WF-001",
  "hopCount": 1,
  "maxHop": 8
}
```

Legacy aliases are accepted for compatibility:

- `role` -> `roleType`
- `assignedUnits` -> `allocatedHeadcount`
- `unitCostKrw` -> `wagePerDay`
- `productivityMultiplier` -> `productivityScore`

## Workday API

```http
POST /api/workforce/workday/run?date=2026-07-10&sourceService=ArchiveOS
```

The response includes:

- `transactionsBacklog`
- `approvalBacklog`
- `settlementBacklog`
- `reconciliationBacklog`
- `callbackBacklog`
- `payrollCost`
- `productivityScore`
- `bottleneckRole`

## Common event meanings

Ledger records these operational events through audit log actions:

- `WORKFORCE_ALLOCATION_ASSIGNED`
- `TRANSACTION_BACKLOG_INCREASED`
- `SETTLEMENT_DELAYED`
- `RECONCILIATION_BACKLOG_INCREASED`
- `APPROVAL_BACKLOG_INCREASED`
- `CALLBACK_RETRY_DELAYED`
- `CAPACITY_SHORTAGE_DETECTED`
- `LEDGER_WORKFORCE_PAYROLL_COST_INCURRED`
- `SETTLEMENT_BACKLOG_COST_INCURRED`
- `RECONCILIATION_DELAY_COST_INCURRED`
- `APPROVAL_BACKLOG_COST_INCURRED`
- `CALLBACK_DELAY_COST_INCURRED`

## Loop safety

- `workdayId + roleType` duplicate allocation is rejected by DB uniqueness.
- `hopCount > maxHop` is rejected.
- workforce cost events are not re-ingested as normal transaction events.
- existing event idempotency rules remain unchanged.
