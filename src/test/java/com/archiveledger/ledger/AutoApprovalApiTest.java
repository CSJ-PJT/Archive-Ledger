package com.archiveledger.ledger;

import com.archiveledger.ledger.approval.ArchiveOsApprovalClient;
import com.archiveledger.ledger.approval.AutoApprovalCandidate;
import com.archiveledger.ledger.approval.AutoApprovalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:archive_ledger_auto_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "archive-ledger.archiveos.enabled=true",
        "archive-ledger.auto-approval.mode=ENFORCE",
        "archive-ledger.auto-approval.max-daily-count=20",
        "archive-ledger.auto-approval.max-daily-amount-krw=10000000",
        "archive.runtime.autorun.enabled=false"
})
@AutoConfigureMockMvc
class AutoApprovalApiTest {
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AutoApprovalService autoApprovals;

    @MockitoBean ArchiveOsApprovalClient archiveOs;

    @BeforeEach
    void resetState() {
        reset(archiveOs);
        clearTables();
    }

    @Test
    void actualLogisticsHighSeverityAmountOnlyPayloadIsAtomicallyAutoApprovedWithoutDispatch() throws Exception {
        String eventId = next("AUTO");

        ingest(eventId, validPayload(350_000, 0.20));

        String transactionId = transactionId(eventId);
        Map<String, Object> decision = jdbc.queryForMap(
                "select mode,outcome,reason_codes,evidence from approval_policy_decision where transaction_id=?", transactionId);
        assertThat(transactionStatus(transactionId)).as(decision.toString()).isEqualTo("SETTLEMENT_READY");
        assertThat(jdbc.queryForObject("select status from approval_request where transaction_id=?", String.class, transactionId))
                .isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select decided_by from approval_request where transaction_id=?", String.class, transactionId))
                .isEqualTo("ledger-policy:logistics-amount-only-v1");
        assertThat(jdbc.queryForObject("select outcome from approval_policy_decision where transaction_id=?", String.class, transactionId))
                .isEqualTo("AUTO_APPROVED");
        assertThat(count("select count(*) from audit_log where action='AUTO_APPROVAL_APPLIED' and trace_id=?", transactionId)).isEqualTo(1);
        verifyNoInteractions(archiveOs);
    }

    @Test
    void nonEligibleRiskRemainsManualAndIsDispatched() throws Exception {
        String eventId = next("RISK");

        ingest(eventId, validPayload(350_000, 0.60));

        String transactionId = transactionId(eventId);
        assertThat(transactionStatus(transactionId)).isEqualTo("APPROVAL_REQUIRED");
        assertThat(jdbc.queryForObject("select status from approval_request where transaction_id=?", String.class, transactionId))
                .isEqualTo("REQUESTED");
        assertThat(jdbc.queryForObject("select outcome from approval_policy_decision where transaction_id=?", String.class, transactionId))
                .isEqualTo("MANUAL_REQUIRED");
        verify(archiveOs, times(1)).requestApproval(anyString(), anyString(), any(BigDecimal.class), anyString(), anyString(), any());
    }

