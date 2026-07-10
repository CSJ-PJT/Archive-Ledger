package com.archiveledger.ledger.game;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class SettlementGameModels {
    private SettlementGameModels() {}

    public record SettlementGameRequest(
            String simulationRunId,
            String settlementCycleId,
            String tickId,
            Integer day,
            String correlationId,
            Integer maxHop,
            BigDecimal nexusInitialCash,
            BigDecimal logisticsInitialCash,
            BigDecimal ledgerInitialCash,
            BigDecimal nexusProductionRevenue,
            BigDecimal nexusMaterialCost,
            BigDecimal nexusMaintenanceCost,
            BigDecimal nexusQualityLossCost,
            BigDecimal logisticsServiceFee,
            BigDecimal logisticsDailySettlementFee,
            int shipmentCount,
            int transactionCount,
            int settlementBatchCount,
            int reconciliationCount,
            int approvalReviewCount,
            int exceptionCount,
            int callbackFailureCount,
            int mismatchCount,
            BigDecimal ledgerTransactionProcessingFee,
            BigDecimal ledgerDailySettlementAgencyFee,
            BigDecimal ledgerReconciliationVerificationFee,
            BigDecimal ledgerApprovalReviewFee,
            BigDecimal ledgerExceptionHandlingFee,
            BigDecimal ledgerEarlySettlementFee,
            BigDecimal ledgerDelayedSettlementPenaltyRevenue,
            BigDecimal ledgerProcessingOperatingCost,
            BigDecimal ledgerSettlementBatchRunCost,
            BigDecimal ledgerReconciliationRunCost,
            BigDecimal ledgerCallbackFailureCost,
            BigDecimal ledgerMismatchInvestigationCost,
            BigDecimal ledgerInfraFixedCost
    ) {}

    public record SettlementGameResponse(
            String simulationRunId,
            String settlementCycleId,
            String tickId,
            int day,
            String correlationId,
            int maxHop,
            String status,
            BigDecimal ecosystemCashBalance,
            BigDecimal ecosystemDailyProfit,
            String bankruptcyRisk,
            Map<String, ServiceEconomics> services,
            List<GameEvent> events,
            List<AgentProposal> proposals,
            Instant createdAt
    ) {}

    public record ServiceEconomics(
            String service,
            BigDecimal cashBefore,
            BigDecimal revenue,
            BigDecimal cost,
            BigDecimal profit,
            BigDecimal cashAfter,
            BigDecimal burnRate,
            String bankruptcyRisk,
            String explanation
    ) {}

    public record GameEvent(
            String eventId,
            String idempotencyKey,
            String eventType,
            String source,
            String target,
            String simulationRunId,
            String settlementCycleId,
            String tickId,
            int day,
            String correlationId,
            int hop,
            int maxHop,
            Map<String, Object> payload
    ) {}

    public record AgentProposal(
            String proposalId,
            String agentName,
            String targetService,
            String actionType,
            String summary,
            BigDecimal expectedCashImpact,
            double confidence,
            boolean safeModeRequired,
            boolean approvalRequired,
            List<String> evidence
    ) {}
}
