package com.archiveledger.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:archive_ledger_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "archive-ledger.archiveos.enabled=false"
})
@AutoConfigureMockMvc
class LedgerApiTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    private static final AtomicLong SEQ = new AtomicLong(1);

    @Test
    void logisticsBulkEventCreatesEventTransactionAndLedgerEntries() throws Exception {
        Map<String, Object> event = logisticsEvent("Archive-Logitics", logisticsEventId(), logisticsIdempotency(), "LOGISTICS_COST_CONFIRMED",
                Map.of(
                        "routePlanId", "ROUTE-" + nextId("LOG"),
                        "shipmentId", "SHIP-" + nextId("SHIP"),
                        "totalCost", 12_340L,
                        "riskScore", 0.12,
                        "factoryId", "FAC-A",
                        "originCode", "FAC-A",
                        "destinationCode", "DC-SEOUL-01",
                        "requiresColdChain", false,
                        "delayed", false,
                        "priority", "NORMAL"
                ));

        String payload = mapper.writeValueAsString(Map.of(
                "source", "Archive-Logitics",
                "events", List.of(event)
        ));

        mvc.perform(post("/api/events/logistics/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(1))
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.duplicate").value(0))
                .andExpect(jsonPath("$.failed").value(0));

        String eventId = event.get("eventId").toString();
        assertThat(count("select count(*) from received_event where source='Archive-Logitics' and event_id=?", eventId)).isEqualTo(1);
        assertThat(count("select count(*) from finance_transaction where source_service='Archive-Logitics' and source_event_id=?", eventId)).isEqualTo(1);
        assertThat(count("select count(*) from ledger_entry le join finance_transaction ft on ft.transaction_id=le.transaction_id where ft.source_event_id=?", eventId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select transaction_type from finance_transaction where source_event_id=?", String.class, eventId))
                .isEqualTo("LOGISTICS_COST");
    }

    @Test
    void totalCostUsedAsAmountBeforeEstimatedCost() throws Exception {
        String eventId = logisticsEventId();
        String idempotency = logisticsIdempotency();
        mvc.perform(post("/api/events/logistics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(logisticsEvent("Archive-Logitics", eventId, idempotency, "LOGISTICS_COST_CONFIRMED",
                                Map.of(
                                        "routePlanId", "ROUTE-" + nextId("LOG"),
                                        "shipmentId", "SHIP-" + nextId("SHIP"),
                                        "factoryId", "FAC-B",
                                        "originCode", "FAC-B",
                                        "destinationCode", "DC-DAEJEON-01",
                                        "totalCost", 5_000L,
                                        "estimatedCost", 1_200L
                                )))))
                .andExpect(status().isOk());

        BigDecimal amount = transactionAmount(eventId);
        assertThat(amount).isEqualTo(BigDecimal.valueOf(5_000L).setScale(2));
    }

    @Test
    void estimatedCostUsedWhenTotalCostIsMissing() throws Exception {
        String eventId = logisticsEventId();
        String idempotency = logisticsIdempotency();
        mvc.perform(post("/api/events/logistics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(logisticsEvent("Archive-Logitics", eventId, idempotency, "LOGISTICS_COST_CONFIRMED",
                                Map.of(
                                        "routePlanId", "ROUTE-" + nextId("LOG"),
                                        "shipmentId", "SHIP-" + nextId("SHIP"),
                                        "factoryId", "FAC-B",
                                        "originCode", "FAC-B",
                                        "destinationCode", "DC-BUSAN-01",
                                        "estimatedCost", 4_200L
                                )))))
                .andExpect(status().isOk());

        BigDecimal amount = transactionAmount(eventId);
        assertThat(amount).isEqualTo(BigDecimal.valueOf(4_200L).setScale(2));
    }

    @Test
    void logisticsAmountMissingValidationFails() throws Exception {
        String eventId = logisticsEventId();
        String idempotency = logisticsIdempotency();
        mvc.perform(post("/api/events/logistics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(logisticsEvent("Archive-Logitics", eventId, idempotency, "LOGISTICS_COST_CONFIRMED",
                                Map.of(
                                        "routePlanId", "ROUTE-" + nextId("LOG"),
                                        "shipmentId", "SHIP-" + nextId("SHIP"),
                                        "factoryId", "FAC-C",
                                        "originCode", "FAC-C",
                                        "destinationCode", "DC-SEOUL-01"
                                )))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void duplicateEventIdPreventsDuplicateTransactionAndLedger() throws Exception {
        String eventId = logisticsEventId();
        String idempotency = logisticsIdempotency();
        String body = mapper.writeValueAsString(logisticsEvent("Archive-Logitics", eventId, idempotency, "LOGISTICS_COST_CONFIRMED", Map.of(
                "routePlanId", "ROUTE-" + nextId("LOG"),
                "shipmentId", "SHIP-" + nextId("SHIP"),
                "factoryId", "FAC-A",
                "originCode", "FAC-A",
                "destinationCode", "DC-SEOUL-01",
                "totalCost", 10_000L
        )));

        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));

        assertThat(count("select count(*) from finance_transaction where source_event_id=?", eventId)).isEqualTo(1);
        assertThat(count("select count(*) from ledger_entry le join finance_transaction ft on ft.transaction_id=le.transaction_id where ft.source_event_id=?", eventId)).isEqualTo(2);
    }

    @Test
    void duplicateIdempotencyPreventsDuplicateTransactionAndLedger() throws Exception {
        String idempotency = logisticsIdempotency();
        String e1 = logisticsEventId();
        String e2 = logisticsEventId();

        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(logisticsEvent("Archive-Logitics", e1, idempotency, "DELAY_PENALTY_CONFIRMED", Map.of(
                                "routePlanId", "ROUTE-" + nextId("LOG"),
                                "shipmentId", "SHIP-" + nextId("SHIP"),
                                "factoryId", "FAC-A",
                                "originCode", "FAC-A",
                                "destinationCode", "DC-SEOUL-01",
                                "totalCost", 10_000L
                        )))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(logisticsEvent("Archive-Logitics", e2, idempotency, "DELAY_PENALTY_CONFIRMED", Map.of(
                                "routePlanId", "ROUTE-" + nextId("LOG"),
                                "shipmentId", "SHIP-" + nextId("SHIP"),
                                "factoryId", "FAC-A",
                                "originCode", "FAC-A",
                                "destinationCode", "DC-SEOUL-01",
                                "totalCost", 20_000L
                        )))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));

        assertThat(count("select count(*) from finance_transaction where source_service='Archive-Logitics' and idempotency_key=?", idempotency)).isEqualTo(1);
    }

    @Test
    void logisticsCostConfirmedMapsAccountsToLogisticsExpenseAndAccountsPayable() throws Exception {
        String eventId = logisticsEventId();
        String idempotency = logisticsIdempotency();
        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(logisticsEvent("Archive-Logitics", eventId, idempotency, "LOGISTICS_COST_CONFIRMED", Map.of(
                                "routePlanId", "ROUTE-" + nextId("LOG"),
                                "shipmentId", "SHIP-" + nextId("SHIP"),
                                "factoryId", "FAC-A",
                                "originCode", "FAC-A",
                                "destinationCode", "DC-SEOUL-01",
                                "totalCost", 1_800L
                        )))))
                .andExpect(status().isOk());

        String transactionId = transactionId(eventId);
        List<String> accounts = accountCodes(transactionId);
        assertThat(accounts).containsExactlyInAnyOrder("LOGISTICS_EXPENSE", "ACCOUNTS_PAYABLE");
    }

    @Test
    void logisticsDailySettlementFeeUsesLedgerFeePaidAndSettlementExpenseAccount() throws Exception {
        String eventId = logisticsEventId();
        String idempotency = logisticsIdempotency();
        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(logisticsEvent("Archive-Logitics", eventId, idempotency, "LOGISTICS_DAILY_SETTLEMENT_FEE_EARNED", Map.of(
                                "settlementId", "LGS-SETTLE-" + nextId("SETTLE"),
                                "settlementCycleId", "LCYCLE-" + nextId("CYCLE"),
                                "factoryId", "FAC-A",
                                "totalCost", 90_000_000L,
                                "ledgerFeePaid", 20_000L
                        )))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        assertThat(jdbc.queryForObject("select transaction_type from finance_transaction where source_event_id=?", String.class, eventId))
                .isEqualTo("LOGISTICS_DAILY_SETTLEMENT_FEE");
        assertThat(transactionAmount(eventId)).isEqualTo(BigDecimal.valueOf(20_000L).setScale(2));
        String transactionId = transactionId(eventId);
        assertThat(accountCodes(transactionId)).containsExactlyInAnyOrder("LOGISTICS_SETTLEMENT_EXPENSE", "ACCOUNTS_PAYABLE");
        assertThat(sum("coalesce(sum(debit_amount),0)", transactionId)).isEqualTo(sum("coalesce(sum(credit_amount),0)", transactionId));
    }

    @Test
    void logisticsSourceSpellingAlsoSupportsDailySettlementFeeEvents() throws Exception {
        String eventId = logisticsEventId();
        String idempotency = logisticsIdempotency();
        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(logisticsEvent("Archive-Logistics", eventId, idempotency, "LOGISTICS_DAILY_SETTLEMENT_FEE_EARNED", Map.of(
                                "settlementId", "LGS-SETTLE-" + nextId("SETTLE"),
                                "settlementCycleId", "LCYCLE-" + nextId("CYCLE"),
                                "factoryId", "FAC-A",
                                "totalCost", 90_000_000L,
                                "ledgerFeePaid", 20_000L
                        )))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        String transactionId = transactionId(eventId);
        assertThat(jdbc.queryForObject("select source_service from finance_transaction where transaction_id=?", String.class, transactionId))
                .isEqualTo("Archive-Logistics");
        assertThat(accountCodes(transactionId)).containsExactlyInAnyOrder("LOGISTICS_SETTLEMENT_EXPENSE", "ACCOUNTS_PAYABLE");
        mvc.perform(get("/api/transactions").param("source", "Archive-Logistics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sourceEventId").value(Matchers.hasItem(eventId)));
        mvc.perform(get("/api/transactions").param("source", "Archive-Logitics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sourceEventId").value(Matchers.hasItem(eventId)));
    }

    @Test
    void urgentDeliveryCostConfirmedMapsAccountsToUrgentDeliveryExpense() throws Exception {
        String eventId = logisticsEventId();
        String idempotency = logisticsIdempotency();
        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(logisticsEvent("Archive-Logitics", eventId, idempotency, "URGENT_DELIVERY_COST_CONFIRMED", Map.of(
                                "routePlanId", "ROUTE-" + nextId("LOG"),
                                "shipmentId", "SHIP-" + nextId("SHIP"),
                                "factoryId", "FAC-B",
                                "originCode", "FAC-B",
                                "destinationCode", "DC-DAEJEON-01",
                                "priority", "HIGH",
                                "totalCost", 2_000L
                        )))))
                .andExpect(status().isOk());

        String transactionId = transactionId(eventId);
        List<String> accounts = accountCodes(transactionId);
        assertThat(accounts).containsExactlyInAnyOrder("URGENT_DELIVERY_EXPENSE", "ACCOUNTS_PAYABLE");
    }

    @Test
    void delayPenaltyConfirmedMapsAccountsToDelayPenaltyExpense() throws Exception {
        String eventId = logisticsEventId();
        String idempotency = logisticsIdempotency();
        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(logisticsEvent("Archive-Logitics", eventId, idempotency, "DELAY_PENALTY_CONFIRMED", Map.of(
                                "routePlanId", "ROUTE-" + nextId("LOG"),
                                "shipmentId", "SHIP-" + nextId("SHIP"),
                                "factoryId", "FAC-B",
                                "originCode", "FAC-B",
                                "destinationCode", "DC-BUSAN-01",
                                "totalCost", 2_500L
                        )))))
                .andExpect(status().isOk());

        String transactionId = transactionId(eventId);
        List<String> accounts = accountCodes(transactionId);
        assertThat(accounts).containsExactlyInAnyOrder("DELAY_PENALTY_EXPENSE", "ACCOUNTS_PAYABLE");
    }

    @Test
    void coldChainRiskCostIsApprovalRequired() throws Exception {
        String eventId = logisticsEventId();
        String idempotency = logisticsIdempotency();
        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(logisticsEvent("Archive-Logitics", eventId, idempotency, "COLD_CHAIN_RISK_COST_CONFIRMED", Map.of(
                                "routePlanId", "ROUTE-" + nextId("LOG"),
                                "shipmentId", "SHIP-" + nextId("SHIP"),
                                "factoryId", "FAC-B",
                                "originCode", "FAC-B",
                                "destinationCode", "DC-SEOUL-01",
                                "totalCost", 1_500L
                        )))))
                .andExpect(status().isOk());

        assertThat(transactionStatus(eventId)).isEqualTo("APPROVAL_REQUIRED");
        String tx = transactionId(eventId);
        List<String> accounts = accountCodes(tx);
        assertThat(accounts).containsExactlyInAnyOrder("COLD_CHAIN_RISK_EXPENSE", "ACCOUNTS_PAYABLE");
    }

    @Test
    void logisticsRiskScore85OrHigherRequiresApproval() throws Exception {
        String eventId = logisticsEventId();
        String idempotency = logisticsIdempotency();
        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(logisticsEvent("Archive-Logitics", eventId, idempotency, "LOGISTICS_COST_CONFIRMED", Map.of(
                                "routePlanId", "ROUTE-" + nextId("LOG"),
                                "shipmentId", "SHIP-" + nextId("SHIP"),
                                "factoryId", "FAC-A",
                                "originCode", "FAC-A",
                                "destinationCode", "DC-SEOUL-01",
                                "riskScore", 0.92,
                                "totalCost", 1_000L
                        )))))
                .andExpect(status().isOk());

        assertThat(transactionStatus(eventId)).isEqualTo("APPROVAL_REQUIRED");
    }

    @Test
    void logisticsTotalCost300000OrHigherRequiresApproval() throws Exception {
        String eventId = logisticsEventId();
        String idempotency = logisticsIdempotency();
        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(logisticsEvent("Archive-Logitics", eventId, idempotency, "LOGISTICS_COST_CONFIRMED", Map.of(
                                "routePlanId", "ROUTE-" + nextId("LOG"),
                                "shipmentId", "SHIP-" + nextId("SHIP"),
                                "factoryId", "FAC-A",
                                "originCode", "FAC-A",
                                "destinationCode", "DC-SEOUL-01",
                                "totalCost", 350_000L
                        )))))
                .andExpect(status().isOk());

        assertThat(transactionStatus(eventId)).isEqualTo("APPROVAL_REQUIRED");
    }

    @Test
    void approvalRequiredTransactionIsNotSettledInDailySettlement() throws Exception {
        String approvalEventId = logisticsEventId();
        String readyEventId = logisticsEventId();

        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(logisticsEvent("Archive-Logitics", approvalEventId, logisticsIdempotency(),
                                "COLD_CHAIN_RISK_COST_CONFIRMED", Map.of(
                                        "routePlanId", "ROUTE-" + nextId("LOG"),
                                        "shipmentId", "SHIP-" + nextId("SHIP"),
                                        "factoryId", "FAC-A",
                                        "originCode", "FAC-A",
                                        "destinationCode", "DC-SEOUL-01",
                                        "totalCost", 2_000L
                                )))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(logisticsEvent("Archive-Logitics", readyEventId, logisticsIdempotency(),
                                "LOGISTICS_COST_CONFIRMED", Map.of(
                                        "routePlanId", "ROUTE-" + nextId("LOG"),
                                        "shipmentId", "SHIP-" + nextId("SHIP"),
                                        "factoryId", "FAC-A",
                                        "originCode", "FAC-A",
                                        "destinationCode", "DC-SEOUL-01",
                                        "totalCost", 2_000L
                                )))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/settlements/daily/run").param("date", LocalDate.now().toString()))
                .andExpect(status().isOk());

        assertThat(transactionStatus(readyEventId)).isEqualTo("SETTLED");
        assertThat(transactionStatus(approvalEventId)).isEqualTo("APPROVAL_REQUIRED");
    }

    @Test
    void approvalCallbackApprovedMovesToSettlementReady() throws Exception {
        String eventId = logisticsEventId();
        String idempotency = logisticsIdempotency();
        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(logisticsEvent("Archive-Logitics", eventId, idempotency, "COLD_CHAIN_RISK_COST_CONFIRMED", Map.of(
                                "routePlanId", "ROUTE-" + nextId("LOG"),
                                "shipmentId", "SHIP-" + nextId("SHIP"),
                                "factoryId", "FAC-C",
                                "originCode", "FAC-C",
                                "destinationCode", "DC-SEOUL-01",
                                "totalCost", 2_000L
                        )))))
                .andExpect(status().isOk());

        String tx = transactionId(eventId);
        String approvalRequestId = jdbc.queryForObject("select approval_request_id from finance_transaction where transaction_id=?", String.class, tx);
        mvc.perform(post("/api/approvals/callback").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                        Map.of(
                                "approvalRequestId", approvalRequestId,
                                "transactionId", tx,
                                "decision", "APPROVED",
                                "decidedBy", "tester",
                        "comment", "approved"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLEMENT_READY"));
        assertThat(jdbc.queryForObject("select status from finance_transaction where transaction_id=?", String.class, tx)).isEqualTo("SETTLEMENT_READY");
    }

    @Test
    void approvedTransactionIsSettledByDailySettlementAndReconciliationBatch() throws Exception {
        clearTablesForDeterministicReconciliation();

        String eventId = logisticsEventId();
        String idempotency = logisticsIdempotency();
        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(logisticsEvent("Archive-Logistics", eventId, idempotency, "COLD_CHAIN_RISK_COST_CONFIRMED", Map.of(
                                "routePlanId", "ROUTE-" + nextId("LOG"),
                                "shipmentId", "SHIP-" + nextId("SHIP"),
                                "factoryId", "FAC-C",
                                "originCode", "FAC-C",
                                "destinationCode", "DC-SEOUL-01",
                                "totalCost", 2_000L
                        )))))
                .andExpect(status().isOk());

        String tx = transactionId(eventId);
        String approvalRequestId = jdbc.queryForObject("select approval_request_id from finance_transaction where transaction_id=?", String.class, tx);
        mvc.perform(post("/api/approvals/callback").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                        Map.of(
                                "approvalRequestId", approvalRequestId,
                                "transactionId", tx,
                                "decision", "APPROVED",
                                "decidedBy", "dan18",
                                "comment", "settlement approved"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLEMENT_READY"));

        mvc.perform(post("/api/batches/daily/run")
                        .param("date", LocalDate.now().toString())
                        .param("approvedBy", "dan18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.approvedBy").value("dan18"))
                .andExpect(jsonPath("$.settlementTransactionCount").value(1))
                .andExpect(jsonPath("$.reconciliationStatus").value("OK"))
                .andExpect(jsonPath("$.mismatchCount").value(0));

        assertThat(transactionStatus(eventId)).isEqualTo("SETTLED");
        assertThat(count("select count(*) from daily_batch_run where approved_by='dan18' and status='SUCCESS'")).isEqualTo(1);
    }

    @Test
    void dailyBatchExcludesApprovalRequiredTransactionsUntilCallbackApproval() throws Exception {
        clearTablesForDeterministicReconciliation();

        String approvalEventId = logisticsEventId();
        String readyEventId = logisticsEventId();

        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(logisticsEvent("Archive-Logistics", approvalEventId, logisticsIdempotency(),
                                "COLD_CHAIN_RISK_COST_CONFIRMED", Map.of(
                                        "routePlanId", "ROUTE-" + nextId("LOG"),
                                        "shipmentId", "SHIP-" + nextId("SHIP"),
                                        "factoryId", "FAC-A",
                                        "originCode", "FAC-A",
                                        "destinationCode", "DC-SEOUL-01",
                                        "totalCost", 2_000L
                                )))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(logisticsEvent("Archive-Logistics", readyEventId, logisticsIdempotency(),
                                "LOGISTICS_COST_CONFIRMED", Map.of(
                                        "routePlanId", "ROUTE-" + nextId("LOG"),
                                        "shipmentId", "SHIP-" + nextId("SHIP"),
                                        "factoryId", "FAC-A",
                                        "originCode", "FAC-A",
                                        "destinationCode", "DC-SEOUL-01",
                                        "totalCost", 2_000L
                                )))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/batches/daily/run")
                        .param("date", LocalDate.now().toString())
                        .param("approvedBy", "dan18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.settlementTransactionCount").value(1));

        assertThat(transactionStatus(readyEventId)).isEqualTo("SETTLED");
        assertThat(transactionStatus(approvalEventId)).isEqualTo("APPROVAL_REQUIRED");
    }

    @Test
    void approvalCallbackRejectedMovesToRejected() throws Exception {
        String eventId = logisticsEventId();
        String idempotency = logisticsIdempotency();
        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(logisticsEvent("Archive-Logitics", eventId, idempotency, "URGENT_DELIVERY_COST_CONFIRMED", Map.of(
                                "routePlanId", "ROUTE-" + nextId("LOG"),
                                "shipmentId", "SHIP-" + nextId("SHIP"),
                                "factoryId", "FAC-C",
                                "originCode", "FAC-C",
                                "destinationCode", "DC-SEOUL-01",
                                "priority", "CRITICAL",
                                "totalCost", 400_000L
                        )))))
                .andExpect(status().isOk());

        String tx = transactionId(eventId);
        String approvalRequestId = jdbc.queryForObject("select approval_request_id from finance_transaction where transaction_id=?", String.class, tx);
        mvc.perform(post("/api/approvals/callback").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                        Map.of(
                                "approvalRequestId", approvalRequestId,
                                "transactionId", tx,
                                "decision", "REJECTED",
                                "decidedBy", "tester",
                        "comment", "rejected"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(jdbc.queryForObject("select status from finance_transaction where transaction_id=?", String.class, tx)).isEqualTo("REJECTED");
    }

    @Test
    void logisticsTransactionDebitCreditAreBalanced() throws Exception {
        String eventId = logisticsEventId();
        String idempotency = logisticsIdempotency();
        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(logisticsEvent("Archive-Logitics", eventId, idempotency, "URGENT_DELIVERY_COST_CONFIRMED", Map.of(
                                "routePlanId", "ROUTE-" + nextId("LOG"),
                                "shipmentId", "SHIP-" + nextId("SHIP"),
                                "factoryId", "FAC-A",
                                "originCode", "FAC-A",
                                "destinationCode", "DC-SEOUL-01",
                                "totalCost", 9_999L
                        )))))
                .andExpect(status().isOk());

        String tx = transactionId(eventId);
        BigDecimal debit = sum("coalesce(sum(debit_amount),0)", tx);
        BigDecimal credit = sum("coalesce(sum(credit_amount),0)", tx);
        assertThat(debit).isEqualTo(credit);
    }

    @Test
    void compatibilityDispatchedEventFromArchiveLogiticsMapsToLogisticsCost() throws Exception {
        String eventId = logisticsEventId();
        String idempotency = logisticsIdempotency();
        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(logisticsEvent("Archive-Logitics", eventId, idempotency, "LOGISTICS_DISPATCHED", Map.of(
                                "routePlanId", "ROUTE-" + nextId("LOG"),
                                "shipmentId", "SHIP-" + nextId("SHIP"),
                                "factoryId", "FAC-A",
                                "originCode", "FAC-A",
                                "destinationCode", "DC-SEOUL-01",
                                "totalCost", 1_100L
                        )))))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select transaction_type from finance_transaction where source_event_id=?", String.class, eventId))
                .isEqualTo("LOGISTICS_COST");
    }

    @Test
    void operationsSummaryContainsLogiticsFromLogiticsCount() throws Exception {
        String eventId = logisticsEventId();
        String idempotency = logisticsIdempotency();
        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(logisticsEvent("Archive-Logitics", eventId, idempotency, "LOGISTICS_COST_CONFIRMED", Map.of(
                                "routePlanId", "ROUTE-" + nextId("LOG"),
                                "shipmentId", "SHIP-" + nextId("SHIP"),
                                "factoryId", "FAC-A",
                                "originCode", "FAC-A",
                                "destinationCode", "DC-SEOUL-01",
                                "totalCost", 2_000L
                        )))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/operations/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventsReceivedFromLogitics").value(Matchers.greaterThanOrEqualTo(1)));
        mvc.perform(get("/api/transactions").param("source", "Archive-Logitics")).andExpect(status().isOk());
        mvc.perform(get("/api/ledger/summary").param("source", "Archive-Logitics")).andExpect(status().isOk());
        mvc.perform(get("/api/events/received").param("source", "Archive-Logitics")).andExpect(status().isOk());
    }

    @Test
    void reconciliationSummaryIncludesLogisticsEventCount() throws Exception {
        String eventId = logisticsEventId();
        String idempotency = logisticsIdempotency();
        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(logisticsEvent("Archive-Logitics", eventId, idempotency, "LOGISTICS_COST_CONFIRMED", Map.of(
                                "routePlanId", "ROUTE-" + nextId("LOG"),
                                "shipmentId", "SHIP-" + nextId("SHIP"),
                                "factoryId", "FAC-A",
                                "originCode", "FAC-A",
                                "destinationCode", "DC-SEOUL-01",
                                "totalCost", 2_000L
                        )))))
                .andExpect(status().isOk());

        String summary = mvc.perform(get("/api/reconciliation/daily").param("date", LocalDate.now().toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode summaryNode = mapper.readTree(summary);
        assertThat(summaryNode.get("logisticsEventCount").asInt()).isGreaterThan(0);
        assertThat(summaryNode.get("directEventCount").asInt()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void reconciliationStatusIgnoresDuplicateEventsFromMismatchCalculation() throws Exception {
        clearTablesForDeterministicReconciliation();

        String eventId = logisticsEventId();
        String idempotency = logisticsIdempotency();
        String body = mapper.writeValueAsString(logisticsEvent("Archive-Logitics", eventId, idempotency, "LOGISTICS_COST_CONFIRMED", Map.of(
                "routePlanId", "ROUTE-" + nextId("LOG"),
                "shipmentId", "SHIP-" + nextId("SHIP"),
                "factoryId", "FAC-A",
                "originCode", "FAC-A",
                "destinationCode", "DC-SEOUL-01",
                "totalCost", 1_234L
        )));

        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));

        String summary = mvc.perform(post("/api/reconciliation/daily").param("date", LocalDate.now().toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode summaryNode = mapper.readTree(summary);
        assertThat(summaryNode.get("status").asText()).isEqualTo("OK");
        assertThat(summaryNode.get("mismatch").asInt()).isEqualTo(0);
        assertThat(summaryNode.get("duplicates").asInt()).isEqualTo(1);
    }

    @Test
    void nexusBulkStillWorks() throws Exception {
        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            String eventId = "NEXUS-BULK-" + i;
            events.add(nexusEvent(eventId, "NEXUS-ID-" + i, "MATERIAL_CONSUMED",
                    Map.of(
                            "factoryId", "FAC-A",
                            "vendorId", "VENDOR-MAINT-01",
                            "syntheticAccountId", "SYN-ACCT-" + i,
                            "estimatedCost", 100_000 + i,
                            "currency", "KRW",
                            "severity", "LOW",
                            "requiresApproval", false,
                            "reason", "bulk-test"
                    )));
        }

        mvc.perform(post("/api/events/nexus/bulk").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(events)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(1000))
                .andExpect(jsonPath("$.accepted").value(1000));
    }

    private Map<String, Object> nexusEvent(String eventId, String idempotencyKey, String eventType, Map<String, Object> payload) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("eventId", eventId);
        request.put("idempotencyKey", idempotencyKey);
        request.put("eventType", eventType);
        request.put("aggregateType", "SyntheticAggregate");
        request.put("aggregateId", "AGG-" + eventId);
        request.put("source", "Archive-Nexus");
        request.put("schemaVersion", 1);
        request.put("payload", payload);
        return request;
    }

    private Map<String, Object> logisticsEvent(String source, String eventId, String idempotencyKey, String eventType, Map<String, Object> overrides) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("factoryId", "FAC-A");
        payload.put("vendorId", "VENDOR-LOGISTICS-01");
        payload.put("syntheticAccountId", "SYN-ACCT-FAC-A-001");
        payload.put("currency", "KRW");
        payload.put("originCode", source.startsWith("Archive-Logitics") ? "FAC-A" : "FAC-B");
        payload.put("destinationCode", "DC-SEOUL-01");
        payload.put("riskScore", 0.20);
        payload.put("requiresApproval", false);
        payload.put("reason", "synthetic logistics test event");
        payload.put("priority", "NORMAL");
        payload.put("requiresColdChain", false);
        payload.put("delayed", false);
        payload.putAll(overrides);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("eventId", eventId);
        request.put("idempotencyKey", idempotencyKey);
        request.put("eventType", eventType);
        request.put("aggregateType", "SyntheticAggregate");
        request.put("aggregateId", "AGG-" + eventId);
        request.put("source", source);
        request.put("schemaVersion", 1);
        request.put("payload", payload);
        request.put("occurredAt", OffsetDateTime.now().toString());
        return request;
    }

    private String transactionId(String sourceEventId) {
        return jdbc.queryForObject("select transaction_id from finance_transaction where source_event_id=?", String.class, sourceEventId);
    }

    private String transactionStatus(String sourceEventId) {
        return jdbc.queryForObject("select status from finance_transaction where source_event_id=?", String.class, sourceEventId);
    }

    private BigDecimal transactionAmount(String sourceEventId) {
        return jdbc.queryForObject("select amount from finance_transaction where source_event_id=?", BigDecimal.class, sourceEventId);
    }

    private BigDecimal sum(String expression, String transactionId) {
        return jdbc.queryForObject("select " + expression + " from ledger_entry where transaction_id=?", BigDecimal.class, transactionId);
    }

    private List<String> accountCodes(String txId) {
        return jdbc.queryForList("select account_code from ledger_entry where transaction_id=? order by account_code", String.class, txId);
    }

    private void clearTablesForDeterministicReconciliation() {
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

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String logisticsEventId() {
        return "EVT-LG-" + nextId("RUN");
    }

    private String logisticsIdempotency() {
        return "IDEMP-LG-" + nextId("IDEMP");
    }

    private String nextId(String prefix) {
        return prefix + "-" + SEQ.getAndIncrement() + "-" + System.nanoTime();
    }
}

