# ADR: Backend-for-frontend boundary, ninth draft (v9): the service side of this increment

> **Status: Proposed, 2026-11-27. Not audited. Do not implement** until the tests of section 14 are red in stage 3.
> v9 answers the eighth audit (blocker F1, warnings F2 to F5, notes) under D-26 to D-39. It is the design of
> **this increment only (D-34)**: the service side. Everything the BFF itself does is listed in section 15 by the
> section of v4 to v8 that holds it, so nothing is lost. Command 3 and quarantine are in (D-38, D-39).

- **Mode:** `@architect`, design mode, stage 2, ninth draft. Business questions still open are in section 17.
- **Replaces:** v8 for everything built now. Where a section says "as v6 9.3" (or similar), that section stands unchanged and is part of this design; nothing else of v4 to v8 is.
- **Scope:** `risk/adapters/*`, `shared/kernel/ActorRole`, new domain types for command 3 (section 10), migration V6, `application.yml`, `pom.xml` (one starter, D-39), and tests. No user interface (D-03). No BFF code.
- **Verification (2026-11-27):** every mechanism below was read with `javap -c -p` in the jars under `~/.m2` and in the JDK 25.0.4.1 runtime, or in `src/main` and `src/test`, or (Micrometer only) in the Boot 4.1.2 POMs and jar fetched from Maven Central. Output is in `/tmp/claude-0/-home-user-kyc-aml/52ec4e60-260e-5863-9603-67b60a05607e/scratchpad/v9/` (file names in section 16); v8 files are cited where not re-run. No Maven was run, nothing in the repository was compiled. "To confirm in the tenant", "deployment check" and "stage 3" mark what bytecode cannot show.

## 0. Delta on v8

| Audit item | Answer in v9 |
|---|---|
| F1 (a) OPTIONS and CORS pre-flight answered before identification | Section 4.2: one filter after `BearerTokenAuthenticationFilter` answers every OPTIONS after identifying; CORS disabled in the chain and asserted. Tests 18.17a to 18.17d |
| F1 (b) Tomcat refusals | D-36. Section 4.1 lists them, each verified in `CoyoteAdapter` and `Http11Processor` |
| F1, found while closing it | Firewall 400 for an unlisted method or a header value; direct `GET /error`; `100 Continue` before identification (section 4.2) |
| F2 v8 predates D-27 to D-35 | Split built now / deferred (section 1). D-28: section 5. D-29: section 6. D-30, D-37: sections 9, 10. D-27, D-31 to D-33, D-35: section 15 |
| F3 Operations role inconsistent | Spec now consistent (D-15, D-19, D-37, D-38, command 3). Sections 6, 9, 10 |
| F4 the BFF's repeat after a refresh must re-identify | Deferred with the rule fixed (section 15, item 4) |
| F5 `IdentityIT.shouldNotServeSpringSecurityEndpoints` | Becomes 401 (section 12) |
| Note: wrong method answers 405 | Catch-all route identifies first (4.2). Test 18.17e |
| Note: bad token and bad claims differ on unknown routes (I-04) | Same catch-all. Test 18.17f |
| v8 owner items 1 to 3, 5 to 8, 10, 11 | Closed by D-27, D-28, D-29, D-30, D-31 to D-33, D-34, D-35. Item 9 (BFF owner) and item 4 (BFF dependencies, now BFF increment) remain |

## 1. Built now, and deferred

**Built now (service):** (1) `GET /api/caller-identification` (section 3); (2) the closed set of answers before identification and its corrections (section 4); (3) mutual TLS with D-28 option B (section 5); (4) token rules with Operations (section 6); (5) the last-known key source (section 7); (6) configuration and logging guards (section 8); (7) the Operations role and permissions (section 9); (8) quarantine and command 3, service side (section 10); (9) the amendment to the main design (section 11).

**Deferred to the BFF increment:** sessions, Redis, CSRF and origin, the two-call client flow and its repeat after a refresh, BFF logging, rotation and renewal, BFF dependencies and deployment checks (section 15). Until the BFF exists, no human caller reaches the service, and no quarantined event can be released (D-37).

## 2. Decisions

The ADR's own decisions keep the `BD` prefix of v8 (BD1 to BD26 as v8 section 1 lists them; BD22's BFF half is deferred).

- **BD27 (made exact): the answers before identification are the closed set of 4.1.** Any other is a defect with a red test (D-24, D-26, D-36).
- **BD28 (D-28, option B): the handshake refuses every unregistered client certificate** through `RegisteredClientTrust` (section 5).
- **BD29: every OPTIONS request is answered by `OptionsRequestAnswer` after the bearer filter, never by the dispatcher** (4.2).
- **BD30: the firewall allows any method and any header value Tomcat accepted**; unknown methods reach the catch-all, which identifies (4.2).
- **BD31: a quarantined event is a row with status `QUARANTINED`; later events of its profile are not claimed while an earlier one is undelivered** (section 10).
- **BD32: the BR-46 metric is one Micrometer counter, `risk.outbox.quarantined`, with no tags** (D-39, section 10.5). The other count-free signals stay log lines (10.5).

## 3. The identification route (D-25)

- **Route:** `GET /api/caller-identification` (HEAD served by the same mapping), `CallerIdentificationResource` in `risk/adapters/web`. No path variable, no query, no body read (`getInputStream`, `getReader`, `getParameter*` never called).
- **Runs:** the security chain of every route, then `ActorContextResolver.identify(bookingCentres)`, the whole of BR-01: certificate (D-20, D-28), token (D-13), presenting application (D-19), claims (D-14 to D-16, D-23), booking centre in scope (D-06).
- **Answers:** identified: `204`, `Cache-Control: no-store`, no body, no header naming role, centre or actor. Not identified: the bytes of `ProblemResponses.notIdentified()`, identical to every other 401 (I-04). Defect: 5xx (D-29). Nothing else, apart from 4.1.
- **No role check.** The decision and release routes judge size (D-09) and readability (D-12) before the role (BR-37, command 3 rows 2 to 4), and the decision's permission depends on the kind in the body (D-08). A role check here would answer 403 where those routes must answer 413 or 400.
- **Reachable by the BFF and onboarding certificates.** An onboarding caller learns only that its own token is valid, which every route already tells it (401 or not).
- **Other methods:** OPTIONS goes to `OptionsRequestAnswer`; POST, PUT and the rest match no mapping of this path and reach the catch-all (4.2), so they are identified first: 401, or the constant 404 `request-not-handled` when identified.

## 4. Answers before identification (D-24, D-26, D-36)

### 4.1 The closed set (service)

