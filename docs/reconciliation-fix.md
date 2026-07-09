# Reconciliation Mismatch Handling

## Problem

Duplicate event delivery is a normal idempotency scenario. A duplicate event should not create a second transaction or another pair of ledger entries.

If reconciliation counts all received and duplicate observations as expected transactions, duplicate-only days can show a mismatch even when Ledger behaved correctly.

## Current Formula

Ledger computes expected transactions after excluding duplicate observations:

```text
expectedTransactionCount = max(0, received - duplicate)
mismatch = max(0, expectedTransactionCount - created - failed)
status = mismatch == 0 ? OK : WARNING
```

## Meaning

- `received`: events persisted in `received_event` for the date
- `duplicate`: duplicate observations recorded in audit log for the date
- `created`: transactions created for the date
- `failed`: failed received events for the date
- `mismatch`: accepted non-duplicate events that did not produce a transaction and were not recorded as failed

## Expected Status

- `OK`: no unexplained missing transaction
- `WARNING`: one or more non-duplicate, non-failed events did not produce a transaction

## Regression Coverage

The test suite includes a duplicate reconciliation regression case:

- post a Logistics event
- post the same event again
- run daily reconciliation
- assert duplicate count is recorded
- assert `mismatch=0`
- assert `status=OK`

## Operator Action

When mismatch is non-zero:

1. Check failed events for the reconciliation date.
2. Check duplicate audit entries.
3. Compare `received_event` and `finance_transaction.source_event_id`.
4. Re-run reconciliation after confirming event processing state.
5. Investigate only non-duplicate, non-failed events that do not have a matching transaction.
