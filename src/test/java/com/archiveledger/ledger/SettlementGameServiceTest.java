package com.archiveledger.ledger;

import com.archiveledger.ledger.game.SettlementGameModels.SettlementGameRequest;
import com.archiveledger.ledger.game.SettlementGameService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementGameServiceTest {
    private final SettlementGameService service = new SettlementGameService();

    @Test
    void simulatesLedgerSettlementAgencyRevenueAndEvents() {
        var response = service.simulate(service.defaultPreset());

        assertThat(response.services().get("ledger").revenue()).isGreaterThan(BigDecimal.ZERO);
        assertThat(response.events()).extracting("simulationRunId").containsOnly(response.simulationRunId());
        assertThat(response.events()).allSatisfy(event -> {
            assertThat(event.settlementCycleId()).isEqualTo(response.settlementCycleId());
            assertThat(event.tickId()).isEqualTo(response.tickId());
            assertThat(event.day()).isEqualTo(response.day());
            assertThat(event.correlationId()).isEqualTo(response.correlationId());
            assertThat(event.hop()).isLessThanOrEqualTo(event.maxHop());
        });
    }

    @Test
    void proposesSafeModeWhenCashRiskIsHigh() {
        SettlementGameRequest preset = service.defaultPreset();
        var risky = new SettlementGameRequest(
                "SIM-RISK", "CYCLE-RISK", "TICK-RISK", 3, "CORR-RISK", 2,
                BigDecimal.valueOf(100_000), BigDecimal.valueOf(100_000), BigDecimal.valueOf(100_000),
                BigDecimal.ZERO, BigDecimal.valueOf(2_000_000), BigDecimal.valueOf(1_000_000), BigDecimal.valueOf(1_000_000),
                BigDecimal.ZERO, BigDecimal.ZERO, 10, 500, 2, 2, 3, 20, 10, 5,
                preset.ledgerTransactionProcessingFee(), preset.ledgerDailySettlementAgencyFee(), preset.ledgerReconciliationVerificationFee(),
                preset.ledgerApprovalReviewFee(), preset.ledgerExceptionHandlingFee(), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.valueOf(10_000), BigDecimal.valueOf(1_000_000), BigDecimal.valueOf(1_000_000),
                BigDecimal.valueOf(500_000), BigDecimal.valueOf(1_000_000), BigDecimal.valueOf(5_000_000)
        );

        var response = service.simulate(risky);

        assertThat(response.bankruptcyRisk()).isIn("WARNING", "CRITICAL");
        assertThat(response.proposals()).isNotEmpty();
        assertThat(response.proposals()).allSatisfy(proposal -> {
            assertThat(proposal.safeModeRequired()).isTrue();
            assertThat(proposal.approvalRequired()).isTrue();
        });
        assertThat(response.events()).hasSize(2);
    }
}
