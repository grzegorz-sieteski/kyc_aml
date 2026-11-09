package com.gs5.kycaml.risk.adapters.web;

import com.gs5.kycaml.risk.adapters.access.AccessRefusal;
import com.gs5.kycaml.risk.adapters.access.Admission;
import com.gs5.kycaml.risk.adapters.access.AuthenticatedCaller;
import com.gs5.kycaml.risk.adapters.access.OperationGate;
import com.gs5.kycaml.risk.adapters.access.RiskPermissions;
import com.gs5.kycaml.risk.domain.BookingCentres;
import com.gs5.kycaml.risk.domain.DecisionKind;
import com.gs5.kycaml.risk.domain.RawRecordComplianceDecision;
import com.gs5.kycaml.risk.domain.RecordComplianceDecisionCommand;
import com.gs5.kycaml.risk.domain.RecordComplianceDecisionError;
import com.gs5.kycaml.risk.domain.RecordComplianceDecisionUseCase;
import com.gs5.kycaml.risk.domain.RiskProfileVersion;
import com.gs5.kycaml.shared.kernel.ActorContext;
import com.gs5.kycaml.shared.kernel.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/** Record a compliance decision: escalate, freeze or clear, each with the role it needs. */
@RestController
@RequestMapping("/api/risk-profiles")
class RecordComplianceDecisionResource {

    private static final String INSTANCE = "/api/risk-profiles/decisions";

    private final RecordComplianceDecisionUseCase useCase;
    private final ActorContextResolver actorContextResolver;
    private final BookingCentres bookingCentres;

    RecordComplianceDecisionResource(RecordComplianceDecisionUseCase useCase,
                                     ActorContextResolver actorContextResolver, BookingCentres bookingCentres) {
        this.useCase = useCase;
        this.actorContextResolver = actorContextResolver;
        this.bookingCentres = bookingCentres;
    }

    /**
     * In the order of BR-37 and design addendum A: the caller is identified (401); then the body is
     * read, bounded (413) and strictly (400); then the permission is chosen from the kind alone, read
     * on its own so no other field can change it (R-02), an unrecognised kind being judged as freezing
     * assets, the narrowest permission (D-08, 403). Only a caller who passed is told the kind is not
     * recognised, and only then is the rest of the command parsed and the use case run.
     */
    @PostMapping("/{customerNumber}/decisions")
    ResponseEntity<Object> decide(HttpServletRequest request) {
        return Result.<AuthenticatedCaller, AccessRefusal>fromOptional(
                        actorContextResolver.identify(bookingCentres), AccessRefusal.CallerNotIdentified::new)
                .fold(caller -> BoundedBody.read(request, BoundedBody.DECISION_LIMIT)
                                .fold(body -> decideFor(caller,
                                                RequestBodies.decision(RawCustomerNumber.of(request), body)),
                                        refusal -> refused(new Refusal.Body(refusal))),
                        access -> refused(new Refusal.Access(access)));
    }

    private ResponseEntity<Object> decideFor(AuthenticatedCaller caller, RawRecordComplianceDecision raw) {
        var permission = RiskPermissions.forDecision(DecisionKind.parse(raw.kind()).orElse(DecisionKind.FREEZE_ASSETS));
        return new OperationGate<RecordComplianceDecisionCommand,
                Result<RiskProfileVersion, RecordComplianceDecisionError>>(permission, this::decideForActor)
                .admit(Optional.of(caller))
                .fold(admission -> decideAdmitted(admission, raw),
                        access -> refused(new Refusal.Access(access)));
    }

    private Result<RiskProfileVersion, RecordComplianceDecisionError> decideForActor(
            ActorContext actor, RecordComplianceDecisionCommand command) {
        return useCase.execute(actor, command);
    }

    private static ResponseEntity<Object> decideAdmitted(
            Admission<RecordComplianceDecisionCommand, Result<RiskProfileVersion, RecordComplianceDecisionError>> admission,
            RawRecordComplianceDecision raw) {
        return RecordComplianceDecisionCommand.parse(raw)
                .fold(command -> admission.run(command)
                                .fold(version -> succeeded(command, version),
                                        error -> refused(new Refusal.Rejected(error))),
                        error -> refused(new Refusal.Rejected(error)));
    }

    private static ResponseEntity<Object> succeeded(RecordComplianceDecisionCommand command, RiskProfileVersion version) {
        return ProblemResponses.ok(new RiskVersionResponse(command.customerNumber().value(), version.value()));
    }

    private sealed interface Refusal {
        record Access(AccessRefusal refusal) implements Refusal {
        }

        record Body(BodyRefusal refusal) implements Refusal {
        }

        record Rejected(RecordComplianceDecisionError error) implements Refusal {
        }
    }

    /** The one exhaustive mapping of every refusal of this operation (design section 10, addendum B). */
    private static ResponseEntity<Object> refused(Refusal refusal) {
        return switch (refusal) {
            case Refusal.Access(var access) -> ProblemResponses.access(access, "record this decision", INSTANCE);
            case Refusal.Body(var body) -> ProblemResponses.body(body, INSTANCE);
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
            case Refusal.Rejected(RecordComplianceDecisionError.VersionViewedNotValid _) -> invalid(
                    "version-viewed-not-valid", "The version viewed is not a valid version.");
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
