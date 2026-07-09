package com.archiveledger.ledger;

import com.archiveledger.ledger.approval.ArchiveOsApprovalClient;
import com.archiveledger.ledger.common.LedgerMetrics;
import com.archiveledger.ledger.common.LedgerModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@Service
public class LedgerService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ArchiveOsApprovalClient archiveOs;
    private final LedgerMetrics metrics;
    private final BigDecimal approvalThreshold;
    private static final String SOURCE_NEXUS = "Archive-Nexus";
    private static final String SOURCE_LOGITICS = "Archive-Logitics";
    private static final String SOURCE_LOGISTICS = "Archive-Logistics";
    private static final BigDecimal LOGISTICS_APPROVAL_THRESHOLD = new BigDecimal("300000");
    private static final int BULK_RESULT_LIMIT = 50;

    public LedgerService(JdbcTemplate jdbc,
                         ObjectMapper mapper,
                         ArchiveOsApprovalClient archiveOs,
                         LedgerMetrics metrics,
                         @Value("${archive-ledger.policy.approval-threshold-krw:3000000}") BigDecimal approvalThreshold) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.archiveOs = archiveOs;
        this.metrics = metrics;
        this.approvalThreshold = approvalThreshold;
    }

    @Transactional
    public EventIngestionResponse ingest(NexusEventRequest request) {
        return ingestWithSource(withSourceFallback(request, SOURCE_NEXUS));
    }

    @Transactional
    public EventIngestionResponse ingestLogistics(NexusEventRequest request) {
        return ingestWithSource(withSourceFallback(request, SOURCE_LOGITICS));
    }

    public BulkIngestionResponse ingestBulk(List<NexusEventRequest> requests) {
        return ingestBulkInternal(
                requests == null ? List.of() : requests,
                req -> withSourceFallback(req, SOURCE_NEXUS)
        );
    }

    public BulkIngestionResponse ingestLogisticsBulk(LogisticsBulkRequest request) {
        String source = value(request == null ? null : request.source(), SOURCE_LOGITICS);
        List<NexusEventRequest> events = request == null || request.events() == null
                ? List.of()
                : request.events().stream().map(event -> withSourceFallback(event, source)).toList();
        return ingestBulkInternal(events, Function.identity());
    }

    private BulkIngestionResponse ingestBulkInternal(List<NexusEventRequest> requests, Function<NexusEventRequest, NexusEventRequest> normalizer) {
        List<EventIngestionResponse> results = requests.stream()
                .map(normalizer)
                .map(this::ingestWithSource)
                .toList();
        int accepted = (int) results.stream().filter(r -> "ACCEPTED".equals(r.status())).count();
        int duplicate = (int) results.stream().filter(r -> r.duplicate()).count();
        int failed = (int) results.stream().filter(r -> "FAILED".equals(r.status())).count();
        List<EventIngestionResponse> limited = results.size() > BULK_RESULT_LIMIT ? results.subList(0, BULK_RESULT_LIMIT) : results;
        return new BulkIngestionResponse(requests.size(), accepted, duplicate, failed, limited);
    }

    private EventIngestionResponse ingestWithSource(NexusEventRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request is required.");
        }
        if (request.eventId() == null || request.eventId().isBlank()) {
            throw new IllegalArgumentException("eventId is required.");
        }
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required.");
        }

        String source = value(request.source(), SOURCE_NEXUS);

        if (exists("select count(*) from received_event where event_id=? or idempotency_key=?", request.eventId(), request.idempotencyKey())) {
            metrics.duplicateEvent();
            audit(request.eventId(), "Archive-Ledger", "DUPLICATE_EVENT", "received_event", request.eventId(),
                    "RECEIVED", "DUPLICATE",
                    Map.of("idempotencyKey", request.idempotencyKey(), "source", source));
            return new EventIngestionResponse(request.eventId(), "DUPLICATE", null, true, "Duplicate event ignored safely.");
        }

        Instant receivedAt = Instant.now();
        String transactionId = "TX-" + LocalDate.now().toString().replace("-", "") + "-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase(Locale.ROOT);
        String approvalRequestId = null;
        String status = "FAILED";
        Normalized normalized = null;

        try {
            jdbc.update("""
                    insert into received_event(event_id,idempotency_key,source,source_service,event_type,schema_version,payload,processing_status,received_at)
                    values(?,?,?,?,?,?,?,?,?)
                    """,
                    request.eventId(),
                    request.idempotencyKey(),
                    source,
                    source,
                    request.eventType(),
                    request.schemaVersion() == null ? 1 : request.schemaVersion(),
                    write(request.payload()),
                    "RECEIVED",
                    ts(receivedAt)
            );
            metrics.eventReceived();

            normalized = normalize(request, source);
            status = normalized.approvalRequired() ? "APPROVAL_REQUIRED" : "SETTLEMENT_READY";
            if (normalized.approvalRequired()) {
                approvalRequestId = "APR-" + LocalDate.now().toString().replace("-", "") + "-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase(Locale.ROOT);
            }

            jdbc.update("""
                    insert into finance_transaction(
                        transaction_id,source_event_id,idempotency_key,source_service,transaction_type,
                        factory_id,vendor_id,route_plan_id,shipment_id,origin_code,destination_code,risk_score,approval_reason,
                        synthetic_account_id,amount,currency,status,approval_required,approval_request_id,reason,occurred_at,created_at,updated_at
                    ) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    transactionId,
                    request.eventId(),
                    request.idempotencyKey(),
                    source,
                    normalized.transactionType(),
                    normalized.factoryId(),
                    normalized.vendorId(),
                    normalized.routePlanId(),
                    normalized.shipmentId(),
                    normalized.originCode(),
                    normalized.destinationCode(),
                    normalized.riskScore(),
                    normalized.approvalReason(),
                    normalized.syntheticAccountId(),
                    normalized.amount(),
                    normalized.currency(),
                    status,
                    normalized.approvalRequired(),
                    approvalRequestId,
                    normalized.reason(),
                    ts(normalized.occurredAt()),
                    ts(receivedAt),
                    ts(receivedAt)
            );
            metrics.transactionCreated();

            createLedgerEntries(transactionId, normalized, receivedAt);
            if (normalized.approvalRequired()) {
                metrics.approvalRequired();
                String evidence = fallbackEvidence(normalized);
                jdbc.update("""
                        insert into approval_request(approval_request_id,transaction_id,requested_to,status,amount,reason,policy_evidence,requested_at)
                        values(?,?,?,?,?,?,?,?)
                        """,
                        approvalRequestId,
                        transactionId,
                        "synthetic-finance-operator",
                        "REQUESTED",
                        normalized.amount(),
                        normalized.reason(),
                        evidence,
                        ts(receivedAt)
                );

                try {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("sourceService", source);
                    metadata.put("routePlanId", normalized.routePlanId());
                    metadata.put("shipmentId", normalized.shipmentId());
                    metadata.put("factoryId", normalized.factoryId());
                    metadata.put("vendorId", normalized.vendorId());
                    metadata.put("eventType", normalized.eventType());
                    metadata.put("riskScore", normalized.riskScore());
                    metadata.put("totalCost", normalized.amount());
                    metadata.put("requiresColdChain", normalized.requiresColdChain());
                    archiveOs.requestApproval(approvalRequestId, transactionId, normalized.amount(), normalized.currency(),
                            normalized.reason(), metadata);
                    audit(transactionId, "Archive-Ledger", "ARCHIVEOS_APPROVAL_REQUESTED", "approval_request",
                            approvalRequestId, null, "REQUESTED",
                            Map.of("archiveOsEnabled", archiveOs.enabled(), "sourceService", source));
                } catch (RuntimeException error) {
                    audit(transactionId, "Archive-Ledger", "ARCHIVEOS_APPROVAL_DEGRADED", "approval_request",
                            approvalRequestId, "REQUESTED", "REQUESTED", Map.of("error", error.getMessage(), "fallbackEvidence", evidence));
                }
            }

            jdbc.update("update received_event set processing_status='PROCESSED', processed_at=? where event_id=?", ts(Instant.now()), request.eventId());
            audit(request.eventId(), "Archive-Ledger", "EVENT_PROCESSED", "received_event", request.eventId(),
                    "RECEIVED", "PROCESSED", Map.of("transactionId", transactionId));
            audit(transactionId, "Archive-Ledger", "TRANSACTION_CREATED", "finance_transaction", transactionId,
                    null, status, Map.of("sourceEventId", request.eventId(), "eventType", request.eventType(), "sourceService", source));
            return new EventIngestionResponse(request.eventId(), "ACCEPTED", transactionId, false, "Synthetic event normalized.");
        } catch (DuplicateKeyException duplicate) {
            metrics.duplicateEvent();
            return new EventIngestionResponse(request.eventId(), "DUPLICATE", null, true, "Duplicate event ignored safely.");
        } catch (RuntimeException error) {
            metrics.processingFailure();
            String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            jdbc.update("update received_event set processing_status='FAILED', failure_reason=?, processed_at=? where event_id=?",
                    reason, ts(Instant.now()), request.eventId());
            audit(request.eventId(), "Archive-Ledger", "EVENT_PROCESSING_FAILED", "received_event", request.eventId(),
                    "RECEIVED", "FAILED", Map.of("error", reason, "source", source));
            if (status.equals("FAILED") && normalized != null) {
                audit(transactionId, "Archive-Ledger", "TRANSACTION_FAILED", "finance_transaction", transactionId,
                        status, "FAILED", Map.of("error", reason, "source", source));
            }
            return new EventIngestionResponse(request.eventId(), "FAILED", transactionId, false, reason);
        }
    }

    @Transactional
    public Map<String, Object> approvalCallback(ApprovalCallbackRequest request) {
        String next = "APPROVED".equalsIgnoreCase(request.decision()) ? "SETTLEMENT_READY" : "REJECTED";
        String before = jdbc.queryForObject("select status from finance_transaction where transaction_id=?", String.class, request.transactionId());
        jdbc.update("update finance_transaction set status=?, updated_at=? where transaction_id=?", next, ts(Instant.now()), request.transactionId());
        jdbc.update("""
                update approval_request
                set status=?, decided_at=?, decided_by=?
                where approval_request_id=? or transaction_id=?
                """,
                "APPROVED".equals(next) ? "APPROVED" : request.decision().toUpperCase(Locale.ROOT),
                ts(Instant.now()),
                value(request.decidedBy(), "synthetic-operator"),
                request.approvalRequestId(),
                request.transactionId()
        );
        audit(request.transactionId(), value(request.decidedBy(), "synthetic-operator"), "APPROVAL_CALLBACK",
                "finance_transaction", request.transactionId(), before, next, Map.of("comment", value(request.comment(), "")));
        return Map.of("transactionId", request.transactionId(), "previousStatus", before, "status", next);
    }

    @Transactional
    public SettlementBatchView runSettlement(LocalDate date) {
        Instant started = Instant.now();
        String batchId = "SET-" + date.toString().replace("-", "") + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        jdbc.update("insert into settlement_batch(batch_id,settlement_date,status,total_transaction_count,total_amount,started_at) values(?,?,?,?,?,?)",
                batchId, Date.valueOf(date), "RUNNING", 0, BigDecimal.ZERO, ts(started));

        try {
            List<TransactionView> candidates = transactionsByStatus("SETTLEMENT_READY").stream()
                    .filter(tx -> LocalDate.ofInstant(tx.occurredAt(), ZoneId.systemDefault()).equals(date))
                    .toList();
            BigDecimal total = BigDecimal.ZERO;
            for (TransactionView tx : candidates) {
                total = total.add(tx.amount());
                jdbc.update("""
                        insert into settlement_detail(batch_id,transaction_id,factory_id,vendor_id,account_code,amount,status,created_at)
                        values(?,?,?,?,?,?,?,?)
                        """,
                        batchId, tx.transactionId(), tx.factoryId(), tx.vendorId(),
                        settlementAccount(tx.transactionType()),
                        tx.amount(), "SETTLED", ts(Instant.now()));
                jdbc.update("update finance_transaction set status='SETTLED', updated_at=? where transaction_id=?", ts(Instant.now()), tx.transactionId());
                audit(tx.transactionId(), "Archive-Ledger", "TRANSACTION_SETTLED", "finance_transaction", tx.transactionId(),
                        "SETTLEMENT_READY", "SETTLED", Map.of("batchId", batchId));
            }
            jdbc.update("update settlement_batch set status='SUCCESS', total_transaction_count=?, total_amount=?, completed_at=? where batch_id=?",
                    candidates.size(), total, ts(Instant.now()), batchId);
            metrics.settlementCompleted();
            metrics.settlementDuration(Duration.between(started, Instant.now()));
            return settlement(batchId);
        } catch (RuntimeException error) {
            jdbc.update("update settlement_batch set status='FAILED', failure_reason=?, completed_at=? where batch_id=?",
                    error.getMessage(), ts(Instant.now()), batchId);
            throw error;
        }
    }

    public boolean hasSettlementReadyTransactions(LocalDate date) {
        return count("select count(*) from finance_transaction where status='SETTLEMENT_READY' and cast(occurred_at as date)=?",
                Date.valueOf(date)) > 0;
    }

    @Transactional
    public ReconciliationView reconcile(LocalDate date) {
        Date day = Date.valueOf(date);
        int received = count("select count(*) from received_event where cast(received_at as date)=?", day);
        int directEvents = count("select count(*) from received_event where cast(received_at as date)=? and coalesce(source_service, source)=?",
                day, SOURCE_NEXUS);
        int logisticsEvents = count("select count(*) from received_event where cast(received_at as date)=? and coalesce(source_service, source) in (?,?)",
                day, SOURCE_LOGITICS, SOURCE_LOGISTICS);
        int created = count("select count(*) from finance_transaction where cast(created_at as date)=?", day);
        int directTransactions = count("select count(*) from finance_transaction where cast(created_at as date)=? and coalesce(source_service, 'Archive-Unknown')=?",
                day, SOURCE_NEXUS);
        int logisticsTransactions = count("select count(*) from finance_transaction where cast(created_at as date)=? and coalesce(source_service, 'Archive-Unknown') in (?,?)",
                day, SOURCE_LOGITICS, SOURCE_LOGISTICS);
        int failed = count("select count(*) from received_event where processing_status='FAILED' and cast(received_at as date)=?", day);
        int duplicate = count("select count(*) from audit_log where action='DUPLICATE_EVENT' and cast(created_at as date)=?", day);
        int approval = count("select count(*) from finance_transaction where status='APPROVAL_REQUIRED' and cast(created_at as date)=?", day);
        int ready = count("select count(*) from finance_transaction where status='SETTLEMENT_READY' and cast(created_at as date)=?", day);
        int settled = count("select count(*) from finance_transaction where status='SETTLED' and cast(created_at as date)=?", day);
        int expectedTransactionCount = Math.max(0, received - duplicate);
        int mismatch = Math.max(0, expectedTransactionCount - created - failed);
        String status = mismatch == 0 ? "OK" : "WARNING";

        jdbc.update("""
                insert into reconciliation_result(
                    reconciliation_date,nexus_event_count,received_event_count,created_transaction_count,
                    duplicate_event_count,failed_event_count,approval_required_count,settlement_ready_count,settled_count,mismatch_count,
                    status,created_at,logistics_event_count,direct_event_count,logistics_transaction_count,direct_transaction_count
                ) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                day,
                directEvents,
                received,
                created,
                duplicate,
                failed,
                approval,
                ready,
                settled,
                mismatch,
                status,
                ts(Instant.now()),
                logisticsEvents,
                directEvents,
                logisticsTransactions,
                directTransactions
        );
        metrics.reconciliationMismatch(mismatch);
        return reconciliation(date);
    }

    public List<ReceivedEventView> receivedEvents() {
        return receivedEvents(null);
    }

    public List<ReceivedEventView> receivedEvents(String source) {
        if (source == null || source.isBlank()) {
            return jdbc.query("select * from received_event order by received_at desc limit 500", this::receivedEventRow);
        }
        if (isLogisticsSource(source)) {
            return jdbc.query("select * from received_event where coalesce(source_service, source) in (?,?) order by received_at desc limit 500",
                    this::receivedEventRow, SOURCE_LOGITICS, SOURCE_LOGISTICS);
        }
        return jdbc.query("select * from received_event where coalesce(source_service, source)=? order by received_at desc limit 500",
                this::receivedEventRow, source);
    }

    public Optional<ReceivedEventView> receivedEvent(String eventId) {
        List<ReceivedEventView> values = jdbc.query("select * from received_event where event_id=?", this::receivedEventRow, eventId);
        return values.stream().findFirst();
    }

    public List<TransactionView> transactions(String status, String source) {
        StringBuilder sql = new StringBuilder("select * from finance_transaction where 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" and status=?");
            args.add(status);
        }
        if (source != null && !source.isBlank()) {
            appendSourceFilter(sql, args, "coalesce(source_service, 'Archive-Unknown')", source);
        }
        sql.append(" order by created_at desc limit 500");
        return jdbc.query(sql.toString(), this::transactionRow, args.toArray());
    }

    public List<TransactionView> transactions(String status) {
        return transactions(status, null);
    }

    public Optional<TransactionView> transaction(String id) {
        List<TransactionView> values = jdbc.query("select * from finance_transaction where transaction_id=?", this::transactionRow, id);
        return values.stream().findFirst();
    }

    public List<LedgerEntryView> ledgerEntries(String transactionId) {
        String sql = (transactionId == null || transactionId.isBlank())
                ? "select * from ledger_entry order by created_at desc limit 500"
                : "select * from ledger_entry where transaction_id=? order by id";
        Object[] args = (transactionId == null || transactionId.isBlank()) ? new Object[]{} : new Object[]{transactionId};
        return jdbc.query(sql, this::ledgerRow, args);
    }

    public LedgerSummary ledgerSummary(LocalDate date, String factoryId, String source) {
        StringBuilder sql = new StringBuilder("select le.* from ledger_entry le join finance_transaction ft on ft.transaction_id=le.transaction_id where 1=1");
        List<Object> args = new ArrayList<>();
        if (date != null) {
            sql.append(" and cast(le.occurred_at as date)=?");
            args.add(Date.valueOf(date));
        }
        if (factoryId != null && !factoryId.isBlank()) {
            sql.append(" and le.factory_id=?");
            args.add(factoryId);
        }
        if (source != null && !source.isBlank()) {
            appendSourceFilter(sql, args, "coalesce(ft.source_service, 'Archive-Unknown')", source);
        }
        sql.append(" order by le.created_at desc");
        List<LedgerEntryView> rows = jdbc.query(sql.toString(), this::ledgerRow, args.toArray());
        BigDecimal debit = rows.stream().map(LedgerEntryView::debitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = rows.stream().map(LedgerEntryView::creditAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        String scope = date != null ? date.toString() : value(factoryId, "all");
        if (source != null && !source.isBlank()) {
            scope = scope + "|" + source;
        }
        return new LedgerSummary(scope, debit, credit, rows.size());
    }

    public List<SettlementBatchView> settlements() {
        return jdbc.query("select * from settlement_batch order by started_at desc limit 200", this::settlementRow);
    }

    public SettlementBatchView settlement(String batchId) {
        return jdbc.queryForObject("select * from settlement_batch where batch_id=?", this::settlementRow, batchId);
    }

    public List<SettlementDetailView> settlementDetails(String batchId) {
        return jdbc.query("select * from settlement_detail where batch_id=? order by id",
                (rs, row) -> new SettlementDetailView(
                        rs.getString("batch_id"),
                        rs.getString("transaction_id"),
                        rs.getString("factory_id"),
                        rs.getString("vendor_id"),
                        rs.getString("account_code"),
                        rs.getBigDecimal("amount"),
                        rs.getString("status"),
                        instant(rs.getTimestamp("created_at"))
                ), batchId);
    }

    public ReconciliationView reconciliation(LocalDate date) {
        List<ReconciliationView> rows = jdbc.query("select * from reconciliation_result where reconciliation_date=? order by created_at desc limit 1",
                this::reconciliationRow, Date.valueOf(date));
        return rows.isEmpty() ? reconcile(date) : rows.get(0);
    }

    public ReconciliationView reconciliationSummary() {
        List<ReconciliationView> rows = jdbc.query("select * from reconciliation_result order by created_at desc limit 1",
                this::reconciliationRow);
        return rows.isEmpty() ? reconcile(LocalDate.now()) : rows.get(0);
    }

    public OperationsSummary operationsSummary() {
        String lastStatus = jdbc.query("select status from reconciliation_result order by created_at desc limit 1",
                rs -> rs.next() ? rs.getString(1) : "UNKNOWN");
        Instant lastSettlement = jdbc.query("select completed_at from settlement_batch where completed_at is not null order by completed_at desc limit 1",
                rs -> rs.next() ? instant(rs.getTimestamp(1)) : null);
        long failed = count("select count(*) from received_event where processing_status='FAILED'");
        long received = count("select count(*) from received_event");
        long transactionCount = count("select count(*) from finance_transaction");
        long duplicates = count("select count(*) from audit_log where action='DUPLICATE_EVENT'");
        long approvalRequired = count("select count(*) from finance_transaction where status='APPROVAL_REQUIRED'");
        long settled = count("select count(*) from finance_transaction where status='SETTLED'");
        long eventsFromNexus = count("select count(*) from received_event where coalesce(source_service, source)=?", SOURCE_NEXUS);
        long eventsFromLogitics = count("select count(*) from received_event where coalesce(source_service, source) in (?,?)",
                SOURCE_LOGITICS, SOURCE_LOGISTICS);
        long logisticsReceived = count("select count(*) from received_event where coalesce(source_service, source) in (?,?)",
                SOURCE_LOGITICS, SOURCE_LOGISTICS);
        long logisticsCostTransactions = count("select count(*) from finance_transaction where transaction_type='LOGISTICS_COST' and coalesce(source_service, 'Archive-Unknown') in (?,?)",
                SOURCE_LOGITICS, SOURCE_LOGISTICS);
        long urgentDeliveryTransactions = count("select count(*) from finance_transaction where transaction_type='URGENT_DELIVERY_COST' and coalesce(source_service, 'Archive-Unknown') in (?,?)",
                SOURCE_LOGITICS, SOURCE_LOGISTICS);
        long delayPenaltyTransactions = count("select count(*) from finance_transaction where transaction_type='DELAY_PENALTY' and coalesce(source_service, 'Archive-Unknown') in (?,?)",
                SOURCE_LOGITICS, SOURCE_LOGISTICS);
        long routeDeviationTransactions = count("select count(*) from finance_transaction where transaction_type='ROUTE_DEVIATION_COST' and coalesce(source_service, 'Archive-Unknown') in (?,?)",
                SOURCE_LOGITICS, SOURCE_LOGISTICS);
        long coldChainRiskTransactions = count("select count(*) from finance_transaction where transaction_type='COLD_CHAIN_RISK_COST' and coalesce(source_service, 'Archive-Unknown') in (?,?)",
                SOURCE_LOGITICS, SOURCE_LOGISTICS);
        String status = failed > 0 ? "DEGRADED" : "HEALTHY";
        return new OperationsSummary(
                status,
                received,
                transactionCount,
                duplicates,
                approvalRequired,
                settled,
                failed,
                lastSettlement,
                lastStatus,
                eventsFromNexus,
                eventsFromLogitics,
                logisticsReceived,
                logisticsCostTransactions,
                urgentDeliveryTransactions,
                delayPenaltyTransactions,
                routeDeviationTransactions,
                coldChainRiskTransactions
        );
    }

    private Normalized normalize(NexusEventRequest request, String source) {
        if (isLogisticsRequest(request, source)) {
            return normalizeLogistics(request, source);
        }
        return normalizeDirect(request, source);
    }

    private boolean isLogisticsRequest(NexusEventRequest request, String source) {
        return isLogisticsSource(source) && (
                "LOGISTICS_DISPATCHED".equals(request.eventType()) ||
                        "LOGISTICS_COST_CONFIRMED".equals(request.eventType()) ||
                        "URGENT_DELIVERY_COST_CONFIRMED".equals(request.eventType()) ||
                        "DELAY_PENALTY_CONFIRMED".equals(request.eventType()) ||
                        "ROUTE_DEVIATION_COST_CONFIRMED".equals(request.eventType()) ||
                        "COLD_CHAIN_RISK_COST_CONFIRMED".equals(request.eventType()) ||
                        "LOGISTICS_DAILY_SETTLEMENT_FEE_EARNED".equals(request.eventType())
        );
    }

    private boolean isLogisticsSource(String source) {
        return SOURCE_LOGITICS.equals(source) || SOURCE_LOGISTICS.equals(source);
    }

    private void appendSourceFilter(StringBuilder sql, List<Object> args, String expression, String source) {
        if (isLogisticsSource(source)) {
            sql.append(" and ").append(expression).append(" in (?,?)");
            args.add(SOURCE_LOGITICS);
            args.add(SOURCE_LOGISTICS);
            return;
        }
        sql.append(" and ").append(expression).append("=?");
        args.add(source);
    }

    private Normalized normalizeLogistics(NexusEventRequest request, String source) {
        Map<String, Object> payload = request.payload();
        if (payload == null) {
            throw new IllegalArgumentException("Payload is required.");
        }

        String transactionType = switch (request.eventType()) {
            case "LOGISTICS_DISPATCHED", "LOGISTICS_COST_CONFIRMED" -> "LOGISTICS_COST";
            case "URGENT_DELIVERY_COST_CONFIRMED" -> "URGENT_DELIVERY_COST";
            case "DELAY_PENALTY_CONFIRMED" -> "DELAY_PENALTY";
            case "ROUTE_DEVIATION_COST_CONFIRMED" -> "ROUTE_DEVIATION_COST";
            case "COLD_CHAIN_RISK_COST_CONFIRMED" -> "COLD_CHAIN_RISK_COST";
            case "LOGISTICS_DAILY_SETTLEMENT_FEE_EARNED" -> "LOGISTICS_DAILY_SETTLEMENT_FEE";
            default -> throw new IllegalArgumentException("Unsupported logistics event_type: " + request.eventType());
        };

        BigDecimal amount = logisticsAmount(request.eventType(), payload);
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount is required for logistics events.");
        }

        BigDecimal riskScore = decimalOrNull(payload.get("riskScore"), new BigDecimal("0"));
        BigDecimal coldChainPenalty = decimalOrNull(payload.get("coldChainPenalty"), BigDecimal.ZERO);
        boolean delayed = bool(payload.get("delayed"), false);
        boolean requiresColdChain = bool(payload.get("requiresColdChain"), false);
        boolean requiresApproval = bool(payload.get("requiresApproval"), false);
        String priority = string(payload, "priority", "NORMAL");
        String currency = string(payload, "currency", "KRW");
        boolean approvalRequired = requiresApproval
                || amount.compareTo(LOGISTICS_APPROVAL_THRESHOLD) >= 0
                || riskScore.compareTo(new BigDecimal("0.85")) >= 0
                || "COLD_CHAIN_RISK_COST_CONFIRMED".equals(request.eventType())
                || ("URGENT_DELIVERY_COST_CONFIRMED".equals(request.eventType()) && amount.compareTo(LOGISTICS_APPROVAL_THRESHOLD) >= 0)
                || coldChainPenalty.compareTo(BigDecimal.ZERO) > 0
                || (delayed && requiresColdChain)
                || "CRITICAL".equalsIgnoreCase(priority);

        String reason = string(payload, "reason", "Synthetic logistics cost confirmed by Archive-Logitics");
        String approvalReason = buildLogisticsApprovalReason(approvalRequired, request.eventType(), amount, riskScore,
                coldChainPenalty, delayed, requiresColdChain, "CRITICAL".equalsIgnoreCase(priority));

        return new Normalized(
                transactionType,
                string(payload, "factoryId", null),
                string(payload, "vendorId", null),
                string(payload, "syntheticAccountId", null),
                string(payload, "routePlanId", null),
                string(payload, "shipmentId", null),
                string(payload, "originCode", null),
                string(payload, "destinationCode", null),
                riskScore,
                approvalReason,
                approvalRequired,
                approvalRequired ? "APPROVAL_REQUIRED" : "SETTLEMENT_READY",
                reason,
                request.occurredAt() == null ? Instant.now() : request.occurredAt(),
                source,
                request.eventType(),
                priority,
                requiresColdChain,
                amount.setScale(2, RoundingMode.HALF_UP),
                currency
        );
    }

    private BigDecimal logisticsAmount(String eventType, Map<String, Object> payload) {
        if ("LOGISTICS_DAILY_SETTLEMENT_FEE_EARNED".equals(eventType)) {
            return firstNonNull(
                    decimalOrNull(payload.get("ledgerFeePaid")),
                    decimalOrNull(payload.get("settlementFee")),
                    decimalOrNull(payload.get("totalCost")),
                    decimalOrNull(payload.get("amount"))
            );
        }
        return firstNonNull(
                decimalOrNull(payload.get("totalCost")),
                decimalOrNull(payload.get("estimatedCost")),
                decimalOrNull(payload.get("amount"))
        );
    }

    private String buildLogisticsApprovalReason(boolean approvalRequired, String eventType, BigDecimal amount, BigDecimal riskScore,
                                                BigDecimal coldChainPenalty, boolean delayed, boolean requiresColdChain, boolean critical) {
        if (!approvalRequired) {
            return "LOGISTICS cost event accepted.";
        }
        List<String> reasons = new ArrayList<>();
        if (amount.compareTo(LOGISTICS_APPROVAL_THRESHOLD) >= 0) reasons.add("totalCost>=300000");
        if (riskScore.compareTo(new BigDecimal("0.85")) >= 0) reasons.add("riskScore>=0.85");
        if ("COLD_CHAIN_RISK_COST_CONFIRMED".equals(eventType)) reasons.add("cold_chain_risk");
        if ("URGENT_DELIVERY_COST_CONFIRMED".equals(eventType) && amount.compareTo(LOGISTICS_APPROVAL_THRESHOLD) >= 0)
            reasons.add("urgent_delivery_high_cost");
        if (coldChainPenalty.compareTo(BigDecimal.ZERO) > 0) reasons.add("coldChainPenalty>0");
        if (delayed && requiresColdChain) reasons.add("delayed_and_cold_chain");
        if (critical) reasons.add("priority=CRITICAL");
        return "Approval required: " + String.join(",", reasons);
    }

    private Normalized normalizeDirect(NexusEventRequest request, String source) {
        Map<String, Object> payload = request.payload();
        if (payload == null) {
            throw new IllegalArgumentException("Payload is required.");
        }
        String transactionType = switch (request.eventType()) {
            case "MAINTENANCE_COMPLETED" -> "MAINTENANCE_EXPENSE";
            case "QUALITY_DEFECT_DETECTED" -> "QUALITY_LOSS";
            case "LOGISTICS_DISPATCHED" -> "LOGISTICS_COST";
            case "MATERIAL_CONSUMED" -> "MATERIAL_COST";
            case "EMERGENCY_PURCHASE_REQUESTED" -> "EMERGENCY_PURCHASE_EXPENSE";
            case "CORPORATE_CARD_USED" -> "CORPORATE_CARD_EXPENSE";
            case "VENDOR_PAYMENT_REQUESTED" -> "VENDOR_PAYMENT";
            case "QUALITY_CLAIM_CHARGED" -> "QUALITY_CLAIM_CHARGEBACK";
            case "SHIPMENT_HOLD_CREATED" -> "SHIPMENT_HOLD_COST";
            case "PRODUCTION_COMPLETED" -> "PRODUCTION_COST";
            default -> throw new IllegalArgumentException("Unsupported event_type: " + request.eventType());
        };
        BigDecimal amount = firstNonNull(
                decimalOrNull(payload.get("estimatedCost")),
                decimalOrNull(payload.get("amount")),
                decimalOrNull(payload.get("cost"))
        );
        if (amount == null) {
            throw new IllegalArgumentException("amount is required for direct events.");
        }
        String severity = string(payload, "severity", "NORMAL");
        boolean requiresApproval = bool(payload.get("requiresApproval"), false);
        boolean approvalRequired = amount.compareTo(approvalThreshold) >= 0
                || "HIGH".equalsIgnoreCase(severity)
                || "CRITICAL".equalsIgnoreCase(severity)
                || "EMERGENCY_PURCHASE_REQUESTED".equals(request.eventType())
                || "QUALITY_CLAIM_CHARGED".equals(request.eventType())
                || "WARNING".equals(string(payload, "vendorRisk", "NORMAL"))
                || "BLOCKED".equals(string(payload, "vendorRisk", "NORMAL"))
                || requiresApproval;

        String reason = string(payload, "reason", "Synthetic finance operation");
        return new Normalized(
                transactionType,
                string(payload, "factoryId", "FAC-SYN"),
                string(payload, "vendorId", "VENDOR-SYN"),
                string(payload, "syntheticAccountId", null),
                null,
                null,
                null,
                null,
                null,
                null,
                approvalRequired,
                approvalRequired ? "APPROVAL_REQUIRED" : "SETTLEMENT_READY",
                reason,
                request.occurredAt() == null ? Instant.now() : request.occurredAt(),
                source,
                request.eventType(),
                severity,
                false,
                amount.setScale(2, RoundingMode.HALF_UP),
                string(payload, "currency", "KRW")
        );
    }

    private void createLedgerEntries(String transactionId, Normalized normalized, Instant createdAt) {
        Account debit = debitAccount(normalized.transactionType());
        Account credit = creditAccount(normalized.transactionType());
        insertLedger(transactionId, debit.code(), debit.name(), normalized.amount(), BigDecimal.ZERO, normalized, createdAt);
        insertLedger(transactionId, credit.code(), credit.name(), BigDecimal.ZERO, normalized.amount(), normalized, createdAt);
    }

    private void insertLedger(String transactionId, String accountCode, String accountName, BigDecimal debit, BigDecimal credit,
                             Normalized normalized, Instant createdAt) {
        jdbc.update("""
                insert into ledger_entry(transaction_id,account_code,account_name,debit_amount,credit_amount,factory_id,vendor_id,
                                         occurred_at,created_at,source_service)
                values(?,?,?,?,?,?,?,?,?,?)
                """,
                transactionId, accountCode, accountName, debit, credit, normalized.factoryId(), normalized.vendorId(),
                ts(normalized.occurredAt()), ts(createdAt), normalized.sourceService());
    }

    private Account debitAccount(String type) {
        return switch (type) {
            case "LOGISTICS_COST" -> new Account("LOGISTICS_EXPENSE", "Synthetic Logistics Expense");
            case "URGENT_DELIVERY_COST" -> new Account("URGENT_DELIVERY_EXPENSE", "Synthetic Urgent Delivery Expense");
            case "DELAY_PENALTY" -> new Account("DELAY_PENALTY_EXPENSE", "Synthetic Delay Penalty Expense");
            case "ROUTE_DEVIATION_COST" -> new Account("ROUTE_DEVIATION_EXPENSE", "Synthetic Route Deviation Expense");
            case "COLD_CHAIN_RISK_COST" -> new Account("COLD_CHAIN_RISK_EXPENSE", "Synthetic Cold Chain Risk Expense");
            case "LOGISTICS_DAILY_SETTLEMENT_FEE" -> new Account("LOGISTICS_SETTLEMENT_EXPENSE", "Synthetic Logistics Settlement Expense");
            case "MAINTENANCE_EXPENSE" -> new Account("MAINTENANCE_EXPENSE", "Synthetic Maintenance Expense");
            case "QUALITY_LOSS", "QUALITY_CLAIM_CHARGEBACK" -> new Account("QUALITY_LOSS", "Synthetic Quality Loss");
            case "SHIPMENT_HOLD_COST" -> new Account("LOGISTICS_EXPENSE", "Synthetic Logistics Expense");
            case "MATERIAL_COST" -> new Account("MATERIAL_COST", "Synthetic Material Cost");
            case "CORPORATE_CARD_EXPENSE", "EMERGENCY_PURCHASE_EXPENSE" -> new Account("OPERATING_EXPENSE", "Synthetic Operating Expense");
            case "VENDOR_PAYMENT", "PRODUCTION_COST" -> new Account(type, "Synthetic " + type);
            default -> new Account("ACCOUNTS_PAYABLE", "Synthetic Accounts Payable");
        };
    }

    private Account creditAccount(String type) {
        return switch (type) {
            case "CORPORATE_CARD_EXPENSE" -> new Account("CORPORATE_CARD_PAYABLE", "Synthetic Corporate Card Payable");
            case "VENDOR_PAYMENT" -> new Account("CASH_CLEARING", "Synthetic Cash Clearing");
            default -> new Account("ACCOUNTS_PAYABLE", "Synthetic Accounts Payable");
        };
    }

    private String settlementAccount(String transactionType) {
        return debitAccount(transactionType).code();
    }

    private String fallbackEvidence(Normalized normalized) {
        return "Synthetic policy evidence: amount=" + normalized.amount() + ", source=" + normalized.sourceService()
                + ", event=" + normalized.eventType() + ", riskScore=" + normalized.riskScore()
                + ", requiresColdChain=" + normalized.requiresColdChain()
                + ", severity=" + normalized.severity();
    }

    private List<TransactionView> transactionsByStatus(String status) {
        return jdbc.query("select * from finance_transaction where status=? order by created_at desc", this::transactionRow, status);
    }

    private ReceivedEventView receivedEventRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ReceivedEventView(
                rs.getString("event_id"),
                rs.getString("idempotency_key"),
                rs.getString("source"),
                rs.getString("event_type"),
                rs.getString("processing_status"),
                instant(rs.getTimestamp("received_at")),
                instant(rs.getTimestamp("processed_at")),
                rs.getString("failure_reason")
        );
    }

    private TransactionView transactionRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new TransactionView(
                rs.getString("transaction_id"),
                rs.getString("source_event_id"),
                rs.getString("idempotency_key"),
                rs.getString("transaction_type"),
                rs.getString("factory_id"),
                rs.getString("vendor_id"),
                rs.getString("synthetic_account_id"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("status"),
                rs.getBoolean("approval_required"),
                rs.getString("approval_request_id"),
                rs.getString("reason"),
                instant(rs.getTimestamp("occurred_at")),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at"))
        );
    }

    private LedgerEntryView ledgerRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new LedgerEntryView(
                rs.getString("transaction_id"),
                rs.getString("account_code"),
                rs.getString("account_name"),
                rs.getBigDecimal("debit_amount"),
                rs.getBigDecimal("credit_amount"),
                rs.getString("factory_id"),
                rs.getString("vendor_id"),
                instant(rs.getTimestamp("occurred_at")),
                instant(rs.getTimestamp("created_at"))
        );
    }

    private SettlementBatchView settlementRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new SettlementBatchView(
                rs.getString("batch_id"),
                rs.getDate("settlement_date").toLocalDate(),
                rs.getString("status"),
                rs.getInt("total_transaction_count"),
                rs.getBigDecimal("total_amount"),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("completed_at")),
                rs.getString("failure_reason")
        );
    }

    private ReconciliationView reconciliationRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ReconciliationView(
                rs.getDate("reconciliation_date").toLocalDate(),
                rs.getInt("nexus_event_count"),
                rs.getInt("received_event_count"),
                rs.getInt("created_transaction_count"),
                rs.getInt("logistics_event_count"),
                rs.getInt("direct_event_count"),
                rs.getInt("logistics_transaction_count"),
                rs.getInt("direct_transaction_count"),
                rs.getInt("duplicate_event_count"),
                rs.getInt("failed_event_count"),
                rs.getInt("approval_required_count"),
                rs.getInt("settlement_ready_count"),
                rs.getInt("settled_count"),
                rs.getInt("mismatch_count"),
                rs.getString("status"),
                instant(rs.getTimestamp("created_at"))
        );
    }

    private void audit(String traceId, String actor, String action, String targetType, String targetId, String before, String after, Map<String, Object> detail) {
        jdbc.update("""
                insert into audit_log(trace_id,actor,action,target_type,target_id,before_status,after_status,detail,created_at)
                values(?,?,?,?,?,?,?,?,?)
                """,
                traceId,
                actor,
                action,
                targetType,
                targetId,
                before,
                after,
                write(detail),
                ts(Instant.now())
        );
    }

    private boolean exists(String sql, Object... args) {
        return count(sql, args) > 0;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("JSON serialization failed", error);
        }
    }

    private BigDecimal decimalOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return new BigDecimal(String.valueOf(value));
    }

    private BigDecimal decimalOrNull(Object value, BigDecimal fallback) {
        BigDecimal parsed = decimalOrNull(value);
        return parsed == null ? fallback : parsed;
    }

    private BigDecimal firstNonNull(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String string(Map<String, Object> payload, String key, String fallback) {
        Object value = payload.get(key);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private boolean bool(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean boolValue) {
            return boolValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String value(String source, String fallback) {
        return source == null || source.isBlank() ? fallback : source;
    }

    private Timestamp ts(Instant value) {
        return Timestamp.from(value);
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private NexusEventRequest withSourceFallback(NexusEventRequest request, String defaultSource) {
        if (request == null) {
            throw new IllegalArgumentException("Request is required.");
        }
        String source = value(request.source(), defaultSource);
        return new NexusEventRequest(
                request.eventId(),
                request.idempotencyKey(),
                request.eventType(),
                request.aggregateType(),
                request.aggregateId(),
                source,
                request.schemaVersion(),
                request.payload(),
                request.occurredAt()
        );
    }

    private record Normalized(
            String transactionType,
            String factoryId,
            String vendorId,
            String syntheticAccountId,
            String routePlanId,
            String shipmentId,
            String originCode,
            String destinationCode,
            BigDecimal riskScore,
            String approvalReason,
            boolean approvalRequired,
            String status,
            String reason,
            Instant occurredAt,
            String sourceService,
            String eventType,
            String severity,
            boolean requiresColdChain,
            BigDecimal amount,
            String currency
    ) {
    }

    private record Account(String code, String name) {
    }
}
