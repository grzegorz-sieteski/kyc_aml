package com.gs5.kycaml.risk.adapters.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * The answer to a request the resource server refused before any resource ran: a bearer token that is
 * malformed, badly signed, expired, not yet valid, or for another issuer or audience (D-13, design addendum
 * 2026-11-09 (3)). It is the same constant BR-01 problem the resources give a caller they cannot identify,
 * with {@code Cache-Control: no-store} and a bare {@code WWW-Authenticate: Bearer} challenge. Neither the
 * exception nor anything from the token reaches the answer: a reason would be an oracle for someone probing
 * token validation.
 *
 * <p>The instance is the constant of the API root, like {@link ContainerErrorResource}: the refusal happens
 * before any route is chosen, and the path would echo the customer number.
 */
final class CallerNotIdentifiedAnswer implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String INSTANCE = "/api/risk-profiles";
    private static final String CHALLENGE = "Bearer";

    /** The default constructor registers Spring's ProblemDetail mixin, as the MVC converter does. */
    private final JacksonJsonHttpMessageConverter json = new JacksonJsonHttpMessageConverter();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException refused)
            throws IOException {
        answer(response);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException refused)
            throws IOException {
        answer(response);
    }

    private void answer(HttpServletResponse response) throws IOException {
        var out = new ServletServerHttpResponse(response);
        out.setStatusCode(HttpStatus.UNAUTHORIZED);
        out.getHeaders().setCacheControl(CacheControl.noStore());
        out.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE);
        json.write(ProblemResponses.notIdentifiedProblem(INSTANCE), MediaType.APPLICATION_PROBLEM_JSON, out);
    }
}
