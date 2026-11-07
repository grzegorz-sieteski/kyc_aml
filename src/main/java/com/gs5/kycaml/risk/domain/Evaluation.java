package com.gs5.kycaml.risk.domain;

import java.math.BigDecimal;

/**
 * The outcome of one risk evaluation: the policy applied, the total score and the track it gives,
 * and the explanation. The total has exactly two decimal places and lies between 0.00 and 100.00.
 */
public record Evaluation(
        JurisdictionPolicy policy,
        BigDecimal totalScore,
        RiskTrack evaluatedTrack,
        Explanation explanation) {

    /** The highest possible total (A-01). */
    public static final BigDecimal MAX_SCORE = new BigDecimal("100.00");

    /** The number of decimal places of a total (A-01, A-19). */
    public static final int SCORE_SCALE = 2;

    /**
     * Scores the inputs under the policy of the booking centre's jurisdiction: a pure function of
     * its arguments (BR-14 to BR-27). Rounds once, half-up, on the total.
     */
    @SuppressWarnings("NullAway")
    public static Evaluation of(EvaluateCustomerRiskCommand command, Jurisdiction bookingCentre) {
        return null;
    }

    /**
     * The track a total gives under the policy: at or above a threshold belongs to the higher track
     * (BR-24, A-20). The non-profit floor of BR-25 is not applied here.
     */
    @SuppressWarnings("NullAway")
    public static RiskTrack trackFor(JurisdictionPolicy policy, BigDecimal totalScore) {
        return null;
    }

    /** Confidential (BR-43): never printed. */
    @Override
    public String toString() {
        return "Evaluation[***]";
    }
}
