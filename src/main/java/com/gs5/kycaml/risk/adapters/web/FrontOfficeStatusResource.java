package com.gs5.kycaml.risk.adapters.web;

import com.gs5.kycaml.risk.adapters.access.AccessRefusal;
import com.gs5.kycaml.risk.adapters.access.OperationGate;
import com.gs5.kycaml.risk.adapters.access.RiskPermissions;
import com.gs5.kycaml.risk.domain.FrontOfficeStatusError;
import com.gs5.kycaml.risk.domain.FrontOfficeStatusQuery;
import com.gs5.kycaml.risk.domain.FrontOfficeStatusView;
import com.gs5.kycaml.risk.domain.GetFrontOfficeStatusUseCase;
import com.gs5.kycaml.shared.kernel.ActorContext;
import com.gs5.kycaml.shared.kernel.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The front-office status: one of two sentences and nothing about risk (BR-40). Its refusals never
 * name risk either (BR-41), so no refusal text here mentions a score, a track or a restriction.
 */
@RestController
@RequestMapping("/api/risk-profiles")
class FrontOfficeStatusResource {

    private static final String INSTANCE = "/api/risk-profiles/front-office-status";

    private final GetFrontOfficeStatusUseCase useCase;
    private final ActorContextResolver actorContextResolver;
    private final OperationGate<FrontOfficeStatusQuery, Result<FrontOfficeStatusView, FrontOfficeStatusError>> gate;

    FrontOfficeStatusResource(GetFrontOfficeStatusUseCase useCase, ActorContextResolver actorContextResolver) {
        this.useCase = useCase;
        this.actorContextResolver = actorContextResolver;
        this.gate = new OperationGate<>(RiskPermissions.FRONT_OFFICE_STATUS, this::askForActor);
    }

    @GetMapping("/{customerNumber}/front-office-status")
    ResponseEntity<Object> status(@PathVariable String customerNumber) {
        return gate.admit(actorContextResolver.resolve())
                .fold(admission -> FrontOfficeStatusQuery.parse(customerNumber)
                                .fold(query -> admission.run(query)
                                                .fold(ResponseEntity::<Object>ok,
                                                        error -> refused(new Refusal.Rejected(error))),
                                        error -> refused(new Refusal.Rejected(error))),
                        access -> refused(new Refusal.Access(access)));
    }

    private Result<FrontOfficeStatusView, FrontOfficeStatusError> askForActor(
            ActorContext actor, FrontOfficeStatusQuery query) {
        return useCase.ask(actor, query);
    }

    private sealed interface Refusal {
        record Access(AccessRefusal refusal) implements Refusal {
        }

        record Rejected(FrontOfficeStatusError error) implements Refusal {
        }
    }

    /** The one exhaustive mapping of every refusal of this operation (design section 10). */
    private static ResponseEntity<Object> refused(Refusal refusal) {
        return switch (refusal) {
            case Refusal.Access(AccessRefusal.CallerNotIdentified _) -> problem(HttpStatus.UNAUTHORIZED,
                    "caller-not-identified", "The caller could not be identified.");
            case Refusal.Access(AccessRefusal.PermissionDenied _) -> problem(HttpStatus.FORBIDDEN,
                    "permission-denied", "The caller may not read this status.");
            case Refusal.Rejected(FrontOfficeStatusError.CustomerNotFound _) -> problem(HttpStatus.NOT_FOUND,
                    "customer-not-found", "The customer was not found.");
        };
    }

    private static ResponseEntity<Object> problem(HttpStatus status, String code, String detail) {
        return ProblemResponses.of(status, code, detail, INSTANCE);
    }
}
