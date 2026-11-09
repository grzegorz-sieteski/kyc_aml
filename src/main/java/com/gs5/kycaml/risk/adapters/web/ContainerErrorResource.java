package com.gs5.kycaml.risk.adapters.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Answers every error the container produces outside the four resources (R-08, design addendum D):
 * an unknown path, a method a route does not take, anything forwarded to the error path. Spring
 * Boot's default answer echoes the request path, and so the customer number in it; this one is a
 * constant RFC 7807 problem with no path, message, exception or trace.
 *
 * <p>The status is the servlet error status when it is a client or server error, and 404 otherwise
 * (a direct request to the error path has none). Being an {@link ErrorController} bean, it replaces
 * Spring Boot's own.
 */
@RestController
@RequestMapping("${spring.web.error.path:${error.path:/error}}")
class ContainerErrorResource implements ErrorController {

    private static final String INSTANCE = "/api/risk-profiles";

    @RequestMapping
    ResponseEntity<Object> error(HttpServletRequest request) {
        var status = Optional.ofNullable(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE))
                .filter(Integer.class::isInstance)
                .map(Integer.class::cast)
                .flatMap(code -> Optional.ofNullable(HttpStatus.resolve(code)))
                .filter(resolved -> resolved.is4xxClientError() || resolved.is5xxServerError())
                .orElse(HttpStatus.NOT_FOUND);
        return ProblemResponses.of(status, "request-not-handled", "The request was not handled.", INSTANCE);
    }
}
