package com.gs5.kycaml.risk.adapters.web;

/** What a successful evaluation or decision answers: the customer and the new version of the profile. */
record RiskVersionResponse(String customerNumber, long version) {
}
