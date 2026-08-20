package com.archiveledger.ledger.security;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ArchiveSecurityStartupValidator implements ApplicationRunner {
    private final ArchiveSecurityProperties properties;

    public ArchiveSecurityStartupValidator(ArchiveSecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            return;
        }
        List<String> required = List.of(
                properties.marketIngestToken(), properties.nexusIngestToken(), properties.logisticsIngestToken(),
                properties.archiveOsCallbackToken(), properties.adminToken(), properties.readToken());
        if (required.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalStateException("RC security is enabled but one or more service tokens are not configured.");
        }
        if (properties.allowedOrigins().isEmpty() || properties.allowedOrigins().contains("*")) {
            throw new IllegalStateException("RC security requires explicit non-wildcard CORS origins.");
        }
    }
}
