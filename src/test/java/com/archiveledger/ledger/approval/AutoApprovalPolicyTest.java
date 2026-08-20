package com.archiveledger.ledger.approval;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AutoApprovalPolicyTest {
    private final AutoApprovalPolicy policy = new AutoApprovalPolicy();

    @Test
    void acceptsOnlyRoutineLogisticsWhoseOnlyApprovalReasonIsAmount() {
        AutoApprovalPolicy.Evaluation evaluation = policy.evaluate(candidate(Map.of()));

        assertThat(evaluation.eligible()).isTrue();
        assertThat(evaluation.reasonCodes()).containsExactly("ROUTINE_LOGISTICS", "AMOUNT_THRESHOLD_ONLY", "LOW_RISK");
    }

    @Test
    void rejectsRiskAtExclusiveLimitAndSpecialHandlingSignals() {
        AutoApprovalCandidate risky = candidate(Map.of(
                "riskScore", 0.50,
                "delayed", true,
                "deviated", true,
                "requiresColdChain", true,
                "coldChainPenalty", 80_000,
                "urgentSurcharge", 30_000
        ));

        AutoApprovalPolicy.Evaluation evaluation = policy.evaluate(risky);

        assertThat(evaluation.eligible()).isFalse();
        assertThat(evaluation.reasonCodes()).contains(
                "RISK_MISSING_MISMATCHED_OR_TOO_HIGH",
                "DELAYED_MISSING_OR_TRUE",
                "DEVIATED_MISSING_OR_TRUE",
                "COLD_CHAIN_MISSING_OR_TRUE",
                "COLD_CHAIN_PENALTY_MISSING_OR_NONZERO",
                "URGENT_SURCHARGE_MISSING_OR_NONZERO");
    }

    @Test
    void failsClosedWhenRequiredStructuredEvidenceIsMissing() {
        Map<String, Object> payload = new LinkedHashMap<>(validPayload());
        payload.remove("riskScore");
        payload.remove("urgentSurcharge");

        AutoApprovalPolicy.Evaluation evaluation = policy.evaluate(new AutoApprovalCandidate(
                "APR-1", "TX-1", "Archive-Logistics", "LOGISTICS_COST_CONFIRMED", "LOGISTICS_COST",
                new BigDecimal("350000"), "KRW", new BigDecimal("0.20"), "HIGH",
                "Approval required: totalCost>=300000", true, payload));

        assertThat(evaluation.eligible()).isFalse();
        assertThat(evaluation.reasonCodes()).contains(
                "RISK_MISSING_MISMATCHED_OR_TOO_HIGH",
                "URGENT_SURCHARGE_MISSING_OR_NONZERO");
    }

    @Test
    void rejectsNonRoutineEventEvenWhenAmountAndRiskAreLow() {
        AutoApprovalCandidate urgent = new AutoApprovalCandidate(
                "APR-1", "TX-1", "Archive-Logistics", "URGENT_DELIVERY_COST_CONFIRMED", "URGENT_DELIVERY_COST",
                new BigDecimal("350000"), "KRW", new BigDecimal("0.20"), "HIGH",
                "Approval required: totalCost>=300000,urgent_delivery_high_cost", true, validPayload());

        assertThat(policy.evaluate(urgent).eligible()).isFalse();
    }

    @Test
    void rejectsSeverityThatDoesNotMatchTheActualLogisticsApprovalContract() {
        Map<String, Object> payload = new LinkedHashMap<>(validPayload());
        payload.put("severity", "NORMAL");
        AutoApprovalCandidate normalSeverity = new AutoApprovalCandidate(
                "APR-1", "TX-1", "Archive-Logitics", "LOGISTICS_COST_CONFIRMED", "LOGISTICS_COST",
                new BigDecimal("350000"), "KRW", new BigDecimal("0.20"), "NORMAL",
                "Approval required: totalCost>=300000", true, payload);

        assertThat(policy.evaluate(normalSeverity).reasonCodes()).contains("SEVERITY_MISSING_OR_NOT_HIGH");
    }

    private AutoApprovalCandidate candidate(Map<String, Object> overrides) {
        Map<String, Object> payload = new LinkedHashMap<>(validPayload());
        payload.putAll(overrides);
        BigDecimal risk = new BigDecimal(String.valueOf(payload.get("riskScore")));
        return new AutoApprovalCandidate(
                "APR-1", "TX-1", "Archive-Logitics", "LOGISTICS_COST_CONFIRMED", "LOGISTICS_COST",
                new BigDecimal("350000"), "KRW", risk, "HIGH",
                "Approval required: totalCost>=300000", true, payload);
    }

    private Map<String, Object> validPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totalCost", 350_000);
        payload.put("currency", "KRW");
        payload.put("riskScore", 0.20);
        payload.put("severity", "HIGH");
        payload.put("priority", "NORMAL");
        payload.put("requiresApproval", true);
        payload.put("delayed", false);
        payload.put("deviated", false);
        payload.put("requiresColdChain", false);
        payload.put("coldChainPenalty", 0);
        payload.put("urgentSurcharge", 0);
        return payload;
    }
}
