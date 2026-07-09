package com.archiveledger.ledger.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class LedgerMetrics {
    private final Counter eventsReceived;
    private final Counter duplicateEvents;
    private final Counter transactionsCreated;
    private final Counter approvalRequired;
    private final Counter settlementCompleted;
    private final Counter reconciliationMismatch;
    private final Counter processingFailure;
    private final Timer settlementDuration;

    public LedgerMetrics(MeterRegistry registry) {
        this.eventsReceived = registry.counter("ledger_events_received_total");
        this.duplicateEvents = registry.counter("ledger_duplicate_events_total");
        this.transactionsCreated = registry.counter("ledger_transactions_created_total");
        this.approvalRequired = registry.counter("ledger_approval_required_total");
        this.settlementCompleted = registry.counter("ledger_settlement_completed_total");
        this.reconciliationMismatch = registry.counter("ledger_reconciliation_mismatch_total");
        this.processingFailure = registry.counter("ledger_event_processing_failure_total");
        this.settlementDuration = registry.timer("ledger_settlement_duration_seconds");
    }

    public void eventReceived() { eventsReceived.increment(); }
    public void duplicateEvent() { duplicateEvents.increment(); }
    public void transactionCreated() { transactionsCreated.increment(); }
    public void approvalRequired() { approvalRequired.increment(); }
    public void settlementCompleted() { settlementCompleted.increment(); }
    public void reconciliationMismatch(long count) { if (count > 0) reconciliationMismatch.increment(count); }
    public void processingFailure() { processingFailure.increment(); }
    public void settlementDuration(Duration duration) { settlementDuration.record(duration); }
}
