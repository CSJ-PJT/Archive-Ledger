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

        int transactionsProcessed = Math.min(demand.transactionsReceived(), Math.min(transactionCapacity, ledgerCapacity));
        int transactionsBacklog = Math.max(0, demand.transactionsReceived() - transactionsProcessed);
        int settlementCompleted = Math.min(demand.settlementReady(), settlementCapacity);
        int settlementBacklog = Math.max(0, demand.settlementReady() - settlementCompleted);
        int reconciliationProcessed = Math.min(demand.reconciliationIssues(), reconciliationCapacity);
        int reconciliationBacklog = Math.max(0, demand.reconciliationIssues() - reconciliationProcessed);
        int approvalReviewed = Math.min(demand.approvalRequired(), approvalCapacity);
        int approvalBacklog = Math.max(0, demand.approvalRequired() - approvalReviewed);
        int callbackProcessed = Math.min(demand.callbackDemand(), callbackCapacity);
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
        return workforceWorkdayResult(resolvedWorkdayId).orElseThrow();
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
                ? BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(processed).divide(BigDecimal.valueOf(totalDemand), 4, RoundingMode.HALF_UP);
        String bottleneckRole = bottleneckRole(transactionsBacklog, settlementBacklog, reconciliationBacklog, approvalBacklog, callbackBacklog);
        String status = backlog > 0 ? "BOTTLENECK_DETECTED" : "HEALTHY";
        return new WorkforceSummary(
                "Archive-Ledger",
                assignmentSource,
                capacity.activeAllocations() > 0,
                capacity.activeAllocations(),
                capacity.assignedUnits(),
                capacity.operatingCost(),
                LEDGER_BASELINE_DAILY_CAPACITY,
                capacity.allocatedCapacity(),
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
                status
        );
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
                roleCapacity.put(role, LEDGER_BASELINE_DAILY_CAPACITY);
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
            case "SETTLEMENT_OPERATOR" -> 120;
            case "RECONCILIATION_ANALYST" -> 20;
            case "APPROVAL_REVIEWER" -> 40;
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
        int transactionsProcessed = latest == null ? 0 : latest.transactionsProcessed();
        int settlementCompleted = latest == null ? 0 : latest.settlementCompletedCount();
        int reconciliationProcessed = latest == null ? 0 : latest.reconciliationProcessedCount();
        int approvalReviewed = latest == null ? 0 : latest.approvalReviewedCount();
        int transactionsBacklog = latest == null ? 0 : latest.transactionsBacklog();
        int settlementBacklog = latest == null ? 0 : latest.settlementBacklog();
        int reconciliationBacklog = latest == null ? 0 : latest.reconciliationBacklog();
        int approvalBacklog = latest == null ? 0 : latest.approvalBacklog();
        int callbackBacklog = latest == null ? 0 : latest.callbackBacklog();
        BigDecimal transactionRevenue = BigDecimal.valueOf(transactionsProcessed).multiply(new BigDecimal("120"));
        BigDecimal settlementRevenue = BigDecimal.valueOf(settlementCompleted).multiply(new BigDecimal("700"));
        BigDecimal reconciliationRevenue = BigDecimal.valueOf(reconciliationProcessed).multiply(new BigDecimal("500"));
        BigDecimal approvalRevenue = BigDecimal.valueOf(approvalReviewed).multiply(new BigDecimal("900"));
        BigDecimal totalRevenue = transactionRevenue.add(settlementRevenue).add(reconciliationRevenue).add(approvalRevenue);
        BigDecimal payroll = latest == null ? BigDecimal.ZERO : latest.payrollCost();
        BigDecimal settlementCost = settlementBacklogCost(settlementBacklog);
        BigDecimal reconciliationCost = reconciliationDelayCost(reconciliationBacklog);
        BigDecimal approvalCost = approvalBacklogCost(approvalBacklog);
        BigDecimal callbackCost = callbackDelayCost(callbackBacklog);
        BigDecimal totalCost = payroll.add(settlementCost).add(reconciliationCost).add(approvalCost).add(callbackCost);
        return new SettlementAgencySummary(
                TARGET_LEDGER,
                transactionRevenue,
                settlementRevenue,
                reconciliationRevenue,
                approvalRevenue,
                totalRevenue,
                payroll,
                settlementCost,
                reconciliationCost,
                approvalCost,
                callbackCost,
                totalCost,
                totalRevenue.subtract(totalCost),
                transactionsProcessed,
                settlementCompleted,
                reconciliationProcessed,
                approvalReviewed,
                transactionsBacklog,
                settlementBacklog,
                reconciliationBacklog,
                approvalBacklog,
                callbackBacklog,
                latest == null ? BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP) : latest.productivityScore(),
                latest == null ? null : latest.bottleneckRole()
        );
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
                workforceSummary(LocalDate.now(), "ArchiveOS")
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
