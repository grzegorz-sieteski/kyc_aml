package com.gs5.kycaml.risk.adapters.web;

import com.gs5.kycaml.risk.adapters.access.AccessRefusal;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
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
    private static final String NOT_IDENTIFIED = "caller-not-identified";
    private static final String NOT_IDENTIFIED_DETAIL = "The caller could not be identified.";
    private static final String NOT_IDENTIFIED_INSTANCE = "/api/risk-profiles";

    /** The whole challenge of a not-identified answer: the scheme, and no error, description or realm. */
    static final String BEARER_CHALLENGE = "Bearer";

    private ProblemResponses() {
    }

    /** A successful answer, never cached. */
    static ResponseEntity<Object> ok(Object body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /**
     * The one mapping of a refusal decided from the caller alone; the role it names identifies nobody.
     * The instance is that of a denied permission only: a caller who is not identified gets the one
     * answer of {@link #notIdentified()}, whatever the route.
     */
    static ResponseEntity<Object> access(AccessRefusal refusal, String action, String instance) {
        return switch (refusal) {
            case AccessRefusal.CallerNotIdentified _ -> notIdentified();
            case AccessRefusal.PermissionDenied(var role) -> of(HttpStatus.FORBIDDEN, "permission-denied",
                    "The role " + role + " may not " + action + ".", instance);
        };
    }

    /**
     * BR-01: the caller is not identified, which includes a caller of an unconfigured booking centre.
     * One answer for every route and every cause (I-04), the same bytes as {@link CallerNotIdentifiedAnswer}
     * writes for a token the resource server refused: the instance of the API root, a bare
     * {@code WWW-Authenticate: Bearer} and {@code no-store}. Were the two to differ, the difference would
     * tell a caller whether a token passed validation.
     */
    static ResponseEntity<Object> notIdentified() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(notIdentifiedProblem());
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
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem(status, code, detail, instance));
    }

    /**
     * The BR-01 problem body alone, for the one answer written outside a resource: a request whose bearer
     * token the resource server refused ({@link CallerNotIdentifiedAnswer}). Same body as
     * {@link #notIdentified}.
     */
    static ProblemDetail notIdentifiedProblem() {
        return problem(HttpStatus.UNAUTHORIZED, NOT_IDENTIFIED, NOT_IDENTIFIED_DETAIL, NOT_IDENTIFIED_INSTANCE);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail, String instance) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + code));
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(instance));
        return problem;
    }
}
