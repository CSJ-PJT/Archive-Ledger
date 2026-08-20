package com.archiveledger.ledger.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ArchiveOsRuntimePublisherTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicInteger responseStatus = new AtomicInteger(202);
    private final AtomicReference<HttpExchange> exchange = new AtomicReference<>();
    private final AtomicReference<byte[]> requestBody = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/live-flow/events/ingest", current -> {
            exchange.set(current);
            requestBody.set(current.getRequestBody().readAllBytes());
            byte[] response = "{\"duplicate\":true}".getBytes(StandardCharsets.UTF_8);
            current.sendResponseHeaders(responseStatus.get(), response.length);
            current.getResponseBody().write(response);
            current.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsCanonicalArchiveOsHeadersAndSanitizedEnvelope() throws Exception {
        ArchiveOsRuntimePublisher publisher = publisher(true, "ledger-to-os-test-token");

        var result = publisher.publish(event());

        assertThat(result.success()).isTrue();
        HttpExchange request = exchange.get();
        assertThat(request.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer ledger-to-os-test-token");
        assertThat(request.getRequestHeaders().getFirst(ArchiveOsRuntimePublisher.SOURCE_HEADER)).isEqualTo("archive-ledger");
        assertThat(request.getRequestHeaders().getFirst(ArchiveOsRuntimePublisher.SCOPE_HEADER)).isEqualTo("runtime:ingest");
        JsonNode body = mapper.readTree(requestBody.get());
        assertThat(body.path("sourceSystem").asText()).isEqualTo("archive-ledger");
        assertThat(body.path("targetSystem").asText()).isEqualTo("archiveos");
        assertThat(body.path("correlationId").asText()).isEqualTo("CORR-001");
        assertThat(body.path("settlementCycleId").asText()).isEqualTo("CYCLE-001");
    }

    @Test
    void classifiesCredentialAndTransientFailuresWithoutThrowing() {
        ArchiveOsRuntimePublisher publisher = publisher(true, "ledger-to-os-test-token");

        responseStatus.set(401);
        assertThat(publisher.publish(event()).classification()).isEqualTo("CONFIG_ERROR");
        responseStatus.set(503);
        assertThat(publisher.publish(event()).retryable()).isTrue();
        responseStatus.set(400);
        assertThat(publisher.publish(event()).classification()).isEqualTo("NON_RETRYABLE_ERROR");
        assertThat(publisher(false, "").publish(event()).classification()).isEqualTo("CONFIG_ERROR");
    }

    @Test
    void keepsIndependentLedgerEventOrderIdNullWithoutFabricatingLineage() throws Exception {
        ArchiveOsRuntimePublisher publisher = publisher(true, "ledger-to-os-test-token");
        var independent = new RuntimeOutboundModels.RuntimeOutboundEvent(
                "rt-reconciliation-001", "RUNTIME:rt-reconciliation-001", "archive-ledger", "archiveos", "ledger",
                "RECONCILIATION_OK", "reconciliation", "REC-001", null, "CORR-REC-001", "rt-settlement-001",
                "SIM-001", "CYCLE-001", "WORKDAY-001", "COMPLETED", "INFO", "Reconciliation completed",
                Instant.parse("2026-07-13T00:00:00Z"), 1, 5, Map.of("settlementCycleId", "CYCLE-001")
        );

        assertThat(publisher.publish(independent).success()).isTrue();
        JsonNode body = mapper.readTree(requestBody.get());
        assertThat(body.path("orderId").isNull()).isTrue();
        assertThat(body.path("entityId").asText()).isEqualTo("REC-001");
        assertThat(body.path("settlementCycleId").asText()).isEqualTo("CYCLE-001");
    }

    private ArchiveOsRuntimePublisher publisher(boolean enabled, String token) {
        return new ArchiveOsRuntimePublisher(mapper, "http://127.0.0.1:" + server.getAddress().getPort(),
                "/api/live-flow/events/ingest", token, enabled, 2_000);
    }

    private RuntimeOutboundModels.RuntimeOutboundEvent event() {
        return new RuntimeOutboundModels.RuntimeOutboundEvent(
                "rt-transaction-001", "RUNTIME:rt-transaction-001", "archive-ledger", "archiveos", "ledger",
                "TRANSACTION_CREATED", "transaction", "TX-001", "ORDER-001", "CORR-001", "evt-inbound-001",
                "SIM-001", "CYCLE-001", "WORKDAY-001", "COMPLETED", "NORMAL", "Transaction created", Instant.parse("2026-07-13T00:00:00Z"),
                1, 5, Map.of("transactionId", "TX-001", "amountBucket", "KRW_50K_TO_100K")
        );
    }
}
