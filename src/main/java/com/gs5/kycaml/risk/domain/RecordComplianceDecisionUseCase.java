package com.gs5.kycaml.risk.domain;

import com.gs5.kycaml.shared.kernel.ActorContext;
import com.gs5.kycaml.shared.kernel.Result;

import java.time.Clock;

/** Records a compliance decision on a customer's risk (command 2). */
public final class RecordComplianceDecisionUseCase {

    private final RiskProfileRepository repository;
    private final RiskProfileIdGenerator idGenerator;
    private final Clock clock;

    public RecordComplianceDecisionUseCase(RiskProfileRepository repository,
                                           RiskProfileIdGenerator idGenerator,
                                           Clock clock) {
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * Judges, in the order of BR-37: the customer is found in the caller's booking centre, the
     * justification is valid, the version viewed is current, no critical fact blocks a clearing,
     * the decision changes something, and no concurrent change is recorded. Success returns the new
     * version.
     */
    public Result<RiskProfileVersion, RecordComplianceDecisionError> execute(
            ActorContext actor, RecordComplianceDecisionCommand command) {
        return repository.findOwn(actor.tenantId(), command.customerNumber())
                .map(snapshot -> decideOn(snapshot, actor, command))
                .orElseGet(() -> Result.failure(new RecordComplianceDecisionError.CustomerNotFound()));
    }

    private Result<RiskProfileVersion, RecordComplianceDecisionError> decideOn(
            RiskProfileSnapshot snapshot, ActorContext actor, RecordComplianceDecisionCommand command) {
        return command.justification()
                .<RecordComplianceDecisionError>mapError(RecordComplianceDecisionUseCase::refusalOf)
                .flatMap(justification -> CustomerRiskProfile.reconstitute(snapshot).decide(
                        command.kind(), justification, command.versionViewed(), actor.actorId(),
                        clock.instant(), idGenerator.nextEventId()))
                .flatMap(outcome -> repository
                        .save(outcome.profile().toSnapshot(), outcome.fact(), outcome.events())
                        .<RecordComplianceDecisionError>mapError(
                                _ -> new RecordComplianceDecisionError.RiskProfileChangedConcurrently()));
    }

    private static RecordComplianceDecisionError refusalOf(JustificationError error) {
        return switch (error) {
            case JustificationError.Missing _ -> new RecordComplianceDecisionError.JustificationMissing();
            case JustificationError.TooShort(var min, var actual) ->
                    new RecordComplianceDecisionError.JustificationTooShort(min, actual);
            case JustificationError.TooLong(var max, var actual) ->
                    new RecordComplianceDecisionError.JustificationTooLong(max, actual);
            case JustificationError.ControlCharacter(var codePoint, var index) ->
                    new RecordComplianceDecisionError.JustificationControlCharacter(codePoint, index);
        };
    }
}
