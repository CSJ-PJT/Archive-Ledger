package com.archiveledger.ledger.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.archiveledger.ledger.runtime.RuntimeOutboundModels.RuntimeDeliveryResult;
import static com.archiveledger.ledger.runtime.RuntimeOutboundModels.RuntimeOutboundEvent;

/** Dedicated ArchiveOS Live Flow client. It is deliberately independent of approval dispatch. */
@Component
public class ArchiveOsRuntimePublisher {
    public static final String SOURCE_HEADER = "X-Archive-Source-System";
    public static final String SCOPE_HEADER = "X-Archive-Service-Scope";
    public static final String SOURCE_SYSTEM = "archive-ledger";
    public static final String TARGET_SYSTEM = "archiveos";
    public static final String REQUIRED_SCOPE = "runtime:ingest";

    private final ObjectMapper mapper;
    private final HttpClient client;
    private final String baseUrl;
    private final String ingestPath;
    private final String token;
    private final boolean enabled;
    private final Duration timeout;

    public ArchiveOsRuntimePublisher(
            ObjectMapper mapper,
            @Value("${archive-ledger.archiveos.base-url:http://host.docker.internal:4000}") String baseUrl,
            @Value("${archive-ledger.runtime-ingest.path:/api/live-flow/events/ingest}") String ingestPath,
            @Value("${archive-ledger.runtime-ingest.token:}") String token,
            @Value("${archive-ledger.runtime-ingest.enabled:false}") boolean enabled,
            @Value("${archive-ledger.runtime-ingest.timeout-ms:2000}") long timeoutMs) {
        this.mapper = mapper;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.ingestPath = ingestPath == null || ingestPath.isBlank() ? "/api/live-flow/events/ingest" : ingestPath;
        this.token = token == null ? "" : token.trim();
        this.enabled = enabled;
        this.timeout = Duration.ofMillis(Math.max(250, timeoutMs));
        this.client = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    public boolean enabled() {
        return enabled;
    }

    public String endpoint() {
        return baseUrl + ingestPath;
    }

    public RuntimeDeliveryResult publish(RuntimeOutboundEvent event) {
        if (!enabled) {
            return RuntimeDeliveryResult.configError("runtime_ingest_disabled");
        }
        if (baseUrl.isBlank() || token.isBlank()) {
            return RuntimeDeliveryResult.configError("runtime_ingest_configuration_missing");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint()))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + token)
                    .header(SOURCE_HEADER, SOURCE_SYSTEM)
                    .header(SCOPE_HEADER, REQUIRED_SCOPE)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(canonicalEnvelope(event))))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return RuntimeDeliveryResult.success(responseDuplicate(response.body()) ? "duplicate" : "accepted");
            }
            if (status == 401 || status == 403) {
                return RuntimeDeliveryResult.configError("archiveos_http_" + status);
            }
            if (status == 408 || status == 429 || status >= 500) {
                return RuntimeDeliveryResult.retryable("archiveos_http_" + status);
            }
            return RuntimeDeliveryResult.nonRetryable("archiveos_http_" + status);
        } catch (Exception error) {
            return RuntimeDeliveryResult.retryable("archiveos_transport_" + error.getClass().getSimpleName());
        }
    }

    private Map<String, Object> canonicalEnvelope(RuntimeOutboundEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", event.eventId());
        body.put("idempotencyKey", event.idempotencyKey());
        body.put("sourceSystem", SOURCE_SYSTEM);
        body.put("targetSystem", TARGET_SYSTEM);
        body.put("sourceService", SOURCE_SYSTEM);
        body.put("targetService", TARGET_SYSTEM);
        body.put("domain", "ledger");
        body.put("eventType", event.eventType());
        body.put("entityType", event.entityType());
        body.put("entityId", event.entityId());
        body.put("orderId", event.orderId());
        body.put("correlationId", event.correlationId());
        body.put("causationId", event.causationId());
        body.put("simulationRunId", event.simulationRunId());
        body.put("settlementCycleId", event.settlementCycleId());
        body.put("workdayId", event.workdayId());
        body.put("status", event.status());
        body.put("severity", event.severity());
        body.put("displayLabel", event.displayLabel());
        body.put("occurredAt", event.occurredAt());
        body.put("hopCount", event.hopCount());
        body.put("maxHop", event.maxHop());
        body.put("metadata", event.metadata());
        return body;
    }

    private boolean responseDuplicate(String body) {
        try {
            JsonNode node = mapper.readTree(body == null ? "" : body);
            return node.path("duplicate").asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String trimTrailingSlash(String value) {
        if (value == null) return "";
        return value.replaceAll("/+$", "");
    }
}
