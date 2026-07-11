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
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Comparator;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class LedgerService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ArchiveOsApprovalClient archiveOs;
    private final LedgerMetrics metrics;
    private final BigDecimal approvalThreshold;
    private final boolean runtimeAutoRunEnabled;
    private final int callbackRetryLimit;
    private final AtomicBoolean runtimeTickLock = new AtomicBoolean(false);
    private volatile Instant lastRuntimeWorkAt;
    private volatile int lastRuntimeEventsProduced;
    private volatile int lastRuntimeEventsConsumed;
    private volatile int lastRuntimeBacklogCount;
    private static final String SOURCE_NEXUS = "Archive-Nexus";
    private static final String SOURCE_LOGITICS = "Archive-Logitics";
    private static final String SOURCE_LOGISTICS = "Archive-Logistics";
    private static final String SOURCE_MARKET = "Archive-Market";
    private static final BigDecimal LOGISTICS_APPROVAL_THRESHOLD = new BigDecimal("300000");
    private static final BigDecimal MARKET_APPROVAL_THRESHOLD = new BigDecimal("300000");
    private static final BigDecimal LOGISTICS_LOW_RISK_SCORE = new BigDecimal("0.85");
    private static final int BULK_RESULT_LIMIT = 50;
    private static final int LEDGER_BASELINE_DAILY_CAPACITY = 500;
    private static final String TARGET_LEDGER = "Archive-Ledger";

    public LedgerService(JdbcTemplate jdbc,
                         ObjectMapper mapper,
                         ArchiveOsApprovalClient archiveOs,
                         LedgerMetrics metrics,
                         @Value("${archive-ledger.policy.approval-threshold-krw:3000000}") BigDecimal approvalThreshold,
                         @Value("${archive.runtime.autorun.enabled:true}") boolean runtimeAutoRunEnabled,
                         @Value("${archive.runtime.callback-retry-limit:3}") int callbackRetryLimit) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.archiveOs = archiveOs;
        this.metrics = metrics;
        this.approvalThreshold = approvalThreshold;
        this.runtimeAutoRunEnabled = runtimeAutoRunEnabled;
        this.callbackRetryLimit = Math.max(0, Math.min(callbackRetryLimit, 10));
    }

    @Transactional
    public EventIngestionResponse ingest(NexusEventRequest request) {
        return ingestWithSource(withSourceFallback(request, SOURCE_NEXUS));
    }

    @Transactional
    public EventIngestionResponse ingestLogistics(NexusEventRequest request) {
        return ingestWithSource(withSourceFallback(request, SOURCE_LOGITICS));
    }

    @Transactional
    public EventIngestionResponse ingestMarket(NexusEventRequest request) {
        return ingestWithSource(withSourceFallback(request, SOURCE_MARKET));
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

    public BulkIngestionResponse ingestMarketBulk(MarketBulkRequest request) {
        String source = value(request == null ? null : request.source(), SOURCE_MARKET);
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

    @Transactional
    public WorkforceAllocationView assignWorkforce(WorkforceAllocationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request is required.");
        }
        int headcount = workforceHeadcount(request);
        if (headcount < 0) {
            throw new IllegalArgumentException("allocatedHeadcount must be greater than or equal to 0.");
        }
        String sourceService = workforceSource(request.sourceService());
        if (!SOURCE_MARKET.equals(sourceService) && !"ArchiveOS".equals(sourceService)) {
            throw new IllegalArgumentException("sourceService must be Archive-Market or ArchiveOS.");
        }
        int hopCount = request.hopCount() == null ? 0 : request.hopCount();
        int maxHop = request.maxHop() == null ? Integer.MAX_VALUE : request.maxHop();
        if (hopCount > maxHop) {
            throw new IllegalArgumentException("hopCount exceeded maxHop.");
        }

        Instant now = Instant.now();
        LocalDate workDate = request.workDate() == null ? LocalDate.now() : request.workDate();
        String workdayId = value(request.workdayId(), "LEDGER-WORKDAY-" + workDate.toString().replace("-", ""));
        String allocationId = value(request.allocationId(),
                "WFA-" + workDate.toString().replace("-", "") + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        String role = workforceRole(request);
        int capacityPerPerson = request.capacityPerPersonPerDay() == null
                ? defaultRoleCapacity(role)
                : request.capacityPerPersonPerDay();
        BigDecimal wage = firstNonNull(request.wagePerDay(), request.unitCostKrw(), defaultRoleWage(role));
        BigDecimal productivity = firstNonNull(request.productivityScore(), request.productivityMultiplier(), BigDecimal.ONE)
                .setScale(4, RoundingMode.HALF_UP);
        int effectiveCapacity = BigDecimal.valueOf(headcount)
                .multiply(BigDecimal.valueOf(capacityPerPerson))
                .multiply(productivity)
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
        boolean enabled = request.enabled() == null || request.enabled();
        String allocationStatus = enabled ? value(request.status(), "ACTIVE") : "DISABLED";
        String targetService = value(request.targetService(), TARGET_LEDGER);

        jdbc.update("""
                insert into ledger_workforce_allocation(
                    allocation_id,workday_id,work_date,simulation_run_id,settlement_cycle_id,source_service,target_service,role_type,
                    allocated_headcount,capacity_per_person_per_day,productivity_score,wage_per_day,effective_capacity,
                    status,created_at,updated_at
                ) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                allocationId,
                workdayId,
                Date.valueOf(workDate),
                request.simulationRunId(),
                request.settlementCycleId(),
                sourceService,
                targetService,
                role,
                headcount,
                capacityPerPerson,
                productivity,
                wage,
                effectiveCapacity,
                allocationStatus,
                ts(now),
                ts(now)
        );
        audit(allocationId, sourceService, "WORKFORCE_ALLOCATION_ASSIGNED", "ledger_workforce_allocation", allocationId,
                null, allocationStatus,
                Map.of(
                        "workdayId", workdayId,
                        "roleType", role,
                        "allocatedHeadcount", headcount,
                        "effectiveCapacity", effectiveCapacity,
                        "correlationId", value(request.correlationId(), ""),
                        "causationId", value(request.causationId(), ""),
                        "hopCount", hopCount,
                        "maxHop", maxHop
                ));
        return workforceAllocation(allocationId).orElseThrow();
    }

    @Transactional
    public WorkforceWorkdayResult runWorkday(LocalDate date, String sourceService, String workdayId) {
        return runWorkday(date, sourceService, workdayId, Integer.MAX_VALUE);
    }

    private WorkforceWorkdayResult runWorkday(LocalDate date, String sourceService, String workdayId, int maxItemsPerRole) {
        LocalDate workDate = date == null ? LocalDate.now() : date;
        String assignmentSource = workforceSource(sourceService);
        String resolvedWorkdayId = value(workdayId,
                "LEDGER-WORKDAY-" + workDate.toString().replace("-", "") + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        WorkforceCapacity capacity = workforceCapacity(workDate, assignmentSource);
        WorkdayDemand demand = ledgerWorkdayDemand(workDate);
        int transactionCapacity = capacity.capacityFor("TRANSACTION_PROCESSOR");
        int ledgerCapacity = capacity.capacityFor("LEDGER_ACCOUNTANT");
        int settlementCapacity = capacity.capacityFor("SETTLEMENT_OPERATOR");
        int reconciliationCapacity = capacity.capacityFor("RECONCILIATION_ANALYST");
        int approvalCapacity = capacity.capacityFor("APPROVAL_REVIEWER");
        int callbackCapacity = capacity.capacityFor("CALLBACK_OPERATOR");
        int roleWorkLimit = maxItemsPerRole == Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(0, maxItemsPerRole);

        int transactionsProcessed = Math.min(demand.transactionsReceived(), Math.min(roleWorkLimit, Math.min(transactionCapacity, ledgerCapacity)));
        int transactionsBacklog = Math.max(0, demand.transactionsReceived() - transactionsProcessed);
        int settlementCompleted = Math.min(demand.settlementReady(), Math.min(roleWorkLimit, settlementCapacity));
        int settlementBacklog = Math.max(0, demand.settlementReady() - settlementCompleted);
        int reconciliationProcessed = Math.min(demand.reconciliationIssues(), Math.min(roleWorkLimit, reconciliationCapacity));
        int reconciliationBacklog = Math.max(0, demand.reconciliationIssues() - reconciliationProcessed);
        int approvalReviewed = Math.min(demand.approvalRequired(), Math.min(roleWorkLimit, approvalCapacity));
        int approvalBacklog = Math.max(0, demand.approvalRequired() - approvalReviewed);
        int callbackProcessed = Math.min(demand.callbackDemand(), Math.min(roleWorkLimit, callbackCapacity));
        int callbackBacklog = Math.max(0, demand.callbackDemand() - callbackProcessed);
        int callbackFailed = Math.max(0, demand.callbackFailures() + callbackBacklog);
        int totalDemand = demand.total();
        int totalProcessed = transactionsProcessed + settlementCompleted + reconciliationProcessed + approvalReviewed + callbackProcessed;
        int totalBacklog = transactionsBacklog + settlementBacklog + reconciliationBacklog + approvalBacklog + callbackBacklog;
        BigDecimal productivity = totalDemand == 0
                ? BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(totalProcessed)
                .divide(BigDecimal.valueOf(totalDemand), 4, RoundingMode.HALF_UP);
        String bottleneckRole = bottleneckRole(transactionsBacklog, settlementBacklog, reconciliationBacklog, approvalBacklog, callbackBacklog);
        boolean bottleneck = bottleneckRole != null;
        String status = bottleneck ? "BOTTLENECK_DETECTED" : "WORKDAY_COMPLETED";
        BigDecimal backlogCost = backlogCost(transactionsBacklog, settlementBacklog, reconciliationBacklog, approvalBacklog, callbackBacklog);
        Instant now = Instant.now();

        jdbc.update("""
                insert into ledger_workday_result(
                    workday_id,work_date,baseline_capacity,allocated_capacity,transactions_received,transactions_processed,transactions_backlog,
                    settlement_ready_count,settlement_completed_count,settlement_backlog_count,
                    reconciliation_processed_count,reconciliation_backlog_count,approval_reviewed_count,approval_backlog_count,
                    callback_processed_count,callback_failed_count,callback_backlog_count,payroll_cost,backlog_cost,
                    productivity_score,bottleneck_role,created_at
                ) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                resolvedWorkdayId,
                Date.valueOf(workDate),
                LEDGER_BASELINE_DAILY_CAPACITY,
                capacity.allocatedCapacity(),
                demand.transactionsReceived(),
                transactionsProcessed,
                transactionsBacklog,
                demand.settlementReady(),
                settlementCompleted,
                settlementBacklog,
                reconciliationProcessed,
                reconciliationBacklog,
                approvalReviewed,
                approvalBacklog,
                callbackProcessed,
                callbackFailed,
                callbackBacklog,
                capacity.operatingCost(),
                backlogCost,
                productivity,
                bottleneckRole,
                ts(now)
        );
        audit(resolvedWorkdayId, "Archive-Ledger", status, "ledger_workday_result", resolvedWorkdayId,
                null, status,
                Map.of("workdayId", resolvedWorkdayId, "demand", totalDemand, "processed", totalProcessed, "backlog", totalBacklog));
        if (transactionsBacklog > 0) audit(resolvedWorkdayId, "Archive-Ledger", "TRANSACTION_BACKLOG_INCREASED", "ledger_workday_result", resolvedWorkdayId, null, status, Map.of("backlog", transactionsBacklog));
        if (settlementBacklog > 0) audit(resolvedWorkdayId, "Archive-Ledger", "SETTLEMENT_DELAYED", "ledger_workday_result", resolvedWorkdayId, null, status, Map.of("backlog", settlementBacklog));
        if (reconciliationBacklog > 0) audit(resolvedWorkdayId, "Archive-Ledger", "RECONCILIATION_BACKLOG_INCREASED", "ledger_workday_result", resolvedWorkdayId, null, status, Map.of("backlog", reconciliationBacklog));
        if (approvalBacklog > 0) audit(resolvedWorkdayId, "Archive-Ledger", "APPROVAL_BACKLOG_INCREASED", "ledger_workday_result", resolvedWorkdayId, null, status, Map.of("backlog", approvalBacklog));
        if (callbackBacklog > 0) audit(resolvedWorkdayId, "Archive-Ledger", "CALLBACK_RETRY_DELAYED", "ledger_workday_result", resolvedWorkdayId, null, status, Map.of("backlog", callbackBacklog));
        if (bottleneck) audit(resolvedWorkdayId, "Archive-Ledger", "CAPACITY_SHORTAGE_DETECTED", "ledger_workday_result", resolvedWorkdayId, null, status, Map.of("bottleneckRole", bottleneckRole));
        if (capacity.operatingCost().compareTo(BigDecimal.ZERO) > 0) audit(resolvedWorkdayId, "Archive-Ledger", "LEDGER_WORKFORCE_PAYROLL_COST_INCURRED", "ledger_workday_result", resolvedWorkdayId, null, status, Map.of("payrollCost", capacity.operatingCost()));
        if (settlementBacklog > 0) audit(resolvedWorkdayId, "Archive-Ledger", "SETTLEMENT_BACKLOG_COST_INCURRED", "ledger_workday_result", resolvedWorkdayId, null, status, Map.of("cost", settlementBacklogCost(settlementBacklog)));
        if (reconciliationBacklog > 0) audit(resolvedWorkdayId, "Archive-Ledger", "RECONCILIATION_DELAY_COST_INCURRED", "ledger_workday_result", resolvedWorkdayId, null, status, Map.of("cost", reconciliationDelayCost(reconciliationBacklog)));
        if (approvalBacklog > 0) audit(resolvedWorkdayId, "Archive-Ledger", "APPROVAL_BACKLOG_COST_INCURRED", "ledger_workday_result", resolvedWorkdayId, null, status, Map.of("cost", approvalBacklogCost(approvalBacklog)));
        if (callbackBacklog > 0) audit(resolvedWorkdayId, "Archive-Ledger", "CALLBACK_DELAY_COST_INCURRED", "ledger_workday_result", resolvedWorkdayId, null, status, Map.of("cost", callbackDelayCost(callbackBacklog)));
        WorkforceWorkdayResult result = workforceWorkdayResult(resolvedWorkdayId).orElseThrow();
        recordSettlementRuntimeBalance(result);
        return result;
    }

    public WorkforceSummary workforceSummary(LocalDate date, String sourceService) {
        LocalDate workDate = date == null ? LocalDate.now() : date;
        String assignmentSource = workforceSource(sourceService);
        WorkforceCapacity capacity = workforceCapacity(workDate, assignmentSource);
        WorkdayDemand demand = ledgerWorkdayDemand(workDate);
        int totalDemand = demand.total();
        int transactionsBacklog = Math.max(0, demand.transactionsReceived() - Math.min(capacity.capacityFor("TRANSACTION_PROCESSOR"), capacity.capacityFor("LEDGER_ACCOUNTANT")));
        int settlementBacklog = Math.max(0, demand.settlementReady() - capacity.capacityFor("SETTLEMENT_OPERATOR"));
        int reconciliationBacklog = Math.max(0, demand.reconciliationIssues() - capacity.capacityFor("RECONCILIATION_ANALYST"));
        int approvalBacklog = Math.max(0, demand.approvalRequired() - capacity.capacityFor("APPROVAL_REVIEWER"));
        int callbackBacklog = Math.max(0, demand.callbackDemand() - capacity.capacityFor("CALLBACK_OPERATOR"));
        int backlog = transactionsBacklog + settlementBacklog + reconciliationBacklog + approvalBacklog + callbackBacklog;
        int processed = Math.max(0, totalDemand - backlog);
        BigDecimal productivity = totalDemand == 0
                ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(processed).divide(BigDecimal.valueOf(totalDemand), 4, RoundingMode.HALF_UP);
        String bottleneckRole = summaryBottleneckRole(settlementBacklog, reconciliationBacklog, approvalBacklog, callbackBacklog);
        String status = "HEALTHY";
        List<WorkforceRoleSummary> roles = workforceRoleSummaries(workDate, assignmentSource, demand);
        int effectiveCapacity = roles.stream().mapToInt(WorkforceRoleSummary::effectiveCapacity).sum();
        int usedCapacity = roles.isEmpty() ? 0 : Math.min(processed, effectiveCapacity);
        int remainingCapacity = Math.max(0, effectiveCapacity - usedCapacity);
        BigDecimal utilization = effectiveCapacity == 0
                ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(usedCapacity).divide(BigDecimal.valueOf(effectiveCapacity), 4, RoundingMode.HALF_UP);
        Instant latestEventAt = latestRuntimeSourceEventAt();
        int reconciliationWarnings = count("select count(*) from reconciliation_result where status in ('WARNING','CRITICAL') or mismatch_count > 0");
        int callbackFailed = count("select count(*) from audit_log where action='ARCHIVEOS_APPROVAL_DEGRADED'");
        return new WorkforceSummary(
                "Archive-Ledger",
                assignmentSource,
                true,
                capacity.activeAllocations() > 0,
                capacity.activeAllocations(),
                capacity.assignedUnits(),
                capacity.assignedUnits(),
                roles,
                capacity.operatingCost(),
                LEDGER_BASELINE_DAILY_CAPACITY,
                capacity.allocatedCapacity(),
                effectiveCapacity,
                usedCapacity,
                remainingCapacity,
                utilization,
                totalDemand,
                backlog,
                transactionsBacklog,
                approvalBacklog,
                settlementBacklog,
                reconciliationBacklog,
                callbackBacklog,
                productivity,
                bottleneckRole,
                capacity.operatingCost(),
                status,
                capacity.capacityFor("APPROVAL_REVIEWER"),
                capacity.capacityFor("SETTLEMENT_OPERATOR"),
                latestEventAt,
                count("select count(*) from finance_transaction"),
                count("select count(*) from approval_request where status in ('APPROVED','REJECTED')"),
                count("select count(*) from finance_transaction where status='SETTLED'"),
                reconciliationWarnings,
                callbackFailed
        );
    }

    public RuntimeStatusResponse runtimeStatus() {
        int backlog = currentRuntimeBacklog();
        Instant lastEventAt = latestRuntimeSourceEventAt();
        Instant lastWorkAt = lastRuntimeWorkAt == null ? latestWorkResultAt() : lastRuntimeWorkAt;
        boolean active = runtimeAutoRunEnabled && (lastWorkAt != null || lastEventAt != null);
        String schedulerStatus = runtimeAutoRunEnabled ? "RUNNING" : "DISABLED";
        String pipelineStatus = runtimeAutoRunEnabled
                ? (lastEventAt == null && lastWorkAt == null ? "WAITING_FOR_DATA" : (backlog > 0 ? "LIVE_WITH_BACKLOG" : "LIVE"))
                : "PAUSED";
        String degradedReason = backlog > 0 ? "BACKLOG_PRESENT" : (lastEventAt == null ? "NO_RUNTIME_DATA" : null);
        return new RuntimeStatusResponse(
                TARGET_LEDGER,
                active,
                runtimeAutoRunEnabled,
                schedulerStatus,
                lastWorkAt,
                lastEventAt,
                lastRuntimeEventsProduced,
                lastRuntimeEventsConsumed,
                backlog,
                oldestRuntimeBacklogAgeSeconds(),
                latestRuntimeCursor(),
                pipelineStatus,
                degradedReason
        );
    }

    public RuntimeTickResult autonomousRuntimeTick(int maxEventsPerTick, int maxBacklogPerTick) {
        String tickId = "LEDGER-RUNTIME-TICK-" + Instant.now().getEpochSecond() / 30;
        return autonomousRuntimeTick(tickId, maxEventsPerTick, maxBacklogPerTick);
    }

    @Transactional
    public RuntimeTickResult autonomousRuntimeTick(String tickId, int maxEventsPerTick, int maxBacklogPerTick) {
        String resolvedTickId = value(tickId, "LEDGER-RUNTIME-TICK-" + Instant.now().getEpochSecond() / 30);
        int safeMaxEvents = Math.max(1, Math.min(maxEventsPerTick, 100));
        int safeMaxBacklog = Math.max(1, Math.min(maxBacklogPerTick, 1000));
        int backlog = Math.min(currentRuntimeBacklog(), safeMaxBacklog);

        if (!runtimeTickLock.compareAndSet(false, true)) {
            return updateRuntimeTickState(resolvedTickId, 0, 0, backlog, true);
        }
        try {
            if (exists("select count(*) from ledger_workday_result where workday_id=?", resolvedTickId)
                    || exists("select count(*) from audit_log where action='RUNTIME_WORK_TICK' and target_id=?", resolvedTickId)) {
                return updateRuntimeTickState(resolvedTickId, 0, 0, backlog, true);
            }

            int produced = 0;
            int consumed = 0;
            LocalDate today = LocalDate.now();

            WorkforceWorkdayResult workday = null;
            if (produced < safeMaxEvents) {
                workday = runWorkday(today, "ArchiveOS", resolvedTickId, safeMaxBacklog);
                produced++;
                consumed += workday.transactionsProcessed();
            }
            int settlementLimit = workday == null ? 0 : Math.min(safeMaxBacklog, workday.settlementCompletedCount());
            if (produced < safeMaxEvents && settlementLimit > 0 && hasSettlementReadyTransactions(today)) {
                SettlementBatchView batch = runSettlement(today, settlementLimit);
                produced++;
                consumed += batch.totalTransactionCount();
            }
            if (produced < safeMaxEvents) {
                reconcile(today);
                produced++;
            }
            if (produced < safeMaxEvents) {
                int callbackRetries = retryPendingApprovalDispatches(Math.min(safeMaxEvents - produced, safeMaxBacklog));
                if (callbackRetries > 0) {
                    produced++;
                    consumed += callbackRetries;
                }
            }

            audit(resolvedTickId, TARGET_LEDGER, "RUNTIME_WORK_TICK", "runtime_work_loop", resolvedTickId,
                    null, "COMPLETED",
                    Map.of(
                            "maxEventsPerTick", safeMaxEvents,
                            "maxBacklogPerTick", safeMaxBacklog,
                            "eventsProduced", produced,
                            "eventsConsumed", consumed,
                            "backlog", backlog,
                            "idempotencyKey", "RUNTIME:" + resolvedTickId,
                            "correlationId", resolvedTickId,
                            "causationId", "Archive-Ledger",
                            "hopCount", 0,
                            "maxHop", 1
                    ));
            return updateRuntimeTickState(resolvedTickId, produced, consumed, backlog, false);
        } finally {
            runtimeTickLock.set(false);
        }
    }

    private RuntimeTickResult updateRuntimeTickState(String tickId, int produced, int consumed, int backlog, boolean duplicate) {
        Instant now = Instant.now();
        lastRuntimeWorkAt = duplicate ? lastRuntimeWorkAt : now;
        lastRuntimeEventsProduced = produced;
        lastRuntimeEventsConsumed = consumed;
        lastRuntimeBacklogCount = backlog;
        return new RuntimeTickResult(tickId, produced, consumed, backlog, duplicate, lastRuntimeWorkAt);
    }

    private WorkdayDemand ledgerWorkdayDemand(LocalDate workDate) {
        Date day = Date.valueOf(workDate);
        int transactionsReceived = count("select count(*) from received_event where cast(received_at as date)=?", day);
        int settlementReady = count("select count(*) from finance_transaction where status='SETTLEMENT_READY' and cast(occurred_at as date)=?", day);
        int approvalRequired = count("select count(*) from finance_transaction where status='APPROVAL_REQUIRED' and cast(created_at as date)=?", day);
        int mismatch = count("select coalesce((select mismatch_count from reconciliation_result where reconciliation_date=? order by created_at desc limit 1), 0)", day);
        int reconciliationIssues = Math.max(mismatch, count("select count(*) from received_event where processing_status='FAILED' and cast(received_at as date)=?", day));
        int callbackDemand = count("select count(*) from approval_request where status='REQUESTED' and cast(requested_at as date)=?", day);
        int callbackFailures = count("select count(*) from audit_log where action='ARCHIVEOS_APPROVAL_DEGRADED' and cast(created_at as date)=?", day);
        return new WorkdayDemand(transactionsReceived, settlementReady, approvalRequired, reconciliationIssues, callbackDemand, callbackFailures);
    }

    private List<WorkforceRoleSummary> workforceRoleSummaries(LocalDate workDate, String sourceService, WorkdayDemand demand) {
        return jdbc.query("""
                select role_type, allocated_headcount, capacity_per_person_per_day, productivity_score, wage_per_day, effective_capacity
                from ledger_workforce_allocation
                where work_date=? and source_service=? and target_service=? and status='ACTIVE'
                order by role_type
                """, (rs, row) -> {
            String role = rs.getString("role_type");
            int effective = rs.getInt("effective_capacity");
            int used = Math.min(roleDemand(role, demand), effective);
            return new WorkforceRoleSummary(
                    role,
                    rs.getInt("allocated_headcount"),
                    rs.getInt("capacity_per_person_per_day"),
                    rs.getBigDecimal("productivity_score"),
                    rs.getBigDecimal("wage_per_day"),
                    effective,
                    used,
                    Math.max(0, effective - used)
            );
        }, Date.valueOf(workDate), sourceService, TARGET_LEDGER);
    }

    private int roleDemand(String role, WorkdayDemand demand) {
        return switch (role == null ? "" : role.toUpperCase(Locale.ROOT)) {
            case "TRANSACTION_PROCESSOR", "LEDGER_ACCOUNTANT" -> demand.transactionsReceived();
            case "SETTLEMENT_OPERATOR" -> demand.settlementReady();
            case "RECONCILIATION_ANALYST" -> demand.reconciliationIssues();
            case "APPROVAL_REVIEWER" -> demand.approvalRequired();
            case "CALLBACK_OPERATOR" -> demand.callbackDemand();
            case "LEDGER_MANAGER" -> demand.total();
            default -> 0;
        };
    }

    private Instant latestRuntimeSourceEventAt() {
        Instant receivedAt = jdbc.query("select max(received_at) from received_event",
                rs -> rs.next() ? instant(rs.getTimestamp(1)) : null);
        Instant transactionAt = jdbc.query("select max(created_at) from finance_transaction",
                rs -> rs.next() ? instant(rs.getTimestamp(1)) : null);
        Instant auditAt = jdbc.query("select max(created_at) from audit_log",
                rs -> rs.next() ? instant(rs.getTimestamp(1)) : null);
        Instant latest = receivedAt;
        if (transactionAt != null && (latest == null || transactionAt.isAfter(latest))) {
            latest = transactionAt;
        }
        if (auditAt != null && (latest == null || auditAt.isAfter(latest))) {
            latest = auditAt;
        }
        return latest;
    }

    private Instant latestWorkResultAt() {
        Instant workdayAt = jdbc.query("select max(created_at) from ledger_workday_result",
                rs -> rs.next() ? instant(rs.getTimestamp(1)) : null);
        Instant reconciliationAt = jdbc.query("select max(created_at) from reconciliation_result",
                rs -> rs.next() ? instant(rs.getTimestamp(1)) : null);
        if (workdayAt == null) {
            return reconciliationAt;
        }
        if (reconciliationAt == null) {
            return workdayAt;
        }
        return workdayAt.isAfter(reconciliationAt) ? workdayAt : reconciliationAt;
    }

    private int currentRuntimeBacklog() {
        return count("select count(*) from finance_transaction where status in ('APPROVAL_REQUIRED','SETTLEMENT_READY')")
                + count("select count(*) from received_event where processing_status='FAILED'")
                + count("select count(*) from approval_request where status='REQUESTED'");
    }

    private long oldestRuntimeBacklogAgeSeconds() {
        Instant oldest = null;
        List<Instant> candidates = new ArrayList<>();
        candidates.add(queryInstant("select min(created_at) from finance_transaction where status in ('APPROVAL_REQUIRED','SETTLEMENT_READY')"));
        candidates.add(queryInstant("select min(received_at) from received_event where processing_status='FAILED'"));
        candidates.add(queryInstant("select min(requested_at) from approval_request where status='REQUESTED'"));
        for (Instant candidate : candidates) {
            if (candidate != null && (oldest == null || candidate.isBefore(oldest))) {
                oldest = candidate;
            }
        }
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest, Instant.now()).toSeconds());
    }

    private Instant queryInstant(String sql) {
        return jdbc.query(sql, rs -> rs.next() ? instant(rs.getTimestamp(1)) : null);
    }

    private String latestRuntimeCursor() {
        return runtimeEventProjection(1, null, null, null).stream()
                .map(RuntimeEventView::cursor)
                .findFirst()
                .orElse(null);
    }

    private WorkforceCapacity workforceCapacity(LocalDate workDate, String sourceService) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select role_type, allocated_headcount, wage_per_day, effective_capacity
                from ledger_workforce_allocation
                where work_date=? and source_service=? and target_service=? and status='ACTIVE'
                """,
                Date.valueOf(workDate), sourceService, TARGET_LEDGER);
        int active = rows.size();
        int units = 0;
        Map<String, Integer> roleCapacity = new HashMap<>();
        BigDecimal cost = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            int assigned = ((Number) row.get("allocated_headcount")).intValue();
            BigDecimal wage = (BigDecimal) row.get("wage_per_day");
            int effective = ((Number) row.get("effective_capacity")).intValue();
            String role = String.valueOf(row.get("role_type"));
            units += assigned;
            cost = cost.add(wage.multiply(BigDecimal.valueOf(assigned)));
            roleCapacity.merge(role, effective, Integer::sum);
        }
        if (active == 0) {
            for (String role : List.of("TRANSACTION_PROCESSOR", "LEDGER_ACCOUNTANT", "SETTLEMENT_OPERATOR", "RECONCILIATION_ANALYST", "APPROVAL_REVIEWER", "CALLBACK_OPERATOR", "LEDGER_MANAGER")) {
                roleCapacity.put(role, defaultRoleCapacity(role));
            }
        }
        int allocatedCapacity = active == 0
                ? LEDGER_BASELINE_DAILY_CAPACITY
                : LEDGER_BASELINE_DAILY_CAPACITY + roleCapacity.values().stream().mapToInt(Integer::intValue).sum();
        return new WorkforceCapacity(
                active,
                units,
                allocatedCapacity,
                roleCapacity,
                cost.setScale(2, RoundingMode.HALF_UP)
        );
    }

    private int defaultRoleCapacity(String role) {
        return switch (role == null ? "" : role.toUpperCase(Locale.ROOT)) {
            case "TRANSACTION_PROCESSOR" -> 100;
            case "LEDGER_ACCOUNTANT" -> 100;
            case "SETTLEMENT_OPERATOR" -> 50;
            case "RECONCILIATION_ANALYST" -> 20;
            case "APPROVAL_REVIEWER" -> 30;
            case "CALLBACK_OPERATOR" -> 50;
            case "LEDGER_MANAGER" -> 200;
            default -> 80;
        };
    }

    private String workforceSource(String sourceService) {
        return value(sourceService, "ArchiveOS");
    }

    private int workforceHeadcount(WorkforceAllocationRequest request) {
        return request.allocatedHeadcount() == null ? request.assignedUnits() : request.allocatedHeadcount();
    }

    private String workforceRole(WorkforceAllocationRequest request) {
        String role = value(request.roleType(), request.role());
        return role.toUpperCase(Locale.ROOT);
    }

    private BigDecimal defaultRoleWage(String role) {
        return switch (role == null ? "" : role.toUpperCase(Locale.ROOT)) {
            case "TRANSACTION_PROCESSOR" -> new BigDecimal("110000");
            case "LEDGER_ACCOUNTANT" -> new BigDecimal("130000");
            case "SETTLEMENT_OPERATOR" -> new BigDecimal("120000");
            case "RECONCILIATION_ANALYST" -> new BigDecimal("150000");
            case "APPROVAL_REVIEWER" -> new BigDecimal("140000");
            case "CALLBACK_OPERATOR" -> new BigDecimal("100000");
            case "LEDGER_MANAGER" -> new BigDecimal("180000");
            default -> new BigDecimal("100000");
        };
    }

    private String bottleneckRole(int transactionsBacklog, int settlementBacklog, int reconciliationBacklog,
                                  int approvalBacklog, int callbackBacklog) {
        Map<String, Integer> values = new HashMap<>();
        values.put("TRANSACTION_PROCESSOR", transactionsBacklog);
        values.put("SETTLEMENT_OPERATOR", settlementBacklog);
        values.put("RECONCILIATION_ANALYST", reconciliationBacklog);
        values.put("APPROVAL_REVIEWER", approvalBacklog);
        values.put("CALLBACK_OPERATOR", callbackBacklog);
        return values.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private String summaryBottleneckRole(int settlementBacklog, int reconciliationBacklog,
                                         int approvalBacklog, int callbackBacklog) {
        Map<String, Integer> values = new HashMap<>();
        values.put("APPROVAL_REVIEWER", approvalBacklog);
        values.put("SETTLEMENT_OPERATOR", settlementBacklog);
        values.put("RECONCILIATION_ANALYST", reconciliationBacklog);
        values.put("CALLBACK_OPERATOR", callbackBacklog);
        return values.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("NONE");
    }

    private BigDecimal backlogCost(int transactionsBacklog, int settlementBacklog, int reconciliationBacklog,
                                   int approvalBacklog, int callbackBacklog) {
        return BigDecimal.valueOf(transactionsBacklog).multiply(new BigDecimal("300"))
                .add(settlementBacklogCost(settlementBacklog))
                .add(reconciliationDelayCost(reconciliationBacklog))
                .add(approvalBacklogCost(approvalBacklog))
                .add(callbackDelayCost(callbackBacklog))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal settlementBacklogCost(int backlog) {
        return BigDecimal.valueOf(backlog).multiply(new BigDecimal("1500"));
    }

    private BigDecimal reconciliationDelayCost(int backlog) {
        return BigDecimal.valueOf(backlog).multiply(new BigDecimal("2000"));
    }

    private BigDecimal approvalBacklogCost(int backlog) {
        return BigDecimal.valueOf(backlog).multiply(new BigDecimal("2500"));
    }

    private BigDecimal callbackDelayCost(int backlog) {
        return BigDecimal.valueOf(backlog).multiply(new BigDecimal("1000"));
    }

    public SettlementAgencySummary settlementAgencySummary() {
        WorkforceWorkdayResult latest = latestWorkdayResult().orElse(null);
        AgencyMetrics metrics = agencyMetrics(latest);
        SettlementBalanceSummary balance = settlementBalanceSummary();
        return new SettlementAgencySummary(
                TARGET_LEDGER,
                metrics.transactionRevenue(),
                metrics.settlementRevenue(),
                metrics.reconciliationRevenue(),
                metrics.approvalRevenue(),
                metrics.totalRevenue(),
                metrics.payrollCost(),
                metrics.settlementBacklogCost(),
                metrics.reconciliationBacklogCost(),
                metrics.approvalBacklogCost(),
                metrics.callbackFailureCost(),
                metrics.operatingCost(),
                metrics.operatingProfit(),
                metrics.transactionsProcessed(),
                metrics.settlementCompleted(),
                metrics.reconciliationProcessed(),
                metrics.approvalReviewed(),
                metrics.transactionsBacklog(),
                metrics.settlementBacklog(),
                metrics.reconciliationBacklog(),
                metrics.approvalBacklog(),
                metrics.callbackBacklog(),
                metrics.productivityScore(),
                metrics.bottleneckRole(),
                balance
        );
    }

    private AgencyMetrics agencyMetrics(WorkforceWorkdayResult result) {
        int transactionsProcessed = result == null ? 0 : result.transactionsProcessed();
        int settlementCompleted = result == null ? 0 : result.settlementCompletedCount();
        int reconciliationProcessed = result == null ? 0 : result.reconciliationProcessedCount();
        int approvalReviewed = result == null ? 0 : result.approvalReviewedCount();
        int transactionsBacklog = result == null ? 0 : result.transactionsBacklog();
        int settlementBacklog = result == null ? 0 : result.settlementBacklog();
        int reconciliationBacklog = result == null ? 0 : result.reconciliationBacklog();
        int approvalBacklog = result == null ? 0 : result.approvalBacklog();
        int callbackBacklog = result == null ? 0 : result.callbackBacklog();
        BigDecimal transactionRevenue = BigDecimal.valueOf(transactionsProcessed).multiply(new BigDecimal("120"));
        BigDecimal settlementRevenue = BigDecimal.valueOf(settlementCompleted).multiply(new BigDecimal("700"));
        BigDecimal reconciliationRevenue = BigDecimal.valueOf(reconciliationProcessed).multiply(new BigDecimal("500"));
        BigDecimal approvalRevenue = BigDecimal.valueOf(approvalReviewed).multiply(new BigDecimal("900"));
        BigDecimal totalRevenue = transactionRevenue.add(settlementRevenue).add(reconciliationRevenue).add(approvalRevenue);
        BigDecimal payroll = result == null ? BigDecimal.ZERO : result.payrollCost();
        BigDecimal settlementCost = settlementBacklogCost(settlementBacklog);
        BigDecimal reconciliationCost = reconciliationDelayCost(reconciliationBacklog);
        BigDecimal approvalCost = approvalBacklogCost(approvalBacklog);
        BigDecimal callbackCost = callbackDelayCost(callbackBacklog);
        BigDecimal totalCost = payroll.add(settlementCost).add(reconciliationCost).add(approvalCost).add(callbackCost);
        return new AgencyMetrics(
                transactionRevenue, settlementRevenue, reconciliationRevenue, approvalRevenue, totalRevenue,
                payroll, settlementCost, reconciliationCost, approvalCost, callbackCost, totalCost,
                totalRevenue.subtract(totalCost), transactionsProcessed, settlementCompleted, reconciliationProcessed,
                approvalReviewed, transactionsBacklog, settlementBacklog, reconciliationBacklog, approvalBacklog,
                callbackBacklog, result == null ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP) : result.productivityScore(),
                result == null ? "NONE" : value(result.bottleneckRole(), "NONE")
        );
    }

    private void recordSettlementRuntimeBalance(WorkforceWorkdayResult result) {
        AgencyMetrics metrics = agencyMetrics(result);
        WorkforceSummary capacity = workforceSummary(result.workDate(), "ArchiveOS");
        String settlementCycleId = latestSettlementCycleId(result.workDate());
        BigDecimal previousCash = previousCashBalance(result.workDate());
        int previousStreak = previousNegativeProfitStreak(result.workDate());
        BigDecimal margin = operatingMargin(metrics.operatingProfit(), metrics.totalRevenue());
        BigDecimal delayRate = settlementDelayRate(metrics.settlementCompleted(), metrics.settlementBacklog());
        int negativeProfitStreak = metrics.operatingProfit().compareTo(BigDecimal.ZERO) < 0 ? previousStreak + 1 : 0;
        BigDecimal cashBalance = previousCash.add(metrics.operatingProfit());

        if (exists("select count(*) from ledger_runtime_balance_snapshot where work_date=?", Date.valueOf(result.workDate()))) {
            jdbc.update("""
                    update ledger_runtime_balance_snapshot
                    set settlement_cycle_id=?,transaction_processing_revenue=?,settlement_agency_revenue=?,
                        reconciliation_revenue=?,approval_review_revenue=?,workforce_cost=?,callback_failure_cost=?,
                        operating_cost=?,operating_profit=?,operating_margin=?,cash_balance=?,transactions_received=?,
                        transactions_processed=?,approval_backlog=?,settlement_backlog=?,reconciliation_backlog=?,
                        callback_backlog=?,capacity_utilization=?,bottleneck_role=?,settlement_delay_rate=?,
                        negative_profit_streak=?,calculated_at=?
                    where work_date=?
                    """,
                    settlementCycleId, metrics.transactionRevenue(), metrics.settlementRevenue(),
                    metrics.reconciliationRevenue(), metrics.approvalRevenue(), metrics.payrollCost(), metrics.callbackFailureCost(),
                    metrics.operatingCost(), metrics.operatingProfit(), margin, cashBalance, result.transactionsReceived(),
                    metrics.transactionsProcessed(), metrics.approvalBacklog(), metrics.settlementBacklog(),
                    metrics.reconciliationBacklog(), metrics.callbackBacklog(), capacity.capacityUtilizationRate(),
                    metrics.bottleneckRole(), delayRate, negativeProfitStreak, ts(Instant.now()), Date.valueOf(result.workDate()));
            return;
        }

        jdbc.update("""
                insert into ledger_runtime_balance_snapshot(
                    work_date,settlement_cycle_id,transaction_processing_revenue,settlement_agency_revenue,
                    reconciliation_revenue,approval_review_revenue,workforce_cost,callback_failure_cost,
                    operating_cost,operating_profit,operating_margin,cash_balance,transactions_received,
                    transactions_processed,approval_backlog,settlement_backlog,reconciliation_backlog,callback_backlog,
                    capacity_utilization,bottleneck_role,settlement_delay_rate,negative_profit_streak,calculated_at
                ) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                Date.valueOf(result.workDate()), settlementCycleId, metrics.transactionRevenue(), metrics.settlementRevenue(),
                metrics.reconciliationRevenue(), metrics.approvalRevenue(), metrics.payrollCost(), metrics.callbackFailureCost(),
                metrics.operatingCost(), metrics.operatingProfit(), margin, cashBalance, result.transactionsReceived(),
                metrics.transactionsProcessed(), metrics.approvalBacklog(), metrics.settlementBacklog(),
                metrics.reconciliationBacklog(), metrics.callbackBacklog(), capacity.capacityUtilizationRate(),
                metrics.bottleneckRole(), delayRate, negativeProfitStreak, ts(Instant.now()));
    }

    private SettlementBalanceSummary settlementBalanceSummary() {
        List<SettlementBalanceSummary> snapshots = jdbc.query("""
                select * from ledger_runtime_balance_snapshot
                order by work_date desc, calculated_at desc limit 1
                """, this::settlementBalanceRow);
        if (!snapshots.isEmpty()) {
            return snapshots.get(0);
        }
        WorkforceWorkdayResult latest = latestWorkdayResult().orElse(null);
        AgencyMetrics metrics = agencyMetrics(latest);
        BigDecimal utilization = latest == null ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : workforceSummary(latest.workDate(), "ArchiveOS").capacityUtilizationRate();
        return balanceFromMetrics(
                latest == null ? null : latest.workDate(),
                latest == null ? null : latestSettlementCycleId(latest.workDate()),
                latest,
                metrics,
                utilization,
                metrics.operatingProfit(),
                metrics.operatingProfit().compareTo(BigDecimal.ZERO) < 0 ? 1 : 0,
                Instant.now()
        );
    }

    private SettlementBalanceSummary settlementBalanceRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new SettlementBalanceSummary(
                true,
                TARGET_LEDGER,
                "WORKDAY",
                rs.getString("settlement_cycle_id"),
                rs.getBigDecimal("transaction_processing_revenue"),
                rs.getBigDecimal("settlement_agency_revenue"),
                rs.getBigDecimal("reconciliation_revenue"),
                rs.getBigDecimal("approval_review_revenue"),
                rs.getBigDecimal("workforce_cost"),
                rs.getBigDecimal("callback_failure_cost"),
                rs.getBigDecimal("operating_cost"),
                rs.getBigDecimal("operating_profit"),
                rs.getBigDecimal("operating_margin"),
                rs.getBigDecimal("cash_balance"),
                rs.getInt("transactions_received"),
                rs.getInt("transactions_processed"),
                rs.getInt("approval_backlog"),
                rs.getInt("settlement_backlog"),
                rs.getInt("reconciliation_backlog"),
                rs.getInt("callback_backlog"),
                rs.getBigDecimal("capacity_utilization"),
                value(rs.getString("bottleneck_role"), "NONE"),
                rs.getBigDecimal("settlement_delay_rate"),
                rs.getInt("negative_profit_streak"),
                instant(rs.getTimestamp("calculated_at"))
        );
    }

    private SettlementBalanceSummary balanceFromMetrics(LocalDate workDate, String settlementCycleId,
                                                         WorkforceWorkdayResult result, AgencyMetrics metrics,
                                                         BigDecimal utilization, BigDecimal cashBalance,
                                                         int negativeProfitStreak, Instant calculatedAt) {
        return new SettlementBalanceSummary(
                result != null,
                TARGET_LEDGER,
                "WORKDAY",
                settlementCycleId,
                metrics.transactionRevenue(), metrics.settlementRevenue(), metrics.reconciliationRevenue(),
                metrics.approvalRevenue(), metrics.payrollCost(), metrics.callbackFailureCost(), metrics.operatingCost(),
                metrics.operatingProfit(), operatingMargin(metrics.operatingProfit(), metrics.totalRevenue()), cashBalance,
                result == null ? 0 : result.transactionsReceived(), metrics.transactionsProcessed(), metrics.approvalBacklog(),
                metrics.settlementBacklog(), metrics.reconciliationBacklog(), metrics.callbackBacklog(), utilization,
                metrics.bottleneckRole(), settlementDelayRate(metrics.settlementCompleted(), metrics.settlementBacklog()),
                negativeProfitStreak, calculatedAt
        );
    }

    private String latestSettlementCycleId(LocalDate workDate) {
        return jdbc.query("""
                select settlement_cycle_id from received_event
                where cast(received_at as date)=? and settlement_cycle_id is not null
                order by received_at desc limit 1
                """, rs -> rs.next() ? rs.getString(1) : null, Date.valueOf(workDate));
    }

    private BigDecimal previousCashBalance(LocalDate workDate) {
        return jdbc.query("""
                select cash_balance from ledger_runtime_balance_snapshot
                where work_date<? order by work_date desc limit 1
                """, rs -> rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO, Date.valueOf(workDate));
    }

    private int previousNegativeProfitStreak(LocalDate workDate) {
        return jdbc.query("""
                select negative_profit_streak from ledger_runtime_balance_snapshot
                where work_date<? order by work_date desc limit 1
                """, rs -> rs.next() ? rs.getInt(1) : 0, Date.valueOf(workDate));
    }

    private BigDecimal operatingMargin(BigDecimal profit, BigDecimal revenue) {
        return revenue.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : profit.divide(revenue, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal settlementDelayRate(int completed, int backlog) {
        int total = completed + backlog;
        return total == 0 ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(backlog).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
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
        Map<String, Object> payload = request.payload();
        String simulationRunId = text(payload == null ? null : payload.get("simulationRunId"));
        String settlementCycleId = text(payload == null ? null : payload.get("settlementCycleId"));
        String correlationId = text(payload == null ? null : payload.get("correlationId"));
        String causationId = text(payload == null ? null : payload.get("causationId"));
        int hopCount = parseInt(payload == null ? null : payload.get("hopCount"), 0);
        int maxHop = parseInt(payload == null ? null : payload.get("maxHop"), Integer.MAX_VALUE);

        if (exists("select count(*) from received_event where event_id=? or idempotency_key=?", request.eventId(), request.idempotencyKey())) {
            metrics.duplicateEvent();
            audit(request.eventId(), "Archive-Ledger", "DUPLICATE_EVENT", "received_event", request.eventId(),
                    "RECEIVED", "DUPLICATE",
                    Map.of("idempotencyKey", request.idempotencyKey(), "source", source));
            return new EventIngestionResponse(request.eventId(), "DUPLICATE", null, true, "Duplicate event ignored safely.");
        }
        if (isMarketSource(source) && correlationId != null && isCorrelationDuplicate(source, request.eventType(), correlationId)) {
            metrics.duplicateEvent();
            audit(request.eventId(), "Archive-Ledger", "CORRELATION_DUPLICATE_EVENT", "received_event", request.eventId(),
                    "RECEIVED", "DUPLICATE",
                    Map.of("correlationId", correlationId, "source", source, "eventType", request.eventType()));
            return new EventIngestionResponse(request.eventId(), "DUPLICATE", null, true,
                    "Duplicate market event by correlationId and eventType.");
        }
        if (isMarketSource(source) && maxHop < Integer.MAX_VALUE && hopCount > maxHop) {
            audit(request.eventId(), "Archive-Ledger", "EVENT_HOP_GUARD_REJECTED", "received_event", request.eventId(),
                    "RECEIVED", "FAILED", Map.of("hopCount", hopCount, "maxHop", maxHop, "source", source));
            return new EventIngestionResponse(request.eventId(), "FAILED", null, false,
                    "Event hopCount exceeded maxHop.");
        }

        Instant receivedAt = Instant.now();
        String transactionId = "TX-" + LocalDate.now().toString().replace("-", "") + "-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase(Locale.ROOT);
        String approvalRequestId = null;
        String status = "FAILED";
        Normalized normalized = null;

        try {
            jdbc.update("""
                insert into received_event(
                        event_id,idempotency_key,source,source_service,event_type,schema_version,payload,processing_status,received_at,
                        simulation_run_id,settlement_cycle_id,correlation_id,causation_id,hop_count,max_hop
                    )
                    values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    request.eventId(),
                    request.idempotencyKey(),
                    source,
                    source,
                    request.eventType(),
                    request.schemaVersion() == null ? 1 : request.schemaVersion(),
                    write(request.payload()),
                    "RECEIVED",
                    ts(receivedAt),
                    simulationRunId,
                    settlementCycleId,
                    correlationId,
                    causationId,
                    hopCount,
                    maxHop
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
                    if (request.payload() != null) {
                        metadata.put("orderId", request.payload().get("orderId"));
                        metadata.put("paymentId", request.payload().get("paymentId"));
                        metadata.put("customerId", request.payload().get("customerId"));
                        metadata.put("returnId", request.payload().get("returnId"));
                        metadata.put("claimId", request.payload().get("claimId"));
                        metadata.put("customerType", request.payload().get("customerType"));
                        metadata.put("simulationRunId", request.payload().get("simulationRunId"));
                        metadata.put("settlementCycleId", request.payload().get("settlementCycleId"));
                        metadata.put("correlationId", request.payload().get("correlationId"));
                        metadata.put("causationId", request.payload().get("causationId"));
                        metadata.put("hopCount", request.payload().get("hopCount"));
                        metadata.put("maxHop", request.payload().get("maxHop"));
                    }
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
        if (!"APPROVAL_REQUIRED".equals(before)) {
            audit(request.transactionId(), value(request.decidedBy(), "synthetic-operator"), "DUPLICATE_APPROVAL_CALLBACK",
                    "finance_transaction", request.transactionId(), before, before,
                    Map.of("approvalRequestId", request.approvalRequestId(), "decision", request.decision()));
            return Map.of("transactionId", request.transactionId(), "previousStatus", before, "status", before, "duplicate", true);
        }
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
        return Map.of("transactionId", request.transactionId(), "previousStatus", before, "status", next, "duplicate", false);
    }

    private int retryPendingApprovalDispatches(int requestedAttempts) {
        if (!archiveOs.enabled() || requestedAttempts <= 0 || callbackRetryLimit == 0) {
            return 0;
        }
        int safeAttempts = Math.min(requestedAttempts, callbackRetryLimit);
        List<Map<String, Object>> requests = jdbc.queryForList("""
                select ar.approval_request_id, ar.transaction_id, ar.amount, ar.reason,
                       ft.currency, ft.source_service, ft.transaction_type, ft.factory_id, ft.vendor_id,
                       re.event_type, re.simulation_run_id, re.settlement_cycle_id, re.correlation_id, re.causation_id
                from approval_request ar
                join finance_transaction ft on ft.transaction_id=ar.transaction_id
                left join received_event re on re.event_id=ft.source_event_id
                where ar.status='REQUESTED'
                  and exists (
                    select 1 from audit_log al
                    where al.action='ARCHIVEOS_APPROVAL_DEGRADED'
                      and al.target_id=ar.approval_request_id
                  )
                order by ar.requested_at asc
                limit ?
                """, safeAttempts);
        int delivered = 0;
        for (Map<String, Object> row : requests) {
            String approvalRequestId = String.valueOf(row.get("approval_request_id"));
            int failureCount = count("select count(*) from audit_log where action='ARCHIVEOS_APPROVAL_DEGRADED' and target_id=?", approvalRequestId);
            if (failureCount >= callbackRetryLimit) {
                continue;
            }
            String transactionId = String.valueOf(row.get("transaction_id"));
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("sourceService", row.get("source_service"));
            metadata.put("eventType", row.get("event_type"));
            metadata.put("transactionType", row.get("transaction_type"));
            putIfPresent(metadata, "factoryId", row.get("factory_id"));
            putIfPresent(metadata, "vendorId", row.get("vendor_id"));
            putIfPresent(metadata, "simulationRunId", row.get("simulation_run_id"));
            putIfPresent(metadata, "settlementCycleId", row.get("settlement_cycle_id"));
            putIfPresent(metadata, "correlationId", row.get("correlation_id"));
            putIfPresent(metadata, "causationId", row.get("causation_id"));
            metadata.put("retryAttempt", failureCount + 1);
            try {
                archiveOs.requestApproval(
                        approvalRequestId,
                        transactionId,
                        (BigDecimal) row.get("amount"),
                        String.valueOf(row.get("currency")),
                        String.valueOf(row.get("reason")),
                        metadata
                );
                audit(transactionId, TARGET_LEDGER, "ARCHIVEOS_APPROVAL_RETRY_SENT", "approval_request", approvalRequestId,
                        "REQUESTED", "REQUESTED", Map.of("retryAttempt", failureCount + 1));
                delivered++;
            } catch (RuntimeException error) {
                audit(transactionId, TARGET_LEDGER, "ARCHIVEOS_APPROVAL_DEGRADED", "approval_request", approvalRequestId,
                        "REQUESTED", "REQUESTED", Map.of("retryAttempt", failureCount + 1, "error", value(error.getMessage(), error.getClass().getSimpleName())));
            }
        }
        return delivered;
    }

    @Transactional
    public SettlementBatchView runSettlement(LocalDate date) {
        return runSettlement(date, Integer.MAX_VALUE);
    }

    private SettlementBatchView runSettlement(LocalDate date, int maxTransactions) {
        Instant started = Instant.now();
        String batchId = "SET-" + date.toString().replace("-", "") + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        int safeLimit = maxTransactions == Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : Math.max(0, Math.min(maxTransactions, 1000));
        jdbc.update("insert into settlement_batch(batch_id,settlement_date,status,total_transaction_count,total_amount,started_at) values(?,?,?,?,?,?)",
                batchId, Date.valueOf(date), "RUNNING", 0, BigDecimal.ZERO, ts(started));
        audit(batchId, TARGET_LEDGER, "SETTLEMENT_STARTED", "settlement_batch", batchId,
                null, "RUNNING", Map.of("settlementDate", date.toString(), "maxTransactions", safeLimit));

        try {
            List<TransactionView> candidates = transactionsByStatus("SETTLEMENT_READY").stream()
                    .filter(tx -> LocalDate.ofInstant(tx.occurredAt(), ZoneId.systemDefault()).equals(date))
                    .limit(safeLimit)
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

    public DailyBatchRunView runDailyBatch(LocalDate date, String approvedBy, String triggerType,
                                           boolean settlementEnabled, boolean reconciliationEnabled) {
        if (!settlementEnabled && !reconciliationEnabled) {
            throw new IllegalArgumentException("At least one of settlement or reconciliation must be enabled.");
        }

        Instant started = Instant.now();
        String runId = "DBR-" + date.toString().replace("-", "") + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String actor = value(approvedBy, "Archive-Ledger-Operator");
        String trigger = value(triggerType, "MANUAL").toUpperCase(Locale.ROOT);
        jdbc.update("""
                insert into daily_batch_run(
                    run_id,batch_date,status,approved_by,trigger_type,settlement_enabled,reconciliation_enabled,
                    settlement_transaction_count,settlement_amount,mismatch_count,started_at
                ) values(?,?,?,?,?,?,?,?,?,?,?)
                """,
                runId,
                Date.valueOf(date),
                "RUNNING",
                actor,
                trigger,
                settlementEnabled,
                reconciliationEnabled,
                0,
                BigDecimal.ZERO,
                0,
                ts(started)
        );

        try {
            SettlementBatchView settlement = null;
            if (settlementEnabled && hasSettlementReadyTransactions(date)) {
                settlement = runSettlement(date);
            }

            ReconciliationView reconciliation = null;
            if (reconciliationEnabled) {
                reconciliation = reconcile(date);
            }

            int transactionCount = settlement == null ? 0 : settlement.totalTransactionCount();
            BigDecimal amount = settlement == null ? BigDecimal.ZERO : settlement.totalAmount();
            int mismatch = reconciliation == null ? 0 : reconciliation.mismatch();
            String reconciliationStatus = reconciliation == null ? null : reconciliation.status();
            String status = mismatch > 0 ? "WARNING" : "SUCCESS";
            jdbc.update("""
                    update daily_batch_run
                    set status=?, settlement_batch_id=?, reconciliation_status=?, settlement_transaction_count=?,
                        settlement_amount=?, mismatch_count=?, completed_at=?
                    where run_id=?
                    """,
                    status,
                    settlement == null ? null : settlement.batchId(),
                    reconciliationStatus,
                    transactionCount,
                    amount,
                    mismatch,
                    ts(Instant.now()),
                    runId
            );
            audit(runId, actor, "DAILY_BATCH_COMPLETED", "daily_batch_run", runId,
                    "RUNNING", status,
                    Map.of(
                            "date", date.toString(),
                            "triggerType", trigger,
                            "settlementBatchId", settlement == null ? "" : settlement.batchId(),
                            "settlementTransactionCount", transactionCount,
                            "reconciliationStatus", reconciliationStatus == null ? "" : reconciliationStatus,
                            "mismatch", mismatch
                    ));
            return dailyBatch(runId).orElseThrow();
        } catch (RuntimeException error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            jdbc.update("update daily_batch_run set status='FAILED', failure_reason=?, completed_at=? where run_id=?",
                    message, ts(Instant.now()), runId);
            audit(runId, actor, "DAILY_BATCH_FAILED", "daily_batch_run", runId,
                    "RUNNING", "FAILED", Map.of("date", date.toString(), "error", message));
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
        int nexusEvents = count("select count(*) from received_event where cast(received_at as date)=? and coalesce(source_service, source)=?",
                day, SOURCE_NEXUS);
        int logisticsEvents = count("select count(*) from received_event where cast(received_at as date)=? and coalesce(source_service, source) in (?,?)",
                day, SOURCE_LOGITICS, SOURCE_LOGISTICS);
        int marketEvents = count("select count(*) from received_event where cast(received_at as date)=? and coalesce(source_service, source)=?",
                day, SOURCE_MARKET);
        int created = count("select count(*) from finance_transaction where cast(created_at as date)=?", day);
        int directTransactions = count("select count(*) from finance_transaction where cast(created_at as date)=? and coalesce(source_service, 'Archive-Unknown')=?",
                day, SOURCE_NEXUS);
        int directEvents = count("select count(*) from received_event where cast(received_at as date)=? and coalesce(source_service, source)=?",
                day, SOURCE_NEXUS);
        int logisticsTransactions = count("select count(*) from finance_transaction where cast(created_at as date)=? and coalesce(source_service, 'Archive-Unknown') in (?,?)",
                day, SOURCE_LOGITICS, SOURCE_LOGISTICS);
        int marketTransactions = count("select count(*) from finance_transaction where cast(created_at as date)=? and coalesce(source_service, 'Archive-Unknown')=?",
                day, SOURCE_MARKET);
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
                    status,created_at,logistics_event_count,direct_event_count,logistics_transaction_count,direct_transaction_count,
                    market_event_count,market_transaction_count
                ) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                day,
                nexusEvents,
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
                directTransactions,
                marketEvents,
                marketTransactions
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

    public List<RuntimeEventView> recentRuntimeEvents(int limit) {
        return recentRuntimeEvents(limit, null);
    }

    public List<RuntimeEventView> recentRuntimeEvents(int limit, String after) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return runtimeEventProjection(safeLimit, null, null, after);
    }

    public List<RuntimeEventView> runtimeEventsByCorrelation(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return List.of();
        }
        return runtimeEventProjection(500, correlationId, null, null);
    }

    public List<RuntimeEventView> runtimeEventsByEntity(String entityId) {
        if (entityId == null || entityId.isBlank()) {
            return List.of();
        }
        return runtimeEventProjection(500, null, entityId, null);
    }

    private List<RuntimeEventView> runtimeEventProjection(int limit, String correlationId, String entityId, String after) {
        List<RuntimeEventView> events = new ArrayList<>();
        events.addAll(runtimeReceivedEvents(limit, correlationId, entityId));
        events.addAll(runtimeTransactionEvents(limit, correlationId, entityId));
        events.addAll(runtimeLedgerEntryEvents(limit, correlationId, entityId));
        events.addAll(runtimeApprovalEvents(limit, correlationId, entityId));
        events.addAll(runtimeSettlementEvents(limit, correlationId, entityId));
        events.addAll(runtimeReconciliationEvents(limit));
        events.addAll(runtimeCallbackEvents(limit, entityId));
        events.addAll(runtimeWorkforceEvents(limit, entityId));
        return events.stream()
                .sorted(Comparator.comparing(RuntimeEventView::occurredAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .map(this::withRuntimeCursor)
                .filter(event -> isAfterCursor(event, after))
                .limit(limit)
                .toList();
    }

    private List<RuntimeEventView> runtimeReceivedEvents(int limit, String correlationId, String entityId) {
        StringBuilder sql = new StringBuilder("""
                select re.*, ft.transaction_id, ft.transaction_type, ft.status as transaction_status,
                       ft.amount, ft.factory_id, ft.vendor_id, ft.route_plan_id, ft.shipment_id,
                       ft.origin_code, ft.destination_code, ft.risk_score
                from received_event re
                left join finance_transaction ft on ft.source_event_id = re.event_id
                where 1=1
                """);
        List<Object> args = new ArrayList<>();
        appendRuntimeFilters(sql, args, correlationId, entityId, "re", "ft");
        sql.append(" order by re.received_at desc limit ?");
        args.add(limit);
        return jdbc.query(sql.toString(), this::runtimeReceivedEventRow, args.toArray());
    }

    private List<RuntimeEventView> runtimeTransactionEvents(int limit, String correlationId, String entityId) {
        StringBuilder sql = new StringBuilder("""
                select ft.*, re.source_service as event_source_service, re.event_type as source_event_type,
                       re.correlation_id, re.causation_id, re.simulation_run_id, re.settlement_cycle_id,
                       re.hop_count, re.max_hop, re.payload
                from finance_transaction ft
                left join received_event re on re.event_id = ft.source_event_id
                where 1=1
                """);
        List<Object> args = new ArrayList<>();
        appendRuntimeFilters(sql, args, correlationId, entityId, "re", "ft");
        sql.append(" order by ft.created_at desc limit ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, row) -> {
            List<RuntimeEventView> projected = new ArrayList<>();
            projected.add(transactionProjection(rs, "TRANSACTION_CREATED", "completed", "normal", "거래 생성 완료"));
            String status = rs.getString("status");
            if ("APPROVAL_REQUIRED".equals(status)) {
                projected.add(transactionProjection(rs, "APPROVAL_REQUIRED", "approval_required", "warning", "승인 대기 거래"));
            } else if ("SETTLEMENT_READY".equals(status)) {
                projected.add(transactionProjection(rs, "SETTLEMENT_READY", "waiting", "info", "정산 대기 거래"));
            }
            return projected;
        }, args.toArray()).stream().flatMap(List::stream).toList();
    }

    private List<RuntimeEventView> runtimeLedgerEntryEvents(int limit, String correlationId, String entityId) {
        StringBuilder sql = new StringBuilder("""
                select ft.transaction_id, ft.source_service, ft.source_event_id, ft.transaction_type, ft.amount,
                       ft.factory_id, ft.vendor_id, re.correlation_id, re.causation_id, re.simulation_run_id,
                       re.settlement_cycle_id, re.hop_count, re.max_hop, re.payload, min(le.created_at) as created_at,
                       count(*) as entry_count
                from ledger_entry le
                join finance_transaction ft on ft.transaction_id = le.transaction_id
                left join received_event re on re.event_id = ft.source_event_id
                where 1=1
                """);
        List<Object> args = new ArrayList<>();
        appendRuntimeFilters(sql, args, correlationId, entityId, "re", "ft");
        sql.append("""
                group by ft.transaction_id, ft.source_service, ft.source_event_id, ft.transaction_type, ft.amount,
                         ft.factory_id, ft.vendor_id, re.correlation_id, re.causation_id, re.simulation_run_id,
                         re.settlement_cycle_id, re.hop_count, re.max_hop, re.payload
                order by min(le.created_at) desc limit ?
                """);
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, row) -> {
            Map<String, Object> metadata = maskedAmountMetadata(rs.getBigDecimal("amount"));
            metadata.put("transactionId", rs.getString("transaction_id"));
            metadata.put("transactionType", rs.getString("transaction_type"));
            metadata.put("entryCount", rs.getInt("entry_count"));
            putIfPresent(metadata, "simulationRunId", rs.getString("simulation_run_id"));
            putIfPresent(metadata, "settlementCycleId", rs.getString("settlement_cycle_id"));
            putIfPresent(metadata, "hopCount", rs.getObject("hop_count"));
            putIfPresent(metadata, "maxHop", rs.getObject("max_hop"));
            copySyntheticPayload(metadata, readPayload(rs.getString("payload")), "workdayId");
            return runtimeView(
                    "rt-ledger-entry-" + rs.getString("transaction_id"),
                    value(rs.getString("source_service"), TARGET_LEDGER),
                    "ledger",
                    "LEDGER_ENTRY_CREATED",
                    "transaction",
                    rs.getString("transaction_id"),
                    rs.getString("correlation_id"),
                    rs.getString("causation_id"),
                    "completed",
                    "normal",
                    "복식 원장 entry " + rs.getInt("entry_count") + "건 생성",
                    instant(rs.getTimestamp("created_at")),
                    metadata
            );
        }, args.toArray());
    }

    private List<RuntimeEventView> runtimeApprovalEvents(int limit, String correlationId, String entityId) {
        StringBuilder sql = new StringBuilder("""
                select ar.*, ft.source_service, ft.source_event_id, ft.transaction_type,
                       re.correlation_id, re.causation_id, re.simulation_run_id, re.settlement_cycle_id,
                       re.hop_count, re.max_hop, re.payload
                from approval_request ar
                join finance_transaction ft on ft.transaction_id = ar.transaction_id
                left join received_event re on re.event_id = ft.source_event_id
                where 1=1
                """);
        List<Object> args = new ArrayList<>();
        appendRuntimeFilters(sql, args, correlationId, entityId, "re", "ft");
        sql.append(" order by coalesce(ar.decided_at, ar.requested_at) desc limit ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, row) -> {
            String approvalStatus = rs.getString("status");
            String eventType = switch (approvalStatus) {
                case "APPROVED" -> "APPROVAL_APPROVED";
                case "REJECTED" -> "APPROVAL_REJECTED";
                default -> "APPROVAL_REQUIRED";
            };
            String status = switch (approvalStatus) {
                case "APPROVED" -> "approved";
                case "REJECTED" -> "rejected";
                default -> "approval_required";
            };
            String severity = "REJECTED".equals(approvalStatus) ? "critical" : ("APPROVED".equals(approvalStatus) ? "normal" : "warning");
            Map<String, Object> metadata = maskedAmountMetadata(rs.getBigDecimal("amount"));
            metadata.put("approvalRequestId", rs.getString("approval_request_id"));
            metadata.put("transactionId", rs.getString("transaction_id"));
            metadata.put("transactionType", rs.getString("transaction_type"));
            putIfPresent(metadata, "simulationRunId", rs.getString("simulation_run_id"));
            putIfPresent(metadata, "settlementCycleId", rs.getString("settlement_cycle_id"));
            putIfPresent(metadata, "hopCount", rs.getObject("hop_count"));
            putIfPresent(metadata, "maxHop", rs.getObject("max_hop"));
            copySyntheticPayload(metadata, readPayload(rs.getString("payload")), "workdayId");
            Instant occurredAt = instant(rs.getTimestamp("decided_at"));
            if (occurredAt == null) {
                occurredAt = instant(rs.getTimestamp("requested_at"));
            }
            return runtimeView(
                    "rt-approval-" + rs.getString("approval_request_id"),
                    value(rs.getString("source_service"), TARGET_LEDGER),
                    "approval",
                    eventType,
                    "approval",
                    rs.getString("approval_request_id"),
                    rs.getString("correlation_id"),
                    rs.getString("causation_id"),
                    status,
                    severity,
                    approvalDisplayLabel(eventType),
                    occurredAt,
                    metadata
            );
        }, args.toArray());
    }

    private List<RuntimeEventView> runtimeSettlementEvents(int limit, String correlationId, String entityId) {
        if (correlationId != null && !correlationId.isBlank()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("select * from settlement_batch where status in ('RUNNING','SUCCESS','FAILED')");
        List<Object> args = new ArrayList<>();
        if (entityId != null && !entityId.isBlank()) {
            sql.append(" and batch_id=?");
            args.add(entityId);
        }
        sql.append(" order by completed_at desc limit ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, row) -> {
            List<RuntimeEventView> projected = new ArrayList<>();
            Map<String, Object> metadata = maskedAmountMetadata(rs.getBigDecimal("total_amount"));
            metadata.put("batchId", rs.getString("batch_id"));
            metadata.put("settlementDate", rs.getDate("settlement_date").toString());
            metadata.put("transactionCount", rs.getInt("total_transaction_count"));
            projected.add(runtimeView(
                    "rt-settlement-started-" + rs.getString("batch_id"),
                    TARGET_LEDGER,
                    "settlement",
                    "SETTLEMENT_STARTED",
                    "settlement_batch",
                    rs.getString("batch_id"),
                    null,
                    null,
                    "processing",
                    "info",
                    "일 정산 시작",
                    instant(rs.getTimestamp("started_at")),
                    metadata
            ));
            if ("SUCCESS".equals(rs.getString("status"))) {
                projected.add(runtimeView(
                        "rt-settlement-completed-" + rs.getString("batch_id"),
                        TARGET_LEDGER,
                        "settlement",
                        "SETTLEMENT_COMPLETED",
                        "settlement_batch",
                        rs.getString("batch_id"),
                        null,
                        null,
                        "settled",
                        "normal",
                        "일 정산 완료 " + rs.getInt("total_transaction_count") + "건",
                        instant(rs.getTimestamp("completed_at")),
                        metadata
                ));
            }
            return projected;
        }, args.toArray()).stream().flatMap(List::stream).toList();
    }

    private List<RuntimeEventView> runtimeReconciliationEvents(int limit) {
        return jdbc.query("""
                select * from reconciliation_result order by created_at desc limit ?
                """, (rs, row) -> runtimeView(
                "rt-reconciliation-" + rs.getDate("reconciliation_date") + "-" + rs.getLong("id"),
                TARGET_LEDGER,
                "reconciliation",
                rs.getInt("mismatch_count") == 0 ? "RECONCILIATION_OK" : "RECONCILIATION_WARNING",
                "reconciliation_result",
                rs.getDate("reconciliation_date").toString(),
                null,
                null,
                rs.getInt("mismatch_count") == 0 ? "completed" : "delayed",
                rs.getInt("mismatch_count") == 0 ? "normal" : "warning",
                rs.getInt("mismatch_count") == 0 ? "대사 정상" : "대사 경고 " + rs.getInt("mismatch_count") + "건",
                instant(rs.getTimestamp("created_at")),
                Map.of("mismatch", rs.getInt("mismatch_count"), "status", rs.getString("status"))
        ), limit);
    }

    private List<RuntimeEventView> runtimeCallbackEvents(int limit, String entityId) {
        StringBuilder sql = new StringBuilder("""
                select * from audit_log
                where action in ('ARCHIVEOS_APPROVAL_REQUESTED','ARCHIVEOS_APPROVAL_DEGRADED','APPROVAL_CALLBACK')
                """);
        List<Object> args = new ArrayList<>();
        if (entityId != null && !entityId.isBlank()) {
            sql.append(" and (target_id=? or trace_id=?)");
            args.add(entityId);
            args.add(entityId);
        }
        sql.append(" order by created_at desc limit ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, row) -> {
            String action = rs.getString("action");
            String eventType = "ARCHIVEOS_APPROVAL_DEGRADED".equals(action) ? "CALLBACK_FAILED" : "CALLBACK_SENT";
            if ("APPROVAL_CALLBACK".equals(action)) {
                eventType = "APPROVED".equals(rs.getString("after_status")) || "SETTLEMENT_READY".equals(rs.getString("after_status"))
                        ? "APPROVAL_APPROVED" : "APPROVAL_REJECTED";
            }
            String status = "CALLBACK_FAILED".equals(eventType) ? "failed" : "completed";
            return runtimeView(
                    "rt-callback-" + rs.getLong("id"),
                    TARGET_LEDGER,
                    "approval",
                    eventType,
                    rs.getString("target_type"),
                    rs.getString("target_id"),
                    null,
                    null,
                    status,
                    "failed".equals(status) ? "warning" : "normal",
                    "CALLBACK_FAILED".equals(eventType) ? "ArchiveOS callback 실패" : "ArchiveOS callback 전송",
                    instant(rs.getTimestamp("created_at")),
                    Map.of("action", action, "beforeStatus", value(rs.getString("before_status"), ""), "afterStatus", value(rs.getString("after_status"), ""))
            );
        }, args.toArray());
    }

    private List<RuntimeEventView> runtimeWorkforceEvents(int limit, String entityId) {
        List<RuntimeEventView> events = new ArrayList<>();
        String allocationSql = "select * from ledger_workforce_allocation where 1=1";
        List<Object> allocationArgs = new ArrayList<>();
        if (entityId != null && !entityId.isBlank()) {
            allocationSql += " and (allocation_id=? or workday_id=?)";
            allocationArgs.add(entityId);
            allocationArgs.add(entityId);
        }
        allocationSql += " order by created_at desc limit ?";
        allocationArgs.add(limit);
        events.addAll(jdbc.query(allocationSql, (rs, row) -> runtimeView(
                "rt-workforce-allocation-" + rs.getString("allocation_id"),
                rs.getString("source_service"),
                "workforce",
                "WORKFORCE_ALLOCATION_ASSIGNED",
                "workforce_allocation",
                rs.getString("allocation_id"),
                null,
                null,
                "completed",
                "normal",
                "정산 인력 배정 " + rs.getString("role_type") + " " + rs.getInt("allocated_headcount") + "명",
                instant(rs.getTimestamp("created_at")),
                Map.of("workdayId", rs.getString("workday_id"), "roleType", rs.getString("role_type"), "effectiveCapacity", rs.getInt("effective_capacity"))
        ), allocationArgs.toArray()));

        String workdaySql = "select * from ledger_workday_result where 1=1";
        List<Object> workdayArgs = new ArrayList<>();
        if (entityId != null && !entityId.isBlank()) {
            workdaySql += " and workday_id=?";
            workdayArgs.add(entityId);
        }
        workdaySql += " order by created_at desc limit ?";
        workdayArgs.add(limit);
        events.addAll(jdbc.query(workdaySql, (rs, row) -> {
            List<RuntimeEventView> projected = new ArrayList<>();
            String workdayId = rs.getString("workday_id");
            projected.add(runtimeView(
                    "rt-workday-" + workdayId,
                    TARGET_LEDGER,
                    "workforce",
                    "WORKDAY_COMPLETED",
                    "workday",
                    workdayId,
                    null,
                    null,
                    rs.getString("bottleneck_role") == null ? "completed" : "delayed",
                    rs.getString("bottleneck_role") == null ? "normal" : "warning",
                    "Ledger workday 완료",
                    instant(rs.getTimestamp("created_at")),
                    Map.of("processed", rs.getInt("transactions_processed"), "backlog", rs.getInt("transactions_backlog"), "bottleneckRole", value(rs.getString("bottleneck_role"), ""))
            ));
            if (rs.getInt("approval_backlog_count") > 0) {
                projected.add(runtimeView("rt-approval-backlog-" + workdayId, TARGET_LEDGER, "workforce", "APPROVAL_BACKLOG_INCREASED",
                        "workday", workdayId, null, null, "delayed", "warning",
                        "승인 대기 거래 " + rs.getInt("approval_backlog_count") + "건",
                        instant(rs.getTimestamp("created_at")), Map.of("approvalBacklog", rs.getInt("approval_backlog_count"))));
            }
            if (rs.getInt("settlement_backlog_count") > 0) {
                projected.add(runtimeView("rt-settlement-delay-" + workdayId, TARGET_LEDGER, "workforce", "SETTLEMENT_DELAYED",
                        "workday", workdayId, null, null, "delayed", "warning",
                        "정산 지연 " + rs.getInt("settlement_backlog_count") + "건",
                        instant(rs.getTimestamp("created_at")), Map.of("settlementBacklog", rs.getInt("settlement_backlog_count"))));
            }
            return projected;
        }, workdayArgs.toArray()).stream().flatMap(List::stream).toList());
        return events;
    }

    public List<SettlementBatchView> settlements() {
        return jdbc.query("select * from settlement_batch order by started_at desc limit 200", this::settlementRow);
    }

    public List<DailyBatchRunView> dailyBatches() {
        return jdbc.query("select * from daily_batch_run order by started_at desc limit 200", this::dailyBatchRunRow);
    }

    public Optional<DailyBatchRunView> dailyBatch(String runId) {
        List<DailyBatchRunView> rows = jdbc.query("select * from daily_batch_run where run_id=?", this::dailyBatchRunRow, runId);
        return rows.stream().findFirst();
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
        long eventsFromMarket = count("select count(*) from received_event where coalesce(source_service, source)=?",
                SOURCE_MARKET);
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
        long marketRevenueTransactions = count("select count(*) from finance_transaction where transaction_type='SALES_REVENUE' and coalesce(source_service, 'Archive-Unknown')=?",
                SOURCE_MARKET);
        long paymentCaptureTransactions = count("select count(*) from finance_transaction where transaction_type='PAYMENT_CAPTURE' and coalesce(source_service, 'Archive-Unknown')=?",
                SOURCE_MARKET);
        long refundTransactions = count("select count(*) from finance_transaction where transaction_type='SALES_REFUND' and coalesce(source_service, 'Archive-Unknown')=?",
                SOURCE_MARKET);
        long claimCompensationTransactions = count("select count(*) from finance_transaction where transaction_type='CLAIM_COMPENSATION_EXPENSE' and coalesce(source_service, 'Archive-Unknown')=?",
                SOURCE_MARKET);
        Instant latestEventAt = jdbc.query("select max(received_at) from received_event",
                rs -> rs.next() ? instant(rs.getTimestamp(1)) : null);
        long settlementReady = count("select count(*) from finance_transaction where status='SETTLEMENT_READY'");
        long settlementCompleted = count("select count(*) from settlement_batch where status='SUCCESS'");
        long reconciliationWarnings = count("select count(*) from reconciliation_result where mismatch_count > 0 or status='WARNING'");
        long callbackFailed = count("select count(*) from audit_log where action='ARCHIVEOS_APPROVAL_DEGRADED'");
        String status = failed > 0 ? "DEGRADED" : "HEALTHY";
        WorkforceSummary workforce = workforceSummary(LocalDate.now(), "ArchiveOS");
        SettlementAgencySummary agency = settlementAgencySummary();
        SettlementBalanceSummary balance = agency.balance();
        RuntimeOutboxSummary outbox = new RuntimeOutboxSummary(0, 0, 0, 0);
        RuntimeEconomySummary economy = new RuntimeEconomySummary(
                balance.transactionProcessingRevenue()
                        .add(balance.settlementAgencyRevenue())
                        .add(balance.reconciliationRevenue())
                        .add(balance.approvalReviewRevenue()),
                balance.operatingCost(),
                balance.operatingProfit()
        );
        RuntimeWorkforceSummary runtimeWorkforce = new RuntimeWorkforceSummary(
                workforce.assignedUnits(),
                workforce.allocatedCapacity(),
                Math.max(0, workforce.demandCount() - workforce.backlogCount()),
                workforce.backlogCount()
        );
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
                eventsFromMarket,
                logisticsReceived,
                logisticsCostTransactions,
                urgentDeliveryTransactions,
                delayPenaltyTransactions,
                routeDeviationTransactions,
                coldChainRiskTransactions,
                marketRevenueTransactions,
                paymentCaptureTransactions,
                refundTransactions,
                claimCompensationTransactions,
                workforce,
                TARGET_LEDGER,
                "Synthetic financial ledger, settlement, reconciliation, approval callback, and workforce capacity service",
                latestEventAt,
                outbox,
                economy,
                runtimeWorkforce,
                failed > 0 ? "FAILED_EVENTS_PRESENT" : null,
                true,
                received,
                transactionCount,
                workforce.approvalBacklog(),
                settlementReady,
                settlementCompleted,
                reconciliationWarnings,
                callbackFailed,
                runtimeStatus(),
                balance
        );
    }

    private Normalized normalize(NexusEventRequest request, String source) {
        if (isLogisticsRequest(request, source)) {
            return normalizeLogistics(request, source);
        }
        if (isMarketRequest(request, source)) {
            return normalizeMarket(request, source);
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

    private boolean isMarketRequest(NexusEventRequest request, String source) {
        if (!SOURCE_MARKET.equals(source)) {
            return false;
        }
        return "SALES_REVENUE_CONFIRMED".equals(request.eventType())
                || "PAYMENT_CAPTURED".equals(request.eventType())
                || "REFUND_REQUESTED".equals(request.eventType())
                || "CLAIM_COMPENSATION_CONFIRMED".equals(request.eventType())
                || "MARKET_SERVICE_FEE_PAID".equals(request.eventType())
                || "PAYMENT_PROCESSING_FEE_PAID".equals(request.eventType());
    }

    private boolean isMarketSource(String source) {
        return SOURCE_MARKET.equals(source);
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

    private Normalized normalizeMarket(NexusEventRequest request, String source) {
        Map<String, Object> payload = request.payload();
        if (payload == null) {
            throw new IllegalArgumentException("Payload is required.");
        }

        String transactionType = switch (request.eventType()) {
            case "SALES_REVENUE_CONFIRMED" -> "SALES_REVENUE";
            case "PAYMENT_CAPTURED" -> "PAYMENT_CAPTURE";
            case "REFUND_REQUESTED" -> "SALES_REFUND";
            case "CLAIM_COMPENSATION_CONFIRMED" -> "CLAIM_COMPENSATION_EXPENSE";
            case "MARKET_SERVICE_FEE_PAID" -> "MARKET_SERVICE_FEE";
            case "PAYMENT_PROCESSING_FEE_PAID" -> "PAYMENT_PROCESSING_FEE";
            default -> throw new IllegalArgumentException("Unsupported market event_type: " + request.eventType());
        };

        BigDecimal amount = decimalOrNull(payload.get("amount"));
        if (amount == null) {
            throw new IllegalArgumentException("amount is required for market events.");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be greater than 0.");
        }

        BigDecimal riskScore = decimalOrNull(payload.get("riskScore"), BigDecimal.ZERO);
        boolean requiresApproval = bool(payload.get("requiresApproval"), false);
        boolean highRiskCustomer = bool(payload.get("highRiskCustomer"), false);
        int riskLevel = parseInt(payload.get("riskLevel"), 0);
        boolean criticalAmount = "CLAIM_COMPENSATION_CONFIRMED".equals(request.eventType()) && amount.compareTo(MARKET_APPROVAL_THRESHOLD) >= 0;
        boolean isClaimLarge = "CLAIM_COMPENSATION_CONFIRMED".equals(request.eventType()) && amount.compareTo(MARKET_APPROVAL_THRESHOLD) >= 0;
        boolean approvalRequired = requiresApproval
                || amount.compareTo(MARKET_APPROVAL_THRESHOLD) >= 0
                || riskScore.compareTo(LOGISTICS_LOW_RISK_SCORE) >= 0
                || highRiskCustomer
                || riskLevel >= 4
                || "REFUND_REQUESTED".equals(request.eventType()) && amount.compareTo(MARKET_APPROVAL_THRESHOLD) >= 0
                || isClaimLarge;

        String reason = string(payload, "reason", "Synthetic market event confirmed");
        String approvalReason = buildMarketApprovalReason(approvalRequired, request.eventType(), amount, riskScore, highRiskCustomer, riskLevel, criticalAmount);

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
                "NORMAL",
                false,
                amount.setScale(2, RoundingMode.HALF_UP),
                string(payload, "currency", "KRW")
        );
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

    private String buildMarketApprovalReason(boolean approvalRequired, String eventType, BigDecimal amount, BigDecimal riskScore,
                                            boolean highRiskCustomer, int riskLevel, boolean criticalClaim) {
        if (!approvalRequired) {
            return "Market event accepted.";
        }
        List<String> reasons = new ArrayList<>();
        if (amount.compareTo(MARKET_APPROVAL_THRESHOLD) >= 0) reasons.add("amount>=300000");
        if (riskScore.compareTo(LOGISTICS_LOW_RISK_SCORE) >= 0) reasons.add("riskScore>=0.85");
        if (highRiskCustomer) reasons.add("highRiskCustomer=true");
        if (riskLevel >= 4) reasons.add("riskLevel>=4");
        if ("REFUND_REQUESTED".equals(eventType) && amount.compareTo(MARKET_APPROVAL_THRESHOLD) >= 0) reasons.add("refundAmountHigh");
        if ("CLAIM_COMPENSATION_CONFIRMED".equals(eventType) && amount.compareTo(MARKET_APPROVAL_THRESHOLD) >= 0) reasons.add("claimAmountHigh");
        if (criticalClaim) reasons.add("claimCompensationHigh");
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
            case "SALES_REVENUE" -> new Account("ACCOUNTS_RECEIVABLE", "Synthetic Accounts Receivable");
            case "PAYMENT_CAPTURE" -> new Account("CASH", "Synthetic Cash");
            case "SALES_REFUND" -> new Account("SALES_REFUND", "Synthetic Sales Refund");
            case "CLAIM_COMPENSATION_EXPENSE" -> new Account("CLAIM_COMPENSATION_EXPENSE", "Synthetic Claim Compensation Expense");
            case "MARKET_SERVICE_FEE" -> new Account("MARKET_SERVICE_FEE_EXPENSE", "Synthetic Market Service Fee Expense");
            case "PAYMENT_PROCESSING_FEE" -> new Account("PAYMENT_PROCESSING_EXPENSE", "Synthetic Payment Processing Expense");
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
            case "SALES_REVENUE" -> new Account("SALES_REVENUE", "Synthetic Sales Revenue");
            case "PAYMENT_CAPTURE" -> new Account("ACCOUNTS_RECEIVABLE", "Synthetic Accounts Receivable");
            case "SALES_REFUND" -> new Account("REFUND_PAYABLE", "Synthetic Refund Payable");
            case "CLAIM_COMPENSATION_EXPENSE" -> new Account("ACCOUNTS_PAYABLE", "Synthetic Accounts Payable");
            case "MARKET_SERVICE_FEE" -> new Account("ACCOUNTS_PAYABLE", "Synthetic Accounts Payable");
            case "PAYMENT_PROCESSING_FEE" -> new Account("CASH", "Synthetic Cash");
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

    private RuntimeEventView runtimeReceivedEventRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        String sourceService = value(rs.getString("source_service"), rs.getString("source"));
        String eventType = runtimeReceivedEventType(sourceService);
        String transactionStatus = rs.getString("transaction_status");
        String processingStatus = rs.getString("processing_status");
        Map<String, Object> payload = readPayload(rs.getString("payload"));
        Map<String, Object> metadata = new HashMap<>();
        putIfPresent(metadata, "transactionId", rs.getString("transaction_id"));
        putIfPresent(metadata, "transactionType", rs.getString("transaction_type"));
        metadata.putAll(maskedAmountMetadata(rs.getBigDecimal("amount")));
        putIfPresent(metadata, "factoryId", rs.getString("factory_id"));
        putIfPresent(metadata, "vendorId", rs.getString("vendor_id"));
        putIfPresent(metadata, "routePlanId", rs.getString("route_plan_id"));
        putIfPresent(metadata, "shipmentId", rs.getString("shipment_id"));
        putIfPresent(metadata, "originCode", rs.getString("origin_code"));
        putIfPresent(metadata, "destinationCode", rs.getString("destination_code"));
        putIfPresent(metadata, "riskScore", rs.getBigDecimal("risk_score"));
        putIfPresent(metadata, "simulationRunId", rs.getString("simulation_run_id"));
        putIfPresent(metadata, "settlementCycleId", rs.getString("settlement_cycle_id"));
        putIfPresent(metadata, "hopCount", rs.getInt("hop_count"));
        putIfPresent(metadata, "maxHop", rs.getObject("max_hop"));
        copySyntheticPayload(metadata, payload, "orderId", "paymentId", "returnId", "claimId", "customerType", "priority");

        String entityType = runtimeEntityType(sourceService, payload, rs.getString("route_plan_id"), rs.getString("shipment_id"));
        String entityId = firstText(
                text(payload.get("orderId")),
                text(payload.get("paymentId")),
                text(payload.get("claimId")),
                text(payload.get("returnId")),
                rs.getString("route_plan_id"),
                rs.getString("shipment_id"),
                rs.getString("transaction_id"),
                rs.getString("event_id")
        );
        String status = runtimeStatus(processingStatus, transactionStatus);
        String severity = runtimeSeverity(status, eventType, rs.getBigDecimal("risk_score"));
        return runtimeView(
                rs.getString("event_id"),
                rs.getString("idempotency_key"),
                sourceService,
                TARGET_LEDGER,
                runtimeDomain(sourceService),
                eventType,
                entityType,
                entityId,
                rs.getString("correlation_id"),
                rs.getString("causation_id"),
                rs.getString("simulation_run_id"),
                rs.getString("settlement_cycle_id"),
                text(payload.get("workdayId")),
                status,
                severity,
                runtimeDisplayLabel(sourceService, eventType, status),
                instant(rs.getTimestamp("received_at")),
                rs.getInt("hop_count"),
                rs.getObject("max_hop") == null ? 5 : rs.getInt("max_hop"),
                metadata
        );
    }

    private void appendRuntimeFilters(StringBuilder sql, List<Object> args, String correlationId, String entityId,
                                      String receivedAlias, String transactionAlias) {
        if (correlationId != null && !correlationId.isBlank()) {
            sql.append(" and ").append(receivedAlias).append(".correlation_id=?");
            args.add(correlationId);
        }
        if (entityId != null && !entityId.isBlank()) {
            sql.append(" and (")
                    .append(receivedAlias).append(".event_id=? or ")
                    .append(transactionAlias).append(".transaction_id=? or ")
                    .append(transactionAlias).append(".route_plan_id=? or ")
                    .append(transactionAlias).append(".shipment_id=? or ")
                    .append(transactionAlias).append(".factory_id=? or ")
                    .append(transactionAlias).append(".vendor_id=? or ")
                    .append(receivedAlias).append(".payload like ?)");
            args.add(entityId);
            args.add(entityId);
            args.add(entityId);
            args.add(entityId);
            args.add(entityId);
            args.add(entityId);
            args.add("%" + entityId + "%");
        }
    }

    private RuntimeEventView transactionProjection(java.sql.ResultSet rs, String eventType, String status,
                                                   String severity, String labelPrefix) throws java.sql.SQLException {
        Map<String, Object> payload = readPayload(rs.getString("payload"));
        Map<String, Object> metadata = maskedAmountMetadata(rs.getBigDecimal("amount"));
        metadata.put("transactionId", rs.getString("transaction_id"));
        metadata.put("transactionType", rs.getString("transaction_type"));
        putIfPresent(metadata, "factoryId", rs.getString("factory_id"));
        putIfPresent(metadata, "vendorId", rs.getString("vendor_id"));
        putIfPresent(metadata, "simulationRunId", rs.getString("simulation_run_id"));
        putIfPresent(metadata, "settlementCycleId", rs.getString("settlement_cycle_id"));
        putIfPresent(metadata, "hopCount", rs.getObject("hop_count"));
        putIfPresent(metadata, "maxHop", rs.getObject("max_hop"));
        copySyntheticPayload(metadata, payload, "workdayId", "orderId", "paymentId", "returnId", "claimId", "customerType", "priority");
        String sourceService = value(rs.getString("source_service"), rs.getString("event_source_service"));
        return runtimeView(
                "rt-transaction-" + eventType + "-" + rs.getString("transaction_id"),
                sourceService,
                "ledger",
                eventType,
                "transaction",
                rs.getString("transaction_id"),
                rs.getString("correlation_id"),
                rs.getString("causation_id"),
                status,
                severity,
                transactionDisplayLabel(eventType, labelPrefix),
                instant(rs.getTimestamp("created_at")),
                metadata
        );
    }

    private RuntimeEventView runtimeView(String eventId, String sourceService, String domain, String eventType,
                                         String entityType, String entityId, String correlationId, String causationId,
                                         String status, String severity, String displayLabel, Instant occurredAt,
                                         Map<String, Object> metadata) {
        return runtimeView(
                eventId,
                "RUNTIME:" + eventId,
                sourceService,
                TARGET_LEDGER.equals(sourceService) ? "ArchiveOS" : TARGET_LEDGER,
                domain,
                eventType,
                entityType,
                entityId,
                correlationId,
                causationId,
                text(metadata.get("simulationRunId")),
                text(metadata.get("settlementCycleId")),
                text(metadata.get("workdayId")),
                status,
                severity,
                displayLabel,
                occurredAt,
                integer(metadata.get("hopCount"), 0),
                integer(metadata.get("maxHop"), 5),
                metadata
        );
    }

    private RuntimeEventView runtimeView(String eventId, String idempotencyKey, String sourceService,
                                         String targetService, String domain, String eventType, String entityType,
                                         String entityId, String correlationId, String causationId,
                                         String simulationRunId, String settlementCycleId, String workdayId,
                                         String status, String severity, String displayLabel, Instant occurredAt,
                                         int hopCount, int maxHop, Map<String, Object> metadata) {
        return new RuntimeEventView(
                eventId,
                idempotencyKey,
                sourceService,
                targetService,
                domain,
                eventType,
                entityType,
                entityId,
                correlationId,
                causationId,
                simulationRunId,
                settlementCycleId,
                workdayId,
                runtimeStatusCode(status),
                runtimeSeverityCode(severity),
                displayLabel,
                occurredAt,
                Math.max(0, hopCount),
                Math.max(1, maxHop),
                null,
                metadata
        );
    }

    private String runtimeStatusCode(String status) {
        return switch (value(status, "unavailable").toLowerCase(Locale.ROOT)) {
            case "created" -> "CREATED";
            case "processing" -> "PROCESSING";
            case "waiting", "approval_required" -> "WAITING";
            case "completed", "approved" -> "COMPLETED";
            case "delayed" -> "DELAYED";
            case "settled" -> "SETTLED";
            case "failed", "rejected" -> "FAILED";
            default -> "FAILED";
        };
    }

    private String runtimeSeverityCode(String severity) {
        return switch (value(severity, "warning").toLowerCase(Locale.ROOT)) {
            case "normal" -> "NORMAL";
            case "info" -> "INFO";
            case "warning" -> "WARNING";
            case "critical" -> "CRITICAL";
            default -> "WARNING";
        };
    }

    private RuntimeEventView withRuntimeCursor(RuntimeEventView event) {
        String cursor = runtimeCursor(event.occurredAt(), event.eventId());
        return new RuntimeEventView(
                event.eventId(),
                event.idempotencyKey(),
                event.sourceService(),
                event.targetService(),
                event.domain(),
                event.eventType(),
                event.entityType(),
                event.entityId(),
                event.correlationId(),
                event.causationId(),
                event.simulationRunId(),
                event.settlementCycleId(),
                event.workdayId(),
                event.status(),
                event.severity(),
                event.displayLabel(),
                event.occurredAt(),
                event.hopCount(),
                event.maxHop(),
                cursor,
                event.metadata()
        );
    }

    private String runtimeCursor(Instant occurredAt, String eventId) {
        long epochMillis = occurredAt == null ? 0 : occurredAt.toEpochMilli();
        String encodedEventId = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value(eventId, "runtime-event").getBytes(StandardCharsets.UTF_8));
        return epochMillis + "." + encodedEventId;
    }

    private boolean isAfterCursor(RuntimeEventView event, String after) {
        if (after == null || after.isBlank()) {
            return true;
        }
        int separator = after.indexOf('.');
        if (separator < 1 || separator == after.length() - 1) {
            return false;
        }
        try {
            long afterMillis = Long.parseLong(after.substring(0, separator));
            String afterEventId = new String(Base64.getUrlDecoder().decode(after.substring(separator + 1)), StandardCharsets.UTF_8);
            long eventMillis = event.occurredAt() == null ? 0 : event.occurredAt().toEpochMilli();
            return eventMillis > afterMillis
                    || (eventMillis == afterMillis && value(event.eventId(), "").compareTo(afterEventId) > 0);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                // Runtime metadata may be absent or a non-numeric compatibility value.
            }
        }
        return fallback;
    }

    private String runtimeReceivedEventType(String sourceService) {
        if (SOURCE_MARKET.equals(sourceService)) {
            return "MARKET_REVENUE_RECEIVED";
        }
        if (isLogisticsSource(sourceService)) {
            return "LOGISTICS_COST_RECEIVED";
        }
        if (SOURCE_NEXUS.equals(sourceService)) {
            return "NEXUS_COST_RECEIVED";
        }
        return "TRANSACTION_CREATED";
    }

    private Map<String, Object> maskedAmountMetadata(BigDecimal amount) {
        Map<String, Object> metadata = new HashMap<>();
        if (amount == null) {
            return metadata;
        }
        metadata.put("amountBucket", amountBucket(amount));
        metadata.put("syntheticKrwRange", syntheticKrwRange(amount));
        return metadata;
    }

    private String amountBucket(BigDecimal amount) {
        if (amount.compareTo(new BigDecimal("100000")) < 0) {
            return "UNDER_100K";
        }
        if (amount.compareTo(new BigDecimal("300000")) < 0) {
            return "100K_TO_300K";
        }
        if (amount.compareTo(new BigDecimal("1000000")) < 0) {
            return "300K_TO_1M";
        }
        return "OVER_1M";
    }

    private String syntheticKrwRange(BigDecimal amount) {
        if (amount.compareTo(new BigDecimal("100000")) < 0) {
            return "0~100,000 KRW";
        }
        if (amount.compareTo(new BigDecimal("300000")) < 0) {
            return "100,000~300,000 KRW";
        }
        if (amount.compareTo(new BigDecimal("1000000")) < 0) {
            return "300,000~1,000,000 KRW";
        }
        return "1,000,000 KRW+";
    }

    private String transactionDisplayLabel(String eventType, String labelPrefix) {
        if ("APPROVAL_REQUIRED".equals(eventType)) {
            return "승인 대기 거래 1건";
        }
        if ("SETTLEMENT_READY".equals(eventType)) {
            return "정산 대기 거래 1건";
        }
        return labelPrefix + " 1건";
    }

    private String approvalDisplayLabel(String eventType) {
        return switch (eventType) {
            case "APPROVAL_APPROVED" -> "승인 완료 거래 1건";
            case "APPROVAL_REJECTED" -> "승인 반려 거래 1건";
            default -> "승인 대기 거래 1건";
        };
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

    private DailyBatchRunView dailyBatchRunRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new DailyBatchRunView(
                rs.getString("run_id"),
                rs.getDate("batch_date").toLocalDate(),
                rs.getString("status"),
                rs.getString("approved_by"),
                rs.getString("trigger_type"),
                rs.getBoolean("settlement_enabled"),
                rs.getBoolean("reconciliation_enabled"),
                rs.getString("settlement_batch_id"),
                rs.getString("reconciliation_status"),
                rs.getInt("settlement_transaction_count"),
                rs.getBigDecimal("settlement_amount"),
                rs.getInt("mismatch_count"),
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
                rs.getInt("market_event_count"),
                rs.getInt("market_transaction_count"),
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

    private Optional<WorkforceAllocationView> workforceAllocation(String allocationId) {
        List<WorkforceAllocationView> rows = jdbc.query(
                "select * from ledger_workforce_allocation where allocation_id=?",
                this::workforceAllocationRow,
                allocationId
        );
        return rows.stream().findFirst();
    }

    private WorkforceAllocationView workforceAllocationRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new WorkforceAllocationView(
                rs.getString("allocation_id"),
                rs.getString("workday_id"),
                rs.getDate("work_date").toLocalDate(),
                rs.getString("source_service"),
                rs.getString("target_service"),
                rs.getString("role_type"),
                rs.getString("role_type"),
                rs.getInt("allocated_headcount"),
                rs.getInt("allocated_headcount"),
                rs.getInt("capacity_per_person_per_day"),
                rs.getBigDecimal("wage_per_day"),
                rs.getBigDecimal("wage_per_day"),
                rs.getBigDecimal("productivity_score"),
                rs.getBigDecimal("productivity_score"),
                rs.getInt("effective_capacity"),
                0,
                rs.getInt("effective_capacity"),
                "ACTIVE".equals(rs.getString("status")),
                rs.getString("status"),
                instant(rs.getTimestamp("created_at"))
        );
    }

    private Optional<WorkforceWorkdayResult> workforceWorkdayResult(String workdayId) {
        List<WorkforceWorkdayResult> rows = jdbc.query(
                "select * from ledger_workday_result where workday_id=?",
                this::workforceWorkdayResultRow,
                workdayId
        );
        return rows.stream().findFirst();
    }

    private Optional<WorkforceWorkdayResult> latestWorkdayResult() {
        List<WorkforceWorkdayResult> rows = jdbc.query(
                "select * from ledger_workday_result order by created_at desc limit 1",
                this::workforceWorkdayResultRow
        );
        return rows.stream().findFirst();
    }

    private WorkforceWorkdayResult workforceWorkdayResultRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        int transactionsReceived = rs.getInt("transactions_received");
        int transactionsProcessed = rs.getInt("transactions_processed");
        int transactionsBacklog = rs.getInt("transactions_backlog");
        int settlementReady = rs.getInt("settlement_ready_count");
        int settlementCompleted = rs.getInt("settlement_completed_count");
        int settlementBacklog = rs.getInt("settlement_backlog_count");
        int reconciliationProcessed = rs.getInt("reconciliation_processed_count");
        int reconciliationBacklog = rs.getInt("reconciliation_backlog_count");
        int approvalReviewed = rs.getInt("approval_reviewed_count");
        int approvalBacklog = rs.getInt("approval_backlog_count");
        int callbackProcessed = rs.getInt("callback_processed_count");
        int callbackFailed = rs.getInt("callback_failed_count");
        int callbackBacklog = rs.getInt("callback_backlog_count");
        int demand = transactionsReceived + settlementReady + reconciliationProcessed + reconciliationBacklog
                + approvalReviewed + approvalBacklog + callbackProcessed + callbackBacklog;
        int processed = transactionsProcessed + settlementCompleted + reconciliationProcessed + approvalReviewed + callbackProcessed;
        int backlog = transactionsBacklog + settlementBacklog + reconciliationBacklog + approvalBacklog + callbackBacklog;
        String bottleneckRole = rs.getString("bottleneck_role");
        return new WorkforceWorkdayResult(
                rs.getString("workday_id"),
                rs.getString("workday_id"),
                rs.getDate("work_date").toLocalDate(),
                TARGET_LEDGER,
                rs.getInt("baseline_capacity"),
                rs.getInt("allocated_capacity"),
                demand,
                processed,
                backlog,
                backlog,
                transactionsReceived,
                transactionsProcessed,
                transactionsBacklog,
                settlementReady,
                settlementCompleted,
                settlementBacklog,
                reconciliationProcessed,
                reconciliationBacklog,
                approvalReviewed,
                approvalBacklog,
                callbackProcessed,
                callbackFailed,
                callbackBacklog,
                rs.getBigDecimal("payroll_cost"),
                rs.getBigDecimal("payroll_cost"),
                rs.getBigDecimal("backlog_cost"),
                rs.getBigDecimal("productivity_score"),
                bottleneckRole,
                bottleneckRole != null,
                bottleneckRole == null ? "WORKDAY_COMPLETED" : "BOTTLENECK_DETECTED",
                instant(rs.getTimestamp("created_at"))
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPayload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Object value = mapper.readValue(json, Map.class);
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> result = new HashMap<>();
                map.forEach((key, item) -> result.put(String.valueOf(key), item));
                return result;
            }
            return Map.of();
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private void copySyntheticPayload(Map<String, Object> target, Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            putIfPresent(target, key, payload.get(key));
        }
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private String runtimeDomain(String sourceService) {
        if (SOURCE_MARKET.equals(sourceService)) {
            return "market";
        }
        if (isLogisticsSource(sourceService)) {
            return "logistics";
        }
        if (SOURCE_NEXUS.equals(sourceService)) {
            return "nexus";
        }
        if ("ArchiveOS".equals(sourceService)) {
            return "operations";
        }
        return "ledger";
    }

    private String runtimeEntityType(String sourceService, Map<String, Object> payload, String routePlanId, String shipmentId) {
        if (payload.containsKey("orderId")) {
            return "order";
        }
        if (payload.containsKey("paymentId")) {
            return "payment";
        }
        if (payload.containsKey("claimId")) {
            return "claim";
        }
        if (payload.containsKey("returnId")) {
            return "return";
        }
        if (routePlanId != null || shipmentId != null || isLogisticsSource(sourceService)) {
            return "shipment";
        }
        if (SOURCE_NEXUS.equals(sourceService)) {
            return "factory_event";
        }
        return "transaction";
    }

    private String runtimeStatus(String processingStatus, String transactionStatus) {
        if ("FAILED".equals(processingStatus)) {
            return "failed";
        }
        if ("APPROVAL_REQUIRED".equals(transactionStatus)) {
            return "approval_required";
        }
        if ("REJECTED".equals(transactionStatus)) {
            return "rejected";
        }
        if ("SETTLED".equals(transactionStatus)) {
            return "settled";
        }
        if ("SETTLEMENT_READY".equals(transactionStatus)) {
            return "waiting";
        }
        if ("PROCESSED".equals(processingStatus)) {
            return "completed";
        }
        return "unavailable";
    }

    private String runtimeSeverity(String status, String eventType, BigDecimal riskScore) {
        if ("failed".equals(status) || "rejected".equals(status)) {
            return "critical";
        }
        if ("approval_required".equals(status)
                || "COLD_CHAIN_RISK_COST_CONFIRMED".equals(eventType)
                || (riskScore != null && riskScore.compareTo(LOGISTICS_LOW_RISK_SCORE) >= 0)) {
            return "warning";
        }
        if ("waiting".equals(status)) {
            return "info";
        }
        return "normal";
    }

    private String runtimeDisplayLabel(String sourceService, String eventType, String status) {
        return sourceService + " " + eventType + " -> " + status;
    }

    private String firstText(String... values) {
        for (String candidate : values) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
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

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private int parseInt(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            if (value instanceof Number number) {
                return number.intValue();
            }
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private boolean isCorrelationDuplicate(String source, String eventType, String correlationId) {
        return exists(
                "select count(*) from received_event where coalesce(source_service, source)=? and event_type=? and correlation_id=?",
                source,
                eventType,
                correlationId
        );
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

    private record WorkforceCapacity(
            int activeAllocations,
            int assignedUnits,
            int allocatedCapacity,
            Map<String, Integer> roleCapacities,
            BigDecimal operatingCost
    ) {
        int capacityFor(String role) {
            return roleCapacities.getOrDefault(role, activeAllocations == 0 ? LEDGER_BASELINE_DAILY_CAPACITY : 0);
        }
    }

    private record AgencyMetrics(
            BigDecimal transactionRevenue,
            BigDecimal settlementRevenue,
            BigDecimal reconciliationRevenue,
            BigDecimal approvalRevenue,
            BigDecimal totalRevenue,
            BigDecimal payrollCost,
            BigDecimal settlementBacklogCost,
            BigDecimal reconciliationBacklogCost,
            BigDecimal approvalBacklogCost,
            BigDecimal callbackFailureCost,
            BigDecimal operatingCost,
            BigDecimal operatingProfit,
            int transactionsProcessed,
            int settlementCompleted,
            int reconciliationProcessed,
            int approvalReviewed,
            int transactionsBacklog,
            int settlementBacklog,
            int reconciliationBacklog,
            int approvalBacklog,
            int callbackBacklog,
            BigDecimal productivityScore,
            String bottleneckRole
    ) {
    }

    private record WorkdayDemand(
            int transactionsReceived,
            int settlementReady,
            int approvalRequired,
            int reconciliationIssues,
            int callbackDemand,
            int callbackFailures
    ) {
        int total() {
            return transactionsReceived + settlementReady + approvalRequired + reconciliationIssues + callbackDemand;
        }
    }
}
