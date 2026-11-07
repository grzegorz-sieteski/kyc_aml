package com.gs5.kycaml.risk.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

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

    private static final int MAX_CATEGORY_POINTS = 100;
    private static final int LOGIN_MISMATCH_POINTS = 30;
    private static final int HIGH_RISK_PRODUCT_POINTS = 60;
    private static final BigDecimal NO_POINTS = new BigDecimal("0.00");
    private static final BigDecimal STANDARD_MULTIPLIER = new BigDecimal("1.00");
    private static final BigDecimal BEHAVIOURAL_SCORE_IN_THIS_INCREMENT = NO_POINTS;

    /**
     * Scores the inputs under the policy of the booking centre's jurisdiction: a pure function of
     * its arguments (BR-14 to BR-27). Rounds once, half-up, on the total.
     */
    public static Evaluation of(EvaluateCustomerRiskCommand command, Jurisdiction bookingCentre) {
        var policy = JurisdictionPolicy.of(bookingCentre);
        var drivers = new ArrayList<String>();

        var highestClass = highestGeographicClass(command);
        var loginMismatch = !command.loginCountry().value().equals(command.residence().code().value());
        var geographicPoints = Math.min(MAX_CATEGORY_POINTS,
                highestClass.points() + (loginMismatch ? LOGIN_MISMATCH_POINTS : 0));
        if (highestClass.points() > 0) {
            drivers.add("GEOGRAPHIC:" + highestClass.name());
        }
        if (loginMismatch) {
            drivers.add("GEOGRAPHIC:LOGIN_COUNTRY_MISMATCH");
        }

        drivers.add("CUSTOMER_PROFILE:" + command.profileClass().name());

        if (command.adverseMedia().points() > 0) {
            drivers.add("ADVERSE_MEDIA:" + command.adverseMedia().name());
        }

        var productRequestPoints = command.highRiskProductRequest() ? HIGH_RISK_PRODUCT_POINTS : 0;
        var productsPoints = Math.max(command.channel().points(), productRequestPoints);
        drivers.add(productRequestPoints > command.channel().points()
                ? "PRODUCTS_AND_CHANNELS:HIGH_RISK_PRODUCT_REQUEST"
                : "PRODUCTS_AND_CHANNELS:" + command.channel().name());

        var categories = List.of(
                contribution(FactorCategory.GEOGRAPHIC, geographicPoints, policy.geographicWeight()),
                contribution(FactorCategory.CUSTOMER_PROFILE, command.profileClass().points(), policy.profileWeight()),
                contribution(FactorCategory.ADVERSE_MEDIA, command.adverseMedia().points(), policy.mediaWeight()),
                contribution(FactorCategory.PRODUCTS_AND_CHANNELS, productsPoints, policy.productsWeight()));
        var staticScore = categories.stream().map(CategoryContribution::contribution)
                .reduce(NO_POINTS, BigDecimal::add);

        var nonResident = !command.residence().code().value().equals(bookingCentre.countryCode());
        var multiplier = nonResident && policy.strictForNonResidents()
                ? policy.strictMultiplier() : STANDARD_MULTIPLIER;

        var criticalFacts = criticalFactsIn(command.criticalFacts());
        criticalFacts.forEach(fact -> drivers.add("CRITICAL_FACT:" + fact.name()));

        var total = criticalFacts.isEmpty()
                ? weightedTotal(policy, staticScore, multiplier)
                : MAX_SCORE;

        var scoreTrack = criticalFacts.isEmpty() ? trackFor(policy, total) : RiskTrack.RED;
        var floorApplied = command.profileClass() == CustomerProfileClass.CROSS_BORDER_NON_PROFIT
                && scoreTrack.compareTo(RiskTrack.ORANGE) < 0;
        var track = floorApplied ? RiskTrack.ORANGE : scoreTrack;

        var explanation = new Explanation(categories, List.copyOf(drivers), staticScore, policy.staticWeight(),
                BEHAVIOURAL_SCORE_IN_THIS_INCREMENT, policy.behaviouralWeight(), multiplier, criticalFacts,
                floorApplied);
        return new Evaluation(policy, total, track, explanation);
    }

    /**
     * The track a total gives under the policy: at or above a threshold belongs to the higher track
     * (BR-24, A-20). The non-profit floor of BR-25 is not applied here.
     */
    public static RiskTrack trackFor(JurisdictionPolicy policy, BigDecimal totalScore) {
        if (totalScore.compareTo(policy.redThreshold()) >= 0) {
            return RiskTrack.RED;
        }
        if (totalScore.compareTo(policy.orangeThreshold()) >= 0) {
            return RiskTrack.ORANGE;
        }
        if (totalScore.compareTo(policy.yellowThreshold()) >= 0) {
            return RiskTrack.YELLOW;
        }
        return RiskTrack.GREEN;
    }

    private static CategoryContribution contribution(FactorCategory category, int points, BigDecimal weight) {
        return new CategoryContribution(category, points, weight, weight.multiply(BigDecimal.valueOf(points)));
    }

    private static GeographicRiskClass highestGeographicClass(EvaluateCustomerRiskCommand command) {
        return Stream.concat(
                        Stream.of(command.residence(), command.birth()),
                        command.citizenships().stream())
                .map(RatedCountry::riskClass)
                .max(Comparator.comparingInt(GeographicRiskClass::points))
                .orElse(GeographicRiskClass.LOW);
    }

    private static List<CriticalFactField> criticalFactsIn(CriticalFacts facts) {
        var inForce = new ArrayList<CriticalFactField>();
        if (facts.sanctionsMatch()) {
            inForce.add(CriticalFactField.SANCTIONS_MATCH);
        }
        if (facts.terroristLink()) {
            inForce.add(CriticalFactField.TERRORIST_LINK);
        }
        if (facts.forgedIdentity()) {
            inForce.add(CriticalFactField.FORGED_IDENTITY);
        }
        return List.copyOf(inForce);
    }

    /** BR-22: rounded once, half-up, at the end; no intermediate value is rounded. */
    private static BigDecimal weightedTotal(JurisdictionPolicy policy, BigDecimal staticScore, BigDecimal multiplier) {
        var weighted = policy.staticWeight().multiply(staticScore)
                .add(policy.behaviouralWeight().multiply(BEHAVIOURAL_SCORE_IN_THIS_INCREMENT))
                .multiply(multiplier);
        return weighted.min(MAX_SCORE).setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    }

    /** Confidential (BR-43): never printed. */
    @Override
    public String toString() {
        return "Evaluation[***]";
    }
}
