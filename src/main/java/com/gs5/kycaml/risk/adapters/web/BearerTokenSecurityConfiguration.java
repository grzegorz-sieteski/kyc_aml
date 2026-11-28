package com.gs5.kycaml.risk.adapters.web;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.JwkSetUriJwtDecoderBuilderCustomizer;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.security.web.header.HeaderWriterFilter;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The identity source (D-13, design addenda 2026-11-09 (3) and (4)): an OAuth2 resource server that
 * validates a JWT bearer token and puts it in the security context, where {@link ActorContextResolver}
 * reads it.
 *
 * <p>The chain decides nothing about access: every request is let through, and the resources' gate keeps
 * the refusal order and the constant problems. A request without a token reaches the resource
 * unauthenticated and is refused there; a request with a token the resource server rejects, or cannot
 * check, is answered by {@link CallerNotIdentifiedAnswer} with the same 401, byte for byte (I-04, I-05).
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
 * {@code iss} equal to {@code issuer-uri}, and {@code aud} containing one of {@code audiences}. Three
 * things are added to it here: the service does not start without those settings ({@link IdentitySettings},
 * I-01), {@link ConfiguredIdentityValidator} refuses a token whenever one of those checks would be absent
 * and requires {@code exp} (I-01, I-02), and the keys come from a rate-limited source (I-06).
 */
@Configuration
@EnableConfigurationProperties(IdentitySettings.class)
class BearerTokenSecurityConfiguration {

    /** How long the service waits to connect to the identity provider's key set, in milliseconds. */
    private static final int KEY_SET_CONNECT_TIMEOUT_MILLIS = 2_000;

    /** How long the service waits for the key set once connected, in milliseconds. */
    private static final int KEY_SET_READ_TIMEOUT_MILLIS = 2_000;

    /**
     * Spring Security's strict firewall, with the exceptions the API's own rules need, and no others. The
     * customer number is a path segment, and BR-06 allows any character in it that is not a control
     * character, so these must reach the resource rather than be refused before it runs: a literal
     * {@code ';'} (R-03, R-26), and an encoded percent sign, period, line separator (U+2028) and paragraph
     * separator (U+2029) (I-03). An encoded CR or LF must reach the resource too, which refuses it as a
     * control character in the customer number (BR-06), the answer the API gave before this chain existed.
     * Everything else the firewall refuses stays refused: {@code //}, a backslash, an encoded slash, an
     * encoded or literal NUL, and path traversal.
     */
    @Bean
    WebSecurityCustomizer riskApiFirewall() {
        var firewall = new StrictHttpFirewall();
        firewall.setAllowSemicolon(true);
        firewall.setAllowUrlEncodedCarriageReturn(true);
        firewall.setAllowUrlEncodedLineFeed(true);
        firewall.setAllowUrlEncodedPercent(true);
        firewall.setAllowUrlEncodedPeriod(true);
        firewall.setAllowUrlEncodedLineSeparator(true);
        firewall.setAllowUrlEncodedParagraphSeparator(true);
        return web -> web.httpFirewall(firewall);
    }

    /** I-01, I-02: Spring Boot adds every such bean to the validators of the decoder it builds. */
    @Bean
    OAuth2TokenValidator<Jwt> configuredIdentityValidator(IdentitySettings settings) {
        return new ConfiguredIdentityValidator(settings);
    }

    /**
     * I-06: the decoder's keys come from Nimbus's own cached, rate-limited source instead of the one Spring
     * Security builds, which fetches the key set again for every token whose {@code kid} it does not know.
     * Here a refresh for an unknown {@code kid} happens at most twice per
     * {@link JWKSourceBuilder#DEFAULT_RATE_LIMIT_MIN_INTERVAL} (Nimbus's limiter lets a second one through),
     * whatever the callers send; the connect and read timeouts bound how long one fetch can hold a request.
     *
     * <p>Only the key selector of Spring Boot's decoder is replaced: the algorithms are still those of
     * {@code jws-algorithms}, the validators are still Spring Boot's, and the issuer is still never used for
     * discovery. A refresh refused by the limiter means "no such key", so the token is an invalid one like
     * any other (the constant 401), not a failure of the identity provider.
     */
    @Bean
    JwkSetUriJwtDecoderBuilderCustomizer rateLimitedKeySet(IdentitySettings settings,
                                                           OAuth2ResourceServerProperties properties)
            throws MalformedURLException {
        var algorithms = properties.getJwt().getJwsAlgorithms().stream()
                .map(JWSAlgorithm::parse)
                .collect(Collectors.toUnmodifiableSet());
        JWKSource<SecurityContext> keySet = JWKSourceBuilder
                .create(URI.create(settings.jwkSetUri()).toURL(), new DefaultResourceRetriever(
                        KEY_SET_CONNECT_TIMEOUT_MILLIS, KEY_SET_READ_TIMEOUT_MILLIS,
                        JWKSourceBuilder.DEFAULT_HTTP_SIZE_LIMIT))
                .cache(true)
                .rateLimited(JWKSourceBuilder.DEFAULT_RATE_LIMIT_MIN_INTERVAL)
                .build();
        JWKSource<SecurityContext> noKeyWhenLimited = (selector, context) -> {
            try {
                return keySet.get(selector, context);
            } catch (RateLimitReachedException limited) {
                return List.of();
            }
        };
        return builder -> builder.jwtProcessorCustomizer(processor ->
                processor.setJWSKeySelector(new JWSVerificationKeySelector<>(algorithms, noKeyWhenLimited)));
    }

    @Bean
    SecurityFilterChain riskApiSecurityFilterChain(HttpSecurity http) throws Exception {
        var notIdentified = new CallerNotIdentifiedAnswer();
        var headerOnly = new DefaultBearerTokenResolver();
        headerOnly.setAllowFormEncodedBodyParameter(false);
        headerOnly.setAllowUriQueryParameter(false);
        // I-05: the filter's own failure handler rethrows an AuthenticationServiceException (keys not
        // fetched), which ends as a 500. The configurer has no setter for it, so the filter is post-processed.
        var answerEveryFailure = new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
            @Override
            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                filter.setAuthenticationFailureHandler(notIdentified);
                return filter;
            }
        };
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
                // I-07: before every filter that could answer, Spring Security's metadata filter included
                // (the configurer places that one just before the pre-authentication position).
                .addFilterAfter(new ProtectedResourceMetadataNotServed(), HeaderWriterFilter.class)
                .oauth2ResourceServer(resourceServer -> {
                    resourceServer.addObjectPostProcessor(answerEveryFailure);
                    resourceServer
                            .bearerTokenResolver(headerOnly)
                            .authenticationEntryPoint(notIdentified)
                            .accessDeniedHandler(notIdentified)
                            .jwt(Customizer.withDefaults());
                })
                .build();
    }
}
