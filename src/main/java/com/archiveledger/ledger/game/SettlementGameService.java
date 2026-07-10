package com.archiveledger.ledger.game;

import com.archiveledger.ledger.game.SettlementGameModels.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

@Service
public class SettlementGameService {
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    public SettlementGameRequest defaultPreset() {
        return new SettlementGameRequest(
                "SIM-RUN-DEMO-001", "CYCLE-DAY-001", "TICK-001", 1, "CORR-DEMO-001", 4,
                bd("50000000"), bd("30000000"), bd("25000000"),
                bd("18000000"), bd("6200000"), bd("1700000"), bd("900000"),
                bd("2100000"), bd("350000"), 120, 180, 1, 1, 9, 4, 2, 1,
                bd("1200"), bd("450000"), bd("250000"), bd("80000"), bd("150000"), bd("220000"), bd("100000"),
                bd("350"), bd("160000"), bd("120000"), bd("60000"), bd("180000"), bd("950000")
        );
    }

    public SettlementGameResponse simulate(SettlementGameRequest input) {
        SettlementGameRequest r = merge(input);
        int maxHop = Math.max(1, Math.min(value(r.maxHop(), 4), 12));
        String simulationRunId = value(r.simulationRunId(), "SIM-RUN-" + UUID.randomUUID());
        String cycleId = value(r.settlementCycleId(), "CYCLE-DAY-" + value(r.day(), 1));
        String tickId = value(r.tickId(), "TICK-" + value(r.day(), 1));
        int day = Math.max(1, value(r.day(), 1));
        String correlationId = value(r.correlationId(), UUID.randomUUID().toString());

        BigDecimal nexusRevenue = money(r.nexusProductionRevenue());
        BigDecimal nexusCost = money(r.nexusMaterialCost()).add(money(r.nexusMaintenanceCost())).add(money(r.nexusQualityLossCost()))
                .add(money(r.logisticsServiceFee())).add(money(r.logisticsDailySettlementFee()));

        BigDecimal logisticsRevenue = money(r.logisticsServiceFee()).add(money(r.logisticsDailySettlementFee()));
        BigDecimal logisticsCost = money(r.ledgerDailySettlementAgencyFee()).add(money(r.ledgerReconciliationVerificationFee()))
                .add(money(r.ledgerTransactionProcessingFee()).multiply(count(r.transactionCount())));

        BigDecimal ledgerRevenue = money(r.ledgerTransactionProcessingFee()).multiply(count(r.transactionCount()))
                .add(money(r.ledgerDailySettlementAgencyFee()).multiply(count(r.settlementBatchCount())))
                .add(money(r.ledgerReconciliationVerificationFee()).multiply(count(r.reconciliationCount())))
                .add(money(r.ledgerApprovalReviewFee()).multiply(count(r.approvalReviewCount())))
                .add(money(r.ledgerExceptionHandlingFee()).multiply(count(r.exceptionCount())))
                .add(money(r.ledgerEarlySettlementFee()))
                .add(money(r.ledgerDelayedSettlementPenaltyRevenue()));
        BigDecimal ledgerCost = money(r.ledgerProcessingOperatingCost()).multiply(count(r.transactionCount()))
                .add(money(r.ledgerSettlementBatchRunCost()).multiply(count(r.settlementBatchCount())))
                .add(money(r.ledgerReconciliationRunCost()).multiply(count(r.reconciliationCount())))
                .add(money(r.ledgerCallbackFailureCost()).multiply(count(r.callbackFailureCount())))
                .add(money(r.ledgerMismatchInvestigationCost()).multiply(count(r.mismatchCount())))
                .add(money(r.ledgerInfraFixedCost()));

        ServiceEconomics nexus = economics("Archive-Nexus", money(r.nexusInitialCash()), nexusRevenue, nexusCost,
                "Manufacturing revenue minus material, maintenance, quality loss, and Logistics fees.");
        ServiceEconomics logistics = economics("Archive-Logistics", money(r.logisticsInitialCash()), logisticsRevenue, logisticsCost,
                "Route/ETA/cost service revenue minus Ledger processing and settlement agency fees.");
        ServiceEconomics ledger = economics("Archive-Ledger", money(r.ledgerInitialCash()), ledgerRevenue, ledgerCost,
                "Settlement agency revenue minus transaction, batch, reconciliation, callback, mismatch, and fixed operating costs.");

        Map<String, ServiceEconomics> services = new LinkedHashMap<>();
        services.put("nexus", nexus); services.put("logistics", logistics); services.put("ledger", ledger);

        BigDecimal ecosystemCash = nexus.cashAfter().add(logistics.cashAfter()).add(ledger.cashAfter());
        BigDecimal ecosystemProfit = nexus.profit().add(logistics.profit()).add(ledger.profit());
        String ecosystemRisk = risk(ecosystemCash, ecosystemProfit.negate());

        List<GameEvent> events = events(simulationRunId, cycleId, tickId, day, correlationId, maxHop, r, ledgerRevenue, ledgerCost);
        List<AgentProposal> proposals = proposals(services, r, ecosystemRisk);
        String status = "CRITICAL".equals(ecosystemRisk) ? "BANKRUPTCY_RISK" : "WARNING".equals(ecosystemRisk) ? "ATTENTION" : "RUNNING";
        return new SettlementGameResponse(simulationRunId, cycleId, tickId, day, correlationId, maxHop, status,
                ecosystemCash, ecosystemProfit, ecosystemRisk, services, events, proposals, Instant.now());
    }

