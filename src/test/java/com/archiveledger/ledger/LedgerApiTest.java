package com.archiveledger.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void duplicateEventCreatesOnlyOneTransaction() throws Exception {
        String body = event("EVT-DUP-1", "IDEMP-DUP-1", "LOGISTICS_DISPATCHED", 500000, false);
        mvc.perform(post("/api/events/nexus").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        mvc.perform(post("/api/events/nexus").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DUPLICATE"));
        assertThat(count("select count(*) from finance_transaction where source_event_id='EVT-DUP-1'")).isEqualTo(1);
    }

    @Test
    void highAmountRequiresApprovalAndLowAmountIsSettlementReady() throws Exception {
        mvc.perform(post("/api/events/nexus").contentType(MediaType.APPLICATION_JSON)
                        .content(event("EVT-HIGH-1", "IDEMP-HIGH-1", "MAINTENANCE_COMPLETED", 4_800_000, true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        mvc.perform(post("/api/events/nexus").contentType(MediaType.APPLICATION_JSON)
                        .content(event("EVT-LOW-1", "IDEMP-LOW-1", "MATERIAL_CONSUMED", 800_000, false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));

        assertThat(jdbc.queryForObject("select status from finance_transaction where source_event_id='EVT-HIGH-1'", String.class))
                .isEqualTo("APPROVAL_REQUIRED");
        assertThat(jdbc.queryForObject("select status from finance_transaction where source_event_id='EVT-LOW-1'", String.class))
                .isEqualTo("SETTLEMENT_READY");
    }

    @Test
    void ledgerEntriesAreBalanced() throws Exception {
        JsonNode response = mapper.readTree(mvc.perform(post("/api/events/nexus").contentType(MediaType.APPLICATION_JSON)
                        .content(event("EVT-BAL-1", "IDEMP-BAL-1", "CORPORATE_CARD_USED", 900000, false)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String tx = response.get("transactionId").asText();
        BigDecimal debit = jdbc.queryForObject("select sum(debit_amount) from ledger_entry where transaction_id=?", BigDecimal.class, tx);
        BigDecimal credit = jdbc.queryForObject("select sum(credit_amount) from ledger_entry where transaction_id=?", BigDecimal.class, tx);
        assertThat(debit).isEqualByComparingTo(credit);
    }

    @Test
    void settlementIncludesOnlyReadyTransactionsAndApprovalCallbackTransitionsStatus() throws Exception {
        JsonNode high = mapper.readTree(mvc.perform(post("/api/events/nexus").contentType(MediaType.APPLICATION_JSON)
                        .content(event("EVT-APR-1", "IDEMP-APR-1", "MAINTENANCE_COMPLETED", 5_000_000, true)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode low = mapper.readTree(mvc.perform(post("/api/events/nexus").contentType(MediaType.APPLICATION_JSON)
                        .content(event("EVT-SET-1", "IDEMP-SET-1", "LOGISTICS_DISPATCHED", 700_000, false)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        mvc.perform(post("/api/settlements/daily/run?date=" + LocalDate.now()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS"));
        assertThat(jdbc.queryForObject("select status from finance_transaction where transaction_id=?",
                String.class, high.get("transactionId").asText())).isEqualTo("APPROVAL_REQUIRED");
        assertThat(jdbc.queryForObject("select status from finance_transaction where transaction_id=?",
                String.class, low.get("transactionId").asText())).isEqualTo("SETTLED");

        String approvalId = jdbc.queryForObject("select approval_request_id from finance_transaction where transaction_id=?",
                String.class, high.get("transactionId").asText());
        mvc.perform(post("/api/approvals/callback").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("approvalRequestId", approvalId,
                                "transactionId", high.get("transactionId").asText(), "decision", "APPROVED",
                                "decidedBy", "synthetic-operator", "comment", "approved"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SETTLEMENT_READY"));
    }

    @Test
    void rejectedApprovalCallbackRejectsTransaction() throws Exception {
        JsonNode high = mapper.readTree(mvc.perform(post("/api/events/nexus").contentType(MediaType.APPLICATION_JSON)
                        .content(event("EVT-REJ-1", "IDEMP-REJ-1", "EMERGENCY_PURCHASE_REQUESTED", 3_500_000, true)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String approvalId = jdbc.queryForObject("select approval_request_id from finance_transaction where transaction_id=?",
                String.class, high.get("transactionId").asText());
        mvc.perform(post("/api/approvals/callback").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("approvalRequestId", approvalId,
                                "transactionId", high.get("transactionId").asText(), "decision", "REJECTED"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void reconciliationCountsAndBadPayloadFailureAreRecorded() throws Exception {
        mvc.perform(post("/api/events/nexus").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("eventId", "EVT-BAD-1", "idempotencyKey", "IDEMP-BAD-1",
                                "eventType", "MATERIAL_CONSUMED", "payload", Map.of("factoryId", "FAC-B")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"));
        mvc.perform(post("/api/reconciliation/daily?date=" + LocalDate.now()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        assertThat(count("select count(*) from audit_log where action='EVENT_PROCESSING_FAILED'")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void bulkThousandEventsAreProcessed() throws Exception {
        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            events.add(mapper.readValue(event("EVT-BULK-" + i, "IDEMP-BULK-" + i, "MATERIAL_CONSUMED", 100000 + i, false), Map.class));
        }
        mvc.perform(post("/api/events/nexus/bulk").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(events)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(1000))
                .andExpect(jsonPath("$.accepted").value(1000));
    }

    private String event(String eventId, String key, String type, long amount, boolean requiresApproval) throws Exception {
        return mapper.writeValueAsString(Map.of(
                "eventId", eventId,
                "idempotencyKey", key,
                "eventType", type,
                "aggregateType", "SyntheticAggregate",
                "aggregateId", "AGG-" + eventId,
                "source", "Archive-Nexus",
                "schemaVersion", 1,
                "payload", Map.of(
                        "synthetic", true,
                        "factoryId", "FAC-B",
                        "vendorId", "VENDOR-MAINT-03",
                        "syntheticAccountId", "SYN-ACCT-FAC-B-001",
                        "estimatedCost", amount,
                        "currency", "KRW",
                        "severity", amount >= 3_000_000 ? "HIGH" : "MEDIUM",
                        "requiresApproval", requiresApproval,
                        "reason", "synthetic test event"
                )
        ));
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
