package com.archiveledger.ledger.common;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class LedgerModels {
    private LedgerModels() {
    }

    public record NexusEventRequest(
            @NotBlank String eventId,
            @NotBlank String idempotencyKey,
            @NotBlank String eventType,
            String aggregateType,
            String aggregateId,
            String source,
            Integer schemaVersion,
            @NotNull Map<String, Object> payload,
            Instant occurredAt
    ) {
    }

    public record LogisticsBulkRequest(
            String source,
            @NotNull List<NexusEventRequest> events
    ) {
    }

    public record MarketBulkRequest(
            String source,
            @NotNull List<NexusEventRequest> events
    ) {
    }

    public record EventIngestionResponse(String eventId, String status, String transactionId, boolean duplicate, String message) {
    }

    public record ApprovalCallbackRequest(
            @NotBlank String approvalRequestId,
            @NotBlank String transactionId,
            @NotBlank String decision,
            String decidedBy,
            String comment
    ) {
    }

    public record ReceivedEventView(String eventId, String idempotencyKey, String source, String eventType,
                                    String processingStatus, Instant receivedAt, Instant processedAt, String failureReason) {
    }

    public record TransactionView(String transactionId, String sourceEventId, String idempotencyKey,
                                  String transactionType, String factoryId, String vendorId,
                                  String syntheticAccountId, BigDecimal amount, String currency, String status,
                                  boolean approvalRequired, String approvalRequestId, String reason,
                                  Instant occurredAt, Instant createdAt, Instant updatedAt) {
    }

    public record LedgerEntryView(String transactionId, String accountCode, String accountName,
                                  BigDecimal debitAmount, BigDecimal creditAmount, String factoryId,
                                  String vendorId, Instant occurredAt, Instant createdAt) {
    }

    public record LedgerSummary(String scope, BigDecimal totalDebit, BigDecimal totalCredit, long entryCount) {
    }

    public record RuntimeEventView(
            String eventId,
            String idempotencyKey,
            String sourceService,
            String targetService,
            String domain,
            String eventType,
            String entityType,
            String entityId,
            String correlationId,
            String causationId,
            String simulationRunId,
            String settlementCycleId,
            String workdayId,
            String status,
            String severity,
            String displayLabel,
            Instant occurredAt,
            int hopCount,
            int maxHop,
            String cursor,
            Map<String, Object> metadata
    ) {
    }

    public record SettlementBatchView(String batchId, LocalDate settlementDate, String status,
                                      int totalTransactionCount, BigDecimal totalAmount, Instant startedAt,
                                      Instant completedAt, String failureReason) {
    }

    public record SettlementDetailView(String batchId, String transactionId, String factoryId, String vendorId,
                                       String accountCode, BigDecimal amount, String status, Instant createdAt) {
    }

    public record DailyBatchRunView(String runId, LocalDate batchDate, String status, String approvedBy,
                                    String triggerType, boolean settlementEnabled, boolean reconciliationEnabled,
                                    String settlementBatchId, String reconciliationStatus,
                                    int settlementTransactionCount, BigDecimal settlementAmount,
                                    int mismatchCount, Instant startedAt, Instant completedAt,
                                    String failureReason) {
    }

    public record ReconciliationView(LocalDate date, int nexusEvents, int receivedEvents, int createdTransactions,
                                     int logisticsEventCount, int directEventCount, int logisticsTransactionCount,
                                     int directTransactionCount, int marketEventCount, int marketTransactionCount,
                                     int duplicates, int failed, int approvalRequired,
                                     int settlementReady, int settled, int mismatch, String status, Instant createdAt) {
    }

    public record OperationsSummary(String status, long receivedEvents, long transactions, long duplicates,
                                    long approvalRequired, long settled, long failed, Instant lastSettlementAt,
                                    String lastReconciliationStatus,
                                    long eventsReceivedFromNexus, long eventsReceivedFromLogitics, long eventsReceivedFromMarket,
                                    long logisticsReceivedEvents, long logisticsCostTransactions,
                                    long urgentDeliveryTransactions, long delayPenaltyTransactions,
                                    long routeDeviationTransactions, long coldChainRiskTransactions,
                                    long marketRevenueTransactions, long paymentCaptureTransactions,
                                    long refundTransactions, long claimCompensationTransactions,
                                    WorkforceSummary workforce,
                                    String serviceName,
                                    String serviceRole,
                                    Instant latestEventAt,
                                    RuntimeOutboxSummary outbox,
                                    RuntimeEconomySummary economy,
                                    RuntimeWorkforceSummary runtimeWorkforce,
                                    String degradedReason,
                                    boolean liveFlowAvailable,
                                    long transactionsReceived,
                                    long transactionsProcessed,
                                    long approvalBacklog,
                                    long settlementReady,
                                    long settlementCompleted,
                                    long reconciliationWarnings,
                                    long callbackFailed,
                                    RuntimeStatusResponse runtime,
                                    SettlementBalanceSummary balance) {
    }

    public record RuntimeOutboxSummary(long pending, long published, long failed, long retry) {
    }

    public record RuntimeEconomySummary(BigDecimal revenue, BigDecimal cost, BigDecimal profit) {
    }

    public record RuntimeWorkforceSummary(int totalHeadcount, int effectiveCapacity, int usedCapacity, int backlog) {
    }

    public record RuntimeStatusResponse(
            String service,
            boolean runtimeActive,
            boolean autoRunEnabled,
            String schedulerStatus,
            Instant lastWorkAt,
            Instant lastEventAt,
            int eventsProducedLastTick,
            int eventsConsumedLastTick,
            int backlogCount,
            long oldestBacklogAgeSeconds,
            String latestCursor,
            String pipelineStatus,
            String degradedReason
    ) {
    }

    public record RuntimeTickResult(
            String tickId,
            int eventsProduced,
            int eventsConsumed,
            int backlogCount,
            boolean duplicate,
            Instant lastWorkAt
    ) {
    }

    public record BulkIngestionResponse(int received, int accepted, int duplicate, int failed,
                                        List<EventIngestionResponse> results) {
    }

    public record WorkforceAllocationRequest(
            String allocationId,
            @NotBlank String workdayId,
            LocalDate workDate,
            String sourceService,
            String targetService,
            @NotBlank String role,
            String roleType,
            int assignedUnits,
            Integer allocatedHeadcount,
            Integer capacityPerPersonPerDay,
            BigDecimal unitCostKrw,
            BigDecimal wagePerDay,
            BigDecimal productivityMultiplier,
            BigDecimal productivityScore,
            Boolean enabled,
            String status,
            String simulationRunId,
            String settlementCycleId,
            String correlationId,
            String causationId,
            Integer hopCount,
            Integer maxHop
    ) {
    }

    public record WorkforceAllocationView(
            String allocationId,
            String workdayId,
            LocalDate workDate,
            String sourceService,
            String targetService,
            String role,
            String roleType,
            int assignedUnits,
            int allocatedHeadcount,
            int capacityPerPersonPerDay,
            BigDecimal unitCostKrw,
            BigDecimal wagePerDay,
            BigDecimal productivityMultiplier,
            BigDecimal productivityScore,
            int effectiveCapacity,
            int usedCapacity,
            int remainingCapacity,
            boolean enabled,
            String status,
            Instant createdAt
    ) {
    }

    public record WorkforceWorkdayResult(
            String resultId,
            String workdayId,
            LocalDate workDate,
            String sourceService,
            int baselineCapacity,
            int allocatedCapacity,
            int demandCount,
            int processedCount,
            int backlogCount,
            int delayedCount,
            int transactionsReceived,
            int transactionsProcessed,
            int transactionsBacklog,
            int settlementReadyCount,
            int settlementCompletedCount,
            int settlementBacklog,
            int reconciliationProcessedCount,
            int reconciliationBacklog,
            int approvalReviewedCount,
            int approvalBacklog,
            int callbackProcessedCount,
            int callbackFailedCount,
            int callbackBacklog,
            BigDecimal operatingCostKrw,
            BigDecimal payrollCost,
            BigDecimal backlogCost,
            BigDecimal productivityScore,
            String bottleneckRole,
            boolean bottleneckDetected,
            String status,
            Instant createdAt
    ) {
    }

    public record WorkforceSummary(
            String service,
            String sourceService,
            boolean available,
            boolean workforceEnabled,
            int activeAllocations,
            int assignedUnits,
            int totalHeadcount,
            List<WorkforceRoleSummary> roles,
            BigDecimal dailyOperatingCostKrw,
            int baselineCapacity,
            int allocatedCapacity,
            int effectiveCapacity,
            int usedCapacity,
            int remainingCapacity,
            BigDecimal capacityUtilizationRate,
            int demandCount,
            int backlogCount,
            int transactionsBacklog,
            int approvalBacklog,
            int settlementBacklog,
            int reconciliationBacklog,
            int callbackBacklog,
            BigDecimal productivityScore,
            String bottleneckRole,
            BigDecimal payrollCost,
            String status,
            int approvalReviewerCapacity,
            int settlementOperatorCapacity,
            Instant latestEventAt,
            int transactionsProcessed,
            int approvalReviewed,
            int settlementsCompleted,
            int reconciliationWarnings,
            int callbackFailed
    ) {
    }

    public record WorkforceRoleSummary(
            String roleType,
            int allocatedHeadcount,
            int capacityPerPersonPerDay,
            BigDecimal productivityScore,
            BigDecimal wagePerDay,
            int effectiveCapacity,
            int usedCapacity,
            int remainingCapacity
    ) {
    }

    public record SettlementAgencySummary(
            String service,
            BigDecimal transactionProcessingFeeRevenue,
            BigDecimal settlementAgencyFeeRevenue,
            BigDecimal reconciliationFeeRevenue,
            BigDecimal approvalReviewFeeRevenue,
            BigDecimal totalRevenue,
            BigDecimal payrollCost,
            BigDecimal settlementBacklogCost,
            BigDecimal reconciliationDelayCost,
            BigDecimal approvalBacklogCost,
            BigDecimal callbackDelayCost,
            BigDecimal totalCost,
            BigDecimal netRevenue,
            int transactionsProcessed,
            int settlementCompleted,
            int reconciliationProcessed,
            int approvalReviewed,
            int transactionsBacklog,
            int settlementBacklog,
            int reconciliationBacklog,
            int approvalBacklog,
            int callbackBacklog,
            BigDecimal workforceProductivityScore,
            String bottleneckRole,
            SettlementBalanceSummary balance
    ) {
    }

    public record SettlementBalanceSummary(
            boolean available,
            String service,
            String calculationScope,
            String settlementCycleId,
            BigDecimal transactionProcessingRevenue,
            BigDecimal settlementAgencyRevenue,
            BigDecimal reconciliationRevenue,
            BigDecimal approvalReviewRevenue,
            BigDecimal workforceCost,
            BigDecimal callbackFailureCost,
            BigDecimal operatingCost,
            BigDecimal operatingProfit,
            BigDecimal operatingMargin,
            BigDecimal cashBalance,
            int transactionsReceived,
            int transactionsProcessed,
            int approvalBacklog,
            int settlementBacklog,
            int reconciliationBacklog,
            int callbackBacklog,
            BigDecimal capacityUtilization,
            String bottleneckRole,
            BigDecimal settlementDelayRate,
            int negativeProfitStreak,
            Instant calculatedAt
    ) {
    }
}
