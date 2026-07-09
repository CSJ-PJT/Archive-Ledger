package com.archiveledger.ledger;

import com.archiveledger.ledger.approval.ArchiveOsApprovalClient;
import com.archiveledger.ledger.common.LedgerMetrics;
import com.archiveledger.ledger.common.LedgerModels.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class LedgerService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ArchiveOsApprovalClient archiveOs;
    private final LedgerMetrics metrics;
    private final BigDecimal approvalThreshold;

    public LedgerService(JdbcTemplate jdbc, ObjectMapper mapper, ArchiveOsApprovalClient archiveOs, LedgerMetrics metrics,
                         @Value("${archive-ledger.policy.approval-threshold-krw:3000000}") BigDecimal approvalThreshold) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.archiveOs = archiveOs;
        this.metrics = metrics;
        this.approvalThreshold = approvalThreshold;
    }

    @Transactional
    public EventIngestionResponse ingest(NexusEventRequest request) {
        if (exists("select count(*) from received_event where event_id=? or idempotency_key=?",
                request.eventId(), request.idempotencyKey())) {
            metrics.duplicateEvent();
            audit(request.eventId(), "Archive-Ledger", "DUPLICATE_EVENT", "received_event", request.eventId(), null, "DUPLICATE",
                    Map.of("idempotencyKey", request.idempotencyKey()));
            return new EventIngestionResponse(request.eventId(), "DUPLICATE", null, "Duplicate event ignored safely.");
        }
        Instant receivedAt = Instant.now();
        try {
            jdbc.update("""
                    insert into received_event(event_id,idempotency_key,source,event_type,schema_version,payload,processing_status,received_at)
                    values(?,?,?,?,?,?,?,?)
                    """, request.eventId(), request.idempotencyKey(), value(request.source(), "Archive-Nexus"),
                    request.eventType(), request.schemaVersion() == null ? 1 : request.schemaVersion(),
                    write(request.payload()), "RECEIVED", ts(receivedAt));
            metrics.eventReceived();

            Normalized normalized = normalize(request);
            String transactionId = "TX-" + LocalDate.now().toString().replace("-", "") + "-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();
            String approvalRequestId = normalized.approvalRequired()
                    ? "APR-" + LocalDate.now().toString().replace("-", "") + "-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase()
                    : null;
            jdbc.update("""
                    insert into finance_transaction(transaction_id,source_event_id,idempotency_key,transaction_type,factory_id,vendor_id,
                    synthetic_account_id,amount,currency,status,approval_required,approval_request_id,reason,occurred_at,created_at,updated_at)
                    values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, transactionId, request.eventId(), request.idempotencyKey(), normalized.transactionType(),
                    normalized.factoryId(), normalized.vendorId(), normalized.syntheticAccountId(), normalized.amount(),
                    normalized.currency(), normalized.status(), normalized.approvalRequired(), approvalRequestId,
                    normalized.reason(), ts(normalized.occurredAt()), ts(receivedAt), ts(receivedAt));
            metrics.transactionCreated();

            createLedgerEntries(transactionId, normalized, receivedAt);
            if (normalized.approvalRequired()) {
                metrics.approvalRequired();
                String evidence = fallbackEvidence(normalized);
                jdbc.update("""
                        insert into approval_request(approval_request_id,transaction_id,requested_to,status,amount,reason,policy_evidence,requested_at)
                        values(?,?,?,?,?,?,?,?)
                        """, approvalRequestId, transactionId, "synthetic-finance-operator", "REQUESTED",
                        normalized.amount(), normalized.reason(), evidence, ts(receivedAt));
                try {
                    archiveOs.requestApproval(approvalRequestId, transactionId, normalized.amount(), normalized.currency(),
                            normalized.reason(), Map.of("factoryId", normalized.factoryId(), "vendorId", normalized.vendorId(),
                                    "eventType", request.eventType(), "synthetic", true));
                    audit(transactionId, "Archive-Ledger", "ARCHIVEOS_APPROVAL_REQUESTED", "approval_request",
                            approvalRequestId, null, "REQUESTED", Map.of("archiveOsEnabled", archiveOs.enabled()));
                } catch (RuntimeException error) {
                    audit(transactionId, "Archive-Ledger", "ARCHIVEOS_APPROVAL_DEGRADED", "approval_request",
                            approvalRequestId, null, "REQUESTED", Map.of("error", error.getMessage(), "fallbackEvidence", evidence));
                }
            }
            jdbc.update("update received_event set processing_status='PROCESSED', processed_at=? where event_id=?",
                    ts(Instant.now()), request.eventId());
            audit(transactionId, "Archive-Ledger", "TRANSACTION_CREATED", "finance_transaction", transactionId,
                    null, normalized.status(), Map.of("sourceEventId", request.eventId(), "eventType", request.eventType()));
            return new EventIngestionResponse(request.eventId(), "ACCEPTED", transactionId, "Synthetic event normalized.");
        } catch (DuplicateKeyException duplicate) {
            metrics.duplicateEvent();
            return new EventIngestionResponse(request.eventId(), "DUPLICATE", null, "Duplicate event ignored safely.");
        } catch (RuntimeException error) {
            metrics.processingFailure();
            String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            jdbc.update("update received_event set processing_status='FAILED', failure_reason=?, processed_at=? where event_id=?",
                    reason, ts(Instant.now()), request.eventId());
            audit(request.eventId(), "Archive-Ledger", "EVENT_PROCESSING_FAILED", "received_event", request.eventId(),
                    "RECEIVED", "FAILED", Map.of("error", reason));
            return new EventIngestionResponse(request.eventId(), "FAILED", null, reason);
        }
    }

    public BulkIngestionResponse ingestBulk(List<NexusEventRequest> requests) {
        List<EventIngestionResponse> results = requests.stream().map(this::ingest).toList();
        int accepted = (int) results.stream().filter(r -> "ACCEPTED".equals(r.status())).count();
        int duplicate = (int) results.stream().filter(r -> "DUPLICATE".equals(r.status())).count();
        int failed = (int) results.stream().filter(r -> "FAILED".equals(r.status())).count();
        return new BulkIngestionResponse(requests.size(), accepted, duplicate, failed, results.size() > 50 ? results.subList(0, 50) : results);
    }

    @Transactional
    public Map<String, Object> approvalCallback(ApprovalCallbackRequest request) {
        String next = "APPROVED".equalsIgnoreCase(request.decision()) ? "SETTLEMENT_READY" : "REJECTED";
        String before = jdbc.queryForObject("select status from finance_transaction where transaction_id=?", String.class, request.transactionId());
        jdbc.update("update finance_transaction set status=?, updated_at=? where transaction_id=?", next, ts(Instant.now()), request.transactionId());
        jdbc.update("update approval_request set status=?, decided_at=?, decided_by=? where approval_request_id=? or transaction_id=?",
                "APPROVED".equals(next) ? "APPROVED" : request.decision().toUpperCase(Locale.ROOT), ts(Instant.now()),
                value(request.decidedBy(), "synthetic-operator"), request.approvalRequestId(), request.transactionId());
        audit(request.transactionId(), value(request.decidedBy(), "synthetic-operator"), "APPROVAL_CALLBACK",
                "finance_transaction", request.transactionId(), before, next, Map.of("comment", value(request.comment(), "")));
        return Map.of("transactionId", request.transactionId(), "previousStatus", before, "status", next);
    }

    @Transactional
    public SettlementBatchView runSettlement(LocalDate date) {
        Instant started = Instant.now();
        String batchId = "SET-" + date.toString().replace("-", "") + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
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
                        """, batchId, tx.transactionId(), tx.factoryId(), tx.vendorId(), settlementAccount(tx.transactionType()),
                        tx.amount(), "SETTLED", ts(Instant.now()));
                jdbc.update("update finance_transaction set status='SETTLED', updated_at=? where transaction_id=?",
                        ts(Instant.now()), tx.transactionId());
                audit(tx.transactionId(), "Archive-Ledger", "TRANSACTION_SETTLED", "finance_transaction",
                        tx.transactionId(), "SETTLEMENT_READY", "SETTLED", Map.of("batchId", batchId));
            }
            jdbc.update("""
                    update settlement_batch set status='SUCCESS', total_transaction_count=?, total_amount=?, completed_at=? where batch_id=?
                    """, candidates.size(), total, ts(Instant.now()), batchId);
            metrics.settlementCompleted();
            metrics.settlementDuration(Duration.between(started, Instant.now()));
            return settlement(batchId);
        } catch (RuntimeException error) {
            jdbc.update("update settlement_batch set status='FAILED', failure_reason=?, completed_at=? where batch_id=?",
                    error.getMessage(), ts(Instant.now()), batchId);
            throw error;
        }
    }

    @Transactional
    public ReconciliationView reconcile(LocalDate date) {
        int received = count("select count(*) from received_event where cast(received_at as date)=?", Date.valueOf(date));
        int created = count("select count(*) from finance_transaction where cast(created_at as date)=?", Date.valueOf(date));
        int failed = count("select count(*) from received_event where processing_status='FAILED' and cast(received_at as date)=?", Date.valueOf(date));
        int approval = count("select count(*) from finance_transaction where status='APPROVAL_REQUIRED'");
        int ready = count("select count(*) from finance_transaction where status='SETTLEMENT_READY'");
        int settled = count("select count(*) from finance_transaction where status='SETTLED'");
        int duplicates = count("select count(*) from audit_log where action='DUPLICATE_EVENT'");
        int mismatch = Math.max(0, received - created - failed);
        String status = mismatch == 0 ? "OK" : failed > 0 ? "WARNING" : "CRITICAL";
        jdbc.update("""
                insert into reconciliation_result(reconciliation_date,nexus_event_count,received_event_count,created_transaction_count,
                duplicate_event_count,failed_event_count,approval_required_count,settlement_ready_count,settled_count,mismatch_count,status,created_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?)
                """, Date.valueOf(date), received + duplicates, received, created, duplicates, failed, approval, ready, settled, mismatch,
                status, ts(Instant.now()));
        metrics.reconciliationMismatch(mismatch);
        return reconciliation(date);
    }

    public List<ReceivedEventView> receivedEvents() {
        return jdbc.query("select * from received_event order by received_at desc limit 500", (rs, row) ->
                new ReceivedEventView(rs.getString("event_id"), rs.getString("idempotency_key"), rs.getString("source"),
                        rs.getString("event_type"), rs.getString("processing_status"), instant(rs.getTimestamp("received_at")),
                        instant(rs.getTimestamp("processed_at")), rs.getString("failure_reason")));
    }

    public Optional<ReceivedEventView> receivedEvent(String eventId) {
        return receivedEvents().stream().filter(event -> event.eventId().equals(eventId)).findFirst();
    }

    public List<TransactionView> transactions(String status) {
        return status == null || status.isBlank() ? jdbc.query("select * from finance_transaction order by created_at desc limit 500", this::transactionRow)
                : transactionsByStatus(status);
    }

    public Optional<TransactionView> transaction(String id) {
        List<TransactionView> values = jdbc.query("select * from finance_transaction where transaction_id=?", this::transactionRow, id);
        return values.stream().findFirst();
    }

    public List<LedgerEntryView> ledgerEntries(String transactionId) {
        String sql = transactionId == null || transactionId.isBlank()
                ? "select * from ledger_entry order by created_at desc limit 500"
                : "select * from ledger_entry where transaction_id=? order by id";
        Object[] args = transactionId == null || transactionId.isBlank() ? new Object[]{} : new Object[]{transactionId};
        return jdbc.query(sql, this::ledgerRow, args);
    }

    public LedgerSummary ledgerSummary(LocalDate date, String factoryId) {
        String clause = date != null ? "cast(occurred_at as date)=?" : factoryId != null ? "factory_id=?" : "1=1";
        Object arg = date != null ? Date.valueOf(date) : factoryId;
        List<LedgerEntryView> rows = arg == null ? jdbc.query("select * from ledger_entry where " + clause, this::ledgerRow)
                : jdbc.query("select * from ledger_entry where " + clause, this::ledgerRow, arg);
        BigDecimal debit = rows.stream().map(LedgerEntryView::debitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = rows.stream().map(LedgerEntryView::creditAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new LedgerSummary(date != null ? date.toString() : value(factoryId, "all"), debit, credit, rows.size());
    }

    public List<SettlementBatchView> settlements() {
        return jdbc.query("select * from settlement_batch order by started_at desc limit 200", this::settlementRow);
    }

    public SettlementBatchView settlement(String batchId) {
        return jdbc.queryForObject("select * from settlement_batch where batch_id=?", this::settlementRow, batchId);
    }

    public List<SettlementDetailView> settlementDetails(String batchId) {
        return jdbc.query("select * from settlement_detail where batch_id=? order by id", (rs, row) ->
                new SettlementDetailView(rs.getString("batch_id"), rs.getString("transaction_id"), rs.getString("factory_id"),
                        rs.getString("vendor_id"), rs.getString("account_code"), rs.getBigDecimal("amount"),
                        rs.getString("status"), instant(rs.getTimestamp("created_at"))), batchId);
    }

    public ReconciliationView reconciliation(LocalDate date) {
        List<ReconciliationView> rows = jdbc.query("select * from reconciliation_result where reconciliation_date=? order by created_at desc limit 1",
                this::reconciliationRow, Date.valueOf(date));
        return rows.isEmpty() ? reconcile(date) : rows.get(0);
    }

    public OperationsSummary operationsSummary() {
        String lastStatus = jdbc.query("select status from reconciliation_result order by created_at desc limit 1",
                rs -> rs.next() ? rs.getString(1) : "UNKNOWN");
        Instant lastSettlement = jdbc.query("select completed_at from settlement_batch where completed_at is not null order by completed_at desc limit 1",
                rs -> rs.next() ? instant(rs.getTimestamp(1)) : null);
        long failed = count("select count(*) from received_event where processing_status='FAILED'");
        return new OperationsSummary(failed > 0 ? "DEGRADED" : "HEALTHY",
                count("select count(*) from received_event"), count("select count(*) from finance_transaction"),
                count("select count(*) from audit_log where action='DUPLICATE_EVENT'"),
                count("select count(*) from finance_transaction where status='APPROVAL_REQUIRED'"),
                count("select count(*) from finance_transaction where status='SETTLED'"), failed, lastSettlement, lastStatus);
    }

    private Normalized normalize(NexusEventRequest request) {
        Map<String, Object> p = request.payload();
        String eventType = request.eventType();
        String transactionType = switch (eventType) {
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
            default -> throw new IllegalArgumentException("Unsupported event_type: " + eventType);
        };
        BigDecimal amount = decimal(firstPresent(p, "estimatedCost", "amount", "cost"));
        String severity = string(p, "severity", "NORMAL");
        boolean approvalRequired = amount.compareTo(approvalThreshold) >= 0
                || List.of("HIGH", "CRITICAL").contains(severity)
                || List.of("EMERGENCY_PURCHASE_REQUESTED", "QUALITY_CLAIM_CHARGED").contains(eventType)
                || List.of("WARNING", "BLOCKED").contains(string(p, "vendorRisk", "NORMAL"))
                || Boolean.TRUE.equals(p.get("requiresApproval"));
        return new Normalized(transactionType, string(p, "factoryId", "FAC-SYN"), string(p, "vendorId", "VENDOR-SYN"),
                string(p, "syntheticAccountId", null), amount, string(p, "currency", "KRW"),
                approvalRequired ? "APPROVAL_REQUIRED" : "SETTLEMENT_READY", approvalRequired,
                string(p, "reason", "synthetic finance operation"), request.occurredAt() == null ? Instant.now() : request.occurredAt(),
                request.eventType(), severity);
    }

    private void createLedgerEntries(String transactionId, Normalized n, Instant createdAt) {
        Account debit = debitAccount(n.transactionType());
        Account credit = creditAccount(n.transactionType());
        insertLedger(transactionId, debit.code(), debit.name(), n.amount(), BigDecimal.ZERO, n, createdAt);
        insertLedger(transactionId, credit.code(), credit.name(), BigDecimal.ZERO, n.amount(), n, createdAt);
    }

    private void insertLedger(String transactionId, String code, String name, BigDecimal debit, BigDecimal credit, Normalized n, Instant createdAt) {
        jdbc.update("""
                insert into ledger_entry(transaction_id,account_code,account_name,debit_amount,credit_amount,factory_id,vendor_id,occurred_at,created_at)
                values(?,?,?,?,?,?,?,?,?)
                """, transactionId, code, name, debit, credit, n.factoryId(), n.vendorId(), ts(n.occurredAt()), ts(createdAt));
    }

    private Account debitAccount(String type) {
        return switch (type) {
            case "MAINTENANCE_EXPENSE" -> new Account("MAINTENANCE_EXPENSE", "Synthetic Maintenance Expense");
            case "QUALITY_LOSS", "QUALITY_CLAIM_CHARGEBACK" -> new Account("QUALITY_LOSS", "Synthetic Quality Loss");
            case "LOGISTICS_COST", "SHIPMENT_HOLD_COST" -> new Account("LOGISTICS_COST", "Synthetic Logistics Cost");
            case "MATERIAL_COST" -> new Account("MATERIAL_COST", "Synthetic Material Cost");
            case "CORPORATE_CARD_EXPENSE", "EMERGENCY_PURCHASE_EXPENSE" -> new Account("OPERATING_EXPENSE", "Synthetic Operating Expense");
            case "VENDOR_PAYMENT" -> new Account("ACCOUNTS_PAYABLE", "Synthetic Accounts Payable");
            default -> new Account("PRODUCTION_COST", "Synthetic Production Cost");
        };
    }

    private Account creditAccount(String type) {
        return switch (type) {
            case "CORPORATE_CARD_EXPENSE" -> new Account("CORPORATE_CARD_PAYABLE", "Synthetic Corporate Card Payable");
            case "VENDOR_PAYMENT" -> new Account("CASH_CLEARING", "Synthetic Cash Clearing");
            default -> new Account("ACCOUNTS_PAYABLE", "Synthetic Accounts Payable");
        };
    }

    private String fallbackEvidence(Normalized n) {
        return "Synthetic policy evidence: amount " + n.amount() + " " + n.currency()
                + ", severity " + n.severity() + ", event " + n.eventType()
                + ". Policy requires approval when amount >= " + approvalThreshold
                + " KRW, severity is HIGH/CRITICAL, emergency purchase or quality claim is detected, or vendor risk is elevated.";
    }

    private String settlementAccount(String transactionType) {
        return debitAccount(transactionType).code();
    }

    private List<TransactionView> transactionsByStatus(String status) {
        return jdbc.query("select * from finance_transaction where status=? order by created_at desc", this::transactionRow, status);
    }

    private TransactionView transactionRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new TransactionView(rs.getString("transaction_id"), rs.getString("source_event_id"), rs.getString("idempotency_key"),
                rs.getString("transaction_type"), rs.getString("factory_id"), rs.getString("vendor_id"),
                rs.getString("synthetic_account_id"), rs.getBigDecimal("amount"), rs.getString("currency"),
                rs.getString("status"), rs.getBoolean("approval_required"), rs.getString("approval_request_id"),
                rs.getString("reason"), instant(rs.getTimestamp("occurred_at")), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")));
    }

    private LedgerEntryView ledgerRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new LedgerEntryView(rs.getString("transaction_id"), rs.getString("account_code"), rs.getString("account_name"),
                rs.getBigDecimal("debit_amount"), rs.getBigDecimal("credit_amount"), rs.getString("factory_id"),
                rs.getString("vendor_id"), instant(rs.getTimestamp("occurred_at")), instant(rs.getTimestamp("created_at")));
    }

    private SettlementBatchView settlementRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new SettlementBatchView(rs.getString("batch_id"), rs.getDate("settlement_date").toLocalDate(),
                rs.getString("status"), rs.getInt("total_transaction_count"), rs.getBigDecimal("total_amount"),
                instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("completed_at")), rs.getString("failure_reason"));
    }

    private ReconciliationView reconciliationRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ReconciliationView(rs.getDate("reconciliation_date").toLocalDate(), rs.getInt("nexus_event_count"),
                rs.getInt("received_event_count"), rs.getInt("created_transaction_count"), rs.getInt("duplicate_event_count"),
                rs.getInt("failed_event_count"), rs.getInt("approval_required_count"), rs.getInt("settlement_ready_count"),
                rs.getInt("settled_count"), rs.getInt("mismatch_count"), rs.getString("status"), instant(rs.getTimestamp("created_at")));
    }

    private void audit(String traceId, String actor, String action, String targetType, String targetId,
                       String before, String after, Map<String, Object> detail) {
        jdbc.update("""
                insert into audit_log(trace_id,actor,action,target_type,target_id,before_status,after_status,detail,created_at)
                values(?,?,?,?,?,?,?,?,?)
                """, traceId, actor, action, targetType, targetId, before, after, write(detail), ts(Instant.now()));
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

    private BigDecimal decimal(Object value) {
        if (value == null) throw new IllegalArgumentException("Payload amount is required.");
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
        return new BigDecimal(String.valueOf(value));
    }

    private Object firstPresent(Map<String, Object> payload, String... keys) {
        for (String key : keys) if (payload.containsKey(key)) return payload.get(key);
        return null;
    }

    private String string(Map<String, Object> payload, String key, String fallback) {
        Object value = payload.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private Timestamp ts(Instant value) {
        return Timestamp.from(value);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record Normalized(String transactionType, String factoryId, String vendorId, String syntheticAccountId,
                              BigDecimal amount, String currency, String status, boolean approvalRequired,
                              String reason, Instant occurredAt, String eventType, String severity) {
    }

    private record Account(String code, String name) {
    }
}
