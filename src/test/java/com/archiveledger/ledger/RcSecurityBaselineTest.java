package com.archiveledger.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:archive_ledger_security_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "archive-ledger.archiveos.enabled=false",
        "archive.runtime.autorun.enabled=false",
        "archive.security.enabled=true",
        "archive.security.market-ingest-token=market-token",
        "archive.security.nexus-ingest-token=nexus-token",
        "archive.security.logistics-ingest-token=logistics-token",
        "archive.security.archiveos-callback-token=callback-token",
        "archive.security.admin-token=admin-token",
        "archive.security.read-token=read-token",
        "archive.security.allowed-origins=http://localhost:4000"
})
@AutoConfigureMockMvc
class RcSecurityBaselineTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Test
    void writeEndpointsRequireValidServiceIdentityScopeAndToken() throws Exception {
        mvc.perform(post("/api/events/market").contentType(MediaType.APPLICATION_JSON).content(eventBody("Archive-Market")))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/events/market").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer invalid-token")
                        .header("X-Archive-Source-System", "archive-market")
                        .header("X-Archive-Service-Scope", "ledger:ingest")
                        .content(eventBody("Archive-Market")))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/events/market").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer market-token")
                        .header("X-Archive-Source-System", "archive-market")
                        .header("X-Archive-Service-Scope", "admin:operate")
                        .content(eventBody("Archive-Market")))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/events/market").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer market-token")
                        .header("X-Archive-Source-System", "archive-nexus")
                        .header("X-Archive-Service-Scope", "ledger:ingest")
                        .content(eventBody("Archive-Market")))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/events/market").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer market-token")
                        .header("X-Archive-Source-System", "archive-market")
                        .header("X-Archive-Service-Scope", "ledger:ingest")
                        .content(eventBody("Archive-Nexus")))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/events/market").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer market-token")
                        .header("X-Archive-Source-System", "archive-market")
                        .header("X-Archive-Service-Scope", "ledger:ingest")
                        .content(eventBody("Archive-Market")))
                .andExpect(status().isOk());
    }

    @Test
    void sensitiveReadRequiresReadScopeWhileHealthAndOperationsRemainAvailable() throws Exception {
        mvc.perform(get("/api/transactions"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/transactions")
                        .header("Authorization", "Bearer read-token")
                        .header("X-Archive-Source-System", "archive-os")
                        .header("X-Archive-Service-Scope", "authenticated:read"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/operations/summary"))
                .andExpect(status().isOk());
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void malformedJsonIsRejectedAfterServiceAuthentication() throws Exception {
        mvc.perform(post("/api/events/market").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer market-token")
                        .header("X-Archive-Source-System", "archive-market")
                        .header("X-Archive-Service-Scope", "ledger:ingest")
                        .content("{malformed"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void corsAllowsOnlyConfiguredArchiveOsOrigin() throws Exception {
        mvc.perform(options("/api/events/market")
                        .header("Origin", "http://localhost:4000")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isNoContent());
        mvc.perform(options("/api/events/market")
                        .header("Origin", "https://untrusted.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    private String eventBody(String source) throws Exception {
        return mapper.writeValueAsString(Map.of(
                "eventId", "evt-security-" + source + "-" + Instant.now().toEpochMilli(),
                "idempotencyKey", "SECURITY:" + source + ":" + Instant.now().toEpochMilli(),
                "source", source,
                "eventType", "SALES_REVENUE_CONFIRMED",
                "schemaVersion", 1,
                "occurredAt", Instant.now().toString(),
                "payload", Map.of("orderId", "ORDER-SECURITY", "amount", 1000, "currency", "KRW")
        ));
    }
}
