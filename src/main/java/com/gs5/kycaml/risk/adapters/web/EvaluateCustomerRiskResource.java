package com.gs5.kycaml.risk.adapters.web;

import com.gs5.kycaml.risk.adapters.access.AccessRefusal;
import com.gs5.kycaml.risk.adapters.access.Admission;
import com.gs5.kycaml.risk.adapters.access.OperationGate;
import com.gs5.kycaml.risk.adapters.access.RiskPermissions;
import com.gs5.kycaml.risk.domain.EvaluateCustomerRiskCommand;
import com.gs5.kycaml.risk.domain.EvaluateCustomerRiskError;
import com.gs5.kycaml.risk.domain.EvaluateCustomerRiskUseCase;
import com.gs5.kycaml.risk.domain.RawEvaluateCustomerRisk;
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
import tools.jackson.databind.json.JsonMapper;

/** Evaluate a customer's risk: the onboarding system's door into the scoring core. */
@RestController
@RequestMapping("/api/risk-profiles")
class EvaluateCustomerRiskResource {

    private static final String INSTANCE = "/api/risk-profiles/evaluations";

    private final EvaluateCustomerRiskUseCase useCase;
    private final ActorContextResolver actorContextResolver;
    private final JsonMapper json;
    private final OperationGate<EvaluateCustomerRiskCommand, Result<RiskProfileVersion, EvaluateCustomerRiskError>> gate;

    EvaluateCustomerRiskResource(EvaluateCustomerRiskUseCase useCase, ActorContextResolver actorContextResolver,
                                 JsonMapper json) {
        this.useCase = useCase;
        this.actorContextResolver = actorContextResolver;
        this.json = json;
        this.gate = new OperationGate<>(RiskPermissions.EVALUATE, this::evaluateForActor);
    }

    /**
     * The customer number comes from the path and the rest from the body; both are read only after
     * the caller is admitted, so a caller who may not evaluate learns nothing from the input.
     */
    @PostMapping("/{customerNumber}/evaluations")
    ResponseEntity<Object> evaluate(@PathVariable String customerNumber, @RequestBody String body) {
        return gate.admit(actorContextResolver.resolve())
                .fold(admission -> evaluateAdmitted(admission, customerNumber, body),
                        access -> refused(new Refusal.Access(access)));
    }

    private Result<RiskProfileVersion, EvaluateCustomerRiskError> evaluateForActor(
            ActorContext actor, EvaluateCustomerRiskCommand command) {
        return useCase.execute(actor, command);
    }

    private ResponseEntity<Object> evaluateAdmitted(
            Admission<EvaluateCustomerRiskCommand, Result<RiskProfileVersion, EvaluateCustomerRiskError>> admission,
            String customerNumber, String body) {
        var raw = RequestBodies.read(json, body, RawEvaluateCustomerRisk.class)
                .orElseGet(() -> new RawEvaluateCustomerRisk(null, null, null, null, null, null, null, null, null,
                        null, null, null));
        return EvaluateCustomerRiskCommand.parse(withCustomerNumber(raw, customerNumber))
                .fold(command -> admission.run(command)
                                .fold(version -> succeeded(command, version),
                                        error -> refused(new Refusal.Rejected(error))),
                        error -> refused(new Refusal.Rejected(error)));
    }

    private static RawEvaluateCustomerRisk withCustomerNumber(RawEvaluateCustomerRisk raw, String customerNumber) {
        return new RawEvaluateCustomerRisk(customerNumber, raw.profileClass(), raw.residence(), raw.citizenships(),
                raw.birth(), raw.loginCountry(), raw.channel(), raw.highRiskProductRequest(),
                raw.adverseMediaClass(), raw.sanctionsMatch(), raw.terroristLink(), raw.forgedIdentity());
    }

    private static ResponseEntity<Object> succeeded(EvaluateCustomerRiskCommand command, RiskProfileVersion version) {
        return ResponseEntity.ok(new RiskVersionResponse(command.customerNumber().value(), version.value()));
    }

    private sealed interface Refusal {
        record Access(AccessRefusal refusal) implements Refusal {
        }

        record Rejected(EvaluateCustomerRiskError error) implements Refusal {
        }
    }

    /** The one exhaustive mapping of every refusal of this operation (design section 10). */
    private static ResponseEntity<Object> refused(Refusal refusal) {
        return switch (refusal) {
            case Refusal.Access(AccessRefusal.CallerNotIdentified _) -> problem(HttpStatus.UNAUTHORIZED,
                    "caller-not-identified", "The caller could not be identified.");
            case Refusal.Access(AccessRefusal.PermissionDenied(var role)) -> problem(HttpStatus.FORBIDDEN,
                    "permission-denied", "The role " + role + " may not evaluate customer risk.");
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
            case Refusal.Rejected(EvaluateCustomerRiskError.BookingCentreUnknown _) -> problem(
                    HttpStatus.UNPROCESSABLE_ENTITY, "booking-centre-unknown",
                    "The caller's booking centre has no jurisdiction configured.");
        };
    }

    private static ResponseEntity<Object> invalid(String code, String detail) {
        return problem(HttpStatus.BAD_REQUEST, code, detail);
    }

    private static ResponseEntity<Object> problem(HttpStatus status, String code, String detail) {
        return ProblemResponses.of(status, code, detail, INSTANCE);
    }
}