| # | Answer | Where it comes from (verified) | Accepted by |
|---|---|---|---|
| 1 | TLS handshake refused: no client certificate, an untrusted CA, an unregistered or expired leaf | JSSE with `RegisteredClientTrust` (section 5); no HTTP exchange | D-20, D-28 |
| 2 | Tomcat 400: invalid request line or header syntax; Host missing, repeated, inconsistent or invalid; SNI not matching Host; URI that does not decode or normalise; `*` with a method other than OPTIONS | `Http11Processor.prepareRequest` (`noHostHeader`, `multipleHosts`, `inconsistentHosts`, `invalidUri`, `request.sni` at 200 to 638), header parse 267 to 409; `CoyoteAdapter.postParseRequest` 240, 326, 387, 443, 642 | D-36 |
| 3 | Tomcat 400 for an oversized header block, a malformed or repeated `Content-Length`, Transfer-Encoding with HTTP/1.0 or invalid | `prepareInputFilters` (`nonNumericContentLength`, `multipleContentLength`, `invalidTransferEncoding`, `transferEncodingWithHttp10`) | D-26, D-36 |
| 4 | Tomcat 505 (version), 417 (Expect other than `100-continue`), 501 (unknown transfer coding; CONNECT) | `prepareRequestProtocol` 149 to 180; `prepareExpectation` 26 to 50; `addInputFilter` 138 to 169; `CoyoteAdapter` 261 to 282 | D-36 |
| 5 | Tomcat 405 for TRACE (`allowTrace` false); `OPTIONS *` answered with the fixed `Allow: GET, HEAD, POST, PUT, DELETE, OPTIONS` | `CoyoteAdapter.postParseRequest` 961 to 1106; 143 to 203 | D-36 |
| 6 | `StrictHttpFirewall` 400 for a malformed path (blocklisted, not normalised, non-printable URI), via `HttpStatusRequestRejectedHandler` and the error dispatch to `ContainerErrorResource` | `getFirewalledRequest` (eager checks: method, blocklist, hosts, normalisation, URI); `FilterChainProxy` default handler | D-26 |
| 7 | The constant 401 `caller-not-identified` | every other request of a caller not identified | D-24 |
| 8 | 5xx for a defect in the token checks | validator `RuntimeException` (section 6) | D-29 |

None depends on the token's validity or reveals data. **Anything else answered before identification is a defect with a red test**, including 200, 404, 405 or a firewall 400 for anything but the path.

### 4.2 Corrections in the service

1. **OPTIONS (BD29).** `RequestMethodsRequestCondition.getMatchingCondition` returns no match for OPTIONS when a mapping declares no methods (offsets 13 to 59, not on an ERROR dispatch), so a method-less `/**` never sees OPTIONS. `handleNoMatch` then answers OPTIONS with `HttpOptionsHandler` (200 and `Allow`, 45 to 92). A CORS pre-flight matches a route through `matchPreFlight` (an empty method set matches), and `AbstractHandlerMapping.getCorsHandlerExecutionChain` replaces the handler by `PreFlightHttpRequestHandler` (1 to 38); `DefaultCorsProcessor.processRequest` with no configuration returns true (1 to 33): 200 with no body. **Design:** `OptionsRequestAnswer extends OncePerRequestFilter`, added with `addFilterAfter(..., BearerTokenAuthenticationFilter.class)`. For every OPTIONS (pre-flight or not, any path): `identify()` empty, the constant 401 bytes (the writer of `CallerNotIdentifiedAnswer`, made package-private); present, the constant 404 `request-not-handled` bytes (`ProblemResponses.notHandledProblem()`, the body `ContainerErrorResource` sends). It never calls `chain.doFilter` for OPTIONS. A bad token is already answered by the bearer filter with the same 401.
2. **CORS off.** `HttpSecurityConfiguration.applyCorsIfAvailable` adds `cors()` only when a `UrlBasedCorsConfigurationSource` bean exists (offsets 4 to 19); none exists today. The chain adds `.cors(AbstractHttpConfigurer::disable)`, and a test asserts no `CorsFilter` in any chain, so a later bean cannot add a pre-flight answer before identification.
3. **Unknown route and wrong method.** `UnknownRouteResource` maps `@RequestMapping("/**")` (no methods). `lookupHandlerMethod` checks the direct-path mappings first and, when none matches the request's conditions, every registration (13 to 64), so a wrong method on a known route (`POST /api/caller-identification`, `GET .../decisions`) matches `/**` and never reaches `handleNoMatch`'s 405 (93 to 108). It identifies first: empty, the constant 401; present, the 404 `request-not-handled`. That also gives a bad token and a valid token with bad claims the same 401 (I-04). Explicit mappings are more specific and win.
4. **Direct request to the error path.** `/error` is a direct path, so `GET /error` reaches `ContainerErrorResource` and gets 404 without identification today. On a dispatch that is not `DispatcherType.ERROR` it answers as `UnknownRouteResource` (identify, then 401 or 404). On the ERROR dispatch it answers as today (row 6, and errors after identification).
5. **Firewall (BD30).** `StrictHttpFirewall` refuses an unlisted method eagerly and checks header values lazily, on the first `getHeader` (for example the bearer resolver's, or the dispatcher's `Origin` read): a tab or a C1 character gives 400 before identification. Tomcat's header parser refuses other control characters itself (row 2; not re-read in bytecode, 18.17h confirms). The firewall bean adds `setUnsafeAllowAnyHttpMethod(true)` and `setAllowedHeaderValues(value -> true)`. An unknown method reaches `FrameworkServlet.service`, which sends methods outside its seven to `processRequest` (0 to 29), then the catch-all. A malformed `Authorization` value fails the resolver's pattern: the constant 401. Path checks stay as they are (row 6). Nothing in the service reflects a request header.
6. **Metadata path.** Spring Security's metadata filter matches `GET /.well-known/oauth-protected-resource/**` exactly (`HttpMethod.GET`, exact name compare). `ProtectedResourceMetadataNotServed` keeps its place and matcher and writes the constant 401 bytes instead of `sendError(404)`, to every caller: it runs before the bearer filter, so it cannot identify, and no client uses the path. HEAD and other methods on that path reach the catch-all.
7. **`100 Continue`.** Tomcat's default `continueResponseTiming=immediately` sends `100` before application code to any `Expect: 100-continue`. The connector customizer of section 5 also calls `AbstractHttp11Protocol.setContinueResponseTiming("onRead")` (verified in 11.0.27), so `100` is sent only when `BoundedBody` reads, after identification (v7 probe 2: 401 with no `100` line, 0 body bytes).

## 5. Mutual TLS, D-28 option B

