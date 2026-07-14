package com.archiveledger.ledger.runtime;

import java.time.Instant;
import java.util.Map;

public final class RuntimeOutboundModels {
    private RuntimeOutboundModels() {
    }

    public record RuntimeOutboundEvent(
            String eventId,
            String idempotencyKey,
            String sourceSystem,
            String targetSystem,
            String domain,
            String eventType,
            String entityType,
            String entityId,
            String orderId,
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
            Map<String, Object> metadata
    ) {
    }

    public record RuntimeOutboundRecord(
            String eventId,
            String idempotencyKey,
            String correlationId,
            String eventType,
            String entityId,
            String deliveryStatus,
            int retryCount,
            String lastError,
            Instant nextRetryAt,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt,
            RuntimeOutboundEvent preview
    ) {
    }

    public record RuntimeOutboundSummary(
            boolean enabled,
            String endpoint,
            long pending,
            long published,
            long retrying,
            long configError,
            long nonRetryableError,
            long failed,
            Instant latestPublishedAt,
            String lastDeliveryError
    ) {
    }

    public record RuntimeDeliveryResult(boolean success, boolean retryable, String classification, String detail) {
        public static RuntimeDeliveryResult success(String detail) {
            return new RuntimeDeliveryResult(true, false, "PUBLISHED", detail);
        }

        public static RuntimeDeliveryResult configError(String detail) {
            return new RuntimeDeliveryResult(false, false, "CONFIG_ERROR", detail);
        }

        public static RuntimeDeliveryResult nonRetryable(String detail) {
            return new RuntimeDeliveryResult(false, false, "NON_RETRYABLE_ERROR", detail);
        }

        public static RuntimeDeliveryResult retryable(String detail) {
            return new RuntimeDeliveryResult(false, true, "RETRY", detail);
        }
    }
}
