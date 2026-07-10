# Settlement Agency Model for Market + Logistics + Nexus

Archive-Ledger runs as a synthetic settlement agency in the platform.
It normalizes external events into transactions and controls what becomes settlement-ready versus approval-only costs.

This model is intentionally conservative: only non-`APPROVAL_REQUIRED` / non-`REJECTED` entries are eligible for settlement.

## Settlement Eligibility

`SETTLEMENT_READY` is the only status that can be included in settlement batches.

Excluded:

- `APPROVAL_REQUIRED`
- `REJECTED`
- `FAILED`
- duplicate events (no transaction created)

## Market Events and Agency Behavior

The following Market events are treated as settlement-candidate business costs/revenue:

- `SALES_REVENUE_CONFIRMED`
- `PAYMENT_CAPTURED`
- `REFUND_REQUESTED`
- `CLAIM_COMPENSATION_CONFIRMED`

The following are pure fee/payment-cost events:

- `MARKET_SERVICE_FEE_PAID`
- `PAYMENT_PROCESSING_FEE_PAID`

`MARKET_SERVICE_FEE_PAID` and `PAYMENT_PROCESSING_FEE_PAID` are still stored as transactions with expense/lifecycle records,
but they are **not additionally re-aggregated as settlement agency charges**.

## Current safeguard

Ledger does not apply secondary settlement-fee charging on already fee events (`chargeFeesOnFeeEvents=false` in operational terminology).
This means:

- settlement agency fee is evaluated only once per normalized source settlement unit
- `..._FEE_PAID` payloads do not accumulate duplicated downstream agency costs

## Source Guarding and Loop Safety

For guarded events (including Market), Ledger records:

- `simulationRunId`
- `settlementCycleId`
- `correlationId`
- `causationId`
- `hopCount`
- `maxHop`

If `hopCount > maxHop`, processing is rejected and marked failed for that event.

## Approval Interaction

Market events can enter `APPROVAL_REQUIRED` by amount/risk rules.
Those events are recorded for callback and are excluded from settlement until callback transitions:

- `APPROVED` -> `SETTLEMENT_READY`
- `REJECTED` -> `REJECTED`

## Accounting Integrity

For every normalized transaction:

- `finance_transaction.amount > 0`
- `ledger_entry` is generated as balanced pair:
  - debit sum == credit sum by `transaction_id`

This invariant protects settlement calculations and reconciliation checks.
