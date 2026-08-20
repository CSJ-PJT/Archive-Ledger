package com.archiveledger.ledger.approval;

import com.archiveledger.ledger.common.LedgerMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AutoApprovalService {
    private static final String ACTOR_PREFIX = "ledger-policy:";
    private static final ZoneId BUDGET_ZONE = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AutoApprovalProperties properties;
    private final AutoApprovalPolicy policy;
    private final LedgerMetrics metrics;
    private final boolean h2;

    public AutoApprovalService(JdbcTemplate jdbc, ObjectMapper mapper, AutoApprovalProperties properties,
                               AutoApprovalPolicy policy, LedgerMetrics metrics) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.properties = properties;
        this.policy = policy;
        this.metrics = metrics;
        String databaseProduct = jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName());
        this.h2 = databaseProduct != null && databaseProduct.equalsIgnoreCase("H2");
    }

    @Transactional
    public Result evaluateNewIngest(AutoApprovalCandidate candidate) {
        Instant evaluatedAt = Instant.now();
        AutoApprovalPolicy.Evaluation evaluation = policy.evaluate(candidate);
        AutoApprovalProperties.Mode mode = properties.mode();
        metrics.autoApprovalEvaluated();

        if (mode == AutoApprovalProperties.Mode.DISABLED) {
            return record(candidate, evaluation, mode, "DISABLED", false, evaluatedAt, null);
        }
        if (!evaluation.eligible()) {
            metrics.autoApprovalDeferred();
            return record(candidate, evaluation, mode, "MANUAL_REQUIRED", false, evaluatedAt, null);
        }
        if (mode == AutoApprovalProperties.Mode.SHADOW) {
            metrics.autoApprovalDeferred();
            return record(candidate, evaluation, mode, "SHADOW_ELIGIBLE", false, evaluatedAt, null);
        }

        lockCandidateState(candidate);
        LocalDate budgetDate = LocalDate.now(BUDGET_ZONE);
        ensureBudgetRow(budgetDate);
        Map<String, Object> budget = jdbc.queryForMap("""
                select approved_count, approved_amount
                from auto_approval_daily_budget
                where budget_date=?
                for update
                """, Date.valueOf(budgetDate));
        int approvedCount = ((Number) budget.get("approved_count")).intValue();
        BigDecimal approvedAmount = (BigDecimal) budget.get("approved_amount");
        if (approvedCount + 1 > properties.maxDailyCount()
                || approvedAmount.add(candidate.amount()).compareTo(properties.maxDailyAmountKrw()) > 0) {
            metrics.autoApprovalDeferred();
            return record(candidate, evaluation, mode, "BUDGET_EXHAUSTED", false, evaluatedAt, null);
        }

        int approvalUpdated = jdbc.update("""
                update approval_request
                set status='APPROVED', decided_at=?, decided_by=?
                where approval_request_id=? and transaction_id=? and status='REQUESTED'
                """,
                Timestamp.from(evaluatedAt), actor(), candidate.approvalRequestId(), candidate.transactionId());
        int transactionUpdated = jdbc.update("""
                update finance_transaction
                set status='SETTLEMENT_READY', updated_at=?
                where transaction_id=? and approval_request_id=? and status='APPROVAL_REQUIRED'
                """,
                Timestamp.from(evaluatedAt), candidate.transactionId(), candidate.approvalRequestId());
        if (approvalUpdated != 1 || transactionUpdated != 1) {
            throw new IllegalStateException("Auto-approval state transition did not update exactly one matching request and transaction.");
        }
        int budgetUpdated = jdbc.update("""
                update auto_approval_daily_budget
                set approved_count=approved_count+1, approved_amount=approved_amount+?, updated_at=?
                where budget_date=?
                """, candidate.amount(), Timestamp.from(evaluatedAt), Date.valueOf(budgetDate));
        if (budgetUpdated != 1) {
            throw new IllegalStateException("Auto-approval daily budget update failed.");
        }

        metrics.autoApprovalApplied();
        return record(candidate, evaluation, mode, "AUTO_APPROVED", true, evaluatedAt, evaluatedAt);
    }

    private void lockCandidateState(AutoApprovalCandidate candidate) {
        List<Map<String, Object>> matching = jdbc.queryForList("""
                select ar.id as approval_row_id, ft.id as transaction_row_id
                from approval_request ar
                join finance_transaction ft on ft.transaction_id=ar.transaction_id
                where ar.approval_request_id=? and ar.transaction_id=? and ar.status='REQUESTED'
                  and ft.approval_request_id=ar.approval_request_id and ft.status='APPROVAL_REQUIRED'
                for update
                """, candidate.approvalRequestId(), candidate.transactionId());
        if (matching.size() != 1) {
            throw new IllegalStateException("Auto-approval candidate state or identifier binding is invalid.");
        }
    }

    private void ensureBudgetRow(LocalDate budgetDate) {
        if (h2) {
            jdbc.update("""
                    insert into auto_approval_daily_budget(budget_date,approved_count,approved_amount,updated_at)
                    select ?,0,0,?
                    where not exists (select 1 from auto_approval_daily_budget where budget_date=?)
                    """, Date.valueOf(budgetDate), Timestamp.from(Instant.now()), Date.valueOf(budgetDate));
            return;
        }
        jdbc.update("""
                insert into auto_approval_daily_budget(budget_date,approved_count,approved_amount,updated_at)
                values(?,0,0,?)
                on conflict (budget_date) do nothing
                """, Date.valueOf(budgetDate), Timestamp.from(Instant.now()));
    }

    private Result record(AutoApprovalCandidate candidate, AutoApprovalPolicy.Evaluation evaluation,
                          AutoApprovalProperties.Mode mode, String outcome, boolean applied,
                          Instant evaluatedAt, Instant appliedAt) {
        Map<String, Object> evidence = new LinkedHashMap<>(evaluation.evidence());
        evidence.put("eligible", evaluation.eligible());
        evidence.put("configurationValid", properties.valid());
        evidence.put("maxDailyCount", properties.maxDailyCount());
        evidence.put("maxDailyAmountKrw", properties.maxDailyAmountKrw());

        String decisionId = "APD-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        jdbc.update("""
                insert into approval_policy_decision(
                    decision_id,approval_request_id,transaction_id,policy_version,mode,outcome,
                    reason_codes,evidence,amount,risk_score,evaluated_at,applied_at
                ) values(?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                decisionId,
                candidate.approvalRequestId(),
                candidate.transactionId(),
                properties.policyVersion(),
                mode.name(),
                outcome,
                json(evaluation.reasonCodes()),
                json(evidence),
                candidate.amount(),
                candidate.riskScore(),
                Timestamp.from(evaluatedAt),
                appliedAt == null ? null : Timestamp.from(appliedAt));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("decisionId", decisionId);
        detail.put("policyVersion", properties.policyVersion());
        detail.put("mode", mode.name());
        detail.put("outcome", outcome);
        detail.put("reasonCodes", evaluation.reasonCodes());
        detail.put("amount", candidate.amount());
        detail.put("riskScore", candidate.riskScore());
        audit(candidate.transactionId(), "AUTO_APPROVAL_POLICY_EVALUATED", "approval_policy_decision", decisionId,
                "REQUESTED", applied ? "APPROVED" : "REQUESTED", detail, evaluatedAt);
        if (applied) {
            audit(candidate.transactionId(), "AUTO_APPROVAL_APPLIED", "approval_request", candidate.approvalRequestId(),
                    "REQUESTED", "APPROVED", detail, evaluatedAt);
        }
        return new Result(applied, outcome, decisionId);
    }

    private void audit(String traceId, String action, String targetType, String targetId,
                       String before, String after, Map<String, Object> detail, Instant createdAt) {
        jdbc.update("""
                insert into audit_log(trace_id,actor,action,target_type,target_id,before_status,after_status,detail,created_at)
                values(?,?,?,?,?,?,?,?,?)
                """, traceId, actor(), action, targetType, targetId, before, after, json(detail), Timestamp.from(createdAt));
    }

    private String actor() {
        return ACTOR_PREFIX + properties.policyVersion();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Auto-approval evidence serialization failed.", error);
        }
    }

    public record Result(boolean applied, String outcome, String decisionId) {
    }
}
