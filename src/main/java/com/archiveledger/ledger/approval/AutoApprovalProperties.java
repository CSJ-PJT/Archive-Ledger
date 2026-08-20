package com.archiveledger.ledger.approval;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;

@Component
public class AutoApprovalProperties {
    public static final BigDecimal MIN_AMOUNT_KRW = new BigDecimal("300000");
    public static final BigDecimal MAX_AMOUNT_KRW = new BigDecimal("500000");
    public static final BigDecimal MAX_RISK_SCORE = new BigDecimal("0.50");
    public static final int SAFETY_MAX_DAILY_COUNT = 20;
    public static final BigDecimal SAFETY_MAX_DAILY_AMOUNT_KRW = new BigDecimal("10000000");
    public static final String ALLOWED_EVENT_TYPE = "LOGISTICS_COST_CONFIRMED";
    public static final String ALLOWED_TRANSACTION_TYPE = "LOGISTICS_COST";

    private final Mode configuredMode;
    private final String policyVersion;
    private final int maxDailyCount;
    private final BigDecimal maxDailyAmountKrw;
    private final boolean valid;

    public AutoApprovalProperties(
            @Value("${archive-ledger.auto-approval.mode:DISABLED}") String mode,
            @Value("${archive-ledger.auto-approval.policy-version:logistics-amount-only-v1}") String policyVersion,
            @Value("${archive-ledger.auto-approval.max-daily-count:20}") int maxDailyCount,
            @Value("${archive-ledger.auto-approval.max-daily-amount-krw:10000000}") BigDecimal maxDailyAmountKrw) {
        Mode parsedMode = Mode.parse(mode);
        String normalizedVersion = policyVersion == null ? "" : policyVersion.trim();
        boolean limitsValid = maxDailyCount > 0
                && maxDailyAmountKrw != null
                && maxDailyAmountKrw.compareTo(BigDecimal.ZERO) > 0;
        this.configuredMode = parsedMode;
        this.policyVersion = normalizedVersion;
        this.maxDailyCount = Math.min(Math.max(maxDailyCount, 0), SAFETY_MAX_DAILY_COUNT);
        this.maxDailyAmountKrw = maxDailyAmountKrw == null
                ? BigDecimal.ZERO
                : maxDailyAmountKrw.min(SAFETY_MAX_DAILY_AMOUNT_KRW).max(BigDecimal.ZERO);
        this.valid = parsedMode != null && !normalizedVersion.isBlank() && limitsValid;
    }

    public Mode mode() {
        return valid ? configuredMode : Mode.DISABLED;
    }

    public String policyVersion() {
        return policyVersion.isBlank() ? "invalid-policy-version" : policyVersion;
    }

    public int maxDailyCount() {
        return maxDailyCount;
    }

    public BigDecimal maxDailyAmountKrw() {
        return maxDailyAmountKrw;
    }

    public boolean valid() {
        return valid;
    }

    public enum Mode {
        DISABLED,
        SHADOW,
        ENFORCE;

        static Mode parse(String value) {
            if (value == null || value.isBlank()) {
                return DISABLED;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }
}
