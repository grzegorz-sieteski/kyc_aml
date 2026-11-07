package com.gs5.kycaml.risk.domain;

/** Every reason the compliance view can be refused, except the two the access gate decides. */
public sealed interface ComplianceRiskViewError {

    record CustomerNotFound() implements ComplianceRiskViewError { }
}
