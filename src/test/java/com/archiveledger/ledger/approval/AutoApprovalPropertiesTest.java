package com.archiveledger.ledger.approval;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AutoApprovalPropertiesTest {
    @Test
    void clampsConfiguredBudgetsToHardSafetyCeilings() {
        AutoApprovalProperties properties = new AutoApprovalProperties(
                "ENFORCE", "v1", 999, new BigDecimal("999999999"));

        assertThat(properties.mode()).isEqualTo(AutoApprovalProperties.Mode.ENFORCE);
        assertThat(properties.maxDailyCount()).isEqualTo(20);
        assertThat(properties.maxDailyAmountKrw()).isEqualByComparingTo("10000000");
    }

    @Test
    void invalidConfigurationFailsClosedToDisabled() {
        AutoApprovalProperties invalidMode = new AutoApprovalProperties(
                "approve-everything", "v1", 20, new BigDecimal("10000000"));
        AutoApprovalProperties invalidBudget = new AutoApprovalProperties(
                "ENFORCE", "v1", 0, BigDecimal.ZERO);

        assertThat(invalidMode.mode()).isEqualTo(AutoApprovalProperties.Mode.DISABLED);
        assertThat(invalidMode.valid()).isFalse();
        assertThat(invalidBudget.mode()).isEqualTo(AutoApprovalProperties.Mode.DISABLED);
        assertThat(invalidBudget.valid()).isFalse();
    }
}
