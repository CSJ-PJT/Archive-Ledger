package com.archiveledger.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class LedgerScheduledJobs {
    private static final Logger log = LoggerFactory.getLogger(LedgerScheduledJobs.class);

    private final LedgerService ledger;
    private final boolean enabled;
    private final boolean settlementEnabled;
    private final boolean reconciliationEnabled;
    private final long settlementDateOffsetDays;
    private final long reconciliationDateOffsetDays;

    public LedgerScheduledJobs(LedgerService ledger,
                               @Value("${archive-ledger.scheduler.enabled:false}") boolean enabled,
                               @Value("${archive-ledger.scheduler.settlement-enabled:true}") boolean settlementEnabled,
                               @Value("${archive-ledger.scheduler.reconciliation-enabled:true}") boolean reconciliationEnabled,
                               @Value("${archive-ledger.scheduler.settlement-date-offset-days:0}") long settlementDateOffsetDays,
                               @Value("${archive-ledger.scheduler.reconciliation-date-offset-days:0}") long reconciliationDateOffsetDays) {
        this.ledger = ledger;
        this.enabled = enabled;
        this.settlementEnabled = settlementEnabled;
        this.reconciliationEnabled = reconciliationEnabled;
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

        if (settlementEnabled) {
            runSettlementIfReady(LocalDate.now().minusDays(settlementDateOffsetDays));
        }
        if (reconciliationEnabled) {
            runReconciliation(LocalDate.now().minusDays(reconciliationDateOffsetDays));
        }
    }

    private void runSettlementIfReady(LocalDate date) {
        try {
            if (!ledger.hasSettlementReadyTransactions(date)) {
                return;
            }
            ledger.runSettlement(date);
            log.info("Scheduled settlement completed for {}", date);
        } catch (RuntimeException error) {
            log.warn("Scheduled settlement failed for {}: {}", date, error.getMessage());
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
