package com.gs5.kycaml.risk.adapters.web;

import com.gs5.kycaml.risk.adapters.access.AccessRefusal;
import com.gs5.kycaml.risk.adapters.access.Admission;
import com.gs5.kycaml.risk.adapters.access.OperationGate;
import com.gs5.kycaml.risk.adapters.access.RiskPermissions;
import com.gs5.kycaml.risk.domain.BookingCentres;
import com.gs5.kycaml.risk.domain.EvaluateCustomerRiskCommand;
import com.gs5.kycaml.risk.domain.EvaluateCustomerRiskError;
import com.gs5.kycaml.risk.domain.EvaluateCustomerRiskUseCase;
import com.gs5.kycaml.risk.domain.RawEvaluateCustomerRisk;
import com.gs5.kycaml.risk.domain.RiskProfileVersion;
import com.gs5.kycaml.shared.kernel.ActorContext;
import com.gs5.kycaml.shared.kernel.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Evaluate a customer's risk: the onboarding system's door into the scoring core. */
@RestController
@RequestMapping("/api/risk-profiles")
class EvaluateCustomerRiskResource {

    private static final String INSTANCE = "/api/risk-profiles/evaluations";

    private final EvaluateCustomerRiskUseCase useCase;
    private final ActorContextResolver actorContextResolver;
    private final BookingCentres bookingCentres;
    private final OperationGate<EvaluateCustomerRiskCommand, Result<RiskProfileVersion, EvaluateCustomerRiskError>> gate;

    EvaluateCustomerRiskResource(EvaluateCustomerRiskUseCase useCase, ActorContextResolver actorContextResolver,
                                 BookingCentres bookingCentres) {
        this.useCase = useCase;
        this.actorContextResolver = actorContextResolver;
        this.bookingCentres = bookingCentres;
        this.gate = new OperationGate<>(RiskPermissions.EVALUATE, this::evaluateForActor);
    }

    /**
     * In the order of design addendum A: the caller is identified (401), then admitted for its role
     * (403); only then is the body read, bounded (413) and strictly (400). The customer number comes
     * from the raw path (R-03) and the rest from the body.
     */
    @PostMapping("/{customerNumber}/evaluations")
    ResponseEntity<Object> evaluate(HttpServletRequest request) {
        return gate.admit(actorContextResolver.identify(bookingCentres))
                .fold(admission -> BoundedBody.read(request, BoundedBody.EVALUATION_LIMIT)
                                .fold(body -> evaluateAdmitted(admission,
                                                RequestBodies.evaluation(RawCustomerNumber.of(request), body)),
                                        refusal -> refused(new Refusal.Body(refusal))),
                        access -> refused(new Refusal.Access(access)));
    }

    private Result<RiskProfileVersion, EvaluateCustomerRiskError> evaluateForActor(
            ActorContext actor, EvaluateCustomerRiskCommand command) {
        return useCase.execute(actor, command);
    }

    private static ResponseEntity<Object> evaluateAdmitted(
            Admission<EvaluateCustomerRiskCommand, Result<RiskProfileVersion, EvaluateCustomerRiskError>> admission,
            RawEvaluateCustomerRisk raw) {
        return EvaluateCustomerRiskCommand.parse(raw)
                .fold(command -> admission.run(command)
                                .fold(version -> succeeded(command, version),
                                        error -> refused(new Refusal.Rejected(error))),
                        error -> refused(new Refusal.Rejected(error)));
    }

    private static ResponseEntity<Object> succeeded(EvaluateCustomerRiskCommand command, RiskProfileVersion version) {
        return ProblemResponses.ok(new RiskVersionResponse(command.customerNumber().value(), version.value()));
    }

    private sealed interface Refusal {
        record Access(AccessRefusal refusal) implements Refusal {
        }

        record Body(BodyRefusal refusal) implements Refusal {
        }

        record Rejected(EvaluateCustomerRiskError error) implements Refusal {
        }
    }

    /** The one exhaustive mapping of every refusal of this operation (design section 10, addendum B). */
    private static ResponseEntity<Object> refused(Refusal refusal) {
        return switch (refusal) {
            case Refusal.Access(var access) -> ProblemResponses.access(access, "evaluate customer risk", INSTANCE);
            case Refusal.Body(var body) -> ProblemResponses.body(body, INSTANCE);
            case Refusal.Rejected(EvaluateCustomerRiskError.CustomerNumberMissing _) -> invalid(
                    "customer-number-missing", "The customer number is missing.");
            case Refusal.Rejected(EvaluateCustomerRiskError.CustomerNumberTooLong _) -> invalid(
                    "customer-number-too-long", "The customer number is too long.");
            case Refusal.Rejected(EvaluateCustomerRiskError.CustomerNumberControlCharacter _) -> invalid(
                    "customer-number-control-character", "The customer number contains a control character.");
            case Refusal.Rejected(EvaluateCustomerRiskError.ProfileClassNotRecognised _) -> invalid(
                    "profile-class-not-recognised", "The customer profile class is not recognised.");
            case Refusal.Rejected(EvaluateCustomerRiskError.CountryInvalid _) -> invalid(
                    "country-invalid", "A country code is not a valid ISO 3166-1 code.");
            case Refusal.Rejected(EvaluateCustomerRiskError.NoCitizenship _) -> invalid(
                    "no-citizenship", "At least one citizenship is required.");
            case Refusal.Rejected(EvaluateCustomerRiskError.TooManyCitizenships _) -> invalid(
                    "too-many-citizenships", "Too many citizenships are given.");
            case Refusal.Rejected(EvaluateCustomerRiskError.GeographicClassInvalid _) -> invalid(
                    "geographic-class-invalid", "A geographic risk class is not recognised.");
            case Refusal.Rejected(EvaluateCustomerRiskError.ChannelNotRecognised _) -> invalid(
                    "channel-not-recognised", "The onboarding channel is not recognised.");
            case Refusal.Rejected(EvaluateCustomerRiskError.ProductRequestMissing _) -> invalid(
                    "product-request-missing", "Whether a high-risk product was requested is missing.");
            case Refusal.Rejected(EvaluateCustomerRiskError.AdverseMediaClassNotRecognised _) -> invalid(
                    "adverse-media-class-not-recognised", "The adverse media class is not recognised.");
            case Refusal.Rejected(EvaluateCustomerRiskError.CriticalFactMissing _) -> invalid(
                    "critical-fact-missing", "A critical fact answer is missing.");
            case Refusal.Rejected(EvaluateCustomerRiskError.RiskProfileChangedConcurrently _) -> problem(
                    HttpStatus.CONFLICT, "risk-profile-changed-concurrently",
                    "The risk profile was changed at the same time. Evaluate again.");
            // Defence in depth: identify() already refuses such a caller. Same answer, same reason (BR-01).
            case Refusal.Rejected(EvaluateCustomerRiskError.BookingCentreUnknown _) ->
                    ProblemResponses.notIdentified();
        };
    }

    private static ResponseEntity<Object> invalid(String code, String detail) {
        return problem(HttpStatus.BAD_REQUEST, code, detail);
    }

    private static ResponseEntity<Object> problem(HttpStatus status, String code, String detail) {
        return ProblemResponses.of(status, code, detail, INSTANCE);
    }
}
