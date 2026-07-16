package com.archiveledger.ledger;

import com.archiveledger.ledger.common.LedgerModels.*;
import com.archiveledger.ledger.security.ArchiveInboundSourceValidator;
import com.archiveledger.ledger.security.ArchiveRequestSecurityFilter;
import com.archiveledger.ledger.runtime.RuntimeOutboundService;
import com.archiveledger.ledger.runtime.RuntimeOutboundModels.RuntimeOutboundRecord;
import com.archiveledger.ledger.runtime.RuntimeOutboundModels.RuntimeOutboundSummary;
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
    private final ArchiveInboundSourceValidator inboundSourceValidator;
    private final RuntimeOutboundService runtimeOutbound;

    public LedgerController(LedgerService ledger, ArchiveInboundSourceValidator inboundSourceValidator,
                            RuntimeOutboundService runtimeOutbound) {
        this.ledger = ledger;
        this.inboundSourceValidator = inboundSourceValidator;
        this.runtimeOutbound = runtimeOutbound;
    }

    @PostMapping("/events/nexus")
    EventIngestionResponse ingest(@RequestHeader(value = ArchiveRequestSecurityFilter.SOURCE_HEADER, required = false) String sourceHeader,
                                  @Valid @RequestBody NexusEventRequest request) {
        inboundSourceValidator.verify(sourceHeader, request.source(), "archive-nexus");
        return ledger.ingest(request);
    }

    @PostMapping("/events/nexus/bulk")
    BulkIngestionResponse ingestBulk(@RequestHeader(value = ArchiveRequestSecurityFilter.SOURCE_HEADER, required = false) String sourceHeader,
                                     @Valid @RequestBody List<NexusEventRequest> requests) {
        requests.forEach(request -> inboundSourceValidator.verify(sourceHeader, request.source(), "archive-nexus"));
        return ledger.ingestBulk(requests);
    }

    @PostMapping("/events/logistics")
    EventIngestionResponse ingestLogistics(@RequestHeader(value = ArchiveRequestSecurityFilter.SOURCE_HEADER, required = false) String sourceHeader,
                                           @Valid @RequestBody NexusEventRequest request) {
        inboundSourceValidator.verify(sourceHeader, request.source(), "archive-logistics", "Archive-Logitics");
        return ledger.ingestLogistics(request);
    }

    @PostMapping("/events/logistics/bulk")
    BulkIngestionResponse ingestLogisticsBulk(@RequestHeader(value = ArchiveRequestSecurityFilter.SOURCE_HEADER, required = false) String sourceHeader,
                                              @Valid @RequestBody LogisticsBulkRequest request) {
        inboundSourceValidator.verify(sourceHeader, request.source(), "archive-logistics", "Archive-Logitics");
        request.events().forEach(event -> inboundSourceValidator.verify(sourceHeader, event.source(), "archive-logistics", "Archive-Logitics"));
        return ledger.ingestLogisticsBulk(request);
    }

    @PostMapping("/events/market")
    EventIngestionResponse ingestMarket(@RequestHeader(value = ArchiveRequestSecurityFilter.SOURCE_HEADER, required = false) String sourceHeader,
                                        @Valid @RequestBody NexusEventRequest request) {
        inboundSourceValidator.verify(sourceHeader, request.source(), "archive-market");
        return ledger.ingestMarket(request);
    }

    @PostMapping("/events/market/bulk")
    BulkIngestionResponse ingestMarketBulk(@RequestHeader(value = ArchiveRequestSecurityFilter.SOURCE_HEADER, required = false) String sourceHeader,
                                           @Valid @RequestBody MarketBulkRequest request) {
        inboundSourceValidator.verify(sourceHeader, request.source(), "archive-market");
        request.events().forEach(event -> inboundSourceValidator.verify(sourceHeader, event.source(), "archive-market"));
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

    @GetMapping("/runtime-events/recent")
    List<RuntimeEventView> recentRuntimeEvents(@RequestParam(required = false) String after,
                                               @RequestParam(defaultValue = "100") int limit) {
        return ledger.recentRuntimeEvents(limit, after);
    }

    @GetMapping("/runtime-events/correlation/{correlationId}")
    List<RuntimeEventView> runtimeEventsByCorrelation(@PathVariable String correlationId) {
        return ledger.runtimeEventsByCorrelation(correlationId);
    }

    @GetMapping("/runtime-events/entity/{entityId}")
    List<RuntimeEventView> runtimeEventsByEntity(@PathVariable String entityId) {
        return ledger.runtimeEventsByEntity(entityId);
    }

    @GetMapping("/runtime-outbound/summary")
    RuntimeOutboundSummary runtimeOutboundSummary() {
        return runtimeOutbound.summary();
    }

    @GetMapping("/runtime-outbound/events")
    List<RuntimeOutboundRecord> runtimeOutboundEvents(@RequestParam(required = false) String status,
                                                       @RequestParam(defaultValue = "100") int limit) {
        return runtimeOutbound.records(status, limit);
    }

    @GetMapping("/runtime-outbound/correlation/{correlationId}/preview")
    List<RuntimeOutboundRecord> runtimeOutboundPreview(@PathVariable String correlationId) {
        return runtimeOutbound.previewByCorrelation(correlationId);
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

    @GetMapping("/runtime/status")
    RuntimeStatusResponse runtimeStatus() {
        return ledger.runtimeStatus();
    }

    @GetMapping("/settlement-agency/summary")
    SettlementAgencySummary settlementAgencySummary() {
        return ledger.settlementAgencySummary();
    }

    @GetMapping("/workforce/summary")
    WorkforceSummary workforceSummary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                      @RequestParam(required = false) String sourceService) {
        return ledger.workforceSummary(date, sourceService);
    }

    @GetMapping("/productivity/summary")
    WorkforceSummary productivitySummary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                         @RequestParam(required = false) String sourceService) {
        return ledger.workforceSummary(date, sourceService);
    }

    @GetMapping("/capacity/summary")
    WorkforceSummary capacitySummary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                     @RequestParam(required = false) String sourceService) {
        return ledger.workforceSummary(date, sourceService);
    }

    @PostMapping("/workforce/allocations")
    WorkforceAllocationView assignWorkforce(@Valid @RequestBody WorkforceAllocationRequest request) {
        return ledger.assignWorkforce(request);
    }

    @PostMapping("/workforce/workday/run")
    WorkforceWorkdayResult runWorkday(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                      @RequestParam(defaultValue = "ArchiveOS") String sourceService,
                                      @RequestParam(required = false) String workdayId) {
        return ledger.runWorkday(date, sourceService, workdayId);
    }
}
