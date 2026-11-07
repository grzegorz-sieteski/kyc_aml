package com.gs5.kycaml.risk.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static com.gs5.kycaml.risk.domain.RiskFixtures.rated;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scoring, as a pure function: the worked examples of the specification (E1 to E7) and the
 * boundaries around them. Every total is checked at exactly two decimal places.
 */
class EvaluationTest {

    private static final RawRatedCountry DE_LOW = rated("DE", "LOW");

    private static RawEvaluateCustomerRisk raw(
            String profile, RawRatedCountry residence, List<RawRatedCountry> citizenships, RawRatedCountry birth,
            String login, String channel, boolean productRequest, String media,
            boolean sanctions, boolean terrorist, boolean forged) {
        return new RawEvaluateCustomerRisk("C-1", profile, residence, citizenships, birth, login, channel,
                productRequest, media, sanctions, terrorist, forged);
    }

    private static Evaluation evaluate(RawEvaluateCustomerRisk raw, Jurisdiction centre) {
        return Evaluation.of(RiskFixtures.parsed(raw), centre);
    }

    private static RawEvaluateCustomerRisk e1() {
        return raw("INDIVIDUAL_EMPLOYED", DE_LOW, List.of(DE_LOW), DE_LOW, "DE", "BRANCH", false, "NONE",
                false, false, false);
    }

    private static RawEvaluateCustomerRisk e3(RawRatedCountry residence) {
        return raw("POLITICALLY_EXPOSED", residence, List.of(rated("AE", "LOW")), DE_LOW, "AE", "REMOTE", true,
                "UNCONFIRMED_FINANCIAL_CRIME", false, false, false);
    }

    private static int points(Evaluation evaluation, FactorCategory category) {
        return evaluation.explanation().categories().stream()
                .filter(c -> c.category() == category).findFirst().orElseThrow().points();
    }

    private static void assertExplanationRecomputesTheTotal(Evaluation evaluation) {
        var explanation = evaluation.explanation();
        var summed = explanation.categories().stream().map(CategoryContribution::contribution)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(summed).isEqualByComparingTo(explanation.staticScore());
        if (explanation.criticalFacts().isEmpty()) {
            var raw = explanation.staticWeight().multiply(explanation.staticScore())
                    .add(explanation.behaviouralWeight().multiply(explanation.behaviouralScore()))
                    .multiply(explanation.multiplier());
            assertThat(raw.min(Evaluation.MAX_SCORE).setScale(Evaluation.SCORE_SCALE, RoundingMode.HALF_UP))
                    .isEqualTo(evaluation.totalScore());
        } else {
            assertThat(evaluation.totalScore()).isEqualTo(Evaluation.MAX_SCORE);
        }
    }

    // ------------------------------------------------------------ worked examples

    @Test
    @DisplayName("E1: an employed resident of Germany onboarded in a branch scores 4.50 and is Green (BR-14 to BR-24)")
    void shouldScoreExampleE1() {
        var result = evaluate(e1(), Jurisdiction.DE);

        assertThat(result.totalScore()).isEqualTo(new BigDecimal("4.50"));
        assertThat(result.evaluatedTrack()).isEqualTo(RiskTrack.GREEN);
        assertThat(result.explanation().staticScore()).isEqualTo(new BigDecimal("4.50"));
        assertThat(result.explanation().multiplier()).isEqualTo(new BigDecimal("1.00"));
        assertThat(result.explanation().categories()).containsExactly(
                new CategoryContribution(FactorCategory.GEOGRAPHIC, 0, new BigDecimal("0.30"), new BigDecimal("0.00")),
                new CategoryContribution(FactorCategory.CUSTOMER_PROFILE, 10, new BigDecimal("0.25"), new BigDecimal("2.50")),
                new CategoryContribution(FactorCategory.ADVERSE_MEDIA, 0, new BigDecimal("0.25"), new BigDecimal("0.00")),
                new CategoryContribution(FactorCategory.PRODUCTS_AND_CHANNELS, 10, new BigDecimal("0.20"), new BigDecimal("2.00")));
        assertThat(result.explanation().drivers()).containsExactly(
                "CUSTOMER_PROFILE:INDIVIDUAL_EMPLOYED", "PRODUCTS_AND_CHANNELS:BRANCH");
        assertThat(result.explanation().criticalFacts()).isEmpty();
        assertThat(result.explanation().nonProfitFloorApplied()).isFalse();
    }