    @Test
    void dailyCanaryBudgetApprovesOnlyFirstTwentyAndDefersTheRest() throws Exception {
        for (int i = 0; i < 21; i++) {
            ingest(next("BUDGET"), validPayload(300_000, 0.20));
        }

        assertThat(count("select count(*) from approval_policy_decision where outcome='AUTO_APPROVED'")).isEqualTo(20);
        assertThat(count("select count(*) from approval_policy_decision where outcome='BUDGET_EXHAUSTED'")).isEqualTo(1);
        assertThat(count("select count(*) from finance_transaction where status='SETTLEMENT_READY'")).isEqualTo(20);
        assertThat(count("select count(*) from finance_transaction where status='APPROVAL_REQUIRED'")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select approved_count from auto_approval_daily_budget", Integer.class)).isEqualTo(20);
        assertThat(jdbc.queryForObject("select approved_amount from auto_approval_daily_budget", BigDecimal.class))
                .isEqualByComparingTo("6000000");
        verify(archiveOs, times(1)).requestApproval(anyString(), anyString(), any(BigDecimal.class), anyString(), anyString(), any());
    }

    @Test
    void existingRequestedRowsAreNeverScannedWhenNewEventsAreEvaluated() throws Exception {
        String oldTransactionId = "TX-HISTORIC-" + next("TX");
        String oldApprovalId = "APR-HISTORIC-" + next("APR");
        Instant old = Instant.parse("2026-01-01T00:00:00Z");
        jdbc.update("""
                insert into finance_transaction(
                    transaction_id,source_event_id,idempotency_key,source_service,transaction_type,amount,currency,status,
                    approval_required,approval_request_id,reason,occurred_at,created_at,updated_at
                ) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, oldTransactionId, "EVT-HISTORIC-" + next("EVT"), "IDEMP-HISTORIC-" + next("IDEMP"),
                "Archive-Logistics", "LOGISTICS_COST", new BigDecimal("350000"), "KRW", "APPROVAL_REQUIRED",
                true, oldApprovalId, "historic", Timestamp.from(old), Timestamp.from(old), Timestamp.from(old));
        jdbc.update("""
                insert into approval_request(approval_request_id,transaction_id,requested_to,status,amount,reason,policy_evidence,requested_at)
                values(?,?,?,?,?,?,?,?)
                """, oldApprovalId, oldTransactionId, "synthetic-finance-operator", "REQUESTED",
                new BigDecimal("350000"), "historic", "historic", Timestamp.from(old));

        ingest(next("NEW"), validPayload(350_000, 0.20));

        assertThat(transactionStatus(oldTransactionId)).isEqualTo("APPROVAL_REQUIRED");
        assertThat(jdbc.queryForObject("select status from approval_request where approval_request_id=?", String.class, oldApprovalId))
                .isEqualTo("REQUESTED");
        assertThat(count("select count(*) from approval_policy_decision where approval_request_id=?", oldApprovalId)).isZero();
    }

    @Test
    void decisionWriteFailureRollsBackBothStateTransitionsAndDailyBudget() {
        String transactionId = "TX-ROLLBACK-" + next("TX");
        String approvalId = "APR-ROLLBACK-" + next("APR");
        Instant now = Instant.now();
        jdbc.update("""
                insert into finance_transaction(
                    transaction_id,source_event_id,idempotency_key,source_service,transaction_type,amount,currency,status,
                    approval_required,approval_request_id,reason,occurred_at,created_at,updated_at
                ) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, transactionId, "EVT-ROLLBACK-" + next("EVT"), "IDEMP-ROLLBACK-" + next("IDEMP"),
                "Archive-Logistics", "LOGISTICS_COST", new BigDecimal("350000"), "KRW", "APPROVAL_REQUIRED",
                true, approvalId, "Approval required: totalCost>=300000", Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                insert into approval_request(approval_request_id,transaction_id,requested_to,status,amount,reason,policy_evidence,requested_at)
                values(?,?,?,?,?,?,?,?)
                """, approvalId, transactionId, "synthetic-finance-operator", "REQUESTED",
                new BigDecimal("350000"), "Approval required: totalCost>=300000", "preseeded", Timestamp.from(now));
        jdbc.update("""
                insert into approval_policy_decision(
                    decision_id,approval_request_id,transaction_id,policy_version,mode,outcome,reason_codes,evidence,
                    amount,risk_score,evaluated_at
                ) values(?,?,?,?,?,?,?,?,?,?,?)
                """, "APD-PRESEEDED-" + next("APD"), approvalId, transactionId,
                "logistics-amount-only-v1", "SHADOW", "SHADOW_ELIGIBLE", "[]", "{}",
                new BigDecimal("350000"), new BigDecimal("0.20"), Timestamp.from(now));

        AutoApprovalCandidate candidate = new AutoApprovalCandidate(
                approvalId, transactionId, "Archive-Logistics", "LOGISTICS_COST_CONFIRMED", "LOGISTICS_COST",
                new BigDecimal("350000"), "KRW", new BigDecimal("0.20"), "HIGH",
                "Approval required: totalCost>=300000", true, validPayload(350_000, 0.20));

        assertThatThrownBy(() -> autoApprovals.evaluateNewIngest(candidate)).isInstanceOf(RuntimeException.class);

        assertThat(transactionStatus(transactionId)).isEqualTo("APPROVAL_REQUIRED");
        assertThat(jdbc.queryForObject("select status from approval_request where approval_request_id=?", String.class, approvalId))
                .isEqualTo("REQUESTED");
        assertThat(count("select count(*) from auto_approval_daily_budget")).isZero();
        assertThat(count("select count(*) from audit_log where action='AUTO_APPROVAL_APPLIED' and trace_id=?", transactionId)).isZero();
    }

    private void ingest(String eventId, Map<String, Object> payload) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("eventId", eventId);
        request.put("idempotencyKey", "IDEMP-" + eventId);
        request.put("eventType", "LOGISTICS_COST_CONFIRMED");
        request.put("aggregateType", "SyntheticAggregate");
        request.put("aggregateId", "AGG-" + eventId);
        request.put("source", "Archive-Logitics");
        request.put("schemaVersion", 1);
        request.put("payload", payload);
        request.put("occurredAt", OffsetDateTime.now().toString());
        mvc.perform(post("/api/events/logistics")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    private Map<String, Object> validPayload(int amount, double risk) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("routePlanId", "ROUTE-" + next("ROUTE"));
        payload.put("shipmentId", "SHIP-" + next("SHIP"));
        payload.put("factoryId", "FAC-A");
        payload.put("originCode", "FAC-A");
        payload.put("destinationCode", "DC-SEOUL-01");
        payload.put("totalCost", amount);
        payload.put("estimatedCost", amount);
        payload.put("currency", "KRW");
        payload.put("riskScore", risk);
        payload.put("requiresApproval", true);
        payload.put("severity", "HIGH");
        payload.put("priority", "NORMAL");
        payload.put("delayed", false);
        payload.put("deviated", false);
        payload.put("requiresColdChain", false);
        payload.put("coldChainPenalty", 0);
        payload.put("urgentSurcharge", 0);
        payload.put("delayPenalty", 0);
        payload.put("reason", "Synthetic logistics route cost calculated");
        payload.put("routeStatus", "PLANNED");
        payload.put("sourceService", "Archive-Logistics");
        return payload;
    }

    private String transactionId(String eventId) {
        return jdbc.queryForObject("select transaction_id from finance_transaction where source_event_id=?", String.class, eventId);
    }

    private String transactionStatus(String transactionId) {
        return jdbc.queryForObject("select status from finance_transaction where transaction_id=?", String.class, transactionId);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private void clearTables() {
        jdbc.execute("delete from approval_policy_decision");
        jdbc.execute("delete from auto_approval_daily_budget");
        jdbc.execute("delete from ledger_runtime_balance_snapshot");
        jdbc.execute("delete from ledger_workforce_allocation");
        jdbc.execute("delete from workforce_workday_result");
        jdbc.execute("delete from workforce_allocation");
        jdbc.execute("delete from daily_batch_run");
        jdbc.execute("delete from reconciliation_result");
        jdbc.execute("delete from settlement_detail");
        jdbc.execute("delete from settlement_batch");
        jdbc.execute("delete from approval_request");
        jdbc.execute("delete from audit_log");
        jdbc.execute("delete from ledger_entry");
        jdbc.execute("delete from finance_transaction");
        jdbc.execute("delete from received_event");
    }

    private static String next(String prefix) {
        return prefix + "-" + LocalDate.now() + "-" + SEQ.incrementAndGet();
    }
}
