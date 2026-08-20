package com.archiveledger.ledger.runtime;

import com.archiveledger.ledger.LedgerService;
import com.archiveledger.ledger.common.LedgerModels.RuntimeEventView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.archiveledger.ledger.runtime.RuntimeOutboundModels.*;

@Service
public class RuntimeOutboundService {
    private static final Set<String> PUBLISHABLE_EVENT_TYPES = Set.of(
            "TRANSACTION_CREATED", "LEDGER_ENTRY_CREATED", "APPROVAL_REQUIRED", "APPROVAL_APPROVED",
            "APPROVAL_REJECTED", "SETTLEMENT_READY", "SETTLEMENT_STARTED", "SETTLEMENT_COMPLETED",
            "RECONCILIATION_OK", "RECONCILIATION_WARNING", "CALLBACK_SENT", "CALLBACK_FAILED"
    );
    private static final Set<String> SAFE_METADATA_KEYS = Set.of(
            "transactionId", "transactionType", "factoryId", "vendorId", "routePlanId", "shipmentId",
            "orderId", "paymentId", "returnId", "claimId", "workdayId", "priority", "customerType",
            "approvalRequestId", "batchId", "entryCount", "mismatchCount", "issueCount", "amountBucket",
            "syntheticKrwRange", "sourceEventType", "bottleneckRole", "settlementDetailCount",
            "simulationRunId", "settlementCycleId", "hopCount", "maxHop", "status", "reasonCode"
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final LedgerService ledger;
    private final ArchiveOsRuntimePublisher publisher;
    private final int maxRetryCount;
    private final int maxBatchSize;
    private final AtomicBoolean deliveryLock = new AtomicBoolean(false);

    public RuntimeOutboundService(JdbcTemplate jdbc,
                                  ObjectMapper mapper,
                                  LedgerService ledger,
                                  ArchiveOsRuntimePublisher publisher,
                                  @Value("${archive-ledger.runtime-ingest.max-retry-count:5}") int maxRetryCount,
                                  @Value("${archive-ledger.runtime-ingest.max-batch-size:100}") int maxBatchSize) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.ledger = ledger;
        this.publisher = publisher;
        this.maxRetryCount = Math.max(1, Math.min(maxRetryCount, 20));
        this.maxBatchSize = Math.max(1, Math.min(maxBatchSize, 500));
    }

