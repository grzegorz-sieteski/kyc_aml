package com.gs5.kycaml.risk.adapters.web;

import com.gs5.kycaml.risk.adapters.access.AccessRefusal;
import com.gs5.kycaml.risk.adapters.access.Admission;
import com.gs5.kycaml.risk.adapters.access.OperationGate;
import com.gs5.kycaml.risk.adapters.access.RiskPermissions;
import com.gs5.kycaml.risk.domain.DecisionKind;
import com.gs5.kycaml.risk.domain.RawRecordComplianceDecision;
import com.gs5.kycaml.risk.domain.RecordComplianceDecisionCommand;
import com.gs5.kycaml.risk.domain.RecordComplianceDecisionError;
import com.gs5.kycaml.risk.domain.RecordComplianceDecisionUseCase;
import com.gs5.kycaml.risk.domain.RiskProfileVersion;
import com.gs5.kycaml.shared.kernel.ActorContext;
import com.gs5.kycaml.shared.kernel.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/** Record a compliance decision: escalate, freeze or clear, each with the role it needs. */
@RestController
@RequestMapping("/api/risk-profiles")
class RecordComplianceDecisionResource {

    private static final String INSTANCE = "/api/risk-profiles/decisions";

    private final RecordComplianceDecisionUseCase useCase;
    private final ActorContextResolver actorContextResolver;
    private final JsonMapper json;

    RecordComplianceDecisionResource(RecordComplianceDecisionUseCase useCase,
                                     ActorContextResolver actorContextResolver, JsonMapper json) {
        this.useCase = useCase;
        this.actorContextResolver = actorContextResolver;
        this.json = json;
    }

    /**
     * What a caller may do depends on the kind of decision, so the kind is read from the body to pick
     * the permission, and nothing else is examined until the caller is admitted. A kind that cannot
     * be read is checked against the narrowest permission, so an unidentified or unauthorised caller
     * is refused as such before being told the kind was wrong.
     */
    @PostMapping("/{customerNumber}/decisions")
    ResponseEntity<Object> decide(@PathVariable String customerNumber, @RequestBody(required = false) @Nullable String body) {
        var raw = RequestBodies.read(json, body, RawRecordComplianceDecision.class)
                .orElseGet(() -> new RawRecordComplianceDecision(null, null, null, null));
        var permission = RiskPermissions.forDecision(DecisionKind.parse(raw.kind()).orElse(DecisionKind.FREEZE_ASSETS));
        var gate = new OperationGate<RecordComplianceDecisionCommand,
                Result<RiskProfileVersion, RecordComplianceDecisionError>>(permission, this::decideForActor);
        return gate.admit(actorContextResolver.resolve())
                .fold(admission -> decideAdmitted(admission, customerNumber, raw),
                        access -> refused(new Refusal.Access(access)));
    }

    private Result<RiskProfileVersion, RecordComplianceDecisionError> decideForActor(
            ActorContext actor, RecordComplianceDecisionCommand command) {
        return useCase.execute(actor, command);
    }

    private static ResponseEntity<Object> decideAdmitted(
            Admission<RecordComplianceDecisionCommand, Result<RiskProfileVersion, RecordComplianceDecisionError>> admission,
            String customerNumber, RawRecordComplianceDecision raw) {
        var withNumber = new RawRecordComplianceDecision(customerNumber, raw.kind(), raw.justification(),
                raw.versionViewed());
        return RecordComplianceDecisionCommand.parse(withNumber)
                .fold(command -> admission.run(command)
                                .fold(version -> succeeded(command, version),
                                        error -> refused(new Refusal.Rejected(error))),
                        error -> refused(new Refusal.Rejected(error)));
    }

    private static ResponseEntity<Object> succeeded(RecordComplianceDecisionCommand command, RiskProfileVersion version) {
        return ResponseEntity.ok(new RiskVersionResponse(command.customerNumber().value(), version.value()));
    }

    private sealed interface Refusal {
        record Access(AccessRefusal refusal) implements Refusal {
        }

        record Rejected(RecordComplianceDecisionError error) implements Refusal {
        }
    }

    /** The one exhaustive mapping of every refusal of this operation (design section 10). */
    private static ResponseEntity<Object> refused(Refusal refusal) {
        return switch (refusal) {
            case Refusal.Access(var access) -> ProblemResponses.access(access, "record this decision", INSTANCE);
            case Refusal.Rejected(RecordComplianceDecisionError.DecisionKindNotRecognised _) -> invalid(
                    "decision-kind-not-recognised", "The decision kind is not recognised.");
            case Refusal.Rejected(RecordComplianceDecisionError.CustomerNotFound _) -> problem(
                    HttpStatus.NOT_FOUND, "customer-not-found", "The customer was not found.");
            case Refusal.Rejected(RecordComplianceDecisionError.JustificationMissing _) -> invalid(
                    "justification-missing", "A justification is required.");
            case Refusal.Rejected(RecordComplianceDecisionError.JustificationTooShort _) -> invalid(
                    "justification-too-short", "The justification is too short.");
            case Refusal.Rejected(RecordComplianceDecisionError.JustificationTooLong _) -> invalid(
                    "justification-too-long", "The justification is too long.");
            case Refusal.Rejected(RecordComplianceDecisionError.JustificationControlCharacter _) -> invalid(
                    "justification-control-character", "The justification contains a control character.");
            case Refusal.Rejected(RecordComplianceDecisionError.RiskProfileChangedSinceViewed _) -> problem(
                    HttpStatus.CONFLICT, "risk-profile-changed-since-viewed",
                    "The risk profile changed after it was viewed. View it again.");
            case Refusal.Rejected(RecordComplianceDecisionError.RiskProfileChangedConcurrently _) -> problem(
                    HttpStatus.CONFLICT, "risk-profile-changed-concurrently",
                    "The risk profile was changed at the same time. Try again.");
            case Refusal.Rejected(RecordComplianceDecisionError.CriticalFactInForce _) -> rule(
                    "critical-fact-in-force", "A critical fact is in force, so the customer cannot be cleared.");
            case Refusal.Rejected(RecordComplianceDecisionError.AlreadyFrozen _) -> rule(
                    "already-frozen", "The customer's assets are already frozen.");
            case Refusal.Rejected(RecordComplianceDecisionError.AlreadyEscalated _) -> rule(
                    "already-escalated", "The customer is already escalated.");
            case Refusal.Rejected(RecordComplianceDecisionError.NothingToClear _) -> rule(
                    "nothing-to-clear", "There is nothing to clear.");
        };
    }

    private static ResponseEntity<Object> invalid(String code, String detail) {
        return problem(HttpStatus.BAD_REQUEST, code, detail);
    }

    private static ResponseEntity<Object> rule(String code, String detail) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, code, detail);
    }

    private static ResponseEntity<Object> problem(HttpStatus status, String code, String detail) {
        return ProblemResponses.of(status, code, detail, INSTANCE);
    }
}
