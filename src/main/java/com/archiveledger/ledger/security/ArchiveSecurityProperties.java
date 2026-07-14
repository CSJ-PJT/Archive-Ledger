package com.archiveledger.ledger.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class ArchiveSecurityProperties {
    private final boolean enabled;
    private final int maxPayloadBytes;
    private final int writeRequestsPerMinute;
    private final List<String> allowedOrigins;
    private final String marketIngestToken;
    private final String nexusIngestToken;
    private final String logisticsIngestToken;
    private final String archiveOsCallbackToken;
    private final String adminToken;
    private final String readToken;

    public ArchiveSecurityProperties(
            @Value("${archive.security.enabled:false}") boolean enabled,
            @Value("${archive.security.max-payload-bytes:1048576}") int maxPayloadBytes,
            @Value("${archive.security.write-requests-per-minute:120}") int writeRequestsPerMinute,
            @Value("${archive.security.allowed-origins:http://localhost:4000,http://127.0.0.1:4000}") String allowedOrigins,
            @Value("${archive.security.market-ingest-token:}") String marketIngestToken,
            @Value("${archive.security.nexus-ingest-token:}") String nexusIngestToken,
            @Value("${archive.security.logistics-ingest-token:}") String logisticsIngestToken,
            @Value("${archive.security.archiveos-callback-token:}") String archiveOsCallbackToken,
            @Value("${archive.security.admin-token:}") String adminToken,
            @Value("${archive.security.read-token:}") String readToken) {
        this.enabled = enabled;
        this.maxPayloadBytes = Math.max(1024, maxPayloadBytes);
        this.writeRequestsPerMinute = Math.max(1, writeRequestsPerMinute);
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
        this.marketIngestToken = marketIngestToken;
        this.nexusIngestToken = nexusIngestToken;
        this.logisticsIngestToken = logisticsIngestToken;
        this.archiveOsCallbackToken = archiveOsCallbackToken;
        this.adminToken = adminToken;
        this.readToken = readToken;
    }

    public boolean enabled() { return enabled; }
    public int maxPayloadBytes() { return maxPayloadBytes; }
    public int writeRequestsPerMinute() { return writeRequestsPerMinute; }
    public List<String> allowedOrigins() { return allowedOrigins; }
    public String marketIngestToken() { return marketIngestToken; }
    public String nexusIngestToken() { return nexusIngestToken; }
    public String logisticsIngestToken() { return logisticsIngestToken; }
    public String archiveOsCallbackToken() { return archiveOsCallbackToken; }
    public String adminToken() { return adminToken; }
    public String readToken() { return readToken; }
}