- **Server TLS** (keys verified in Boot 4.1.2): `server.ssl: {enabled: true, bundle: service-tls, client-auth: need}`; `server.forward-headers-strategy: none`; `spring.ssl.bundle.jks.service-tls.keystore` and `.truststore` as v5 9.1 (PKCS12, `SERVICE_TLS_KEYSTORE`, `CLIENT_CA_TRUSTSTORE` and passwords); new `spring.ssl.bundle.pem.registered-clients.truststore.certificate: ${REGISTERED_CLIENT_CERTIFICATES}`, one PEM file holding exactly the registered leaves (`PemSslStoreBundle.addCertificates` stores each as its own trusted entry, offsets 4 to 58). `reload-on-update: false` written for both bundles (the field defaults to false) and pinned by test.
- **`RegisteredClientTrust extends X509ExtendedTrustManager`** (`risk/adapters/config`; no banned suffix, checkstyle `(Service|Facade|Manager|Helper|Util|Utils)\b`). Two delegates: the CA trust manager of `service-tls` and the trust manager of `registered-clients`; Boot builds both with `TrustManagerFactory.getDefaultAlgorithm()` (`DefaultSslManagerBundle` 10 to 22), which is `PKIX` (`java.security` line 313), whose `X509TrustManagerImpl` extends `X509ExtendedTrustManager`. Each of the three `checkClientTrusted` overloads calls the CA delegate, then the leaf delegate, with the same arguments, and declares `throws CertificateException`; `checkServerTrusted` delegates likewise; `getAcceptedIssuers` returns the CA delegate's. Our code neither throws nor catches: an unregistered leaf has no path to an anchor in a store of leaves, and `PKIXValidator` throws; a registered leaf is returned at once by the leaf shortcut (`trustedCerts.contains`, 138 to 161). The CA delegate runs first and keeps expiry, key usage and chain checks.
- **Selection without a throw.** `RegisteredClientTrust.of(TrustManager[] ca, TrustManager[] leaves)` returns `Optional`, present only when each array holds exactly one `X509ExtendedTrustManager` (`instanceof` pattern). The `@Bean @Nullable RegisteredClientTrust` method returns `of(...).orElse(null)`; the customizer bean requires it, and Spring refuses a `NullBean` for a required dependency (`DefaultListableBeanFactory.resolveInstance` 5 to 26, `raiseNoMatchingBeanFound`), so startup fails closed, never falling back to CA-only trust.
- **`RegisteredClientSslContext implements org.apache.tomcat.util.net.SSLContext`** (8 methods, verified). Built in the `@Bean` method, which may declare `throws GeneralSecurityException` (the JDK throws, not our code): `javax.net.ssl.SSLContext.getInstance("TLS")`, `init(keyManagers of service-tls, {trust}, null)`. Delegation: `createSSLEngine`, `getServerSessionContext`, `getServerSocketFactory`, `getSupportedSSLParameters` to the JSSE context; `getCertificateChain(alias)` from the first `X509KeyManager`; `getAcceptedIssuers` from `trust`; `destroy` does nothing; `init(km, tm, random)` re-initialises with `km` and `trust` only, ignoring `tm`, so no caller can drop the leaf check. Comment required (copilot 0.7): Tomcat's own `jsse.JSSESSLContext` is package-private (verified), so it cannot be reused.
- **Installation and order.** A `TomcatConnectorCustomizer` bean (`org.springframework.boot.tomcat`). `TomcatWebServerFactory.customizeConnector` runs protocol-handler customizers at 66, `customizeSsl` (Boot's `SslConnectorCustomizer`, which calls `setCertificateKeystore`, `setTrustStore`, `setCertificateVerification("required")` and `addSslHostConfig`) at 130, connector customizers at 190 (re-verified). Ours: for each `connector.findSslHostConfigs()` and each certificate, `setSslContext(context)` (sets `sslContextProvided`); and `setContinueResponseTiming("onRead")` (4.2 item 7). `AbstractEndpoint.createSSLContext` keeps a provided context and generates one only when none is provided (122 to 192); `createSSLEngine` still applies `setNeedClientAuth(true)` from `certificateVerification` (435 to 503); `releaseSSLContext` destroys only a generated context (30 to 49).
- **Deployment checks:** bundle reload stays off (it would build a new `SSLHostConfig` without our context); `server.tomcat.mbeanregistry.enabled` stays false (no JMX `reloadSslHostConfigs`); the PEM bundle gains a renewed leaf before rollout and loses the old one after (D-28: renewal is a configuration change); TLS ends at the service's Tomcat (no re-sent certificate).
- **Registrations (D-19):** `bff`, `onboarding.us`, `.gb`, `.de`, each with client id, certificate subject and (onboarding) booking centre, in the validated record of section 8. **The certificate subject selects the application before any key fetch:** `CertificateSubjectResolver` as v6 section 6 step 1 (attribute `X509Certificate[]`, element 0, issuer and subject as `X500Principal`), mapping each registration to its own `ProviderManager` and key chain (v7 6.1, F10). A subject that is not registered gets the refusing manager (no keys, no fetch), the constant 401 and one WARN per process (kept as a log line, BD32). Under option B it is reached only if the PEM bundle holds a leaf whose subject is not registered.
- **Test PKI:** `TestPki` as v7 section 11 (JDK `keytool`, PKCS12, no new dependency), plus a PEM bundle written with `keytool -exportcert -rfc` holding the registered leaves (BFF, us, gb, de) and one expired registered leaf (`-startdate -30d -validity 1`); an untrusted CA with a leaf; an unregistered leaf from the trusted CA. `ClientCertificates` as v7 section 11.
- **The five ITs move to https** exactly as v8 section 10 (table unchanged): `RiskApiHardeningIT`, `RiskApiAdversarialIT`, `IdentityIT`, `IdentityKeysUnavailableIT`, `IdentityClaimNamesIT`; `RiskApiFlowIT` keeps MockMvc with the certificate attribute.

## 6. Token rules (D-13 to D-16, D-19, D-23, D-29, D-30, D-37)

- **Branch by `azp`** (a `String`, exact, case-sensitive). The BFF's client id: user branch. A registered onboarding client id: app-only branch. Anything else refuses. Each chain's `ApplicationTokenValidator` is built for its registration, so `azp` must equal the client id of the certificate's registration (a token of one application on another's certificate: 401).
- **"`scp` present"** is `claims.containsKey("scp")`, whatever its value. User branch: present. App-only branch: absent.
- **`roles`:** a `List` with exactly one element, a `String` equal to an `ActorRole` name of the branch. User branch: `AML_ANALYST_L1`, `AML_ANALYST_L2`, `MLRO`, `FRONT_OFFICE`, `OPERATIONS`. App-only branch: `ONBOARDING_SYSTEM`. A single string, `[]`, `[""]`, a second name of any kind (D-23), duplicates, case variants and non-strings refuse.
- **Caller:** actor from `oid`; user branch: centre from the configured centre claim; app-only branch: centre from the registration, no centre claim read. `identify()` keeps the in-scope check. To confirm in the tenant: `azp`, `scp`, `idtyp`, `roles`, `oid`, `tid`, `typ`, no `cnf`.
- **`CallerClaims` rewrite** (v7 6.3): drops the single-string case and the filtering of foreign names; reads claim names from the record (section 8), not `@Value`. **`ConfiguredIdentityValidator` rewrite** (v8 section 7): raw `getClaims()`, `iss instanceof String` equal, `aud instanceof List<?>` of strings containing the one audience, `exp instanceof Instant`; no conversion accessor, no `throw`, no catch-all. `MappedJwtClaimSetConverter` converts only `aud`, `exp`, `iat`, `iss`, `jti`, `nbf`, `sub` (v8, `mapped-defaults.txt`).
- **Composition, static in `IdentityJwtDecoder`,** used by every chain and the refusing decoder: `libraryValidators(issuer, audience)` = `JwtValidators.createDefaultWithIssuer(issuer)` and `new JwtAudienceValidator(audience)`; `validators(...)` adds `ConfiguredIdentityValidator` and the chain's `ApplicationTokenValidator` (v7 section 8 code, unchanged). Decoder: `NimbusJwtDecoder.withJwkSource(chain).jwsAlgorithm(SignatureAlgorithm.RS256).build()`, then `setJwtValidator(validators(...))` (both verified). `X509CertificateThumbprintValidator` passes a token with no `cnf` (offsets 1 to 49).
- **Configurer:** `.authenticationManagerResolver(certificateSubjectResolver)` and no `.jwt(...)` (v7 6.2); `rateLimitedKeySet`, `noKeyWhenLimited` and `IdentitySettings` removed.
- **D-29 accepted:** a `RuntimeException` from a validator is caught by nothing (v6 9.6) and gives 5xx. No catch-all is added; test 18.12e documents it.

