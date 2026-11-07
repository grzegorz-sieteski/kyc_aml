package com.gs5.kycaml.risk.adapters.persistence;

import com.gs5.kycaml.risk.domain.AccountRestriction;
import com.gs5.kycaml.risk.domain.ComplianceRiskView;
import com.gs5.kycaml.risk.domain.EvaluateCustomerRiskCommand;
import com.gs5.kycaml.risk.domain.RatedCountry;
import com.gs5.kycaml.risk.domain.RecordedFact;
import com.gs5.kycaml.risk.domain.RiskDomainEvent;
import com.gs5.kycaml.risk.domain.RiskProfileSnapshot;
import com.gs5.kycaml.risk.domain.RiskTrack;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the documents stored beside a profile: the history fact, the announced event and the
 * compliance view as of that version. Value objects with private constructors cannot be serialised,
 * so they are written out as their text here.
 */
final class OutboxDocuments {

    private OutboxDocuments() {
    }

    /** The fact appended to the history: for an evaluation, the inputs and the result. */
    static Map<String, Object> historyFact(RecordedFact fact) {
        return switch (fact) {
            case RecordedFact.EvaluationFact(var inputs, var evaluation, _, _) -> {
                var document = new LinkedHashMap<String, Object>();
                document.put("inputs", inputsOf(inputs));
                document.put("evaluation", evaluation);
                yield document;
            }
            case RecordedFact.DecisionFact(var decision) -> Map.of("decision", decision);
        };
    }

    /** The announced fact. It never carries a justification (A-31) or the internal identity (D-05). */
    static Map<String, Object> payload(RiskDomainEvent event) {
        var document = new LinkedHashMap<String, Object>();
        document.put("eventId", event.eventId());
        document.put("tenantId", event.tenantId());
        document.put("customerNumber", event.customerNumber().value());
        document.put("version", event.version().value());
        document.put("occurredAt", event.occurredAt());
        switch (event) {
            case RiskDomainEvent.CustomerRiskEvaluated evaluated -> {
                document.put("evaluation", evaluated.evaluation());
                document.put("decidedTrack", evaluated.decidedTrack());
                document.put("effectiveTrack", evaluated.effectiveTrack());
                document.put("restriction", evaluated.restriction());
            }
            case RiskDomainEvent.ComplianceDecisionRecorded decided -> {
                document.put("kind", decided.kind());
                document.put("decidedTrack", decided.decidedTrack());
                document.put("effectiveTrack", decided.effectiveTrack());
                document.put("restriction", decided.restriction());
                document.put("decidedBy", decided.decidedBy());
            }
        }
        return document;
    }

    /** The compliance view as of the version the event announces: for the in-process projector only. */
    static ComplianceRiskView view(RiskProfileSnapshot snapshot, RiskDomainEvent event) {
        var evaluation = snapshot.latestEvaluation();
        return new ComplianceRiskView(snapshot.customerNumber().value(), evaluation.policy(),
                evaluation.totalScore(), evaluation.evaluatedTrack(), decidedTrackOf(event),
                effectiveTrackOf(event), restrictionOf(event), evaluation.explanation().criticalFacts(),
                evaluation.explanation(), snapshot.latestDecision(), snapshot.evaluatedAt(),
                event.version().value());
    }

    static String eventType(RiskDomainEvent event) {
        return switch (event) {
            case RiskDomainEvent.CustomerRiskEvaluated _ -> "CustomerRiskEvaluated";
            case RiskDomainEvent.ComplianceDecisionRecorded _ -> "ComplianceDecisionRecorded";
        };
    }

    private static @Nullable RiskTrack decidedTrackOf(RiskDomainEvent event) {
        return switch (event) {
            case RiskDomainEvent.CustomerRiskEvaluated evaluated -> evaluated.decidedTrack();
            case RiskDomainEvent.ComplianceDecisionRecorded decided -> decided.decidedTrack();
        };
    }

    private static RiskTrack effectiveTrackOf(RiskDomainEvent event) {
        return switch (event) {
            case RiskDomainEvent.CustomerRiskEvaluated evaluated -> evaluated.effectiveTrack();
            case RiskDomainEvent.ComplianceDecisionRecorded decided -> decided.effectiveTrack();
        };
    }

    private static AccountRestriction restrictionOf(RiskDomainEvent event) {
        return switch (event) {
            case RiskDomainEvent.CustomerRiskEvaluated evaluated -> evaluated.restriction();
            case RiskDomainEvent.ComplianceDecisionRecorded decided -> decided.restriction();
        };
    }

    private static Map<String, Object> inputsOf(EvaluateCustomerRiskCommand inputs) {
        var document = new LinkedHashMap<String, Object>();
        document.put("customerNumber", inputs.customerNumber().value());
        document.put("profileClass", inputs.profileClass());
        document.put("residence", countryOf(inputs.residence()));
        document.put("citizenships", inputs.citizenships().stream().map(OutboxDocuments::countryOf).toList());
        document.put("birth", countryOf(inputs.birth()));
        document.put("loginCountry", inputs.loginCountry().value());
        document.put("channel", inputs.channel());
        document.put("highRiskProductRequest", inputs.highRiskProductRequest());
        document.put("adverseMedia", inputs.adverseMedia());
        document.put("criticalFacts", inputs.criticalFacts());
        return document;
    }

    private static Map<String, Object> countryOf(RatedCountry country) {
        return Map.of("country", country.code().value(), "riskClass", country.riskClass());
    }
}
