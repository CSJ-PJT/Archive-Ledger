package com.archiveledger.ledger.security;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

@Component
public class ArchiveInboundSourceValidator {
    private final ArchiveSecurityProperties properties;

    public ArchiveInboundSourceValidator(ArchiveSecurityProperties properties) {
        this.properties = properties;
    }

    public void verify(String headerSource, String bodySource, String expectedSource, String... compatibleSources) {
        if (!properties.enabled()) {
            return;
        }
        expectedSource = canonical(expectedSource);
        String canonicalHeader = canonical(headerSource);
        Set<String> allowed = java.util.Arrays.stream(compatibleSources).map(this::canonical).collect(java.util.stream.Collectors.toSet());
        if (!expectedSource.equals(canonicalHeader)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Source header does not match the endpoint identity.");
        }
        if (bodySource != null && !bodySource.isBlank()
                && !expectedSource.equals(canonical(bodySource)) && !allowed.contains(canonical(bodySource))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Source header and event body source do not match.");
        }
    }

    private String canonical(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase();
        return switch (normalized) {
            case "archive-market" -> "archive-market";
            case "archive-nexus" -> "archive-nexus";
            case "archive-logistics", "archive-logitics" -> "archive-logistics";
            case "archiveos", "archive-os" -> "archive-os";
            default -> normalized;
        };
    }
}
