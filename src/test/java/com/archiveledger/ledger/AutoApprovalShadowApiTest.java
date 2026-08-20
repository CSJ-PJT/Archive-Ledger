package com.archiveledger.ledger;

import com.archiveledger.ledger.approval.ArchiveOsApprovalClient;
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
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:archive_ledger_shadow_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "archive-ledger.archiveos.enabled=true",
        "archive-ledger.auto-approval.mode=SHADOW",
        "archive.runtime.autorun.enabled=false"
})
@AutoConfigureMockMvc
class AutoApprovalShadowApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ArchiveOsApprovalClient archiveOs;

    @BeforeEach
    void clear() {
        reset(archiveOs);
        jdbc.execute("delete from approval_policy_decision");
        jdbc.execute("delete from auto_approval_daily_budget");
        jdbc.execute("delete from approval_request");
        jdbc.execute("delete from audit_log");
        jdbc.execute("delete from ledger_entry");
        jdbc.execute("delete from finance_transaction");
        jdbc.execute("delete from received_event");
    }

    @Test
    void shadowRecordsEligibilityWithoutChangingApprovalStateOrBudget() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("routePlanId", "ROUTE-SHADOW");
        payload.put("shipmentId", "SHIP-SHADOW");
        payload.put("totalCost", 350_000);
        payload.put("currency", "KRW");
        payload.put("riskScore", 0.20);
        payload.put("requiresApproval", true);
        payload.put("severity", "HIGH");
        payload.put("priority", "NORMAL");
        payload.put("delayed", false);
        payload.put("deviated", false);
        payload.put("requiresColdChain", false);
        payload.put("coldChainPenalty", 0);
        payload.put("urgentSurcharge", 0);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("eventId", "EVT-SHADOW");
        request.put("idempotencyKey", "IDEMP-SHADOW");
        request.put("eventType", "LOGISTICS_COST_CONFIRMED");
        request.put("source", "Archive-Logistics");
        request.put("schemaVersion", 1);
        request.put("payload", payload);
        request.put("occurredAt", OffsetDateTime.now().toString());

        mvc.perform(post("/api/events/logistics").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select status from finance_transaction where source_event_id='EVT-SHADOW'", String.class))
                .isEqualTo("APPROVAL_REQUIRED");
        assertThat(jdbc.queryForObject("select status from approval_request", String.class)).isEqualTo("REQUESTED");
        assertThat(jdbc.queryForObject("select outcome from approval_policy_decision", String.class)).isEqualTo("SHADOW_ELIGIBLE");
        assertThat(jdbc.queryForObject("select count(*) from auto_approval_daily_budget", Integer.class)).isZero();
        verify(archiveOs, times(1)).requestApproval(anyString(), anyString(), any(BigDecimal.class), anyString(), anyString(), any());
    }
}
