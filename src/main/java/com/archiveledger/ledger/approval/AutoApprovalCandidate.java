package com.archiveledger.ledger.approval;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public record AutoApprovalCandidate(
        String approvalRequestId,
        String transactionId,
        String sourceService,
        String eventType,
        String transactionType,
        BigDecimal amount,
        String currency,
        BigDecimal riskScore,
        String severity,
        String approvalReason,
        boolean approvalRequired,
        Map<String, Object> payload
) {
    public AutoApprovalCandidate {
        payload = payload == null ? Map.of() : new LinkedHashMap<>(payload);
    }
}