## 7. Key source

- **Chain per registration** (v6 9.1, v7 6.1): `JWKSetBasedJWKSource` → `LastKnownKeySetSource` (serving layer) → `CachingJWKSetSource(source, 60 000, REFRESH_TIMEOUT_MILLIS = 5 000, listener)` → `FetchRecordingJWKSetSource` → `RateLimitedJWKSetSource(source, 30 000, listener)` → `URLBasedJWKSetSource` over `DefaultResourceRetriever(2 000, 2 000, 51 200)`. Constructors and the evaluator signature re-verified (`nimbus-signatures.txt`).
- **Fetch recording at the lock:** `FetchRecordingJWKSetSource` sits below the cache, so it runs inside the cache's refresh lock, at each real IdP request; it stores `(keys, now)` on success and calls `fetchFailed` for `JWKSetRetrievalException` and `JWKSetParseException` (v6 9.2 catch clauses unchanged). A stamp never decreases (v6 9.3); the cost of a discarded stale set is v8 8.2.
- **Serving layer**, pair first, then the clock, CAS-loser loop, and no CAS on an already LAPSED pair:

```
loop:
  pair = health.current(); t = clock.millis();
  if (pair.record == null) return EMPTY;
  if (t - pair.record.fetchedAtMillis < windowMillis) return pair.record.keys;   // the cached instance (v7 9.1)
  if (pair.state == LAPSED) return EMPTY;                                        // no CAS, no line
  if (health.compareAndSet(pair, pair.lapsed())) { WARN once; return EMPTY; }
  // CAS lost: another thread changed the pair; read again
```

- **Health:** the four states, transitions and one-time lines of v6 9.3 (UNKNOWN, OK, FAILING, LAPSED; one WARN on entry to FAILING or LAPSED, one INFO on recovery; root-cause class name only). Log lines name no chain (BR-43).
- **Window:** `LAST_KNOWN_WINDOW_MAXIMUM = 300 s` (D-21); `isKeySetWindowOrdered()` requires `0 < rate-limit-interval < cache-time-to-live < last-known-window <= 300 s`, both comparisons strict (v6 7.4). Shipped: 30 s, 60 s, 300 s. Read timeouts are per read (v8 8.3); a dripping IdP is a residual.

## 8. Configuration and logging guards

- **One validated record** `OnboardingCentresInScope` (prefix `app`, `risk/adapters/config`), as v6 7.1 and 7.4 (cross-checks: centres canonical and in scope with the right jurisdiction; ids, subjects and centres pairwise distinct; window order; subjects parse as `X500Principal`; null-safe `is...` methods), with two trees made explicit: `tls` and `database`.
- **Per-field placeholder check** (v6 7.2): every non-secret String has `@NotBlank` and `@Pattern(regexp = "(?s)(?!.*\\$\\{).*", message = "<property> (<VARIABLE>) must be set: the variable is unset")`, because Boot binds an unset `${NAME}` as literal text (`ignoreUnresolvablePlaceholders` true). **Secrets** by `@AssertTrue` only, never a constraint on the value (Boot echoes rejected values, v7 section 7); the secrets record's `toString` is `Secrets[values not shown]`.
- **The 23 variables:** `ISSUER_URI` (`app.identity.issuer`), `TOKEN_AUDIENCE` (`.audience`, one string), `JWK_SET_URI`, `CLIENT_CA_SUBJECT` (`.client-certificate-issuer`), `BFF_CLIENT_ID`, `BFF_CERTIFICATE_SUBJECT`, `ONBOARDING_{US,GB,DE}_{CLIENT_ID,BOOKING_CENTRE,CERTIFICATE_SUBJECT}` (9), `FLYWAY_USERNAME` (`app.database.flyway-username`), `SERVICE_TLS_KEYSTORE` (`app.tls.service-keystore`), `CLIENT_CA_TRUSTSTORE` (`app.tls.client-ca-truststore`), **`REGISTERED_CLIENT_CERTIFICATES` (new, `app.tls.registered-client-certificates`)**; secrets `DATABASE_PASSWORD`, `FLYWAY_PASSWORD`, `SERVICE_TLS_KEYSTORE_PASSWORD`, `CLIENT_CA_TRUSTSTORE_PASSWORD` (`app.secrets.*`). Each `app.*` value names the same variable as the Spring property it guards (bound twice on purpose).
- **`spring.security.oauth2.resourceserver.jwt.*` leaves `application.yml`** (asserted absent, v6 7.5).
- **Logging:** `logging.level.org.springframework.web: INFO` next to the security pin, with a comment (why: v8 section 5). `ProductionIdentitySettingsTest.shouldPinSpringWebLoggingToInfo`, and `shouldNotLowerAChildLogger`: the shipped file has no `logging.level` key below `org.springframework.web` or `org.springframework.security` and no `spring.mvc.log-request-details`. **Deployment check:** no environment variable (`LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_WEB...`, `..._SECURITY...`), argument or logging file sets a child logger such as `org.springframework.web.servlet.DispatcherServlet` below INFO; a child setting overrides the parent pin. **AGENTS.md lines required** (the architect does not edit it): the `org.springframework.web` ban (text of v8 section 5) and the deployment checks of sections 5 and 8.

## 9. The Operations role (D-30, D-37)

