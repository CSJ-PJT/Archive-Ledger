-- Repair historic partial writes before enforcing the runtime balance invariant.
-- Current ingestion creates both entries in one transaction; this migration only affects prior mismatches.
insert into audit_log(trace_id, actor, action, target_type, target_id, before_status, after_status, detail, created_at)
select transaction_id,
       'Archive-Ledger-Migration',
       'LEDGER_BALANCE_REPAIRED',
       'finance_transaction',
       transaction_id,
       'UNBALANCED',
       'BALANCE_REPAIR_PENDING',
       '{"reason":"historic partial ledger entry"}',
       current_timestamp
from (
  select ft.transaction_id,
         coalesce(sum(le.debit_amount), 0) as debit_total,
         coalesce(sum(le.credit_amount), 0) as credit_total
  from finance_transaction ft
  join ledger_entry le on le.transaction_id = ft.transaction_id
  group by ft.transaction_id
  having coalesce(sum(le.debit_amount), 0) <> coalesce(sum(le.credit_amount), 0)
) imbalance;

insert into ledger_entry(transaction_id, account_code, account_name, debit_amount, credit_amount, factory_id, vendor_id, occurred_at, created_at)
select transaction_id,
       case when debit_total > credit_total then
         case transaction_type
           when 'CORPORATE_CARD_EXPENSE' then 'CORPORATE_CARD_PAYABLE'
           when 'VENDOR_PAYMENT' then 'CASH_CLEARING'
           when 'SALES_REVENUE' then 'SALES_REVENUE'
           when 'PAYMENT_CAPTURE' then 'ACCOUNTS_RECEIVABLE'
           when 'SALES_REFUND' then 'REFUND_PAYABLE'
           when 'PAYMENT_PROCESSING_FEE' then 'CASH'
           else 'ACCOUNTS_PAYABLE'
         end
       else
         case transaction_type
           when 'LOGISTICS_COST' then 'LOGISTICS_EXPENSE'
           when 'URGENT_DELIVERY_COST' then 'URGENT_DELIVERY_EXPENSE'
           when 'DELAY_PENALTY' then 'DELAY_PENALTY_EXPENSE'
           when 'ROUTE_DEVIATION_COST' then 'ROUTE_DEVIATION_EXPENSE'
           when 'COLD_CHAIN_RISK_COST' then 'COLD_CHAIN_RISK_EXPENSE'
           when 'SALES_REVENUE' then 'ACCOUNTS_RECEIVABLE'
           when 'PAYMENT_CAPTURE' then 'CASH'
           when 'SALES_REFUND' then 'SALES_REFUND'
           when 'CLAIM_COMPENSATION_EXPENSE' then 'CLAIM_COMPENSATION_EXPENSE'
           when 'MARKET_SERVICE_FEE' then 'MARKET_SERVICE_FEE_EXPENSE'
           when 'PAYMENT_PROCESSING_FEE' then 'PAYMENT_PROCESSING_EXPENSE'
           when 'MAINTENANCE_EXPENSE' then 'MAINTENANCE_EXPENSE'
           when 'QUALITY_LOSS' then 'QUALITY_LOSS'
           when 'QUALITY_CLAIM_CHARGEBACK' then 'QUALITY_LOSS'
           when 'SHIPMENT_HOLD_COST' then 'LOGISTICS_EXPENSE'
           when 'MATERIAL_COST' then 'MATERIAL_COST'
           else 'OPERATING_EXPENSE'
         end
       end,
       'Synthetic Ledger Balance Repair',
       case when credit_total > debit_total then credit_total - debit_total else 0 end,
       case when debit_total > credit_total then debit_total - credit_total else 0 end,
       factory_id,
       vendor_id,
       occurred_at,
       current_timestamp
from (
  select ft.transaction_id,
         ft.transaction_type,
         ft.factory_id,
         ft.vendor_id,
         ft.occurred_at,
         coalesce(sum(le.debit_amount), 0) as debit_total,
         coalesce(sum(le.credit_amount), 0) as credit_total
  from finance_transaction ft
  join ledger_entry le on le.transaction_id = ft.transaction_id
  group by ft.transaction_id, ft.transaction_type, ft.factory_id, ft.vendor_id, ft.occurred_at
  having coalesce(sum(le.debit_amount), 0) <> coalesce(sum(le.credit_amount), 0)
) imbalance;
