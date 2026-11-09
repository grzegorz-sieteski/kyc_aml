package com.gs5.kycaml.risk.adapters.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.StrictHttpFirewall;

/**
 * The identity source (D-13, design addendum 2026-11-09 (3)): an OAuth2 resource server that validates a JWT
 * bearer token and puts it in the security context, where {@link ActorContextResolver} reads it.
 *
 * <p>The chain decides nothing about access: every request is let through, and the resources' gate keeps
 * the refusal order and the constant problems. A request without a token reaches the resource
 * unauthenticated and is refused there; a request with a token the resource server rejects is answered by
 * {@link CallerNotIdentifiedAnswer} with the same 401.
 *
 * <p>Nothing here reads the request body before the caller is identified (D-09, R-21, R-22): the token is
 * taken from the {@code Authorization} header only (the resolver's form-body and query parameters are off,
 * and in Spring Security 7.1 it does not call {@code getParameter} when they are), CSRF is off (it would
 * read a {@code _csrf} form parameter, and a bearer-token API carries no cookie to forge), and there is no
 * form login, HTTP Basic, logout or request cache. Stateless: no session is created or read.
 *
 * <p>Token validation is Spring Boot's {@code JwtDecoder} from
 * {@code spring.security.oauth2.resourceserver.jwt.*}: keys from {@code jwk-set-uri} (so the issuer is never
 * used for discovery), the algorithms of {@code jws-algorithms} (RS256), {@code exp} and {@code nbf},
 * {@code iss} equal to {@code issuer-uri}, and {@code aud} containing one of {@code audiences}.
 */
@Configuration
class BearerTokenSecurityConfiguration {

    /**
     * Spring Security's strict firewall, with the three exceptions the API's own rules need. A literal
     * {@code ';'} is part of the customer number on every route (BR-06, R-03, R-26), so it must reach the
     * resource rather than be refused as a path parameter. An encoded CR or LF must reach the resource
     * too, which refuses it as a control character in the customer number (BR-06), the answer the API
     * gave before this chain existed. Everything else the firewall refuses stays refused.
     */
    @Bean
    WebSecurityCustomizer riskApiFirewall() {
        var firewall = new StrictHttpFirewall();
        firewall.setAllowSemicolon(true);
        firewall.setAllowUrlEncodedCarriageReturn(true);
        firewall.setAllowUrlEncodedLineFeed(true);
        return web -> web.httpFirewall(firewall);
    }

    @Bean
    SecurityFilterChain riskApiSecurityFilterChain(HttpSecurity http) throws Exception {
        var notIdentified = new CallerNotIdentifiedAnswer();
        var headerOnly = new DefaultBearerTokenResolver();
        headerOnly.setAllowFormEncodedBodyParameter(false);
        headerOnly.setAllowUriQueryParameter(false);
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(notIdentified)
                        .accessDeniedHandler(notIdentified))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(headerOnly)
                        .authenticationEntryPoint(notIdentified)
                        .accessDeniedHandler(notIdentified)
                        .jwt(Customizer.withDefaults()))
                .build();
    }
}