- `ActorRole` gains `OPERATIONS` (Javadoc: a person who releases quarantined events through the BFF; no access to risk information, decisions or views).
- `RiskPermissions`: **`FRONT_OFFICE_STATUS` becomes `rolesAllowed` of every role except `OPERATIONS`** (D-30: no views; it was `anyIdentifiedCaller`). New `RELEASE_QUARANTINE = rolesAllowed(Set.of(OPERATIONS))`. `EVALUATE`, `COMPLIANCE_VIEW` and `forDecision` are unchanged and already exclude Operations. The `PermissionDenied` arm of `FrontOfficeStatusResource` becomes reachable: 403 `permission-denied` naming the role, no risk word (BR-41).
- **Note for `@business-analyst`:** query 2 lists no role refusal; D-30 requires one for Operations (BR-02). The spec text should name it.

## 10. Quarantine and command 3, service side (D-18, BR-44 to BR-46, D-30, D-37 to D-39)

### 10.1 Migration `V6__quarantine.sql` (schema owner)

```sql
ALTER TABLE command.outbox DROP CONSTRAINT outbox_status_check,               -- V1's unnamed column CHECK
    ALTER COLUMN status TYPE VARCHAR(11),
    ADD CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'DELIVERED', 'QUARANTINED'));
CREATE INDEX idx_outbox_undelivered_profile ON command.outbox (profile_id, aggregate_version) WHERE status <> 'DELIVERED';
CREATE INDEX idx_outbox_quarantined ON command.outbox (tenant_id, customer_number, aggregate_version) WHERE status = 'QUARANTINED';
CREATE TABLE command.quarantine_release (                                     -- BR-46, append-only
    release_id      UUID PRIMARY KEY,
    event_id        UUID NOT NULL REFERENCES command.outbox (event_id),         -- internal, never exposed (D-05)
    tenant_id       UUID NOT NULL,
    customer_number VARCHAR(64) COLLATE "C" NOT NULL,
    actor_id        VARCHAR(255) NOT NULL,
    justification   TEXT NOT NULL,
    released_at     TIMESTAMPTZ NOT NULL);
-- command.refuse_release_change(): RAISE 'quarantine_release is append-only'; BEFORE UPDATE OR DELETE FOR EACH ROW
-- and BEFORE TRUNCATE FOR EACH STATEMENT triggers, as V1 and V4 do for the history.
GRANT SELECT, INSERT ON command.quarantine_release TO "${service_role}";
UPDATE command.outbox SET status = 'QUARANTINED' WHERE status = 'PENDING' AND attempts >= 10;   -- rows parked today
-- and for each such (tenant_id, customer_number) the front-office hold of 10.3, as one INSERT ... ON CONFLICT.
```

The constraint name `outbox_status_check` is PostgreSQL's default for V1's column CHECK: stage 3 confirms it in `pg_constraint`. Widening `VARCHAR(10)` to `(11)` needs no rewrite. The outbox keeps `SELECT, INSERT, UPDATE` for the service (V5).

### 10.2 Relay (`OutboxRelay`)

- **Later events wait (BR-45).** The claim's inner `SELECT` (alias `candidate`) adds `AND NOT EXISTS (SELECT 1 FROM command.outbox earlier WHERE earlier.profile_id = candidate.profile_id AND earlier.aggregate_version < candidate.aggregate_version AND earlier.status <> 'DELIVERED')`. `attempts < :maxAttempts` stays as defence. In `projectAndMark`, the first failure of a profile stops that profile: its later rows in the chunk are not projected, count nothing, and their lease is cleared in the same transaction.
- **Quarantine at the tenth attempt (BR-44).** `countAttempt` becomes `UPDATE command.outbox SET attempts = attempts + 1, status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'QUARANTINED' ELSE status END WHERE event_id IN (:ids) AND locked_by = :owner RETURNING event_type, status, tenant_id, customer_number`. For each row returned as `QUARANTINED`, the hold of 10.3 runs in the same chunk transaction.
- **Line and metric only after commit.** `projectChunk` logs and counts only when `transaction.execute` returned; a rolled-back chunk quarantined nothing.

### 10.3 Front office while quarantined (BR-45)

`ViewProjector.hold(UUID tenantId, String customerNumber)`, in the projector's savepoint, returns `Result<Boolean, ProjectionFailure>` like `project`: `INSERT INTO query.front_office_status (..., projection_version, status, ...) VALUES (..., 0, 'VERIFICATION_PENDING', ...) ON CONFLICT (tenant_id, customer_number) DO UPDATE SET status = 'VERIFICATION_PENDING', projected_at = EXCLUDED.projected_at`. The version is kept, so the held event (a higher version, the next undelivered one by 10.2) passes the gate when it is applied and writes the real status; an older repeat cannot clear the hold. A customer with no row (first evaluation quarantined) gets one. The reader is unchanged: `VERIFICATION_PENDING` gives the neutral message (BR-40). A failed hold still commits the quarantine and writes one ERROR line, `front-office hold not written for a quarantined event`. Rejected: the reader consulting `command.outbox` (CQRS by schema, `RiskSchemaAccessRulesTest`).

### 10.4 Release record and command 3

- **Domain (`risk/domain`, pure):** `RawReleaseQuarantinedEvent(@Nullable String customerNumber, @Nullable String justification)`; `ReleaseQuarantinedEventCommand(CustomerNumber customerNumber, Result<Justification, JustificationError> justification)` with `static parse(raw)` (a number that cannot be one is `CustomerNotFound`; the justification is kept as a result, as in command 2); `toString` masked.
- `public sealed interface ReleaseQuarantinedEventError` permits, in the spec's order after the edge: `CustomerNotFound()`, `JustificationMissing()`, `JustificationTooShort(int minLength, int actualLength)`, `JustificationTooLong(int maxLength, int actualLength)`, `JustificationControlCharacter(int codePoint, int index)`, `NothingQuarantined()`, `ChangedConcurrently()`.
- Port `QuarantinedEvents`: `Optional<QuarantinedEventRef> oldestOf(UUID tenantId, CustomerNumber)` (`ORDER BY aggregate_version LIMIT 1`, status `QUARANTINED`); `Result<QuarantineReleased, QuarantineReleaseConflict> release(QuarantinedEventRef, QuarantineReleaseRecord)`. `QuarantinedEventRef(UUID eventId)` never leaves the service. `QuarantineReleaseRecord(UUID releaseId, UUID tenantId, CustomerNumber, String actorId, Justification, Instant releasedAt)`, `toString` masked.
- `ReleaseQuarantinedEventUseCase.execute(ActorContext, ReleaseQuarantinedEventCommand) → Result<QuarantineReleased, ReleaseQuarantinedEventError>`; constructor: `RiskProfileRepository` (customer found, `findOwn`, BR-04), `QuarantinedEvents`, `RiskProfileIdGenerator` (`nextEventId()` as the release id), `Clock`. Order: customer, justification (BR-34 via `Justification.parse`), oldest quarantined (`NothingQuarantined`), release (`ChangedConcurrently`).
- **Adapter** `QuarantineSqlAdapter` (`adapters/persistence`), one transaction: `UPDATE command.outbox SET status = 'PENDING', attempts = 0, locked_until = NULL, locked_by = NULL WHERE event_id = :id AND status = 'QUARANTINED'`; one row: insert the record; zero rows: the conflict, nothing inserted (BR-30, BR-42). The event is then claimed in order like any pending row.
- **Route:** `POST /api/risk-profiles/{customerNumber}/quarantine-releases`, `ReleaseQuarantinedEventResource`, body `{"justification": "..."}`. Order, as command 3: identify (401); `BoundedBody.read(request, RELEASE_LIMIT)` (413) and `StrictJson` (400); `OperationGate` with `RELEASE_QUARANTINE` (403); parse; use case. Answers: `204` no-store, no body (D-30); 404 `customer-not-found`; 400 with the four justification codes of command 2; 422 `nothing-quarantined`; 409 `quarantine-changed-concurrently`; instance `/api/risk-profiles/quarantine-releases`. `RELEASE_LIMIT` is a named constant, proposed 16 384 bytes (section 17 item 2). Customer number from the raw path (`RawCustomerNumber`, addendum C).
- **Reachable only through the BFF (D-37):** `OPERATIONS` is accepted only from a BFF user token (section 6); onboarding gets 403 after size and readability. Through the BFF it is a body route: the two-call flow of section 15 item 3 applies.