    @Test
    @DisplayName("E2: a non-resident of the booking centre gets the 1.25 multiplier: 4.50 x 1.25 = 5.625, rounded half-up to 5.63")
    void shouldScoreExampleE2() {
        var france = rated("FR", "LOW");
        var result = evaluate(raw("INDIVIDUAL_EMPLOYED", france, List.of(DE_LOW), DE_LOW, "FR", "BRANCH", false,
                "NONE", false, false, false), Jurisdiction.DE);

        assertThat(result.explanation().multiplier()).isEqualTo(new BigDecimal("1.25"));
        assertThat(result.totalScore()).isEqualTo(new BigDecimal("5.63"));
        assertThat(result.evaluatedTrack()).isEqualTo(RiskTrack.GREEN);
    }

    @Test
    @DisplayName("E3: a politically exposed remote customer with a mismatched login scores 51.00 and is Yellow")
    void shouldScoreExampleE3() {
        var result = evaluate(e3(DE_LOW), Jurisdiction.DE);

        assertThat(points(result, FactorCategory.GEOGRAPHIC)).isEqualTo(30);
        assertThat(points(result, FactorCategory.CUSTOMER_PROFILE)).isEqualTo(70);
        assertThat(points(result, FactorCategory.ADVERSE_MEDIA)).isEqualTo(50);
        assertThat(points(result, FactorCategory.PRODUCTS_AND_CHANNELS)).isEqualTo(60);
        assertThat(result.explanation().staticScore()).isEqualTo(new BigDecimal("51.00"));
        assertThat(result.totalScore()).isEqualTo(new BigDecimal("51.00"));
        assertThat(result.evaluatedTrack()).isEqualTo(RiskTrack.YELLOW);
        assertThat(result.explanation().drivers()).containsExactly(
                "GEOGRAPHIC:LOGIN_COUNTRY_MISMATCH",
                "CUSTOMER_PROFILE:POLITICALLY_EXPOSED",
                "ADVERSE_MEDIA:UNCONFIRMED_FINANCIAL_CRIME",
                "PRODUCTS_AND_CHANNELS:HIGH_RISK_PRODUCT_REQUEST");
    }

    @Test
    @DisplayName("E4: the same customer resident in France is a non-resident of Germany: 51.00 x 1.25 = 63.75, Orange")
    void shouldScoreExampleE4() {
        var result = evaluate(e3(rated("FR", "LOW")), Jurisdiction.DE);

        assertThat(result.totalScore()).isEqualTo(new BigDecimal("63.75"));
        assertThat(result.evaluatedTrack()).isEqualTo(RiskTrack.ORANGE);
    }

    @Test
    @DisplayName("E5: a cross-border non-profit scoring 23.25 is raised to Orange by the floor (BR-25)")
    void shouldScoreExampleE5() {
        var result = evaluate(raw("CROSS_BORDER_NON_PROFIT", DE_LOW, List.of(DE_LOW), DE_LOW, "DE", "BRANCH",
                false, "NONE", false, false, false), Jurisdiction.DE);

        assertThat(result.totalScore()).isEqualTo(new BigDecimal("23.25"));
        assertThat(result.evaluatedTrack()).isEqualTo(RiskTrack.ORANGE);
        assertThat(result.explanation().nonProfitFloorApplied()).isTrue();
    }

    @Test
    @DisplayName("E6: a non-resident student on the black list with radicalisation reports scores 91.56 and is Red")
    void shouldScoreExampleE6() {
        var black = rated("IR", "FATF_BLACK_LIST_OR_CONFLICT_ZONE");
        var result = evaluate(raw("INDIVIDUAL_STUDENT_OR_NO_INCOME", black, List.of(black), black, "DE", "REMOTE",
                true, "RADICALISATION_OR_PARAMILITARY", false, false, false), Jurisdiction.DE);

        assertThat(points(result, FactorCategory.GEOGRAPHIC)).isEqualTo(100);
        assertThat(result.explanation().staticScore()).isEqualTo(new BigDecimal("73.25"));
        assertThat(result.explanation().multiplier()).isEqualTo(new BigDecimal("1.25"));
        assertThat(result.totalScore()).isEqualTo(new BigDecimal("91.56"));
        assertThat(result.evaluatedTrack()).isEqualTo(RiskTrack.RED);
    }

    @Test
    @DisplayName("E7: a confirmed sanctions match sets the total to 100.00 and the track to Red, whatever else is true (BR-23)")
    void shouldScoreExampleE7() {
        var result = evaluate(raw("INDIVIDUAL_EMPLOYED", DE_LOW, List.of(DE_LOW), DE_LOW, "DE", "BRANCH", false,
                "NONE", true, false, false), Jurisdiction.DE);

        assertThat(result.totalScore()).isEqualTo(new BigDecimal("100.00"));
        assertThat(result.evaluatedTrack()).isEqualTo(RiskTrack.RED);
        assertThat(result.explanation().criticalFacts()).containsExactly(CriticalFactField.SANCTIONS_MATCH);
        assertThat(result.explanation().staticScore()).isEqualTo(new BigDecimal("4.50"));
    }

