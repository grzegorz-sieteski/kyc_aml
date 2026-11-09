package com.gs5.kycaml.risk.adapters.web;

import com.gs5.kycaml.risk.adapters.access.AccessRefusal;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.net.URI;

/**
 * Builds every answer of the risk resources: the success body and the RFC 7807 problem every refusal
 * is answered with. The detail is constant text, and the {@code instance} is a constant given by the
 * resource: Spring would otherwise copy the request path into it and so echo the customer number back
 * (design section 10). Every answer carries {@code Cache-Control: no-store} (R-18): none of them may
 * be kept by a browser or a proxy.
 */
final class ProblemResponses {

    private static final String TYPE_PREFIX = "urn:problem-type:risk:";

    private ProblemResponses() {
    }

    /** A successful answer, never cached. */
    static ResponseEntity<Object> ok(Object body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /** The one mapping of a refusal decided from the caller alone; the role it names identifies nobody. */
    static ResponseEntity<Object> access(AccessRefusal refusal, String action, String instance) {
        return switch (refusal) {
            case AccessRefusal.CallerNotIdentified _ -> notIdentified(instance);
            case AccessRefusal.PermissionDenied(var role) -> of(HttpStatus.FORBIDDEN, "permission-denied",
                    "The role " + role + " may not " + action + ".", instance);
        };
    }

    /** BR-01: the caller is not identified, which includes a caller of an unconfigured booking centre. */
    static ResponseEntity<Object> notIdentified(String instance) {
        return of(HttpStatus.UNAUTHORIZED, "caller-not-identified", "The caller could not be identified.", instance);
    }

    /** The one mapping of a body refused before any field was read (D-09, D-12). */
    static ResponseEntity<Object> body(BodyRefusal refusal, String instance) {
        return switch (refusal) {
            case BodyRefusal.TooLarge _ -> of(HttpStatus.CONTENT_TOO_LARGE, "request-too-large",
                    "The request is too large.", instance);
            case BodyRefusal.NotReadable _ -> of(HttpStatus.BAD_REQUEST, "request-not-readable",
                    "The request is not a single readable JSON object.", instance);
        };
    }

    static ResponseEntity<Object> of(HttpStatus status, String code, String detail, String instance) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + code));
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(instance));
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