### 10.5 Log line and metric (BR-46, D-39)

- **ERROR line, once per quarantined event, after commit:** `Outbox event quarantined after 10 failed attempts (<event type>)`: the type only when it is `CustomerRiskEvaluated` or `ComplianceDecisionRecorded`, else `unknown`; "the count" of BR-46 is read as the attempt count. No customer number, id, payload or score.
- **Counter:** `Counter.builder("risk.outbox.quarantined").description(...).register(meterRegistry)`, incremented once per quarantined event after commit; **no tags** (D-39). `OutboxRelay` takes a `MeterRegistry` in both constructors; `RelaySupport` passes a `SimpleMeterRegistry`.
- **Artifact:** `org.springframework.boot:spring-boot-starter-micrometer-metrics` (version from the 4.1.2 parent; BOM lines 2593, Micrometer 1.17.2). Its POM pulls `spring-boot-micrometer-metrics`, which pulls `micrometer-core` 1.17.2 and `spring-boot-micrometer-observation`; its auto-configuration (`MetricsAutoConfiguration`, `CompositeMeterRegistryAutoConfiguration`, `SimpleMetricsExportAutoConfiguration`) gives a `MeterRegistry` **without actuator** (verified on Maven Central). **Chosen over `spring-boot-starter-actuator`** because actuator adds HTTP endpoints, new answers outside 4.1. Tests use `SimpleMeterRegistry`; the `-test` starter is not added.
- **Side effects of the starter:** it also binds JVM, system, logback, executor and SSL-bundle meters, and `WebMvcObservationAutoConfiguration` (spring-boot-webmvc) becomes active (`http.server.requests` with route templates and statuses). Stage 3 asserts no meter name or tag holds a filled-in path or customer number (test 18.24b). SSL-bundle meters tag certificate aliases of the bundles; export is the decision of section 17 item 3.
- **Other count-free signals stay log lines:** the unregistered-subject WARN (section 5) and the key-set health lines (section 7). Counters for them would add nothing a log alert lacks.

## 11. Amendment to `customer-risk-scoring-design.md` (to be applied by the architect before stage 3)

> **Addendum 2026-11-27: quarantine and release (D-18, D-30, D-37, D-38, D-39; BR-44 to BR-46).** Replaces the
> first item of "Not doing" ("Quarantine and replay of a poisoned event ... a later increment adds quarantine") and
> amends §4, §8 step 5, §9 and §10. **§8 step 5:** a projection failure counts an attempt; the tenth sets the row to
> `QUARANTINED`, after which it is never claimed automatically (BR-44); while any earlier row of a profile is not
> delivered, its later rows are not claimed, so order is kept (BR-45); quarantine writes the front-office status
> `VERIFICATION_PENDING` (BR-45), one ERROR line with the event type and the attempt count, and increments the
> counter `risk.outbox.quarantined` (BR-46, D-39). **§4:** command 3, `ReleaseQuarantinedEventUseCase`, its
> command, its sealed error and its port, as `bff-boundary-v9.md` section 10.4. **§9:** `V6__quarantine.sql` (status
> `QUARANTINED`, two indexes, the append-only `command.quarantine_release`, the conversion of rows parked at ten
> attempts). **§10:** `POST /api/risk-profiles/{customerNumber}/quarantine-releases`, Operations only, `204`.
> **§2:** `ActorRole.OPERATIONS`. Until the BFF exists no release is possible (D-37).

## 12. Existing tests that change

| Test | Change |
|---|---|
| `IdentityIT.shouldNotServeSpringSecurityEndpoints` | No token: 401 constant bytes on all four paths. Valid token: the metadata paths 401, `/login` and `/logout` 404 `request-not-handled` |
| `IdentityIT.shouldIgnoreForeignRoles` | Becomes `shouldRefuseForeignRoles`: 401 (D-23) |
| `IdentityIT.shouldAcceptASingleStringRole` | Becomes `shouldRefuseASingleStringRole`: 401 (section 6) |
| `IdentityIT` onboarding cases | `TestTokens.appClaims("de")` on the `de` certificate |
| `TestTokens` | `claims(role)` adds `azp` (BFF client id) and `scp` (`"risk.read"`); `appClaims(registration)` (onboarding `azp`, `[ONBOARDING_SYSTEM]`, no `scp`, no centre claim); four client-id constants |
| `src/test/resources/config/application.yml` | All 23 values; `app.booking-centres` with US `05a0...a5`, GB (new UUID) and DE `0de0...0d`, matching the three registrations; secrets refer to the Spring properties they guard (v7 section 11). The seven classes that set `app.booking-centres` keep identical values or drop them |
| `IdentitySettingsStartupIT` | Keys move to `app.identity.*`; adds 18.6p (23 cases) and 18.6s |
| `ActorRoleTest` | Six roles |
| `PermissionMatrixTest` | Front-office admits every role except `OPERATIONS`; `shouldNeverRefuseARoleForTheFrontOfficeStatus` becomes `shouldRefuseOnlyOperationsForTheFrontOfficeStatus`; new `RELEASE_QUARANTINE` row; the `@EnumSource` rows include `OPERATIONS` |
| `RiskSchemaAccessRulesTest` | `COMMAND_SQL` adds `quarantine_release` |
| `OutboxRelay` ITs, `RelaySupport` | `MeterRegistry` argument; `OutboxLeaseClaimIT.shouldLeaveParkedRowsAlone` stays green (defence `attempts < 10`) |
| The five ITs of section 5 | https with client certificates (v8 section 10) |

ArchUnit suites need no rule change: new classes are `*Resource` in `web`, adapters outside `domain/`, one public `execute` on the new use case.

