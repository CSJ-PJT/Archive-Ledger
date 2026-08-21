package com.archiveledger.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

@Component
public class LedgerScheduledJobs {
    private static final Logger log = LoggerFactory.getLogger(LedgerScheduledJobs.class);

    private final LedgerService ledger;
    private final boolean enabled;
    private final boolean settlementEnabled;
    private final boolean reconciliationEnabled;
    private final boolean autoApproveAllEnabled;
    private final int autoApprovalBatchSize;
    private final long settlementDateOffsetDays;
    private final long reconciliationDateOffsetDays;

    public LedgerScheduledJobs(LedgerService ledger,
                               @Value("${archive-ledger.scheduler.enabled:false}") boolean enabled,
                               @Value("${archive-ledger.scheduler.settlement-enabled:true}") boolean settlementEnabled,
                               @Value("${archive-ledger.scheduler.reconciliation-enabled:true}") boolean reconciliationEnabled,
                               @Value("${archive-ledger.scheduler.auto-approve-all-enabled:false}") boolean autoApproveAllEnabled,
                               @Value("${archive-ledger.scheduler.auto-approval-batch-size:1000}") int autoApprovalBatchSize,
                               @Value("${archive-ledger.scheduler.settlement-date-offset-days:0}") long settlementDateOffsetDays,
                               @Value("${archive-ledger.scheduler.reconciliation-date-offset-days:0}") long reconciliationDateOffsetDays) {
        this.ledger = ledger;
        this.enabled = enabled;
        this.settlementEnabled = settlementEnabled;
        this.reconciliationEnabled = reconciliationEnabled;
        this.autoApproveAllEnabled = autoApproveAllEnabled;
        this.autoApprovalBatchSize = Math.max(1, Math.min(autoApprovalBatchSize, 5_000));
        this.settlementDateOffsetDays = settlementDateOffsetDays;
        this.reconciliationDateOffsetDays = reconciliationDateOffsetDays;
    }

    @Scheduled(
            initialDelayString = "${archive-ledger.scheduler.initial-delay-ms:15000}",
            fixedDelayString = "${archive-ledger.scheduler.fixed-delay-ms:60000}"
    )
    public void runOperationalCycle() {
        if (!enabled) {
            return;
        }

        if (autoApproveAllEnabled) {
            try {
                Map<String, Object> result = ledger.approveAllRequested(autoApprovalBatchSize, "archive-ledger-approval-agent");
                if (((Number) result.getOrDefault("approved", 0)).intValue() > 0) {
                    log.info("Scheduled approval agent approved {} requests; {} remain", result.get("approved"), result.get("remaining"));
                }
            } catch (RuntimeException error) {
                log.warn("Scheduled approval agent failed: {}", error.getMessage());
                return;
            }
        }

        if (settlementEnabled) {
            runDailyBatchIfReady(LocalDate.now().minusDays(settlementDateOffsetDays));
            return;
        }
        runReconciliation(LocalDate.now().minusDays(reconciliationDateOffsetDays));
    }

    private void runDailyBatchIfReady(LocalDate date) {
        try {
            if (!ledger.hasSettlementReadyTransactions(date)) {
                if (reconciliationEnabled) {
                    runReconciliation(LocalDate.now().minusDays(reconciliationDateOffsetDays));
                }
                return;
            }
            ledger.runDailyBatch(date, "Archive-Ledger-Scheduler", "SCHEDULED", true, reconciliationEnabled);
            log.info("Scheduled daily batch completed for {}", date);
        } catch (RuntimeException error) {
            log.warn("Scheduled daily batch failed for {}: {}", date, error.getMessage());
        }
    }

    private void runReconciliation(LocalDate date) {
        try {
            ledger.reconcile(date);
            log.info("Scheduled reconciliation completed for {}", date);
        } catch (RuntimeException error) {
            log.warn("Scheduled reconciliation failed for {}: {}", date, error.getMessage());
        }
    }
}