    @Test
    @DisplayName("each critical fact alone gives 100.00 and Red, and all three are listed in a fixed order (BR-23)")
    void shouldTreatEachCriticalFactAsDecisive() {
        for (var facts : List.of(new boolean[]{true, false, false}, new boolean[]{false, true, false},
                new boolean[]{false, false, true})) {
            var result = evaluate(raw("INDIVIDUAL_EMPLOYED", DE_LOW, List.of(DE_LOW), DE_LOW, "DE", "BRANCH", false,
                    "NONE", facts[0], facts[1], facts[2]), Jurisdiction.DE);
            assertThat(result.totalScore()).isEqualTo(new BigDecimal("100.00"));
            assertThat(result.evaluatedTrack()).isEqualTo(RiskTrack.RED);
        }
        var all = evaluate(raw("INDIVIDUAL_EMPLOYED", DE_LOW, List.of(DE_LOW), DE_LOW, "DE", "BRANCH", false,
                "NONE", true, true, true), Jurisdiction.DE);
        assertThat(all.explanation().criticalFacts()).containsExactly(
                CriticalFactField.SANCTIONS_MATCH, CriticalFactField.TERRORIST_LINK, CriticalFactField.FORGED_IDENTITY);
    }

    // ----------------------------------------------------------------- categories

    @Test
    @DisplayName("geographic points take the highest class among residence, every citizenship and birth (BR-14, A-14)")
    void shouldTakeTheHighestGeographicClass() {
        var grey = rated("TR", "FATF_GREY_LIST");
        var black = rated("SY", "FATF_BLACK_LIST_OR_CONFLICT_ZONE");

        assertThat(points(evaluate(raw("INDIVIDUAL_EMPLOYED", DE_LOW, List.of(DE_LOW, grey), DE_LOW, "DE",
                "BRANCH", false, "NONE", false, false, false), Jurisdiction.DE), FactorCategory.GEOGRAPHIC))
                .isEqualTo(50);
        assertThat(points(evaluate(raw("INDIVIDUAL_EMPLOYED", DE_LOW, List.of(DE_LOW), black, "DE",
                "BRANCH", false, "NONE", false, false, false), Jurisdiction.DE), FactorCategory.GEOGRAPHIC))
                .isEqualTo(100);
        assertThat(points(evaluate(raw("INDIVIDUAL_EMPLOYED", grey, List.of(DE_LOW), DE_LOW, "TR",
                "BRANCH", false, "NONE", false, false, false), Jurisdiction.DE), FactorCategory.GEOGRAPHIC))
                .isEqualTo(50);
    }

    @Test
    @DisplayName("a login country different from the residence adds 30 geographic points: grey list plus mismatch is 80 (BR-14)")
    void shouldAddThirtyPointsForALoginMismatch() {
        var grey = rated("TR", "FATF_GREY_LIST");
        var result = evaluate(raw("INDIVIDUAL_EMPLOYED", DE_LOW, List.of(grey), DE_LOW, "FR", "BRANCH", false,
                "NONE", false, false, false), Jurisdiction.DE);

        assertThat(points(result, FactorCategory.GEOGRAPHIC)).isEqualTo(80);
        assertThat(result.explanation().drivers()).contains("GEOGRAPHIC:FATF_GREY_LIST",
                "GEOGRAPHIC:LOGIN_COUNTRY_MISMATCH");
    }

    @Test
    @DisplayName("the geographic category never exceeds 100: black list plus mismatch is 100 (BR-14)")
    void shouldCapTheGeographicCategoryAtOneHundred() {
        var black = rated("SY", "FATF_BLACK_LIST_OR_CONFLICT_ZONE");
        var result = evaluate(raw("INDIVIDUAL_EMPLOYED", black, List.of(DE_LOW), DE_LOW, "FR", "BRANCH", false,
                "NONE", false, false, false), Jurisdiction.DE);

        assertThat(points(result, FactorCategory.GEOGRAPHIC)).isEqualTo(100);
    }

    @Test
    @DisplayName("products and channels takes the higher of the channel and the product request, never the sum (BR-17, A-15)")
    void shouldTakeTheHigherOfChannelAndProductRequest() {
        assertThat(points(evaluate(raw("INDIVIDUAL_EMPLOYED", DE_LOW, List.of(DE_LOW), DE_LOW, "DE", "REMOTE",
                false, "NONE", false, false, false), Jurisdiction.DE), FactorCategory.PRODUCTS_AND_CHANNELS))
                .isEqualTo(40);
        assertThat(points(evaluate(raw("INDIVIDUAL_EMPLOYED", DE_LOW, List.of(DE_LOW), DE_LOW, "DE", "REMOTE",
                true, "NONE", false, false, false), Jurisdiction.DE), FactorCategory.PRODUCTS_AND_CHANNELS))
                .isEqualTo(60);
        assertThat(points(evaluate(raw("INDIVIDUAL_EMPLOYED", DE_LOW, List.of(DE_LOW), DE_LOW, "DE", "BRANCH",
                true, "NONE", false, false, false), Jurisdiction.DE), FactorCategory.PRODUCTS_AND_CHANNELS))
                .isEqualTo(60);
    }

