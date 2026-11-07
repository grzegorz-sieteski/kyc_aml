package com.gs5.kycaml.risk.adapters.web;

import com.gs5.kycaml.risk.adapters.access.AccessRefusal;
import com.gs5.kycaml.risk.adapters.access.OperationGate;
import com.gs5.kycaml.risk.adapters.access.RiskPermissions;
import com.gs5.kycaml.risk.domain.ComplianceRiskView;
import com.gs5.kycaml.risk.domain.ComplianceRiskViewError;
import com.gs5.kycaml.risk.domain.ComplianceRiskViewQuery;
import com.gs5.kycaml.risk.domain.GetComplianceRiskViewUseCase;
import com.gs5.kycaml.shared.kernel.ActorContext;
import com.gs5.kycaml.shared.kernel.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The compliance view of a customer's risk: for the AML analysts and the MLRO only. */
@RestController
@RequestMapping("/api/risk-profiles")
class ComplianceRiskViewResource {

    private static final String INSTANCE = "/api/risk-profiles/compliance-view";

    private final GetComplianceRiskViewUseCase useCase;
    private final ActorContextResolver actorContextResolver;
    private final OperationGate<ComplianceRiskViewQuery, Result<ComplianceRiskView, ComplianceRiskViewError>> gate;

    ComplianceRiskViewResource(GetComplianceRiskViewUseCase useCase, ActorContextResolver actorContextResolver) {
        this.useCase = useCase;
        this.actorContextResolver = actorContextResolver;
        this.gate = new OperationGate<>(RiskPermissions.COMPLIANCE_VIEW, this::askForActor);
    }

    @GetMapping("/{customerNumber}")
    ResponseEntity<Object> view(@PathVariable String customerNumber) {
        return gate.admit(actorContextResolver.resolve())
                .fold(admission -> ComplianceRiskViewQuery.parse(customerNumber)
                                .fold(query -> admission.run(query)
                                                .fold(ResponseEntity::<Object>ok,
                                                        error -> refused(new Refusal.Rejected(error))),
                                        error -> refused(new Refusal.Rejected(error))),
                        access -> refused(new Refusal.Access(access)));
    }

    private Result<ComplianceRiskView, ComplianceRiskViewError> askForActor(
            ActorContext actor, ComplianceRiskViewQuery query) {
        return useCase.ask(actor, query);
    }

    private sealed interface Refusal {
        record Access(AccessRefusal refusal) implements Refusal {
        }

        record Rejected(ComplianceRiskViewError error) implements Refusal {
        }
    }

    /** The one exhaustive mapping of every refusal of this operation (design section 10). */
    private static ResponseEntity<Object> refused(Refusal refusal) {
        return switch (refusal) {
            case Refusal.Access(AccessRefusal.CallerNotIdentified _) -> problem(HttpStatus.UNAUTHORIZED,
                    "caller-not-identified", "The caller could not be identified.");
            case Refusal.Access(AccessRefusal.PermissionDenied(var role)) -> problem(HttpStatus.FORBIDDEN,
                    "permission-denied", "The role " + role + " may not view the compliance view.");
            case Refusal.Rejected(ComplianceRiskViewError.CustomerNotFound _) -> problem(HttpStatus.NOT_FOUND,
                    "customer-not-found", "The customer was not found.");
        };
    }

    private static ResponseEntity<Object> problem(HttpStatus status, String code, String detail) {
        return ProblemResponses.of(status, code, detail, INSTANCE);
    }
}
