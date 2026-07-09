package com.archiveledger.ledger.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
public class ArchiveOsApprovalClient {
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final String baseUrl;
    private final boolean enabled;
    private final Duration timeout;

    public ArchiveOsApprovalClient(ObjectMapper mapper,
                                   @Value("${archive-ledger.archiveos.base-url:http://host.docker.internal:4000}") String baseUrl,
                                   @Value("${archive-ledger.archiveos.enabled:false}") boolean enabled,
                                   @Value("${archive-ledger.archiveos.timeout-ms:2000}") long timeoutMs) {
        this.mapper = mapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.enabled = enabled;
        this.timeout = Duration.ofMillis(Math.max(250, timeoutMs));
        this.client = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    public boolean enabled() {
        return enabled;
    }

    public void requestApproval(String approvalRequestId, String transactionId, BigDecimal amount,
                                String currency, String reason, Map<String, Object> metadata) {
        if (!enabled) return;
        try {
            Map<String, Object> body = Map.of(
                    "source", "Archive-Ledger",
                    "approvalRequestId", approvalRequestId,
                    "correlationId", "LEDGER-" + transactionId,
                    "transactionId", transactionId,
                    "amount", amount,
                    "currency", currency,
                    "reason", reason,
                    "policyQuestion", "Why does this synthetic finance operation require approval?",
                    "metadata", metadata,
                    "callback", Map.of("targetSystemId", "archive-ledger", "callbackPath", "/api/approvals/callback")
            );
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/approvals/external"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("ArchiveOS approval API returned " + response.statusCode());
            }
        } catch (Exception error) {
            throw new IllegalStateException("ArchiveOS approval request failed: " + error.getMessage(), error);
        }
    }
}