## 13. Residuals and checks

1. **Dripping IdP** (v8 8.3): one request per chain held; accepted. **Older set served once more inside its window** (v7 9.3): safe for D-21.
2. **Pinning certificates, not subjects** (D-28): renewal is a PEM change (deployment check, section 5). Revocation is not checked (as today).
3. **Firewall relaxed** (BD30): methods and header values the firewall used to refuse now reach the catch-all or the resolver; no code reflects a header (stage 3 confirms with a grep test on `getHeader` uses).
4. **Tenant facts:** the claims of section 6; three onboarding registrations; `accessTokenAcceptedVersion: 2`.
5. **Deployment checks:** sections 5 and 8; ingress only from the BFF and onboarding segments (D-17); `server.tomcat.mbeanregistry.enabled` false.

## 14. Tests required before stage 4

Stage 3 writes these red (test-compile passes, `test` fails). Key-source tests pass `now` and a test `Clock`.

- **18.1, 18.1a, 18.1b, 18.1c, 18.1d:** as v8 section 14. **18.1e:** after one reader moved OK to LAPSED, a second reader of the LAPSED pair answers EMPTY, the pair reference is unchanged and no second WARN is written.
- **18.2, 18.4, 18.4b, 18.12, 18.12b to 18.12f, 18.16:** as v8 section 14 (per chain). **18.3:** a registered-CA leaf in the PEM bundle whose subject is not a registration: 401, zero IdP requests, same bytes, one WARN per process, no subject in logs.
- **18.5:** v7 6.3 and v8 section 7 cases, plus: BFF `[OPERATIONS]` accepted; `[OPERATIONS, MLRO]`, `[operations]`, `"OPERATIONS"` refused; app-only `[OPERATIONS]` refused; onboarding `azp` with `[OPERATIONS]` and `scp` refused.
- **18.6 (a) to (l), 18.6p (23 variables, each unset, message names it), 18.6s:** as v6 and v7, with the new variable.
- **18.7, 18.7b, 18.8, 18.8b to 18.8f:** as v7. 18.7 also pins `reload-on-update: false` on both bundles and `continueResponseTiming` absent from the shipped file (it is set in code).
- **18.9(a), (b), (d):** as v5 9.3 and 9.2.
- **18.11c (`CallerIdentificationResource`, real TLS):** identified BFF caller and `ONBOARDING_SYSTEM` on `de`: 204, no body, `no-store`, no role, centre or actor header; `FRONT_OFFICE` and `OPERATIONS`: 204 (no role check); no token, bad token, foreign role, out-of-scope centre, unregistered `azp`: the decision route's exact 401 bytes; a request with a body is not read (counting wrapper); OPTIONS and POST on the path, without a token: 401, with a token: 404 `request-not-handled`.
- **18.17a (OPTIONS):** on `/api/risk-profiles/X/decisions`, `/api/unknown` and `/api/caller-identification`: no token 401, bad token 401, valid token with bad claims 401 (all byte-identical), valid token 404; never `Allow`, never 200. **18.17b:** `GET /.well-known/oauth-protected-resource` and `/x`: the 401 bytes with and without a token. **18.17c (CORS pre-flight):** `OPTIONS .../decisions` with `Origin` and `Access-Control-Request-Method: POST`: 401 without a token, 404 with one; no `Access-Control-*` or `Vary` header. **18.17d:** no `CorsFilter` in any `SecurityFilterChain`. **18.17e (wrong method, unidentified):** `GET .../decisions`, `DELETE /api/risk-profiles/X`, `PATCH .../front-office-status`, method `FOO`: 401; identified: 404. **18.17f (I-04):** unknown path with a bad token and with a valid token naming two roles: identical 401 bytes. **18.17g:** `GET /error` and `POST /error`: 401 without a token, 404 with one. **18.17h (header values):** `Authorization: Bearer` with a tab inside, and `Origin: a<TAB>b` with a valid token: 401 and the route's own answer respectively, never firewall 400. **18.17i (closed set, raw TLS socket):** TRACE 405 from Tomcat; `OPTIONS *` the fixed `Allow`; HTTP/2.0 request line 505; `Expect: foo` 417; `Transfer-Encoding: gzip` 501; no Host 400; two Hosts 400; each with no application log line and no problem body. **18.17j:** `Expect: 100-continue` unidentified with a 20 000 000 declared length: 401 with no `100` line; identified with 100 bytes: `100`, then the answer.
- **18.18:** `shouldPinSpringWebLoggingToInfo`, `shouldNotLowerAChildLogger`.
- **18.19 (handshakes, option B):** registered leaf of the trusted CA: succeeds; unregistered leaf of the trusted CA: refused, no HTTP exchange, no application log; registered leaf of an untrusted CA: refused; expired registered leaf: refused; the certificate request lists only the CA; no client certificate: refused. A context where the bundle yields no `X509ExtendedTrustManager` (test bean) fails to start. Stage 3 records how TLS 1.3 reports the refusal.
- **18.20:** the five ITs over TLS; each refuses a connection without a client certificate.
- **18.21 (quarantine):** one row failing ten times: `QUARANTINED` at the tenth, not before, never claimed after; a rolled-back chunk leaves status and attempts unchanged.
- **18.22 (order):** v1 failing and v2 pending for one profile, same chunk and separate batches: v2 never projected or counted while v1 is pending or quarantined; other profiles delivered; after release and success, v1 then v2.
- **18.23 (front office):** an Active customer whose next event is quarantined reads `VERIFICATION_PENDING` with the BR-40 message; a customer with no row gets one; once the released event applies, the real status; a hold that fails still quarantines and writes its ERROR line.
- **18.24:** exactly one ERROR line per quarantined event, after commit, matching `Outbox event quarantined after 10 failed attempts \((CustomerRiskEvaluated|ComplianceDecisionRecorded|unknown)\)`, no customer number; `risk.outbox.quarantined` increments by one with no tags; nothing on rollback. **18.24b:** after requests with customer number `C-ID-SECRET`, no meter id, name or tag contains it.
- **18.25 (`ReleaseQuarantinedEventUseCaseTest`):** each refusal in order (customer not found before a bad justification; justification before nothing quarantined; nothing quarantined; conflict); success releases the oldest of two migrated quarantined rows and records actor, time, customer and justification.
- **18.26 (resource IT):** Operations through a BFF token: 204, no body; no token 401; 20 000 bytes 413 before the role; non-JSON 400 before the role; `ONBOARDING_SYSTEM` and `MLRO` 403; other centre 404; the 400, 422 and 409 codes; two concurrent releases: one 204, one 409.
- **18.27 (V6):** `ck_outbox_status` present and `outbox_status_check` gone; UPDATE, DELETE and TRUNCATE on `quarantine_release` refused for owner and service role (SQLState `P0001` or insufficient privilege); the service role can insert; parked rows converted and held.
- **18.28:** `OPERATIONS` gets 403 on evaluate, compliance view, front-office status and each decision kind, before any input is read.
- **ArchUnit:** `ArchitectureRulesTest`, `RiskModuleArchitectureRulesTest`, `RiskWebRulesTest`, `RiskSchemaAccessRulesTest` green with `CallerIdentificationResource`, `UnknownRouteResource`, `ReleaseQuarantinedEventResource`, `OptionsRequestAnswer`, `RegisteredClientTrust`, `RegisteredClientSslContext`, `QuarantineSqlAdapter`, none in `domain/` except the command 3 types.

