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
                                    long refundTransactions, long claimCompensationTransactions) {
    }

    public record BulkIngestionResponse(int received, int accepted, int duplicate, int failed,
                                        List<EventIngestionResponse> results) {
    }
}
