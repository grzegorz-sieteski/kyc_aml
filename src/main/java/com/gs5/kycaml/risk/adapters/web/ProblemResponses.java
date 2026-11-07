package com.gs5.kycaml.risk.adapters.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.net.URI;

/**
 * Builds the RFC 7807 problem every refusal is answered with. The detail is constant text, and the
 * {@code instance} is a constant given by the resource: Spring would otherwise copy the request
 * path into it and so echo the customer number back (design section 10).
 */
final class ProblemResponses {

    private static final String TYPE_PREFIX = "urn:problem-type:risk:";

    private ProblemResponses() {
    }

    static ResponseEntity<Object> of(HttpStatus status, String code, String detail, String instance) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + code));
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(instance));
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
