# Settlement Agency Profit + Bankruptcy Prevention Game

This is a Synthetic Data / Demo Data simulation namespace. It does not use real financial, user,
card, account, logistics, or map data.

The game models Archive Platform Ecosystem economics:

- Archive-Nexus earns manufacturing revenue and pays material, maintenance, quality, Logistics, and settlement costs.
- Archive-Logistics earns logistics service and daily settlement fees from Nexus, then pays Ledger for financial processing.
- Archive-Ledger earns settlement-agency fees and pays operating costs.
- ArchiveOS observes cash balance, burn rate, bankruptcy risk, and agent proposals. Agents propose only; writes require safe-mode / approval / user decision.

## Namespace

Operational APIs remain separate from game/simulation APIs.

```http
GET  /api/game/settlement-agency/preset
POST /api/game/settlement-agency/simulate
```

## Required simulation metadata

Every game event contains:

- `simulationRunId`
- `settlementCycleId`
- `tickId`
- `day`
- `correlationId`
- `hop`
- `maxHop`

`maxHop` prevents recursive ecosystem flows from becoming an infinite loop.

## Ledger revenue model

Archive-Ledger can generate synthetic settlement-agency revenue through:

- transaction processing fee
- daily settlement agency fee
- reconciliation verification fee
- approval review fee
- exception handling fee
- early settlement fee
- delayed settlement penalty revenue

Ledger also has costs:

- transaction processing operating cost
- settlement batch run cost
- reconciliation run cost
- callback failure cost
- mismatch investigation cost
- infra fixed cost

## Example request

```json
{
  "simulationRunId": "SIM-RUN-DEMO-001",
  "settlementCycleId": "CYCLE-DAY-001",
  "tickId": "TICK-001",
  "day": 1,
  "correlationId": "CORR-DEMO-001",
  "maxHop": 4,
  "nexusInitialCash": 50000000,
  "logisticsInitialCash": 30000000,
  "ledgerInitialCash": 25000000,
  "nexusProductionRevenue": 18000000,
  "nexusMaterialCost": 6200000,
  "nexusMaintenanceCost": 1700000,
  "nexusQualityLossCost": 900000,
  "logisticsServiceFee": 2100000,
  "logisticsDailySettlementFee": 350000,
  "shipmentCount": 120,
  "transactionCount": 180,
  "settlementBatchCount": 1,
  "reconciliationCount": 1,
  "approvalReviewCount": 9,
  "exceptionCount": 4,
  "callbackFailureCount": 2,
  "mismatchCount": 1,
  "ledgerTransactionProcessingFee": 1200,
  "ledgerDailySettlementAgencyFee": 450000,
  "ledgerReconciliationVerificationFee": 250000,
  "ledgerApprovalReviewFee": 80000,
  "ledgerExceptionHandlingFee": 150000,
  "ledgerEarlySettlementFee": 220000,
  "ledgerDelayedSettlementPenaltyRevenue": 100000,
  "ledgerProcessingOperatingCost": 350,
  "ledgerSettlementBatchRunCost": 160000,
  "ledgerReconciliationRunCost": 120000,
  "ledgerCallbackFailureCost": 60000,
  "ledgerMismatchInvestigationCost": 180000,
  "ledgerInfraFixedCost": 950000
}
```

## Response interpretation

The response contains:

- ecosystem cash balance and daily profit
- bankruptcy risk: `LOW`, `WARNING`, or `CRITICAL`
- per-service economics for Nexus, Logistics, Ledger
- synthetic game events with idempotency keys
- agent proposals

Agent proposals are not execution commands. ArchiveOS must treat them as suggestions and route real writes through safe-mode, approval, and user decision controls.
