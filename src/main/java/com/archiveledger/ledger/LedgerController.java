package com.archiveledger.ledger;

import com.archiveledger.ledger.common.LedgerModels.*;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class LedgerController {
    private final LedgerService ledger;

    public LedgerController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping("/events/nexus")
    EventIngestionResponse ingest(@Valid @RequestBody NexusEventRequest request) {
        return ledger.ingest(request);
    }

    @PostMapping("/events/nexus/bulk")
    BulkIngestionResponse ingestBulk(@Valid @RequestBody List<NexusEventRequest> requests) {
        return ledger.ingestBulk(requests);
    }

    @PostMapping("/events/logistics")
    EventIngestionResponse ingestLogistics(@Valid @RequestBody NexusEventRequest request) {
        return ledger.ingestLogistics(request);
    }

    @PostMapping("/events/logistics/bulk")
    BulkIngestionResponse ingestLogisticsBulk(@Valid @RequestBody LogisticsBulkRequest request) {
        return ledger.ingestLogisticsBulk(request);
    }

    @PostMapping("/events/market")
    EventIngestionResponse ingestMarket(@Valid @RequestBody NexusEventRequest request) {
        return ledger.ingestMarket(request);
    }

    @PostMapping("/events/market/bulk")
    BulkIngestionResponse ingestMarketBulk(@Valid @RequestBody MarketBulkRequest request) {
        return ledger.ingestMarketBulk(request);
    }

    @GetMapping("/events/received")
    List<ReceivedEventView> receivedEvents(@RequestParam(required = false) String source) {
        return ledger.receivedEvents(source);
    }

    @GetMapping("/events/received/{eventId}")
    ResponseEntity<ReceivedEventView> receivedEvent(@PathVariable String eventId) {
        return ResponseEntity.of(ledger.receivedEvent(eventId));
    }

    @GetMapping("/transactions")
    List<TransactionView> transactions(@RequestParam(required = false) String status,
                                      @RequestParam(required = false) String source) {
        return ledger.transactions(status, source);
    }

    @GetMapping("/transactions/{transactionId}")
    ResponseEntity<TransactionView> transaction(@PathVariable String transactionId) {
        return ResponseEntity.of(ledger.transaction(transactionId));
    }

    @GetMapping("/ledger/entries")
    List<LedgerEntryView> ledgerEntries(@RequestParam(required = false) String transactionId) {
        return ledger.ledgerEntries(transactionId);
    }

    @GetMapping("/ledger/summary")
    LedgerSummary ledgerSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String factoryId,
            @RequestParam(required = false) String source) {
        return ledger.ledgerSummary(date, factoryId, source);
    }

    @PostMapping("/settlements/daily/run")
    SettlementBatchView runSettlement(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ledger.runSettlement(date);
    }

    @PostMapping("/batches/daily/run")
    DailyBatchRunView runDailyBatch(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                    @RequestParam(defaultValue = "Archive-Ledger-Operator") String approvedBy,
                                    @RequestParam(defaultValue = "MANUAL") String triggerType,
                                    @RequestParam(defaultValue = "true") boolean settlement,
                                    @RequestParam(defaultValue = "true") boolean reconciliation) {
        return ledger.runDailyBatch(date, approvedBy, triggerType, settlement, reconciliation);
    }

    @GetMapping("/batches/daily")
    List<DailyBatchRunView> dailyBatches() {
        return ledger.dailyBatches();
    }

    @GetMapping("/batches/daily/{runId}")
    ResponseEntity<DailyBatchRunView> dailyBatch(@PathVariable String runId) {
        return ResponseEntity.of(ledger.dailyBatch(runId));
    }

    @GetMapping("/settlements")
    List<SettlementBatchView> settlements() {
        return ledger.settlements();
    }

    @GetMapping("/settlements/{batchId}")
    SettlementBatchView settlement(@PathVariable String batchId) {
        return ledger.settlement(batchId);
    }

    @GetMapping("/settlements/{batchId}/details")
    List<SettlementDetailView> settlementDetails(@PathVariable String batchId) {
        return ledger.settlementDetails(batchId);
    }

    @PostMapping("/reconciliation/daily")
    ReconciliationView reconcile(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ledger.reconcile(date);
    }

    @GetMapping("/reconciliation/daily")
    ReconciliationView reconciliation(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ledger.reconciliation(date);
    }

    @GetMapping("/reconciliation/summary")
    ReconciliationView reconciliationSummary() {
        return ledger.reconciliationSummary();
    }

    @PostMapping("/approvals/callback")
    Map<String, Object> approvalCallback(@Valid @RequestBody ApprovalCallbackRequest request) {
        return ledger.approvalCallback(request);
    }

    @GetMapping("/operations/summary")
    OperationsSummary operationsSummary() {
        return ledger.operationsSummary();
    }
}
