# Market Event Contract

Archive-Ledger receives commerce settlement-related events from **Archive-Market** via native endpoints.

This integration uses synthetic/demo data only. No real payment data, customer personal data, or real account data is used.

## Event Endpoints

- `POST /api/events/market`
- `POST /api/events/market/bulk`

`POST /api/events/market` accepts a single event envelope.
`POST /api/events/market/bulk` accepts:

```json
{
  "source": "Archive-Market",
  "events": [
    { ...event envelope... }
  ]
}
```

## Single Event Example

```json
{
  "eventId": "evt-market-smoke-001",
  "idempotencyKey": "MARKET:SALES_REVENUE_CONFIRMED:ORDER-001",
  "source": "Archive-Market",
  "eventType": "SALES_REVENUE_CONFIRMED",
  "schemaVersion": 1,
  "occurredAt": "2026-01-15T10:45:00.000Z",
  "payload": {
    "orderId": "ORDER-001",
    "amount": 120000,
    "currency": "KRW",
    "factoryId": "FAC-A",
    "vendorId": "VENDOR-MARKET-01",
    "originCode": "FAC-A",
    "destinationCode": "DC-SEOUL-01",
    "riskScore": 0.30,
    "requiresApproval": false,
    "customerType": "B2B"
  }
}
```

## Supported Event Types

- `SALES_REVENUE_CONFIRMED`
- `PAYMENT_CAPTURED`
- `REFUND_REQUESTED`
- `CLAIM_COMPENSATION_CONFIRMED`
- `MARKET_SERVICE_FEE_PAID`
- `PAYMENT_PROCESSING_FEE_PAID`

## eventType → transactionType Mapping

| Event Type | transactionType |
| --- | --- |
| `SALES_REVENUE_CONFIRMED` | `SALES_REVENUE` |
| `PAYMENT_CAPTURED` | `PAYMENT_CAPTURE` |
| `REFUND_REQUESTED` | `SALES_REFUND` |
| `CLAIM_COMPENSATION_CONFIRMED` | `CLAIM_COMPENSATION_EXPENSE` |
| `MARKET_SERVICE_FEE_PAID` | `MARKET_SERVICE_FEE` |
| `PAYMENT_PROCESSING_FEE_PAID` | `PAYMENT_PROCESSING_FEE` |

## Amount Resolution

For Market events, Ledger requires amount in the following order:

1. `payload.amount`

If amount is missing or <= 0, the item fails validation and processing status becomes `FAILED`.

`payload.currency` is optional and defaults to `KRW`.

## Approval Logic

`APPROVAL_REQUIRED` is set when any rule is true:

- `payload.requiresApproval = true`
- `eventType = REFUND_REQUESTED` and `amount >= 300000`
- `eventType = CLAIM_COMPENSATION_CONFIRMED` and `amount >= 300000`
- `payload.riskScore >= 0.85`
- `payload.highRiskCustomer = true`
- `customer riskLevel >= 4`

Otherwise the transaction is created as `SETTLEMENT_READY`.

## Settlement Eligibility

- `APPROVAL_REQUIRED` / `REJECTED` / `FAILED` transactions are excluded from settlement.
- Approved transactions move to `SETTLEMENT_READY` and participate in settlement.

## Ledger Account Mapping

| transactionType | debit | credit |
| --- | --- | --- |
| `SALES_REVENUE` | `ACCOUNTS_RECEIVABLE` | `SALES_REVENUE` |
| `PAYMENT_CAPTURE` | `CASH` | `ACCOUNTS_RECEIVABLE` |
| `SALES_REFUND` | `SALES_REFUND` | `REFUND_PAYABLE` |
| `CLAIM_COMPENSATION_EXPENSE` | `CLAIM_COMPENSATION_EXPENSE` | `ACCOUNTS_PAYABLE` |
| `MARKET_SERVICE_FEE` | `MARKET_SERVICE_FEE_EXPENSE` | `ACCOUNTS_PAYABLE` |
| `PAYMENT_PROCESSING_FEE` | `PAYMENT_PROCESSING_EXPENSE` | `CASH` |

For every accepted Market transaction, `debit == credit` is enforced in Ledger entries by transaction construction.

## Duplicate and Loop Guards

Archive-Ledger applies the same duplicate and loop protections:

- `eventId` + `idempotencyKey` duplicate => safe duplicate response, no duplicate transaction/ledger rows
- Market source duplicate guard by `(source_service, event_type, correlation_id)`
- `hopCount > maxHop` => rejected / failed for that event

## Supported source filters

The following endpoints support `source=Archive-Market`:

- `GET /api/events/received?source=Archive-Market`
- `GET /api/transactions?source=Archive-Market`
- `GET /api/ledger/summary?source=Archive-Market`

Summary APIs also include Market counters:

- `eventsReceivedFromMarket`
- `marketRevenueTransactions`
- `paymentCaptureTransactions`
- `refundTransactions`
- `claimCompensationTransactions`

Reconciliation includes:

- `marketEventCount`
- `marketTransactionCount`

## Compatibility note

`source = Archive-Logitics` remains kept for compatibility where older payloads/contracts are still in use in the ecosystem.
