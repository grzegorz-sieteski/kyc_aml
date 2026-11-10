package com.gs5.kycaml.risk.adapters.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Makes {@code GET /.well-known/oauth-protected-resource/**} a path this service does not have (I-07).
 * Spring Security 7.1 always adds {@code OAuth2ProtectedResourceMetadataFilter} to a resource-server chain
 * (no switch in 7.1.2), and its answer reflects the request's host and path and claims certificate-bound
 * tokens. This filter runs before it and sends the request to the container's error path with 404, where
 * {@link ContainerErrorResource} gives the constant {@code request-not-handled} problem of any unknown path.
 *
 * <p>The matcher is the one Spring Security's filter builds for itself (same builder, method and pattern),
 * so nothing that filter would answer gets past this one.
 */
final class ProtectedResourceMetadataNotServed extends OncePerRequestFilter {

    private final RequestMatcher metadataRequest = PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.GET, "/.well-known/oauth-protected-resource/**");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (metadataRequest.matches(request)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        } else {
            chain.doFilter(request, response);
        }
    }
}
