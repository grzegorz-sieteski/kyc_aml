package com.gs5.kycaml.risk.domain;

import com.gs5.kycaml.shared.kernel.Result;

/** A request for the front-office status of one customer. */
public record FrontOfficeStatusQuery(CustomerNumber customerNumber) {

    /** A text that cannot be a customer number names no customer (BR-04, BR-06). */
    public static Result<FrontOfficeStatusQuery, FrontOfficeStatusError> parse(String rawCustomerNumber) {
        return CustomerNumber.parse(rawCustomerNumber)
                .map(FrontOfficeStatusQuery::new)
                .mapError(_ -> new FrontOfficeStatusError.CustomerNotFound());
    }
}
