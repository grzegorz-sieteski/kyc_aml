package com.gs5.kycaml.risk.domain;

import com.gs5.kycaml.shared.kernel.Result;

/** A request for the compliance view of one customer. */
public record ComplianceRiskViewQuery(CustomerNumber customerNumber) {

    /** A text that cannot be a customer number names no customer (BR-04, BR-06). */
    @SuppressWarnings("NullAway")
    public static Result<ComplianceRiskViewQuery, ComplianceRiskViewError> parse(String rawCustomerNumber) {
        return null;
    }
}
