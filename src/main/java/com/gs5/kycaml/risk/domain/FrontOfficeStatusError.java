package com.gs5.kycaml.risk.domain;

/**
 * Every reason the front-office status can be refused, except the caller not being identified.
 * Neither refers to risk (BR-41).
 */
public sealed interface FrontOfficeStatusError {

    record CustomerNotFound() implements FrontOfficeStatusError { }
}
