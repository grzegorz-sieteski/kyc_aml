package com.gs5.kycaml.risk.domain;

import com.gs5.kycaml.shared.kernel.ActorContext;
import com.gs5.kycaml.shared.kernel.Result;

/** Reads the compliance view of a customer's risk (query 1). */
public final class GetComplianceRiskViewUseCase {

    private final ComplianceRiskViewReader reader;

    public GetComplianceRiskViewUseCase(ComplianceRiskViewReader reader) {
        this.reader = reader;
    }

    /** Reads only within the caller's own booking centre (BR-39). */
    public Result<ComplianceRiskView, ComplianceRiskViewError> ask(
            ActorContext actor, ComplianceRiskViewQuery query) {
        return Result.<ComplianceRiskView, ComplianceRiskViewError>fromOptional(
                reader.find(actor.tenantId(), query.customerNumber()),
                ComplianceRiskViewError.CustomerNotFound::new);
    }
}