    /** Captures a snapshot of existing runtime projections without any external I/O. */
    public int captureRuntimeEvents(int limit) {
        int captured = 0;
        for (RuntimeEventView event : ledger.recentRuntimeEvents(Math.max(1, Math.min(limit, 500)))) {
            if (!PUBLISHABLE_EVENT_TYPES.contains(event.eventType())) {
                continue;
            }
            RuntimeOutboundEvent outbound = toOutbound(event);
            try {
                if (alreadyCaptured(outbound)) {
                    continue;
                }
                int inserted = jdbc.update("""
                        insert into archiveos_runtime_outbox
                        (event_id, idempotency_key, correlation_id, event_type, entity_id, payload, delivery_status,
                         retry_count, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
                        """,
                        outbound.eventId(), outbound.idempotencyKey(), outbound.correlationId(), outbound.eventType(),
                        outbound.entityId(), writePayload(outbound), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
                captured += inserted;
            } catch (DuplicateKeyException ignored) {
                // idempotency_key is also unique; another event with that key is already safely retained.
            }
        }
        return captured;
    }

    private boolean alreadyCaptured(RuntimeOutboundEvent outbound) {
        Integer count = jdbc.queryForObject("""
                select count(*) from archiveos_runtime_outbox
                where event_id=? or idempotency_key=?
                """, Integer.class, outbound.eventId(), outbound.idempotencyKey());
        return count != null && count > 0;
    }

    /** Scheduler entry point: capture first, then deliver only when runtime ingest is explicitly enabled. */
    public void runDeliveryCycle() {
        if (!deliveryLock.compareAndSet(false, true)) {
            return;
        }
        try {
            captureRuntimeEvents(maxBatchSize);
            if (!publisher.enabled()) {
                return;
            }
            deliverPending(maxBatchSize);
        } finally {
            deliveryLock.set(false);
        }
    }

    public int deliverPending(int limit) {
        int delivered = 0;
        for (RuntimeOutboundRecord record : pendingRecords(Math.max(1, Math.min(limit, maxBatchSize)))) {
            RuntimeDeliveryResult outcome = publisher.publish(record.preview());
            applyOutcome(record, outcome);
            if (outcome.success()) {
                delivered++;
            }
        }
        return delivered;
    }

    public RuntimeOutboundSummary summary() {
        long pending = count("PENDING");
        long published = count("PUBLISHED");
        long retrying = count("RETRY");
        long configError = count("CONFIG_ERROR");
        long nonRetryable = count("NON_RETRYABLE_ERROR");
        long failed = count("FAILED");
        List<Map<String, Object>> latest = jdbc.queryForList("""
                select published_at, last_error from archiveos_runtime_outbox
                where published_at is not null or last_error is not null
                order by updated_at desc limit 1
                """);
        Instant latestPublishedAt = latest.isEmpty() ? null : instant(latest.get(0).get("published_at"));
        String lastError = latest.isEmpty() ? null : text(latest.get(0).get("last_error"));
        return new RuntimeOutboundSummary(publisher.enabled(), publisher.endpoint(), pending, published, retrying,
                configError, nonRetryable, failed, latestPublishedAt, lastError);
    }

    public List<RuntimeOutboundRecord> records(String status, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        String sql = "select * from archiveos_runtime_outbox";
        List<Object> args = new ArrayList<>();
        if (!normalized.isBlank()) {
            sql += " where delivery_status=?";
            args.add(normalized);
        }
        sql += " order by created_at desc, id desc limit ?";
        args.add(safeLimit);
        return jdbc.query(sql, (rs, row) -> toRecord(
                rs.getString("event_id"), rs.getString("idempotency_key"), rs.getString("correlation_id"),
                rs.getString("event_type"), rs.getString("entity_id"), rs.getString("delivery_status"),
                rs.getInt("retry_count"), rs.getString("last_error"), instant(rs.getTimestamp("next_retry_at")),
                instant(rs.getTimestamp("published_at")), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")), readPayload(rs.getString("payload"))), args.toArray());
    }

    /** Preview is read-only and uses stored snapshots only. */
    public List<RuntimeOutboundRecord> previewByCorrelation(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return List.of();
        }
        return jdbc.query("select * from archiveos_runtime_outbox where correlation_id=? order by created_at asc, id asc",
                (rs, row) -> toRecord(
                        rs.getString("event_id"), rs.getString("idempotency_key"), rs.getString("correlation_id"),
                        rs.getString("event_type"), rs.getString("entity_id"), rs.getString("delivery_status"),
                        rs.getInt("retry_count"), rs.getString("last_error"), instant(rs.getTimestamp("next_retry_at")),
                        instant(rs.getTimestamp("published_at")), instant(rs.getTimestamp("created_at")),
                        instant(rs.getTimestamp("updated_at")), readPayload(rs.getString("payload"))), correlationId);
    }

    private List<RuntimeOutboundRecord> pendingRecords(int limit) {
        return jdbc.query("""
                select * from archiveos_runtime_outbox
                where delivery_status in ('PENDING', 'RETRY')
                  and (next_retry_at is null or next_retry_at <= ?)
                order by created_at asc, id asc limit ?
                """, (rs, row) -> toRecord(
                        rs.getString("event_id"), rs.getString("idempotency_key"), rs.getString("correlation_id"),
                        rs.getString("event_type"), rs.getString("entity_id"), rs.getString("delivery_status"),
                        rs.getInt("retry_count"), rs.getString("last_error"), instant(rs.getTimestamp("next_retry_at")),
                        instant(rs.getTimestamp("published_at")), instant(rs.getTimestamp("created_at")),
                        instant(rs.getTimestamp("updated_at")), readPayload(rs.getString("payload"))),
                Timestamp.from(Instant.now()), limit);
    }

    private void applyOutcome(RuntimeOutboundRecord record, RuntimeDeliveryResult result) {
        Instant now = Instant.now();
        if (result.success()) {
            jdbc.update("""
                    update archiveos_runtime_outbox set delivery_status='PUBLISHED', published_at=?, next_retry_at=null,
                    last_error=null, updated_at=? where event_id=? and delivery_status in ('PENDING', 'RETRY')
                    """, Timestamp.from(now), Timestamp.from(now), record.eventId());
            return;
        }
        if (!result.retryable()) {
            jdbc.update("""
                    update archiveos_runtime_outbox set delivery_status=?, last_error=?, next_retry_at=null, updated_at=?
                    where event_id=? and delivery_status in ('PENDING', 'RETRY')
                    """, result.classification(), result.detail(), Timestamp.from(now), record.eventId());
            return;
        }
        int retryCount = record.retryCount() + 1;
        String status = retryCount >= maxRetryCount ? "FAILED" : "RETRY";
        Instant nextRetry = "FAILED".equals(status) ? null : now.plusSeconds(backoffSeconds(retryCount));
        jdbc.update("""
                update archiveos_runtime_outbox set delivery_status=?, retry_count=?, last_error=?, next_retry_at=?, updated_at=?
                where event_id=? and delivery_status in ('PENDING', 'RETRY')
                """, status, retryCount, result.detail(), timestamp(nextRetry), Timestamp.from(now), record.eventId());
    }

    private RuntimeOutboundEvent toOutbound(RuntimeEventView event) {
        Map<String, Object> metadata = sanitizeMetadata(event.metadata());
        String correlationId = value(event.correlationId(), event.entityId());
        String causationId = resolvedCausationId(event);
        String orderId = resolveOrderId(event, metadata);
        return new RuntimeOutboundEvent(
                event.eventId(), event.idempotencyKey(), ArchiveOsRuntimePublisher.SOURCE_SYSTEM,
                ArchiveOsRuntimePublisher.TARGET_SYSTEM, "ledger", event.eventType(), event.entityType(), event.entityId(),
                orderId, correlationId, causationId, event.simulationRunId(), event.settlementCycleId(), event.workdayId(),
                event.status(), event.severity(), event.displayLabel(), event.occurredAt(), event.hopCount(), event.maxHop(), metadata
        );
    }

    private Map<String, Object> sanitizeMetadata(Map<String, Object> metadata) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (metadata == null) return sanitized;
        metadata.forEach((key, value) -> {
            if (SAFE_METADATA_KEYS.contains(key) && value != null) {
                sanitized.put(key, value);
            }
        });
        return sanitized;
    }

    /**
     * Ledger transaction projections must preserve the upstream business parent rather than
     * introducing the received-event transport ID as a timeline-only parent.
     */
    private String resolvedCausationId(RuntimeEventView event) {
        String direct = value(event.causationId(), event.eventId());
        if (!"TRANSACTION_CREATED".equals(event.eventType()) || direct.equals(event.eventId())) {
            return direct;
        }
        List<String> upstream = jdbc.query(
                "select causation_id from received_event where event_id=? and causation_id is not null and causation_id<>''",
                (rs, row) -> rs.getString(1), direct);
        return upstream.isEmpty() ? direct : upstream.getFirst();
    }

    /**
     * Order lineage is only emitted when it is carried by the inbound event context.
     * A Ledger transaction, settlement cycle, or entity identifier is never an order substitute.
     */
    private String resolveOrderId(RuntimeEventView event, Map<String, Object> metadata) {
        String direct = text(metadata.get("orderId"));
        if (direct != null && !direct.isBlank()) {
            return direct;
        }

        String transactionId = text(metadata.get("transactionId"));
        if (transactionId != null && !transactionId.isBlank()) {
            String fromTransaction = uniqueOrderId("""
                    select re.payload from finance_transaction ft
                    join received_event re on re.event_id=ft.source_event_id
                    where ft.transaction_id=?
                    """, transactionId);
            if (fromTransaction != null) {
                return fromTransaction;
            }
        }

        String causationId = event.causationId();
        if (causationId != null && !causationId.isBlank()) {
            String fromCause = uniqueOrderId("select payload from received_event where event_id=?", causationId);
            if (fromCause != null) {
                return fromCause;
            }
        }

        if (event.correlationId() != null && !event.correlationId().isBlank()) {
            return uniqueOrderId("select payload from received_event where correlation_id=? order by received_at asc", event.correlationId());
        }
        return null;
    }

    private String uniqueOrderId(String sql, Object... args) {
        List<String> orderIds = jdbc.query(sql, (rs, row) -> orderIdFromPayload(rs.getString("payload")), args)
                .stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        return orderIds.size() == 1 ? orderIds.getFirst() : null;
    }

    private String orderIdFromPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return findOrderId(mapper.readTree(payload));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String findOrderId(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            JsonNode orderId = node.get("orderId");
            if (orderId != null && orderId.isTextual() && !orderId.asText().isBlank()) {
                return orderId.asText();
            }
            var fields = node.elements();
            while (fields.hasNext()) {
                String found = findOrderId(fields.next());
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                String found = findOrderId(item);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private RuntimeOutboundRecord toRecord(String eventId, String idempotencyKey, String correlationId, String eventType,
                                            String entityId, String deliveryStatus, int retryCount, String lastError,
                                            Instant nextRetryAt, Instant publishedAt, Instant createdAt, Instant updatedAt,
                                            RuntimeOutboundEvent preview) {
        return new RuntimeOutboundRecord(eventId, idempotencyKey, correlationId, eventType, entityId, deliveryStatus,
                retryCount, lastError, nextRetryAt, publishedAt, createdAt, updatedAt, preview);
    }

    private String writePayload(RuntimeOutboundEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (Exception error) {
            throw new IllegalStateException("runtime outbound payload serialization failed", error);
        }
    }

    private RuntimeOutboundEvent readPayload(String payload) {
        try {
            return mapper.readValue(payload, RuntimeOutboundEvent.class);
        } catch (Exception error) {
            throw new IllegalStateException("runtime outbound payload parsing failed", error);
        }
    }

    private long count(String status) {
        Long value = jdbc.queryForObject("select count(*) from archiveos_runtime_outbox where delivery_status=?", Long.class, status);
        return value == null ? 0L : value;
    }

    private long backoffSeconds(int retryCount) {
        return Math.min(300L, 5L * (1L << Math.min(retryCount - 1, 5)));
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        return null;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String value(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate;
    }
}
