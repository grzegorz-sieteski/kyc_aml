package com.gs5.kycaml.risk.domain;

import java.math.BigDecimal;

/**
 * The weights, multiplier and thresholds of one jurisdiction (BR-18 to BR-24, A-13, A-18). Recorded
 * with every evaluation, so a later change never changes how an earlier evaluation reads (BR-27).
 *
 * @param staticWeight      w1, the weight of the static score in the total
 * @param behaviouralWeight w2, the weight of the behavioural score in the total
 * @param strictMultiplier  the multiplier for a non-resident where the policy is stricter
 * @param yellowThreshold   the lowest total that is Yellow
 * @param orangeThreshold   the lowest total that is Orange
 * @param redThreshold      the lowest total that is Red
 */
public record JurisdictionPolicy(
        Jurisdiction jurisdiction,
        BigDecimal geographicWeight,
        BigDecimal profileWeight,
        BigDecimal mediaWeight,
        BigDecimal productsWeight,
        BigDecimal staticWeight,
        BigDecimal behaviouralWeight,
        BigDecimal strictMultiplier,
        BigDecimal yellowThreshold,
        BigDecimal orangeThreshold,
        BigDecimal redThreshold,
        boolean strictForNonResidents) {

    private static final BigDecimal GEOGRAPHIC_WEIGHT = new BigDecimal("0.30");
    private static final BigDecimal PROFILE_WEIGHT = new BigDecimal("0.25");
    private static final BigDecimal MEDIA_WEIGHT = new BigDecimal("0.25");
    private static final BigDecimal PRODUCTS_WEIGHT = new BigDecimal("0.20");
    private static final BigDecimal ONBOARDING_STATIC_WEIGHT = new BigDecimal("1.00");
    private static final BigDecimal ONBOARDING_BEHAVIOURAL_WEIGHT = new BigDecimal("0.00");
    private static final BigDecimal NON_RESIDENT_MULTIPLIER = new BigDecimal("1.25");
    private static final BigDecimal YELLOW_THRESHOLD = new BigDecimal("31.00");
    private static final BigDecimal ORANGE_THRESHOLD = new BigDecimal("61.00");
    private static final BigDecimal RED_THRESHOLD = new BigDecimal("85.00");

    /**
     * The policy of a jurisdiction. In this increment the three in-scope jurisdictions share the
     * same numbers (D-06, A-13, A-17, A-18).
     */
    public static JurisdictionPolicy of(Jurisdiction jurisdiction) {
        return new JurisdictionPolicy(jurisdiction, GEOGRAPHIC_WEIGHT, PROFILE_WEIGHT, MEDIA_WEIGHT,
                PRODUCTS_WEIGHT, ONBOARDING_STATIC_WEIGHT, ONBOARDING_BEHAVIOURAL_WEIGHT,
                NON_RESIDENT_MULTIPLIER, YELLOW_THRESHOLD, ORANGE_THRESHOLD, RED_THRESHOLD, true);
    }
}
