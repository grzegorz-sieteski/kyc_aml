package com.gs5.kycaml.risk.adapters.web;

/**
 * Why the edge refused a request body before any field was read (design addendum A, B). Decided at
 * the edge only: no use case ever sees a body that was refused here.
 */
sealed interface BodyRefusal {

    /** D-09: the body is larger than the limit of its operation. */
    record TooLarge() implements BodyRefusal {
    }

    /** D-12: the body is not exactly one well-formed JSON object, or it could not be read at all. */
    record NotReadable() implements BodyRefusal {
    }
}