    private List<GameEvent> events(String run, String cycle, String tick, int day, String correlation, int maxHop,
                                   SettlementGameRequest r, BigDecimal ledgerRevenue, BigDecimal ledgerCost) {
        List<GameEvent> out = new ArrayList<>();
        out.add(event("GAME_NEXUS_PRODUCTION_PROFIT", "Archive-Nexus", "ArchiveOS", run, cycle, tick, day, correlation, 1, maxHop,
                Map.of("productionRevenue", money(r.nexusProductionRevenue()), "materialCost", money(r.nexusMaterialCost()))));
        out.add(event("GAME_LOGISTICS_DAILY_SETTLEMENT_FEE", "Archive-Logistics", "Archive-Nexus", run, cycle, tick, day, correlation, 2, maxHop,
                Map.of("shipments", r.shipmentCount(), "logisticsServiceFee", money(r.logisticsServiceFee()), "dailySettlementFee", money(r.logisticsDailySettlementFee()))));
        out.add(event("GAME_LEDGER_SETTLEMENT_AGENCY_REVENUE", "Archive-Ledger", "ArchiveOS", run, cycle, tick, day, correlation, 3, maxHop,
                Map.of("ledgerRevenue", ledgerRevenue, "ledgerCost", ledgerCost, "transactions", r.transactionCount(), "mismatches", r.mismatchCount())));
        return out.stream().filter(e -> e.hop() <= e.maxHop()).toList();
    }

    private GameEvent event(String type, String source, String target, String run, String cycle, String tick, int day, String correlation, int hop, int maxHop, Map<String, Object> payload) {
        String seed = run + ":" + cycle + ":" + tick + ":" + type + ":" + hop;
        String id = "GAME-" + Math.abs(seed.hashCode());
        return new GameEvent(id, seed, type, source, target, run, cycle, tick, day, correlation, hop, maxHop, payload);
    }

    private List<AgentProposal> proposals(Map<String, ServiceEconomics> services, SettlementGameRequest r, String ecosystemRisk) {
        List<AgentProposal> out = new ArrayList<>();
        for (ServiceEconomics s : services.values()) {
            if (!"LOW".equals(s.bankruptcyRisk())) {
                out.add(new AgentProposal("PROP-" + s.service().replace("Archive-", "").toUpperCase(Locale.ROOT),
                        s.service() + "Agent", s.service(), "GAME_PROPOSAL",
                        s.service() + " cash risk is " + s.bankruptcyRisk() + ". Reduce costs, raise fees, or pause risky settlement cycles.",
                        s.burnRate(), 0.78, true, true,
                        List.of("cashAfter=" + s.cashAfter(), "profit=" + s.profit(), "burnRate=" + s.burnRate())));
            }
        }
        if (!"LOW".equals(ecosystemRisk)) {
            out.add(new AgentProposal("PROP-ECOSYSTEM-SAFE-MODE", "ArchiveOSGameMasterAgent", "Archive Platform", "SAFE_MODE_RECOMMENDATION",
                    "Enable safe-mode for high-cost writes and require approval before fee policy or settlement acceleration changes.",
                    money(r.ledgerInfraFixedCost()), 0.82, true, true,
                    List.of("ecosystemRisk=" + ecosystemRisk, "simulation namespace is GAME/SIMULATION", "agents propose only")));
        }
        return out;
    }

    private ServiceEconomics economics(String service, BigDecimal cash, BigDecimal revenue, BigDecimal cost, String explanation) {
        BigDecimal profit = revenue.subtract(cost);
        BigDecimal cashAfter = cash.add(profit);
        BigDecimal burn = profit.signum() < 0 ? profit.abs() : ZERO;
        return new ServiceEconomics(service, cash, revenue, cost, profit, cashAfter, burn, risk(cashAfter, burn), explanation);
    }

    private String risk(BigDecimal cashAfter, BigDecimal burn) {
        if (cashAfter.signum() <= 0) return "CRITICAL";
        if (burn.signum() == 0) return "LOW";
        BigDecimal runway = cashAfter.divide(burn.max(BigDecimal.ONE), 2, RoundingMode.HALF_UP);
        if (runway.compareTo(BigDecimal.valueOf(2)) <= 0) return "CRITICAL";
        if (runway.compareTo(BigDecimal.valueOf(7)) <= 0) return "WARNING";
        return "LOW";
    }

    private SettlementGameRequest merge(SettlementGameRequest input) {
        return input == null ? defaultPreset() : input;
    }
    private BigDecimal money(BigDecimal v) { return v == null ? ZERO : v.max(ZERO); }
    private BigDecimal count(int v) { return BigDecimal.valueOf(Math.max(0, v)); }
    private int value(Integer v, int fallback) { return v == null ? fallback : v; }
    private String value(String v, String fallback) { return v == null || v.isBlank() ? fallback : v; }
    private BigDecimal bd(String v) { return new BigDecimal(v); }
}