    // --------------------------------------------------------------- jurisdictions

    @Test
    @DisplayName("residence in the booking centre's country is a resident (1.00); anywhere else is a non-resident (1.25) (BR-21)")
    void shouldDecideNonResidenceAgainstTheBookingCentre() {
        var us = rated("US", "LOW");
        var inUs = evaluate(raw("INDIVIDUAL_EMPLOYED", us, List.of(us), us, "US", "BRANCH", false, "NONE",
                false, false, false), Jurisdiction.US);
        var germanInUs = evaluate(e1(), Jurisdiction.US);
        var gb = rated("GB", "LOW");
        var inGb = evaluate(raw("INDIVIDUAL_EMPLOYED", gb, List.of(gb), gb, "GB", "BRANCH", false, "NONE",
                false, false, false), Jurisdiction.GB);

        assertThat(inUs.explanation().multiplier()).isEqualTo(new BigDecimal("1.00"));
        assertThat(inUs.policy().jurisdiction()).isEqualTo(Jurisdiction.US);
        assertThat(germanInUs.explanation().multiplier()).isEqualTo(new BigDecimal("1.25"));
        assertThat(inGb.explanation().multiplier()).isEqualTo(new BigDecimal("1.00"));
        assertThat(inGb.policy().jurisdiction()).isEqualTo(Jurisdiction.GB);
    }

    @Test
    @DisplayName("the policy applied is recorded with the evaluation (BR-27)")
    void shouldRecordThePolicyApplied() {
        assertThat(evaluate(e1(), Jurisdiction.GB).policy()).isEqualTo(JurisdictionPolicy.of(Jurisdiction.GB));
    }

    // ------------------------------------------------------------------- the tracks

    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource({"0.00,GREEN", "30.99,GREEN", "31.00,YELLOW", "60.99,YELLOW", "61.00,ORANGE", "84.99,ORANGE",
            "85.00,RED", "100.00,RED"})
    @DisplayName("a total on a threshold belongs to the higher track: 31.00 is Yellow, 61.00 Orange, 85.00 Red (BR-24, A-20)")
    void shouldPlaceTheBoundaries(String total, RiskTrack expected) {
        assertThat(Evaluation.trackFor(JurisdictionPolicy.of(Jurisdiction.DE), new BigDecimal(total)))
                .isEqualTo(expected);
    }

    // ----------------------------------------------------------- explanation and purity

    @Test
    @DisplayName("recomputing the total from the explanation alone gives exactly the recorded total (BR-26)")
    void shouldMakeEveryTotalExplainable() {
        var black = rated("IR", "FATF_BLACK_LIST_OR_CONFLICT_ZONE");
        assertExplanationRecomputesTheTotal(evaluate(e1(), Jurisdiction.DE));
        assertExplanationRecomputesTheTotal(evaluate(e3(DE_LOW), Jurisdiction.DE));
        assertExplanationRecomputesTheTotal(evaluate(e3(rated("FR", "LOW")), Jurisdiction.DE));
        assertExplanationRecomputesTheTotal(evaluate(raw("INDIVIDUAL_STUDENT_OR_NO_INCOME", black, List.of(black),
                black, "DE", "REMOTE", true, "RADICALISATION_OR_PARAMILITARY", false, false, false), Jurisdiction.DE));
        assertExplanationRecomputesTheTotal(evaluate(raw("INDIVIDUAL_EMPLOYED", DE_LOW, List.of(DE_LOW), DE_LOW,
                "DE", "BRANCH", false, "NONE", false, true, false), Jurisdiction.DE));
    }

    @Test
    @DisplayName("scoring is a pure function: the same inputs give an equal evaluation")
    void shouldBeAPureFunction() {
        assertThat(evaluate(e3(DE_LOW), Jurisdiction.DE)).isEqualTo(evaluate(e3(DE_LOW), Jurisdiction.DE));
    }

    @Test
    @DisplayName("an evaluation is confidential: its toString shows no score and no track (BR-43)")
    void shouldNeverPrintTheEvaluation() {
        var printed = evaluate(e3(DE_LOW), Jurisdiction.DE).toString();

        assertThat(printed).doesNotContain("51.00").doesNotContain("YELLOW").doesNotContain("POLITICALLY");
    }
}
