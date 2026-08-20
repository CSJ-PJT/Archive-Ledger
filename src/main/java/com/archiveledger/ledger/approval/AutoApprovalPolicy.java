package com.archiveledger.ledger.approval;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class AutoApprovalPolicy {
    private static final String EXPECTED_APPROVAL_REASON = "Approval required: totalCost>=300000";

    public Evaluation evaluate(AutoApprovalCandidate candidate) {
        List<String> rejections = new ArrayList<>();
        Map<String, Object> payload = candidate.payload();

        require(candidate.approvalRequired(), "NOT_APPROVAL_REQUIRED", rejections);
        require(isLogistics(candidate.sourceService()), "SOURCE_NOT_LOGISTICS", rejections);
        require(AutoApprovalProperties.ALLOWED_EVENT_TYPE.equals(candidate.eventType()), "EVENT_TYPE_NOT_ALLOWED", rejections);
        require(AutoApprovalProperties.ALLOWED_TRANSACTION_TYPE.equals(candidate.transactionType()), "TRANSACTION_TYPE_NOT_ALLOWED", rejections);
        require("KRW".equalsIgnoreCase(candidate.currency()), "CURRENCY_NOT_KRW", rejections);
        require("KRW".equalsIgnoreCase(text(payload.get("currency"))), "PAYLOAD_CURRENCY_MISSING_OR_NOT_KRW", rejections);
        require(inAmountRange(candidate.amount()), "AMOUNT_OUTSIDE_300K_500K", rejections);
        require(equalDecimal(candidate.amount(), decimal(payload.get("totalCost"))), "TOTAL_COST_MISSING_OR_MISMATCHED", rejections);
        require(candidate.riskScore() != null
                        && decimal(payload.get("riskScore")) != null
                        && candidate.riskScore().compareTo(AutoApprovalProperties.MAX_RISK_SCORE) < 0
                        && equalDecimal(candidate.riskScore(), decimal(payload.get("riskScore"))),
                "RISK_MISSING_MISMATCHED_OR_TOO_HIGH", rejections);
        require("HIGH".equalsIgnoreCase(candidate.severity())
                        && "HIGH".equalsIgnoreCase(text(payload.get("severity"))),
                "SEVERITY_MISSING_OR_NOT_HIGH", rejections);
        require("NORMAL".equalsIgnoreCase(text(payload.get("priority"))), "PRIORITY_MISSING_OR_NOT_NORMAL", rejections);
        require(Boolean.TRUE.equals(strictBoolean(payload.get("requiresApproval"))), "UPSTREAM_APPROVAL_FLAG_MISSING", rejections);
        require(Boolean.FALSE.equals(strictBoolean(payload.get("delayed"))), "DELAYED_MISSING_OR_TRUE", rejections);
        require(Boolean.FALSE.equals(strictBoolean(payload.get("deviated"))), "DEVIATED_MISSING_OR_TRUE", rejections);
        require(Boolean.FALSE.equals(strictBoolean(payload.get("requiresColdChain"))), "COLD_CHAIN_MISSING_OR_TRUE", rejections);
        require(isExplicitZero(payload.get("coldChainPenalty")), "COLD_CHAIN_PENALTY_MISSING_OR_NONZERO", rejections);
        require(isExplicitZero(payload.get("urgentSurcharge")), "URGENT_SURCHARGE_MISSING_OR_NONZERO", rejections);
        require(EXPECTED_APPROVAL_REASON.equals(candidate.approvalReason()), "APPROVAL_REASON_NOT_AMOUNT_ONLY", rejections);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sourceService", candidate.sourceService());
        evidence.put("eventType", candidate.eventType());
        evidence.put("transactionType", candidate.transactionType());
        evidence.put("amount", candidate.amount());
        evidence.put("currency", candidate.currency());
        evidence.put("riskScore", candidate.riskScore());
        evidence.put("severity", candidate.severity());
        evidence.put("priority", payload.get("priority"));
        evidence.put("delayed", payload.get("delayed"));
        evidence.put("deviated", payload.get("deviated"));
        evidence.put("requiresColdChain", payload.get("requiresColdChain"));
        evidence.put("coldChainPenalty", payload.get("coldChainPenalty"));
        evidence.put("urgentSurcharge", payload.get("urgentSurcharge"));
        evidence.put("approvalReason", candidate.approvalReason());
        evidence.put("minimumAmountKrw", AutoApprovalProperties.MIN_AMOUNT_KRW);
        evidence.put("maximumAmountKrw", AutoApprovalProperties.MAX_AMOUNT_KRW);
        evidence.put("maximumRiskScoreExclusive", AutoApprovalProperties.MAX_RISK_SCORE);

        if (rejections.isEmpty()) {
            return new Evaluation(true,
                    List.of("ROUTINE_LOGISTICS", "AMOUNT_THRESHOLD_ONLY", "LOW_RISK"), evidence);
        }
        return new Evaluation(false, List.copyOf(rejections), evidence);
    }

    private boolean isLogistics(String source) {
        return "Archive-Logitics".equals(source) || "Archive-Logistics".equals(source);
    }

    private boolean inAmountRange(BigDecimal amount) {
        return amount != null
                && amount.compareTo(AutoApprovalProperties.MIN_AMOUNT_KRW) >= 0
                && amount.compareTo(AutoApprovalProperties.MAX_AMOUNT_KRW) <= 0;
    }

    private boolean isExplicitZero(Object value) {
        BigDecimal decimal = decimal(value);
        return decimal != null && decimal.compareTo(BigDecimal.ZERO) == 0;
    }

    private boolean equalDecimal(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof BigDecimal decimal) {
                return decimal;
            }
            if (value instanceof Number number) {
                return new BigDecimal(number.toString());
            }
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Boolean strictBoolean(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text)) return true;
            if ("false".equalsIgnoreCase(text)) return false;
        }
        return null;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim().toUpperCase(Locale.ROOT);
    }

    private void require(boolean condition, String rejection, List<String> rejections) {
        if (!condition) {
            rejections.add(rejection);
        }
    }

    public record Evaluation(boolean eligible, List<String> reasonCodes, Map<String, Object> evidence) {
    }
}
