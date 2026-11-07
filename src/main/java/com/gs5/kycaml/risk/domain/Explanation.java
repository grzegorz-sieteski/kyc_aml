package com.gs5.kycaml.risk.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * Why a total is what it is (BR-26). Recomputing the total from this alone gives exactly the
 * recorded total: the sum of the category contributions is the static score, and the total is the
 * weighted static and behavioural scores times the multiplier, or the maximum when a critical fact
 * is in force.
 *
 * @param categories          one entry per category, in the order of {@link FactorCategory}
 * @param drivers             the facts that produced points, as codes such as
 *                            {@code GEOGRAPHIC:FATF_GREY_LIST} or {@code GEOGRAPHIC:LOGIN_COUNTRY_MISMATCH}
 * @param criticalFacts       the critical facts in force; empty when none
 * @param nonProfitFloorApplied whether BR-25 raised the track to Orange
 */
public record Explanation(
        List<CategoryContribution> categories,
        List<String> drivers,
        BigDecimal staticScore,
        BigDecimal staticWeight,
        BigDecimal behaviouralScore,
        BigDecimal behaviouralWeight,
        BigDecimal multiplier,
        List<CriticalFactField> criticalFacts,
        boolean nonProfitFloorApplied) { }
