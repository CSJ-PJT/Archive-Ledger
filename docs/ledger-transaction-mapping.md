# Ledger Transaction Mapping (Logistics)

This document defines how logistics events are normalized into finance transactions and ledger accounts.

## Logistics event -> transactionType

| Logistics event type | transactionType |
| --- | --- |
| LOGISTICS_COST_CONFIRMED | LOGISTICS_COST |
| URGENT_DELIVERY_COST_CONFIRMED | URGENT_DELIVERY_COST |
| DELAY_PENALTY_CONFIRMED | DELAY_PENALTY |
| ROUTE_DEVIATION_COST_CONFIRMED | ROUTE_DEVIATION_COST |
| COLD_CHAIN_RISK_COST_CONFIRMED | COLD_CHAIN_RISK_COST |
| LOGISTICS_DISPATCHED (Archive-Logistics compatibility source `Archive-Logitics`) | LOGISTICS_COST |

## Ledger debit / credit mapping

| transactionType | debit account | credit account |
| --- | --- | --- |
| LOGISTICS_COST | LOGISTICS_EXPENSE | ACCOUNTS_PAYABLE |
| URGENT_DELIVERY_COST | URGENT_DELIVERY_EXPENSE | ACCOUNTS_PAYABLE |
| DELAY_PENALTY | DELAY_PENALTY_EXPENSE | ACCOUNTS_PAYABLE |
| ROUTE_DEVIATION_COST | ROUTE_DEVIATION_EXPENSE | ACCOUNTS_PAYABLE |
| COLD_CHAIN_RISK_COST | COLD_CHAIN_RISK_EXPENSE | ACCOUNTS_PAYABLE |

Notes:

- direct Nexus event mappings remain unchanged
- each transaction always creates two entries (debit and credit)
- debit/credit totals are equal by construction

## Settlement / approval behavior

- `APPROVAL_REQUIRED`: excluded from settlement
- `SETTLEMENT_READY`: included in daily settlement
- Approval callback:
  - `APPROVED` -> `SETTLEMENT_READY`
  - `REJECTED` -> `REJECTED`

Source handling:

- `source=Archive-Nexus` uses direct mapping as implemented in existing rules.
- Archive-Logistics events use the logistics mapping above and are included in source-level reconciliation (`logisticsTransactionCount`).
- `source=Archive-Logitics` remains the compatibility source value used by existing event payloads and query examples.
