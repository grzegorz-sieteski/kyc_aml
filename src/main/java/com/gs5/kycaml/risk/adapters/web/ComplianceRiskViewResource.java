package com.gs5.kycaml.risk.adapters.web;

import com.gs5.kycaml.risk.adapters.access.AccessRefusal;
import com.gs5.kycaml.risk.adapters.access.OperationGate;
import com.gs5.kycaml.risk.adapters.access.RiskPermissions;
import com.gs5.kycaml.risk.domain.BookingCentres;
import com.gs5.kycaml.risk.domain.ComplianceRiskView;
import com.gs5.kycaml.risk.domain.ComplianceRiskViewError;
import com.gs5.kycaml.risk.domain.ComplianceRiskViewQuery;
import com.gs5.kycaml.risk.domain.GetComplianceRiskViewUseCase;
import com.gs5.kycaml.shared.kernel.ActorContext;
import com.gs5.kycaml.shared.kernel.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The compliance view of a customer's risk: for the AML analysts and the MLRO only. */
@RestController
@RequestMapping("/api/risk-profiles")
class ComplianceRiskViewResource {

    private static final String INSTANCE = "/api/risk-profiles/compliance-view";

    private final GetComplianceRiskViewUseCase useCase;
    private final ActorContextResolver actorContextResolver;
    private final BookingCentres bookingCentres;
    private final OperationGate<ComplianceRiskViewQuery, Result<ComplianceRiskView, ComplianceRiskViewError>> gate;

    ComplianceRiskViewResource(GetComplianceRiskViewUseCase useCase, ActorContextResolver actorContextResolver,
                               BookingCentres bookingCentres) {
        this.useCase = useCase;
        this.actorContextResolver = actorContextResolver;
        this.bookingCentres = bookingCentres;
        this.gate = new OperationGate<>(RiskPermissions.COMPLIANCE_VIEW, this::askForActor);
    }

    @GetMapping("/{customerNumber}")
    ResponseEntity<Object> view(HttpServletRequest request) {
        return gate.admit(actorContextResolver.identify(bookingCentres))
                .fold(admission -> ComplianceRiskViewQuery.parse(RawCustomerNumber.of(request))
                                .fold(query -> admission.run(query)
                                                .fold(ProblemResponses::ok,
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
            case Refusal.Access(var access) -> ProblemResponses.access(access, "view the compliance view", INSTANCE);
            case Refusal.Rejected(ComplianceRiskViewError.CustomerNotFound _) -> problem(HttpStatus.NOT_FOUND,
                    "customer-not-found", "The customer was not found.");
        };
    }

    private static ResponseEntity<Object> problem(HttpStatus status, String code, String detail) {
        return ProblemResponses.of(status, code, detail, INSTANCE);
    }
}
