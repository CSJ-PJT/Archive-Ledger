# Ledger Transaction Mapping

This document describes how accepted events become finance transactions and double-entry ledger rows.

## Logistics Event Mapping

| Logistics eventType | transactionType |
| --- | --- |
| `LOGISTICS_COST_CONFIRMED` | `LOGISTICS_COST` |
| `URGENT_DELIVERY_COST_CONFIRMED` | `URGENT_DELIVERY_COST` |
| `DELAY_PENALTY_CONFIRMED` | `DELAY_PENALTY` |
| `ROUTE_DEVIATION_COST_CONFIRMED` | `ROUTE_DEVIATION_COST` |
| `COLD_CHAIN_RISK_COST_CONFIRMED` | `COLD_CHAIN_RISK_COST` |
| `LOGISTICS_DAILY_SETTLEMENT_FEE_EARNED` | `LOGISTICS_DAILY_SETTLEMENT_FEE` |
| `LOGISTICS_DISPATCHED` with logistics source `Archive-Logitics` or `Archive-Logistics` | `LOGISTICS_COST` |

## Logistics Ledger Accounts

| transactionType | debit account | credit account |
| --- | --- | --- |
| `LOGISTICS_COST` | `LOGISTICS_EXPENSE` | `ACCOUNTS_PAYABLE` |
| `URGENT_DELIVERY_COST` | `URGENT_DELIVERY_EXPENSE` | `ACCOUNTS_PAYABLE` |
| `DELAY_PENALTY` | `DELAY_PENALTY_EXPENSE` | `ACCOUNTS_PAYABLE` |
| `ROUTE_DEVIATION_COST` | `ROUTE_DEVIATION_EXPENSE` | `ACCOUNTS_PAYABLE` |
| `COLD_CHAIN_RISK_COST` | `COLD_CHAIN_RISK_EXPENSE` | `ACCOUNTS_PAYABLE` |
| `LOGISTICS_DAILY_SETTLEMENT_FEE` | `LOGISTICS_SETTLEMENT_EXPENSE` | `ACCOUNTS_PAYABLE` |

## Market Event Mapping

| Market eventType | transactionType |
| --- | --- |
| `SALES_REVENUE_CONFIRMED` | `SALES_REVENUE` |
| `PAYMENT_CAPTURED` | `PAYMENT_CAPTURE` |
| `REFUND_REQUESTED` | `SALES_REFUND` |
| `CLAIM_COMPENSATION_CONFIRMED` | `CLAIM_COMPENSATION_EXPENSE` |
| `MARKET_SERVICE_FEE_PAID` | `MARKET_SERVICE_FEE` |
| `PAYMENT_PROCESSING_FEE_PAID` | `PAYMENT_PROCESSING_FEE` |

## Market Ledger Accounts

| transactionType | debit account | credit account |
| --- | --- | --- |
| `SALES_REVENUE` | `ACCOUNTS_RECEIVABLE` | `SALES_REVENUE` |
| `PAYMENT_CAPTURE` | `CASH` | `ACCOUNTS_RECEIVABLE` |
| `SALES_REFUND` | `SALES_REFUND` | `REFUND_PAYABLE` |
| `CLAIM_COMPENSATION_EXPENSE` | `CLAIM_COMPENSATION_EXPENSE` | `ACCOUNTS_PAYABLE` |
| `MARKET_SERVICE_FEE` | `MARKET_SERVICE_FEE_EXPENSE` | `ACCOUNTS_PAYABLE` |
| `PAYMENT_PROCESSING_FEE` | `PAYMENT_PROCESSING_EXPENSE` | `CASH` |

## Direct Nexus Mapping

Direct Nexus events retain the existing Ledger direct mapping. Examples include maintenance, shipment hold, emergency purchase, and corporate card-style synthetic operating expenses.

## Balance Invariant

For every accepted transaction:

```text
sum(ledger_entry.debit_amount where transaction_id = X)
==
sum(ledger_entry.credit_amount where transaction_id = X)
```

Settlement and reconciliation assume this invariant. Any future account mapping change should be covered by a test that verifies debit/credit balance by `transaction_id`.
