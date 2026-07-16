package com.archiveledger.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.archiveledger.ledger.runtime.RuntimeOutboundService;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        "archive-ledger.archiveos.enabled=false",
        "archive.runtime.autorun.enabled=false"
})
@AutoConfigureMockMvc
class LedgerApiTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LedgerService ledger;

    @Autowired
    RuntimeOutboundService runtimeOutbound;

    private static final AtomicLong SEQ = new AtomicLong(1);

    @Test
    void runtimeOutboundCaptureIsDuplicateSafeAndReadOnlyApisExposeStoredSnapshots() throws Exception {
        String eventId = logisticsEventId();
        mvc.perform(post("/api/events/logistics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(logisticsEvent("Archive-Logitics", eventId, logisticsIdempotency(),
                                "LOGISTICS_COST_CONFIRMED", Map.of(
                                        "routePlanId", "ROUTE-" + nextId("OUT"), "shipmentId", "SHIP-" + nextId("OUT"),
                                        "factoryId", "FAC-A", "originCode", "FAC-A", "destinationCode", "DC-SEOUL-01",
                                        "totalCost", 12_000L)))) )
                .andExpect(status().isOk());

        int captured = runtimeOutbound.captureRuntimeEvents(500);
        assertThat(captured).isGreaterThan(0);
        int capturedAgain = runtimeOutbound.captureRuntimeEvents(500);
        assertThat(capturedAgain).isZero();

        String correlationId = runtimeOutbound.records(null, 500).stream()
                .filter(record -> record.preview().metadata().containsValue(eventId)
                        || record.preview().entityId() != null)
                .map(record -> record.preview().correlationId())
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElseThrow();
        mvc.perform(get("/api/runtime-outbound/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(Matchers.greaterThanOrEqualTo(1)));
        mvc.perform(get("/api/runtime-outbound/correlation/{correlationId}/preview", correlationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].preview.sourceSystem").value("archive-ledger"));

        assertThat(count("select count(*) from archiveos_runtime_outbox where event_id like 'rt-%'"))
                .isGreaterThan(0);
    }

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
        String correlationId = "CORR-APPROVAL-CALLBACK-" + nextId("CORR");
        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(logisticsEvent("Archive-Logitics", eventId, idempotency, "COLD_CHAIN_RISK_COST_CONFIRMED", Map.of(
                                "routePlanId", "ROUTE-" + nextId("LOG"),
                                "shipmentId", "SHIP-" + nextId("SHIP"),
                                "factoryId", "FAC-C",
                                "originCode", "FAC-C",
                                "destinationCode", "DC-SEOUL-01",
                                "totalCost", 2_000L,
                                "correlationId", correlationId
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
        mvc.perform(get("/api/runtime-events/correlation/{correlationId}", correlationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].correlationId", Matchers.everyItem(Matchers.is(correlationId))))
                .andExpect(jsonPath("$[*].eventType", Matchers.hasItems(
                        "APPROVAL_APPROVED", "SETTLEMENT_READY", "CALLBACK_COMPLETED")));
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
    void marketSalesRevenueEventFromArchiveMarketCreatesSalesTransactionAndAccounts() throws Exception {
        String eventId = logisticsEventId().replace("LG", "MK");
        String idempotency = logisticsIdempotency().replace("LG", "MK");
        mvc.perform(post("/api/events/market").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(marketEvent("Archive-Market", eventId, idempotency, "SALES_REVENUE_CONFIRMED", Map.of(
                                "orderId", "ORDER-" + nextId("ORD"),
                                "factoryId", "FAC-A",
                                "originCode", "FAC-A",
                                "destinationCode", "DC-SEOUL-01",
                                "amount", 120_000L
                        )))))
                .andExpect(status().isOk());

        String transactionId = transactionId(eventId);
        assertThat(jdbc.queryForObject("select transaction_type from finance_transaction where transaction_id=?", String.class, transactionId))
                .isEqualTo("SALES_REVENUE");
        assertThat(accountCodes(transactionId)).containsExactlyInAnyOrder("ACCOUNTS_RECEIVABLE", "SALES_REVENUE");
        assertThat(transactionAmount(eventId)).isEqualTo(BigDecimal.valueOf(120_000L).setScale(2));
    }

    @Test
    void marketPaymentCapturedMapsToCashAndAccountsReceivable() throws Exception {
        String eventId = logisticsEventId().replace("LG", "MK");
        String idempotency = logisticsIdempotency().replace("LG", "MK");
        mvc.perform(post("/api/events/market").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(marketEvent("Archive-Market", eventId, idempotency, "PAYMENT_CAPTURED", Map.of(
                                "orderId", "ORDER-" + nextId("ORD"),
                                "factoryId", "FAC-B",
                                "originCode", "FAC-B",
                                "destinationCode", "DC-SEOUL-01",
                                "amount", 58_000L
                        )))))
                .andExpect(status().isOk());

        String transactionId = transactionId(eventId);
        assertThat(jdbc.queryForObject("select transaction_type from finance_transaction where transaction_id=?", String.class, transactionId))
                .isEqualTo("PAYMENT_CAPTURE");
        assertThat(accountCodes(transactionId)).containsExactlyInAnyOrder("CASH", "ACCOUNTS_RECEIVABLE");
    }

    @Test
    void marketRefundRequestedBecomesSalesRefundAndRequiresApprovalWhenOverThreshold() throws Exception {
        String eventId = logisticsEventId().replace("LG", "MK");
        String idempotency = logisticsIdempotency().replace("LG", "MK");
        mvc.perform(post("/api/events/market").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(marketEvent("Archive-Market", eventId, idempotency, "REFUND_REQUESTED", Map.of(
                                "returnId", "RET-" + nextId("RET"),
                                "orderId", "ORDER-" + nextId("ORD"),
                                "amount", 350_000L,
                                "riskScore", 0.15
                        )))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        assertThat(transactionStatus(eventId)).isEqualTo("APPROVAL_REQUIRED");
        assertThat(accountCodes(transactionId(eventId))).containsExactlyInAnyOrder("SALES_REFUND", "REFUND_PAYABLE");
    }

    @Test
    void marketClaimCompensationHighRiskRequiresApprovalAndExcludesSettlement() throws Exception {
        clearTablesForDeterministicReconciliation();

        String approvalEventId = logisticsEventId().replace("LG", "MK");
        String readyEventId = logisticsEventId().replace("LG", "MK");

        mvc.perform(post("/api/events/market").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(marketEvent("Archive-Market", approvalEventId, logisticsIdempotency().replace("LG", "MK"),
                                "CLAIM_COMPENSATION_CONFIRMED", Map.of(
                                        "claimId", "CLM-" + nextId("CLM"),
                                        "orderId", "ORDER-" + nextId("ORD"),
                                        "customerType", "VIP",
                                        "highRiskCustomer", true,
                                        "amount", 420_000L,
                                        "currency", "KRW"
                                )))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        mvc.perform(post("/api/events/market").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(marketEvent("Archive-Market", readyEventId, logisticsIdempotency().replace("LG", "MK"),
                                "SALES_REVENUE_CONFIRMED", Map.of(
                                        "orderId", "ORDER-" + nextId("ORD"),
                                        "amount", 200_000L
                                )))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        mvc.perform(post("/api/batches/daily/run")
                        .param("date", LocalDate.now().toString())
                        .param("approvedBy", "dan18"))
                .andExpect(status().isOk());
        assertThat(transactionStatus(approvalEventId)).isEqualTo("APPROVAL_REQUIRED");
        assertThat(transactionStatus(readyEventId)).isEqualTo("SETTLED");
    }

    @Test
    void marketFeeEventsAreProcessedWithFeeAccountsAndNotDoubleCharged() throws Exception {
        String eventId = logisticsEventId().replace("LG", "MK");
        String idempotency = logisticsIdempotency().replace("LG", "MK");
        mvc.perform(post("/api/events/market").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(marketEvent("Archive-Market", eventId, idempotency, "MARKET_SERVICE_FEE_PAID", Map.of(
                                "orderId", "ORDER-" + nextId("ORD"),
                                "amount", 55_000L,
                                "vendorId", "VENDOR-SERVICE-01"
                        )))))
                .andExpect(status().isOk());

        String tx = transactionId(eventId);
        assertThat(accountCodes(tx)).containsExactlyInAnyOrder("MARKET_SERVICE_FEE_EXPENSE", "ACCOUNTS_PAYABLE");
        assertThat(jdbc.queryForObject("select status from finance_transaction where transaction_id=?", String.class, tx))
                .isEqualTo("SETTLEMENT_READY");
        assertThat(sum("coalesce(sum(debit_amount),0)", tx)).isEqualTo(sum("coalesce(sum(credit_amount),0)", tx));
    }

    @Test
    void marketAmountMissingReturnsFailed() throws Exception {
        String eventId = logisticsEventId().replace("LG", "MK");
        String idempotency = logisticsIdempotency().replace("LG", "MK");
        mvc.perform(post("/api/events/market").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(marketEvent("Archive-Market", eventId, idempotency, "PAYMENT_CAPTURED", Map.of(
                                "orderId", "ORDER-" + nextId("ORD"),
                                "factoryId", "FAC-A"
                        )))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void marketDuplicateIdempotencyNotCreateDuplicateEntries() throws Exception {
        String eventId = logisticsEventId().replace("LG", "MK");
        String idempotency = logisticsIdempotency().replace("LG", "MK");
        String body = mapper.writeValueAsString(marketEvent("Archive-Market", eventId, idempotency, "PAYMENT_PROCESSING_FEE_PAID", Map.of(
                "orderId", "ORDER-" + nextId("ORD"),
                "amount", 20_000L,
                "paymentId", "PAY-" + nextId("PAY")
        )));

        mvc.perform(post("/api/events/market").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        mvc.perform(post("/api/events/market").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));

        assertThat(count("select count(*) from finance_transaction where source_event_id=?", eventId)).isEqualTo(1);
    }

    @Test
    void marketOperationSummaryAndReconciliationIncludeMarketCounts() throws Exception {
        mvc.perform(post("/api/events/market").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(marketEvent("Archive-Market", logisticsEventId().replace("LG", "MK"), logisticsIdempotency().replace("LG", "MK"),
                                "PAYMENT_CAPTURED", Map.of("orderId", "ORDER-" + nextId("ORD"), "amount", 30_000L)))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/operations/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventsReceivedFromMarket").value(Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.paymentCaptureTransactions").value(Matchers.greaterThanOrEqualTo(1)));

        String summary = mvc.perform(get("/api/reconciliation/daily").param("date", LocalDate.now().toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode summaryNode = mapper.readTree(summary);
        assertThat(summaryNode.get("marketEventCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(summaryNode.get("marketTransactionCount").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void runtimeEventsExposeRecentCorrelationEntityAndOperationsLiveFlowContract() throws Exception {
        String eventId = logisticsEventId().replace("LG", "MK");
        String idempotency = logisticsIdempotency().replace("LG", "MK");
        String orderId = "ORDER-LIVE-" + nextId("ORD");
        String correlationId = "CORR-LIVE-" + nextId("CORR");

        mvc.perform(post("/api/events/market").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(marketEvent("Archive-Market", eventId, idempotency,
                                "SALES_REVENUE_CONFIRMED", Map.of(
                                        "orderId", orderId,
                                        "amount", 120_000L,
                                        "currency", "KRW",
                                        "simulationRunId", "SIM-LIVE",
                                        "settlementCycleId", "CYCLE-LIVE",
                                        "correlationId", correlationId,
                                        "causationId", "CAUSE-LIVE",
                                        "hopCount", 1,
                                        "maxHop", 8
                                )))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/runtime-events/recent").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").exists())
                .andExpect(jsonPath("$[0].sourceService").exists())
                .andExpect(jsonPath("$[0].domain").exists())
                .andExpect(jsonPath("$[0].eventType").exists())
                .andExpect(jsonPath("$[0].entityType").exists())
                .andExpect(jsonPath("$[0].entityId").exists())
                .andExpect(jsonPath("$[0].status").exists())
                .andExpect(jsonPath("$[0].severity").exists());

        mvc.perform(get("/api/runtime-events/correlation/{correlationId}", correlationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].correlationId").value(correlationId))
                .andExpect(jsonPath("$[0].metadata.simulationRunId").value("SIM-LIVE"))
                .andExpect(jsonPath("$[*].eventType", Matchers.hasItems("MARKET_REVENUE_RECEIVED", "TRANSACTION_CREATED", "LEDGER_ENTRY_CREATED", "SETTLEMENT_READY")))
                .andExpect(jsonPath("$[0].metadata.amount").doesNotExist())
                .andExpect(jsonPath("$[0].metadata.amountBucket").exists());

        mvc.perform(get("/api/runtime-events/entity/{entityId}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].entityId").value(orderId));

        runtimeOutbound.captureRuntimeEvents(500);
        assertThat(runtimeOutbound.records(null, 500).stream()
                .filter(record -> correlationId.equals(record.preview().correlationId()))
                .filter(record -> "TRANSACTION_CREATED".equals(record.eventType()))
                .map(record -> record.preview().orderId())
                .distinct())
                .containsExactly(orderId);

        mvc.perform(get("/api/operations/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceName").value("Archive-Ledger"))
                .andExpect(jsonPath("$.serviceRole").exists())
                .andExpect(jsonPath("$.latestEventAt").exists())
                .andExpect(jsonPath("$.outbox.pending").value(Matchers.greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.economy.revenue").exists())
                .andExpect(jsonPath("$.runtimeWorkforce.effectiveCapacity").exists())
                .andExpect(jsonPath("$.transactionsReceived").value(Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.transactionsProcessed").value(Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.settlementReady").value(Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.callbackFailed").exists())
                .andExpect(jsonPath("$.workforce.approvalReviewerCapacity").exists())
                .andExpect(jsonPath("$.workforce.settlementOperatorCapacity").exists())
                .andExpect(jsonPath("$.liveFlowAvailable").value(true));
    }

    @Test
    void independentReconciliationOutboundKeepsOrderIdNullAndDoesNotFabricateOrderLineage() {
        clearTablesForDeterministicReconciliation();
        ledger.reconcile(LocalDate.now());

        runtimeOutbound.captureRuntimeEvents(500);
        assertThat(runtimeOutbound.records(null, 500).stream()
                .filter(record -> record.eventType().startsWith("RECONCILIATION_"))
                .map(record -> record.preview().orderId())
                .distinct())
                .containsExactly((String) null);
        assertThat(runtimeOutbound.records(null, 500).stream()
                .map(record -> record.preview().orderId())
                .filter(value -> value != null)
                .noneMatch(value -> value.startsWith("LEDGER-")))
                .isTrue();
    }

    @Test
    void runtimeEventsExposeApprovalSettlementReconciliationAndWorkforceProjection() throws Exception {
        clearTablesForDeterministicReconciliation();
        LocalDate workDate = LocalDate.now();
        String eventId = logisticsEventId();
        String idempotency = logisticsIdempotency();
        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(logisticsEvent("Archive-Logistics", eventId, idempotency, "COLD_CHAIN_RISK_COST_CONFIRMED", Map.of(
                                "routePlanId", "ROUTE-" + nextId("LOG"),
                                "shipmentId", "SHIP-" + nextId("SHIP"),
                                "factoryId", "FAC-A",
                                "originCode", "FAC-A",
                                "destinationCode", "DC-SEOUL-01",
                                "totalCost", 400_000L,
                                "riskScore", 0.91,
                                "correlationId", "CORR-APPROVAL-" + nextId("CORR")
                        )))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        mvc.perform(post("/api/reconciliation/daily").param("date", workDate.toString()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/workforce/workday/run")
                        .param("date", workDate.toString())
                        .param("sourceService", "ArchiveOS"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/runtime-events/entity/{entityId}", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].eventType", Matchers.hasItems("LOGISTICS_COST_RECEIVED", "TRANSACTION_CREATED", "LEDGER_ENTRY_CREATED", "APPROVAL_REQUIRED")))
                .andExpect(jsonPath("$[*].displayLabel", Matchers.hasItem("승인 대기 거래 1건")));

        mvc.perform(get("/api/runtime-events/recent").param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].eventType", Matchers.hasItems("RECONCILIATION_OK", "WORKDAY_COMPLETED")));
    }

    @Test
    void correlationIdIsPreservedAcrossTransactionLedgerSettlementAndReconciliation() throws Exception {
        clearTablesForDeterministicReconciliation();
        LocalDate workDate = LocalDate.now();
        String eventId = logisticsEventId().replace("LG", "MK");
        String idempotency = logisticsIdempotency().replace("LG", "MK");
        String correlationId = "CORR-SETTLEMENT-" + nextId("CORR");
        String cycleId = "CYCLE-SETTLEMENT-" + nextId("CYCLE");

        Map<String, Object> request = marketEvent("Archive-Market", eventId, idempotency,
                "SALES_REVENUE_CONFIRMED", Map.of(
                        "orderId", "ORDER-" + nextId("ORD"),
                        "amount", 120_000L,
                        "currency", "KRW"
                ));
        request.put("correlationId", correlationId);
        request.put("causationId", "MARKET-ORDER-" + nextId("CAUSE"));
        request.put("settlementCycleId", cycleId);

        mvc.perform(post("/api/events/market").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));

        String transactionId = transactionId(eventId);
        assertThat(jdbc.queryForObject("select correlation_id from finance_transaction where transaction_id=?", String.class, transactionId))
                .isEqualTo(correlationId);
        assertThat(jdbc.queryForObject("select count(*) from ledger_entry where transaction_id=? and correlation_id=?", Integer.class,
                transactionId, correlationId)).isEqualTo(2);
        assertThat(sum("sum(debit_amount)", transactionId)).isEqualByComparingTo(sum("sum(credit_amount)", transactionId));

        mvc.perform(post("/api/settlements/daily/run").param("date", workDate.toString()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/reconciliation/daily").param("date", workDate.toString()))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select correlation_id from settlement_detail where transaction_id=?", String.class, transactionId))
                .isEqualTo(correlationId);
        mvc.perform(get("/api/runtime-events/correlation/{correlationId}", correlationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].correlationId", Matchers.everyItem(Matchers.is(correlationId))))
                .andExpect(jsonPath("$[*].eventType", Matchers.hasItems(
                        "TRANSACTION_CREATED", "LEDGER_ENTRY_CREATED", "SETTLEMENT_READY",
                        "SETTLEMENT_STARTED", "SETTLEMENT_COMPLETED", "RECONCILIATION_OK")))
                .andExpect(jsonPath("$[*].settlementCycleId", Matchers.hasItem(cycleId)));
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
        clearTablesForDeterministicReconciliation();
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
    void workforceAllocationIncreasesLedgerCapacityAndCost() throws Exception {
        LocalDate workDate = LocalDate.of(2031, 1, 2);
        String workdayId = "LEDGER-WORKDAY-" + nextId("WF");

        mvc.perform(get("/api/workforce/summary")
                        .param("date", workDate.toString())
                        .param("sourceService", "ArchiveOS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workforceEnabled").value(false))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.totalHeadcount").value(0))
                .andExpect(jsonPath("$.roles").isArray())
                .andExpect(jsonPath("$.baselineCapacity").value(500))
                .andExpect(jsonPath("$.allocatedCapacity").value(500))
                .andExpect(jsonPath("$.effectiveCapacity").value(0))
                .andExpect(jsonPath("$.usedCapacity").value(0))
                .andExpect(jsonPath("$.remainingCapacity").value(0));

        mvc.perform(get("/api/productivity/summary")
                        .param("date", workDate.toString())
                        .param("sourceService", "ArchiveOS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("Archive-Ledger"))
                .andExpect(jsonPath("$.productivityScore").exists())
                .andExpect(jsonPath("$.transactionsProcessed").exists())
                .andExpect(jsonPath("$.reconciliationWarnings").exists());

        Map<String, Object> allocation = new LinkedHashMap<>();
        allocation.put("workdayId", workdayId);
        allocation.put("workDate", workDate.toString());
        allocation.put("sourceService", "ArchiveOS");
        allocation.put("role", "APPROVAL_REVIEWER");
        allocation.put("assignedUnits", 2);
        allocation.put("unitCostKrw", 100_000);
        allocation.put("productivityMultiplier", 1.0);
        allocation.put("enabled", true);
        allocation.put("simulationRunId", "SIM-WF");
        allocation.put("settlementCycleId", "CYCLE-WF");
        allocation.put("correlationId", "CORR-WF-" + nextId("CORR"));
        allocation.put("causationId", "CAUSE-WF");
        allocation.put("hopCount", 1);
        allocation.put("maxHop", 8);

        mvc.perform(post("/api/workforce/allocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(allocation)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceService").value("ArchiveOS"))
                .andExpect(jsonPath("$.role").value("APPROVAL_REVIEWER"))
                .andExpect(jsonPath("$.assignedUnits").value(2));

        mvc.perform(get("/api/capacity/summary")
                        .param("date", workDate.toString())
                        .param("sourceService", "ArchiveOS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workforceEnabled").value(true))
                .andExpect(jsonPath("$.activeAllocations").value(1))
                .andExpect(jsonPath("$.assignedUnits").value(2))
                .andExpect(jsonPath("$.totalHeadcount").value(2))
                .andExpect(jsonPath("$.allocatedCapacity").value(560))
                .andExpect(jsonPath("$.effectiveCapacity").value(60))
                .andExpect(jsonPath("$.approvalReviewerCapacity").value(60))
                .andExpect(jsonPath("$.dailyOperatingCostKrw").value(200000.00));
    }

    @Test
    void workforceSummaryApisAreReadOnlyAndExposeApprovalBottleneck() throws Exception {
        clearTablesForDeterministicReconciliation();
        LocalDate workDate = LocalDate.of(2031, 1, 5);
        insertSyntheticTransactions(workDate, "APPROVAL_REQUIRED", 35);
        long beforeAudit = count("select count(*) from audit_log");
        long beforeWorkday = count("select count(*) from ledger_workday_result");
        long beforeSettlement = count("select count(*) from settlement_batch");

        mvc.perform(get("/api/workforce/summary")
                        .param("date", workDate.toString())
                        .param("sourceService", "ArchiveOS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HEALTHY"))
                .andExpect(jsonPath("$.approvalBacklog").value(5))
                .andExpect(jsonPath("$.bottleneckRole").value("APPROVAL_REVIEWER"))
                .andExpect(jsonPath("$.latestEventAt").exists());

        mvc.perform(get("/api/capacity/summary")
                        .param("date", workDate.toString())
                        .param("sourceService", "ArchiveOS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacityUtilizationRate").exists())
                .andExpect(jsonPath("$.remainingCapacity").exists());

        assertThat(count("select count(*) from audit_log")).isEqualTo(beforeAudit);
        assertThat(count("select count(*) from ledger_workday_result")).isEqualTo(beforeWorkday);
        assertThat(count("select count(*) from settlement_batch")).isEqualTo(beforeSettlement);
    }

    @Test
    void autonomousRuntimeTickCreatesLimitedWorkEventAndIsDuplicateSafe() throws Exception {
        clearTablesForDeterministicReconciliation();
        insertSyntheticTransactions(LocalDate.now(), "APPROVAL_REQUIRED", 3);

        var first = ledger.autonomousRuntimeTick("LEDGER-RUNTIME-TICK-TEST", 1, 50);
        assertThat(first.eventsProduced()).isLessThanOrEqualTo(1);
        assertThat(first.duplicate()).isFalse();
        assertThat(count("select count(*) from ledger_workday_result where workday_id=?", "LEDGER-RUNTIME-TICK-TEST")).isEqualTo(1);
        assertThat(count("select count(*) from finance_transaction where status='APPROVAL_REQUIRED'")).isEqualTo(3);

        long workdayCount = count("select count(*) from ledger_workday_result");
        var second = ledger.autonomousRuntimeTick("LEDGER-RUNTIME-TICK-TEST", 1, 50);
        assertThat(second.duplicate()).isTrue();
        assertThat(second.eventsProduced()).isEqualTo(0);
        assertThat(count("select count(*) from ledger_workday_result")).isEqualTo(workdayCount);

        mvc.perform(get("/api/runtime/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("Archive-Ledger"))
                .andExpect(jsonPath("$.lastWorkAt").exists())
                .andExpect(jsonPath("$.lastEventAt").exists())
                .andExpect(jsonPath("$.eventsProducedLastTick").value(0))
                .andExpect(jsonPath("$.oldestBacklogAgeSeconds").exists())
                .andExpect(jsonPath("$.latestCursor").exists())
                .andExpect(jsonPath("$.pipelineStatus").exists());
    }

    @Test
    void runtimeEventsExposeRuntimeMeshHeadersAndSupportCursorPolling() throws Exception {
        clearTablesForDeterministicReconciliation();
        String eventId = logisticsEventId().replace("LG", "MK");
        String orderId = "ORDER-MESH-" + nextId("ORD");

        mvc.perform(post("/api/events/market")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(marketEvent("Archive-Market", eventId,
                                logisticsIdempotency().replace("LG", "MK"), "SALES_REVENUE_CONFIRMED", Map.of(
                                        "orderId", orderId,
                                        "amount", 80_000L,
                                        "currency", "KRW",
                                        "simulationRunId", "SIM-MESH",
                                        "settlementCycleId", "CYCLE-MESH",
                                        "correlationId", "CORR-MESH-" + nextId("CORR"),
                                        "causationId", "CAUSE-MESH",
                                        "workdayId", "WORKDAY-MESH",
                                        "hopCount", 1,
                                        "maxHop", 5
                                )))))
                .andExpect(status().isOk());

        String response = mvc.perform(get("/api/runtime-events/recent").param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").exists())
                .andExpect(jsonPath("$[0].idempotencyKey").exists())
                .andExpect(jsonPath("$[0].sourceService").exists())
                .andExpect(jsonPath("$[0].targetService").exists())
                .andExpect(jsonPath("$[0].simulationRunId").exists())
                .andExpect(jsonPath("$[0].settlementCycleId").exists())
                .andExpect(jsonPath("$[0].hopCount").exists())
                .andExpect(jsonPath("$[0].maxHop").exists())
                .andExpect(jsonPath("$[0].cursor").exists())
                .andReturn().getResponse().getContentAsString();
        JsonNode events = mapper.readTree(response);
        String oldestCursor = events.get(events.size() - 1).get("cursor").asText();

        mvc.perform(get("/api/runtime-events/recent").param("after", oldestCursor).param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cursor").exists());
    }

    @Test
    void workforceWorkdayReportsBacklogAndProductivity() throws Exception {
        clearTablesForDeterministicReconciliation();
        LocalDate workDate = LocalDate.of(2031, 1, 3);
        insertSettlementReadyTransactions(workDate, 505);

        mvc.perform(post("/api/workforce/workday/run")
                        .param("date", workDate.toString())
                        .param("sourceService", "ArchiveOS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baselineCapacity").value(500))
                .andExpect(jsonPath("$.allocatedCapacity").value(500))
                .andExpect(jsonPath("$.demandCount").value(505))
                .andExpect(jsonPath("$.processedCount").value(50))
                .andExpect(jsonPath("$.backlogCount").value(455))
                .andExpect(jsonPath("$.status").value("BOTTLENECK_DETECTED"));

        String workdayId = "LEDGER-WORKDAY-" + nextId("WF");
        mvc.perform(post("/api/workforce/allocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "workdayId", workdayId,
                                "workDate", workDate.toString(),
                                "sourceService", "ArchiveOS",
                                "role", "SETTLEMENT_OPERATOR",
                                "assignedUnits", 5,
                                "capacityPerPersonPerDay", 120,
                                "unitCostKrw", 120_000,
                                "productivityMultiplier", 1.0,
                                "enabled", true
                        ))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/workforce/workday/run")
                        .param("date", workDate.toString())
                        .param("sourceService", "ArchiveOS")
                        .param("workdayId", workdayId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allocatedCapacity").value(1100))
                .andExpect(jsonPath("$.demandCount").value(505))
                .andExpect(jsonPath("$.processedCount").value(505))
                .andExpect(jsonPath("$.backlogCount").value(0))
                .andExpect(jsonPath("$.status").value("WORKDAY_COMPLETED"));
    }

    @Test
    void ledgerWorkforceCreatesRoleSpecificBacklogsAndSettlementAgencyCost() throws Exception {
        clearTablesForDeterministicReconciliation();
        LocalDate workDate = LocalDate.of(2031, 1, 4);
        for (int i = 0; i < 3; i++) {
            insertReceivedEvent(workDate, "EVT-WF-RCV-" + i + "-" + nextId("EVT"));
        }
        insertSyntheticTransactions(workDate, "SETTLEMENT_READY", 4);
        insertSyntheticTransactions(workDate, "APPROVAL_REQUIRED", 2);
        insertApprovalRequests(workDate, 2);
        insertReconciliationResult(workDate, 2);

        String workdayId = "LEDGER-WORKDAY-20310104";
        mvc.perform(post("/api/workforce/allocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "workdayId", workdayId,
                                "workDate", workDate.toString(),
                                "sourceService", "ArchiveOS",
                                "roleType", "TRANSACTION_PROCESSOR",
                                "role", "TRANSACTION_PROCESSOR",
                                "allocatedHeadcount", 1,
                                "capacityPerPersonPerDay", 2,
                                "wagePerDay", 100_000,
                                "productivityScore", 1.0,
                                "enabled", true
                        ))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/workforce/allocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "workdayId", workdayId,
                                "workDate", workDate.toString(),
                                "sourceService", "ArchiveOS",
                                "roleType", "LEDGER_ACCOUNTANT",
                                "role", "LEDGER_ACCOUNTANT",
                                "allocatedHeadcount", 1,
                                "capacityPerPersonPerDay", 2,
                                "wagePerDay", 110_000,
                                "productivityScore", 1.0,
                                "enabled", true
                        ))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/workforce/workday/run")
                        .param("date", workDate.toString())
                        .param("sourceService", "ArchiveOS")
                        .param("workdayId", workdayId + "-RUN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionsReceived").value(3))
                .andExpect(jsonPath("$.transactionsProcessed").value(2))
                .andExpect(jsonPath("$.transactionsBacklog").value(1))
                .andExpect(jsonPath("$.settlementBacklog").value(4))
                .andExpect(jsonPath("$.approvalBacklog").value(2))
                .andExpect(jsonPath("$.reconciliationBacklog").value(2))
                .andExpect(jsonPath("$.bottleneckRole").value("SETTLEMENT_OPERATOR"))
                .andExpect(jsonPath("$.payrollCost").value(210000.00));

        mvc.perform(get("/api/settlement-agency/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payrollCost").value(210000.00))
                .andExpect(jsonPath("$.settlementBacklog").value(4))
                .andExpect(jsonPath("$.approvalBacklog").value(2))
                .andExpect(jsonPath("$.reconciliationBacklog").value(2));
    }

    @Test
    void autonomousTickLimitsSettlementByCapacityAndExposesBalanceMetrics() throws Exception {
        clearTablesForDeterministicReconciliation();
        LocalDate workDate = LocalDate.now();
        String correlationId = "CORR-SETTLEMENT-" + nextId("CORR");
        String simulationRunId = "SIM-SETTLEMENT-" + nextId("SIM");
        insertSettlementReadyTransactions(workDate, 55, correlationId, simulationRunId);

        String tickId = "LEDGER-SETTLEMENT-CAPACITY-" + nextId("TICK");
        var tick = ledger.autonomousRuntimeTick(tickId, 3, 10);

        assertThat(tick.eventsProduced()).isLessThanOrEqualTo(3);
        assertThat(count("select count(*) from finance_transaction where status='SETTLED'")).isEqualTo(10);
        assertThat(count("select count(*) from finance_transaction where status='SETTLEMENT_READY'")).isEqualTo(45);
        assertThat(jdbc.queryForObject("select total_transaction_count from settlement_batch where status='SUCCESS'", Integer.class)).isEqualTo(10);

        String runtimeJson = mvc.perform(get("/api/runtime-events/correlation/{correlationId}", correlationId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode runtimeEvents = mapper.readTree(runtimeJson);
        List<JsonNode> started = new ArrayList<>();
        List<JsonNode> completed = new ArrayList<>();
        Set<String> settlementEventIds = new HashSet<>();
        for (JsonNode event : runtimeEvents) {
            String eventType = event.path("eventType").asText();
            if ("SETTLEMENT_STARTED".equals(eventType)) started.add(event);
            if ("SETTLEMENT_COMPLETED".equals(eventType)) completed.add(event);
            if (eventType.startsWith("SETTLEMENT_")) settlementEventIds.add(event.path("eventId").asText());
        }
        assertThat(started).hasSize(10);
        assertThat(completed).hasSize(10);
        assertThat(settlementEventIds).hasSize(20);
        for (JsonNode event : started) {
            assertThat(event.path("sourceService").asText()).isEqualTo("archive-ledger");
            assertThat(event.path("correlationId").asText()).isEqualTo(correlationId);
            assertThat(event.path("simulationRunId").asText()).isEqualTo(simulationRunId);
            assertThat(event.path("causationId").asText()).startsWith("rt-settlement-ready-");
        }
        Set<String> startedEventIds = new HashSet<>();
        for (JsonNode event : started) startedEventIds.add(event.path("eventId").asText());
        for (JsonNode event : completed) {
            assertThat(event.path("sourceService").asText()).isEqualTo("archive-ledger");
            assertThat(event.path("correlationId").asText()).isEqualTo(correlationId);
            assertThat(event.path("simulationRunId").asText()).isEqualTo(simulationRunId);
            assertThat(startedEventIds).contains(event.path("causationId").asText());
        }
        var duplicateTick = ledger.autonomousRuntimeTick(tickId, 3, 10);
        assertThat(duplicateTick.duplicate()).isTrue();
        assertThat(count("select count(*) from settlement_batch where status='SUCCESS'")).isEqualTo(1);

        mvc.perform(get("/api/settlement-agency/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance.available").value(true))
                .andExpect(jsonPath("$.balance.calculationScope").value("WORKDAY"))
                .andExpect(jsonPath("$.balance.transactionProcessingRevenue").exists())
                .andExpect(jsonPath("$.balance.settlementAgencyRevenue").exists())
                .andExpect(jsonPath("$.balance.workforceCost").exists())
                .andExpect(jsonPath("$.balance.callbackFailureCost").exists())
                .andExpect(jsonPath("$.balance.operatingProfit").exists())
                .andExpect(jsonPath("$.balance.operatingMargin").exists())
                .andExpect(jsonPath("$.balance.cashBalance").exists())
                .andExpect(jsonPath("$.balance.settlementDelayRate").value(0.8182));

        mvc.perform(get("/api/operations/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance.available").value(true))
                .andExpect(jsonPath("$.balance.calculationScope").value("WORKDAY"))
                .andExpect(jsonPath("$.balance.transactionsProcessed").value(0))
                .andExpect(jsonPath("$.balance.settlementBacklog").value(45))
                .andExpect(jsonPath("$.balance.capacityUtilization").exists())
                .andExpect(jsonPath("$.balance.negativeProfitStreak").value(1));
    }

    @Test
    void balanceSnapshotPreservesSettlementCycleContext() throws Exception {
        clearTablesForDeterministicReconciliation();
        String cycleId = "CYCLE-BALANCE-" + nextId("CYCLE");
        mvc.perform(post("/api/events/market").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(marketEvent("Archive-Market", logisticsEventId().replace("LG", "MK"),
                                logisticsIdempotency().replace("LG", "MK"), "SALES_REVENUE_CONFIRMED", Map.of(
                                        "orderId", "ORDER-BALANCE-" + nextId("ORD"),
                                        "amount", 120_000L,
                                        "currency", "KRW",
                                        "settlementCycleId", cycleId,
                                        "correlationId", "CORR-BALANCE-" + nextId("CORR")
                                )))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/workforce/workday/run").param("date", LocalDate.now().toString()))
                .andExpect(status().isOk());

        mvc.perform(get("/api/operations/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance.settlementCycleId").value(cycleId));
    }

    @Test
    void duplicateApprovalCallbackDoesNotReopenSettledOrReadyTransaction() throws Exception {
        String eventId = logisticsEventId();
        String idempotency = logisticsIdempotency();
        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(logisticsEvent("Archive-Logistics", eventId, idempotency,
                                "COLD_CHAIN_RISK_COST_CONFIRMED", Map.of(
                                        "routePlanId", "ROUTE-" + nextId("LOG"),
                                        "shipmentId", "SHIP-" + nextId("SHIP"),
                                        "totalCost", 400_000L,
                                        "riskScore", 0.91
                                )))))
                .andExpect(status().isOk());
        String transactionId = transactionId(eventId);
        String approvalRequestId = jdbc.queryForObject("select approval_request_id from finance_transaction where transaction_id=?", String.class, transactionId);
        Map<String, Object> callback = Map.of(
                "approvalRequestId", approvalRequestId,
                "transactionId", transactionId,
                "decision", "APPROVED",
                "decidedBy", "synthetic-operator"
        );

        mvc.perform(post("/api/approvals/callback").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(callback)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLEMENT_READY"))
                .andExpect(jsonPath("$.duplicate").value(false));
        mvc.perform(post("/api/approvals/callback").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(callback)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLEMENT_READY"))
                .andExpect(jsonPath("$.duplicate").value(true));
        assertThat(transactionStatus(eventId)).isEqualTo("SETTLEMENT_READY");
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

    private Map<String, Object> marketEvent(String source, String eventId, String idempotencyKey, String eventType, Map<String, Object> overrides) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("factoryId", "FAC-MK");
        payload.put("vendorId", "VENDOR-MARKET-01");
        payload.put("currency", "KRW");
        payload.put("originCode", "FAC-MK");
        payload.put("destinationCode", "DC-SEOUL-01");
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
        jdbc.execute("delete from archiveos_runtime_outbox");
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

    private void insertSettlementReadyTransactions(LocalDate workDate, int count) {
        insertSyntheticTransactions(workDate, "SETTLEMENT_READY", count);
    }

    private void insertSettlementReadyTransactions(LocalDate workDate, int count, String correlationId, String simulationRunId) {
        insertSyntheticTransactions(workDate, "SETTLEMENT_READY", count, correlationId, simulationRunId);
    }

    private void insertSyntheticTransactions(LocalDate workDate, String status, int count) {
        insertSyntheticTransactions(workDate, status, count, null, null);
    }

    private void insertSyntheticTransactions(LocalDate workDate, String status, int count,
                                             String correlationId, String simulationRunId) {
        for (int i = 0; i < count; i++) {
            String id = "TX-WF-" + i + "-" + nextId("TX");
            jdbc.update("""
                    insert into finance_transaction(
                        transaction_id,source_event_id,idempotency_key,transaction_type,
                        factory_id,vendor_id,synthetic_account_id,amount,currency,status,
                        approval_required,approval_request_id,reason,occurred_at,created_at,updated_at
                        ,source_service,correlation_id,simulation_run_id
                    ) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    id,
                    "EVT-WF-" + i + "-" + nextId("EVT"),
                    "IDEMP-WF-" + i + "-" + nextId("IDEMP"),
                    "WORKFORCE_TEST",
                    "FAC-WF",
                    "VENDOR-WF",
                    "SYN-WF",
                    BigDecimal.valueOf(1000),
                    "KRW",
                    status,
                    "APPROVAL_REQUIRED".equals(status),
                    "APPROVAL_REQUIRED".equals(status) ? "APR-WF-" + i + "-" + nextId("APR") : null,
                    "synthetic workforce demand",
                    java.sql.Timestamp.valueOf(workDate.atTime(10, 0)),
                    java.sql.Timestamp.valueOf(workDate.atTime(10, 0)),
                    java.sql.Timestamp.valueOf(workDate.atTime(10, 0)),
                    correlationId == null ? null : "archive-ledger",
                    correlationId,
                    simulationRunId
            );
        }
    }

    private void insertReceivedEvent(LocalDate workDate, String eventId) {
        jdbc.update("""
                insert into received_event(event_id,idempotency_key,source,event_type,schema_version,payload,processing_status,received_at,processed_at)
                values(?,?,?,?,?,?,?,?,?)
                """,
                eventId,
                "IDEMP-" + eventId,
                "Archive-Nexus",
                "MATERIAL_CONSUMED",
                1,
                "{}",
                "PROCESSED",
                java.sql.Timestamp.valueOf(workDate.atTime(9, 0)),
                java.sql.Timestamp.valueOf(workDate.atTime(9, 1))
        );
    }

    private void insertApprovalRequests(LocalDate workDate, int count) {
        for (int i = 0; i < count; i++) {
            jdbc.update("""
                    insert into approval_request(approval_request_id,transaction_id,requested_to,status,amount,reason,policy_evidence,requested_at)
                    values(?,?,?,?,?,?,?,?)
                    """,
                    "APR-WF-REQ-" + i + "-" + nextId("APR"),
                    "TX-WF-APR-" + i + "-" + nextId("TX"),
                    "synthetic-finance-operator",
                    "REQUESTED",
                    BigDecimal.valueOf(1000),
                    "synthetic approval workload",
                    "synthetic evidence",
                    java.sql.Timestamp.valueOf(workDate.atTime(11, 0))
            );
        }
    }

    private void insertReconciliationResult(LocalDate workDate, int mismatch) {
        jdbc.update("""
                insert into reconciliation_result(
                    reconciliation_date,nexus_event_count,received_event_count,created_transaction_count,
                    duplicate_event_count,failed_event_count,approval_required_count,settlement_ready_count,
                    settled_count,mismatch_count,status,created_at,logistics_event_count,direct_event_count,
                    logistics_transaction_count,direct_transaction_count,market_event_count,market_transaction_count
                ) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                java.sql.Date.valueOf(workDate),
                0, 0, 0, 0, 0, 0, 0, 0, mismatch, "WARNING",
                java.sql.Timestamp.valueOf(workDate.atTime(12, 0)),
                0, 0, 0, 0, 0, 0
        );
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

