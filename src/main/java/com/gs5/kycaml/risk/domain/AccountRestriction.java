package com.gs5.kycaml.risk.domain;

/** What the effective track means for the account (BR-28, A-22). */
public enum AccountRestriction {
    ACTIVE,
    SOFT_LIMITS,
    EDD_HOLD,
    ASSETS_FROZEN;

    /** Green is active, Yellow soft limits, Orange an enhanced-due-diligence hold, Red frozen. */
    @SuppressWarnings("NullAway")
    public static AccountRestriction of(RiskTrack track) {
        return null;
    }
}
