create table if not exists approval_policy_decision (
  id bigserial primary key,
  decision_id varchar(80) not null unique,
  approval_request_id varchar(80) not null,
  transaction_id varchar(80) not null,
  policy_version varchar(120) not null,
  mode varchar(20) not null,
  outcome varchar(40) not null,
  reason_codes text not null,
  evidence text not null,
  amount numeric(19,2) not null,
  risk_score numeric(10,4),
  evaluated_at timestamp not null,
  applied_at timestamp,
  constraint approval_policy_decision_request_fk
    foreign key (approval_request_id) references approval_request(approval_request_id),
  constraint approval_policy_decision_request_version_uq
    unique (approval_request_id, policy_version)
);

create index if not exists approval_policy_decision_outcome_idx
  on approval_policy_decision(outcome, evaluated_at desc);

create table if not exists auto_approval_daily_budget (
  budget_date date primary key,
  approved_count integer not null default 0,
  approved_amount numeric(19,2) not null default 0,
  updated_at timestamp not null
);

alter table ledger_runtime_balance_snapshot
  add column if not exists backlog_exposure numeric(19,2) not null default 0;