## 15. Deferred to the BFF increment (D-34)

1. **Routes, sign-in, tokens, cookies, what the BFF never does:** v4 sections 4 to 6 and 10; v5 sections 5 and 6.
2. **Sessions and Redis:** v4 section 7, v5 section 6, v6 section 5, v7 section 5, v8 section 6, under D-31 (one live session), D-32 (15 min idle, 8 h absolute; the absolute timeout is also the retired-key period) and D-33 (local sign-out). BFF dependency decision (Redis client, session store, Entra client).
3. **Origin, CSRF and the two calls:** v4 section 8; v8 sections 3.1, 3.3 to 3.7 and 4 (identify with a body-less `GET`, read at most 16 385 bytes only after a 204, forward with a known length, 10 s deadlines). The release route of 10.4 follows the same two calls (its order puts size and readability before the role).
4. **Repeat after a refresh (F4), fixed now:** after a 401 and one refresh, the BFF repeats the **identify** call first and sends the buffered body again only on its 204; at most one refresh per client request (amends v8 3.4).
5. **BFF logging:** v7 3.4 and v8 3.6 (one line, generated correlation id), now with D-27 (coarse outcome `ok`, `refused`, `unavailable`, never status, code or template) and D-35 (the same fields on the risk read routes).
6. **BFF-side answers before identification:** the BFF's 404 for an unknown route and 403 for origin or CSRF (D-26), its firewall and Tomcat refusals (D-36), and the BFF's own pins (`org.springframework.web` INFO, `include-stacktrace: never`, `connection-timeout: 5s`).
7. **Tests deferred:** 18.10, 18.11, 18.11a, 18.11b, 18.11d, 18.13, 18.14 to 18.14d, 18.15 to 18.15c, and the BFF half of 18.17.
8. **Operations sign-in (D-37)** and the BFF's client certificate and Entra registration.

## 16. Verification status (2026-11-27)

Files under `/tmp/claude-0/-home-user-kyc-aml/52ec4e60-260e-5863-9603-67b60a05607e/scratchpad/v9/`:

- **Spring MVC 7.0.10:** `mvc-RequestMethodsRequestCondition.txt` (OPTIONS with no methods 13 to 59; pre-flight 0 to 12; `matchPreFlight` 0 to 13); `mvc-AbstractHandlerMethodMapping.txt` (`lookupHandlerMethod` 13 to 64, 315); `mvc-RequestMappingInfoHandlerMapping.txt` (`handleNoMatch` 45 to 108); `mvc-HttpOptionsHandler.txt`; `mvc-AbstractHandlerMapping.txt` (CORS 196 to 212, `getCorsHandlerExecutionChain` 1 to 38); `mvc-FrameworkServlet.txt` (`service` 0 to 29). **Spring Web:** `web-DefaultCorsProcessor.txt` (1 to 33), `web-CorsUtils.txt`.
- **Spring Security 7.1.2:** `sec-HttpSecurityConfiguration.txt` (`applyCorsIfAvailable` 4 to 19); `sec-MetadataFilter.txt`, `sec-PathPatternRequestMatcher.txt`, `sec-HttpMethodRequestMatcher.txt` (GET, exact); `sec-StrictHttpFirewall.txt` (eager checks, `setUnsafeAllowAnyHttpMethod`, `setAllowedHeaderValues`; `FilterChainProxy` default `HttpStatusRequestRejectedHandler`); `jose-signatures.txt` (`setJwtValidator`, `createDefaultWithIssuer`, `JwtAudienceValidator(String)`, thumbprint validator without `cnf`).
- **Tomcat 11.0.27:** `tc-CoyoteAdapter.txt`, `tc-postParseRequest.txt`, `tc-Http11Processor.txt`, `tc-Http11InputBuffer.txt` (section 4.1 offsets); `tc-SSLContext-interface.txt`; `tc-AbstractEndpoint.txt` (`createSSLContext` 122 to 192, `createSSLEngine` 435 to 503, `releaseSSLContext` 30 to 49); `tc-SSLHostConfigCertificate.txt` (`setSslContext`, `getSslContext`); `tc-continue-timing.txt` (`setContinueResponseTiming`, `findSslHostConfigs`, `JSSESSLContext` package-private).
- **Spring Boot 4.1.2:** `boot-TomcatWebServerFactory.txt` (66, 130, 190); `boot-SslConnectorCustomizer.txt`; `boot-DefaultSslManagerBundle.txt`; `boot-PemSslStoreBundle.txt`; `boot-ssl-bundle-props.txt` (`certificate`, `reloadOnUpdate`); `boot-observation-autoconfig.txt`; Micrometer: `central-*.pom`, `boot-micrometer-metrics-autoconfig.txt`, BOM `spring-boot-dependencies-4.1.2.pom` lines 150, 1878, 2593.
- **Spring Beans 7.0.10:** `beans-resolveInstance.txt` (NullBean, required, 5 to 26). **Nimbus 10.9.1:** `nimbus-signatures.txt`. **JDK 25.0.4.1:** `jdk-trust.txt`; v8 `jdk-pkix-leaf-shortcut.txt`, `jdk-default-tm-alg.txt`.
- **Repository (read):** the classes and tests named in sections 3 to 12, migrations V1 to V5, `application.yml` (no `app.booking-centres`, no web pin), `OutboxRelay` (no per-profile order today: a later row of a failing profile is projected), `ViewProjector`, `RiskPermissions` (`anyIdentifiedCaller`), checkstyle patterns.
- **Not checked (stage 3):** the PostgreSQL constraint name; TLS 1.3 refusal reporting; that the service role may insert into `quarantine_release`; the meter list of a running context; every tenant fact.

## 17. Decision requested

What the product owner must still decide:

1. **Owner of the BFF:** code, Entra registration, certificates, session store (D-34). The BFF increment cannot start without it; until then no person reaches the service and no quarantined event can be released (D-37).
2. **Size limit for command 3 (D-09).** D-09 names 64 KiB (evaluation) and 16 KiB (decision); command 3 refuses "request too large" with no number. Proposed: 16 KiB, as a decision, since a release carries one justification under BR-34.
3. **Where the quarantine metric goes (D-39).** The counter exists in the process; the service exposes no scrape endpoint (no actuator, D-24, D-36), so export needs a push registry from the Micrometer BOM for the bank's monitoring system, which only operations can name.
