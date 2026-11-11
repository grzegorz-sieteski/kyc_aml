# ADR: Backend-for-frontend boundary, third draft (v3)

> Replaces `docs/adr/bff-boundary-v2.md`, audited on 2026-11-11 as `[CHANGES REQUIRED]` with four blockers. v2 and the first draft stay as the audit record. Everything in v2 the audit did not fault is kept here; what changed is listed in section 1.

- **Mode:** `@architect`, design mode, stage 2.
- **Status:** Proposed. Section 19 lists what the product owner still decides.
- **Date:** 2026-11-11
- **Scope:** the boundary between browser, BFF, risk service and onboarding system, and the identity changes in the service (`risk/adapters/web`, `application.yml`). No change to `domain/`. No user interface (D-03).
- **Inputs:** AGENTS.md; spec D-03, D-09, D-13 to D-21, BR-01 to BR-04, BR-37, BR-39 to BR-46, A-02, A-29; design section 10, addenda (3) and (4) and both "Decision 2026-11-11" entries; `bff-boundary-v2.md` with its audit banner; `bff-boundary.md` with the product owner's answers; the committed code in `risk/adapters/web/`, `risk/adapters/config/RiskProperties.java` and `application.yml`.

## 1. Audit findings of v2 and where v3 answers them

| Finding (v2 audit) | Answer in v3 |
|---|---|
| Blocker 1: a rate-limit refusal is not served from the last known keys (limiter outside the outage layer) | Section 12: own source chain with `LastKnownKeySetSource` above the cache; Nimbus `OutageTolerantJWKSetSource` is not used |
| Blocker 2: an unregistered certificate from a trusted CA reaches the key fetch | Section 11: the certificate subject selects the decoder through `authenticationManagerResolver`; an unregistered subject gets a decoder with an empty, immutable key set: 401, zero fetches |
| Blocker 3: outage window is the library default (cache TTL x 10 = 50 min) | Section 12: one window, `app.identity.key-set.last-known-window: 300s` (D-21), in a validated record; no builder default is reachable |
| Blocker 4: the BFF logs refusal codes of decision and risk routes (BR-43) | Section 13: a code is logged only for the BFF's own refusals |
| A service 401 deletes the session | Section 5.4: refresh once, retry once, then pass the 401 through; only an Entra-refused refresh ends the session |
| Pre-login state not bound to the browser | Section 5.1: separate `__Host-` cookie, `SameSite=Lax`, 5 minutes; section 5.2 lists every callback check |
| Logout claims to end the Entra session | Section 5.5: logout is local only; Entra revocation is a product-owner question |
| The BFF's 413 precedes identification (BR-37) | Section 8: the BFF no longer answers 413; it forwards at most `limit + 1` bytes and the service decides in BR-37 order |
| Server certificate, multipart and form filter unspecified | Sections 11.4 and 8 |
| Redis and refresh failure modes; AES-GCM nonce and associated data; non-atomic session index | Section 7 |
| Onboarding map unspecified | Section 11.3: `@Validated @ConfigurationProperties(prefix = "app.identity")` record and a startup cross-check |
| BFF in this increment? Customer number in logs; BFF routes; D4 handshake claim | Sections 3, 13, 4 and D4 |

## 2. Decisions

- **D1.** Only the BFF holds tokens. The browser holds an opaque session cookie and never sees a token.
- **D2.** The BFF is a confidential OpenID Connect client (authorization code flow with PKCE, certificate credential). Its access token is for the service's scope and is issued to the BFF's registration. No on-behalf-of exchange. (To confirm in the tenant: `azp` is the BFF's client id.)
- **D3.** The service identifies the application as well as the user (D-19). Human roles only from a user token whose `azp` is the BFF; `ONBOARDING_SYSTEM` only from an app-only token of a registered onboarding application, whose booking centre comes from configuration.
- **D4.** TLS on every connection to the service, with a client certificate (D-20). The handshake checks only that the certificate chains to the trusted CA: a connection without one ends there. A certificate from that CA whose subject is not registered **passes the handshake** and is refused at the first request with a token by the constant 401, before any key fetch (section 11). The handshake is not the registration check; the subject check is.
- **D5.** A user whose claims do not identify them gets the service's constant 401, passed through unchanged.
- **D6.** Each registered application has its own decoder and so its own key source, rate limit, cache and last-known keys. An unregistered subject has none.
- **D7.** The service keeps verifying with the last successfully fetched key set for at most 300 s after that fetch, whatever refused the refresh: the rate limiter or the identity provider (D-21).
- **D8.** Redis holds only encrypted confidential material, bound to its session (section 7).
- **D9.** On a state-changing route the BFF checks session, origin, CSRF header, then reads at most `limit + 1` bytes. It parses no body and refuses no size itself.

## 3. Increment

The BFF is **not built in this increment** and not in this repository. D-03 excludes the user interface, and the BFF has no other client. This ADR is its contract. The service-side changes (sections 11 and 12) **are** in this increment: D-19 to D-21 are confirmed rules of this service, and onboarding needs them. Until a BFF exists, the human routes have no caller that the service accepts. Whether the BFF is built now is the product owner's (section 19).

## 4. BFF routes (complete)

| Method and path | Purpose | Forwarded to the service |
|---|---|---|
| `GET /auth/login` | Start sign-in | no |
| `GET /auth/callback` | Finish sign-in | no |
| `POST /auth/logout` | End the BFF session | no |
| `GET /api/session` | Returns `{"csrfToken": ...}` only: no identity, no role | no |
| `POST /api/risk-profiles/{customerNumber}/decisions` | Record compliance decision | yes, path byte for byte |
| `GET /api/risk-profiles/{customerNumber}` | Compliance view | yes |
| `GET /api/risk-profiles/{customerNumber}/front-office-status` | Front-office status | yes |

Any other method or path, Evaluate included, gets the service's constant 404 `request-not-handled` (I-07) from the BFF without a call. No static content (D-03). No list, search or enrichment route.

## 5. Sign-in, tokens and cookies

**5.1 Login.** `GET /auth/login` creates `state`, `nonce` and a PKCE verifier (S256), and a random 256-bit pre-login id. Redis stores the three values under `bff:prelogin:<HMAC of the id>` with a TTL of 300 s. The browser gets a separate cookie `__Host-bff-login=<id>`: `Secure`, `HttpOnly`, `SameSite=Lax` (the callback is a top-level navigation from Entra, which `Strict` would not carry), `Path=/`, no `Domain`, `Max-Age=300`. Then 302 to Entra's authorize endpoint.

**5.2 Callback.** `GET /auth/callback`, every check in this order; any failure creates no session and answers the constant 401:
1. The `__Host-bff-login` cookie is present; its record exists and is deleted at once (single use; `GETDEL`). The cookie is cleared in the answer whatever the outcome.
2. `state` equals the record's. An `error` parameter from Entra ends here.
3. The code is redeemed at the token endpoint with the PKCE verifier and the certificate credential, over TLS to the configured tenant only.
4. ID token: signature with an `alg` of RS256 against the BFF's own Entra key source; `iss` equals the tenant's v2.0 issuer; `aud` equals the BFF's client id; `exp` in the future and `iat`/`nbf` not in the future (default skew); `nonce` equals the record's; `tid` equals the configured tenant (to confirm in the tenant). The ID token is then discarded.
5. Access token: `token_type` is `Bearer`; signature against the same key source; `iss` as above; `aud` equals the service's client id; `azp` equals the BFF's client id; `exp` in the future; `scp` contains the service's scope; `oid` equals the ID token's `oid`. (To confirm in the tenant: a v2.0 access token for the service's own API is verifiable with the tenant keys.) The BFF does not read role or booking centre: the service decides them.
6. A refresh token is present.
7. New session id (256 bits, no fixation), session record written (section 7), 302 with the session cookie.

**5.3 Session cookie.** `__Host-bff-session`, `Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/`, no `Domain`, no `Max-Age`. Lifetime on the server. A link from another site opens logged out: accepted.

**5.4 A service 401.** The BFF refreshes the access token once (unless it was already refreshed for this request), stores the result, and repeats the request once with the same bounded bytes. Whatever the service then answers passes through, a 401 included; the session stays. Repeating a `POST` here is safe: the service identifies before anything else (BR-37) and a refused request records nothing (BR-42). Only an Entra refusal of the refresh (`invalid_grant`) deletes the session and answers the constant 401. Entra unreachable or 5xx on refresh: constant 503, session kept.

**5.5 Logout.** `POST /auth/logout` with the CSRF header. The BFF deletes the session record and its index entry (section 7) and clears the cookie with `Max-Age=0`. **Logout is local only:** the Entra session and the refresh token are not revoked, so a stolen refresh token stays usable at Entra until it expires. Whether to revoke at Entra is open (section 19).

**5.6 Lifetimes.** Idle and absolute timeouts: no value in the spec (section 19). Each Redis key's TTL is the shortest of idle timeout, absolute timeout and refresh-token expiry.

## 6. What the BFF never does

1. No token in a URL, log, response or browser; the browser's `Authorization` header and cookies are not forwarded.
2. No caching of `/api/**`; every answer carries `Cache-Control: no-store`.
3. No change to a service answer: status, headers it forwards, `type`, `title`, `detail`, `instance` pass through byte for byte.
4. No decision the service makes: no role, input, size or existence check. Its own refusals are session (401), origin and CSRF (403), unknown route (404), and availability (503, 504).
5. The path is forwarded byte for byte. No identity from a client header (`X-Forwarded-*` included).
6. No automatic retry of `POST`, except the single repeat after a 401 (section 5.4).
7. No Evaluate route (Command 1 is the onboarding system's).

## 7. Redis

| Control | Decision |
|---|---|
| Content | Per session: encrypted access and refresh tokens, their expiry, the CSRF token, the session's index key. No ID token, customer number, risk data or response |
| Encryption | AES-256-GCM in the application. Key from the secret store, identified by a key id stored with the record. Per encryption a fresh 96-bit nonce from `SecureRandom`, never reused; a key is retired after 2^31 encryptions or on rotation, whichever comes first. Associated data: `bff:v1 | session id | key id | field name`, so a ciphertext moved to another session, key or field fails authentication. A record that fails to decrypt is deleted: constant 401 |
| Transport and storage | Redis over TLS; persistence off or volume encrypted. Client and properties not checked (section 16) |
| Access | Dedicated ACL user on `bff:*`; no `KEYS`, `SCAN` or admin command |
| Eviction | `maxmemory-policy noeviction`; a live session is never evicted |
| One live session per user | Sign-in runs one Lua script (atomic): read `bff:user:<HMAC of oid>`, delete the session it names, write the new session record and the index, both with the same TTL (section 5.6). Logout and expiry delete the index only if it still names this session (compare-and-delete in Lua). The HMAC key is secret |
| Booking centre | A session belongs to the one booking centre of its token; no key contains a customer number or booking centre (BR-04) |

**Failure modes.** None of them deletes a session, except where stated:

| Failure | Answer |
|---|---|
| Redis unreachable (read or write) | Constant 503 |
| Redis full (`OOM` under `noeviction`) on sign-in, refresh or CSRF write | Constant 503; existing sessions with a valid access token keep working (reads succeed) |
| Refresh succeeded at Entra, Redis write failed | Constant 503; the new tokens are dropped from memory, the old record stays. If Entra rotated the refresh token and refuses the old one next time, that refusal ends the session (5.4). (To confirm in the tenant: whether the previous refresh token stays valid after rotation.) |
| Decryption failure | Record deleted, constant 401 |

## 8. CSRF, the cookie session and body bounds

Order on `POST`:
1. **Session.** Cookie to record. None: constant 401; no body read.
2. **Origin.** `Sec-Fetch-Site` must be `same-origin`; `Origin`, if present, the BFF's origin. Else the BFF's constant 403; no body read.
3. **CSRF token.** Synchronizer token from the session record, sent in header `X-CSRF-TOKEN`. The BFF installs a `CsrfTokenRequestHandler` that reads the header only: the default falls back to `request.getParameter`, which parses a form body (checked in bytecode, v2). Mismatch: constant 403; no body read.
4. **Body.** The BFF reads at most `limit + 1` bytes (16 KiB + 1 for the decision route, D-09), as `BoundedBody` does, whatever `Content-Length` says, and forwards exactly the bytes read with `Content-Type` and the correlation id. It answers no 413 itself: the service identifies the caller first and then refuses an oversized body as `request-too-large` (BR-37 order kept). After answering, the BFF closes the browser connection if bytes remain unread.
5. **Forward.** The service decides role, then input.

Mirrored from the service: `spring.servlet.multipart.enabled: false` and `spring.mvc.formcontent.filter.enabled: false` (both names checked in Boot metadata), so no multipart or form body is parsed before the session check.

## 9. Refusals through the BFF

| Situation | Browser receives | Basis |
|---|---|---|
| No session, expired, logged out, decryption failure | Constant 401, the service's caller-not-identified bytes (instance `/api/risk-profiles`, `WWW-Authenticate: Bearer`, `no-store`) | BR-01, I-04 |
| Service 401 | Refresh once, repeat once, then the service's answer unchanged; session kept | BR-01; 5.4 |
| Entra refuses the refresh | Constant 401; session deleted | D-13 |
| Service 403, 400, 404, 409, 413, 422 | Unchanged | Service catalogue |
| BFF origin or CSRF refusal | BFF's constant 403, no risk word | Section 8 |
| Route not in section 4 | Service's constant 404 `request-not-handled`, no call | I-07 |
| Service 5xx, unexpected status, unreachable, TLS failure; Redis or Entra unavailable | Constant 503 | Operational |
| `POST` timeout | Constant 504: outcome unknown, reload before repeating | 6.6 |

The BFF's own refusals are constant texts with no reason that refers to risk (BR-41).

## 10. Role by operation by channel

Unchanged from v2. "403" is the service's `permission-denied`; "no route" means the BFF exposes none.

| Role | Channel | Evaluate | Escalate | Freeze | Clear | Compliance view | Front-office status |
|---|---|---|---|---|---|---|---|
| ONBOARDING_SYSTEM | Onboarding | yes | 403 | 403 | 403 | 403 | yes (A-02 "every role") |
| AML_ANALYST_L1 | BFF | no route | yes | 403 | 403 | yes | yes |
| AML_ANALYST_L2 | BFF | no route | yes | yes | yes | yes | yes |
| MLRO | BFF | no route | yes | yes | yes | yes | yes |
| FRONT_OFFICE | BFF | no route | 403 | 403 | 403 | 403 | yes (neutral message, BR-40) |

A human role in an app-only token, `ONBOARDING_SYSTEM` in a user token, a user token with an onboarding `azp`, an app-only token with the BFF's `azp`, and any token presented on another application's certificate: 401.

## 11. Identity in the service

All new classes in `com.gs5.kycaml.risk.adapters.web`, package-private, beside `BearerTokenSecurityConfiguration`. `ActorContextResolver.resolve()` and `identify()` keep their signatures.

**11.1 Order of checks for a request with a token.**
1. **Certificate subject.** `BearerTokenAuthenticationFilter` extracts the token from the header, then calls `AuthenticationManagerResolver.resolve(request)`, then `authenticate` (bytecode order checked in `spring-security-oauth2-resource-server-7.1.2.jar`). `CertificateSubjectManagerResolver implements AuthenticationManagerResolver<HttpServletRequest>` reads `jakarta.servlet.request.X509Certificate` (Tomcat `SSLSupport.CERTIFICATE_KEY`, checked), takes the leaf's `getSubjectX500Principal()`, and looks it up in a `Map<X500Principal, AuthenticationManager>` built at startup (`X500Principal.equals` compares canonical forms). No certificate or an unregistered subject: the `refuseEvery` manager, a `JwtAuthenticationProvider` over a decoder whose key source is `new ImmutableJWKSet<>(new JWKSet())`. Every token fails signature selection: constant 401, zero fetches, no WARN.
2. **Signature and key fetch** with that application's decoder (section 12): `NimbusJwtDecoder.withJwkSource(keys).jwsAlgorithm(RS256).build()`, wrapped in `new ProviderManager(new JwtAuthenticationProvider(decoder))`.
3. **Claims**, in the decoder's validator: `JwtValidators.createDefaultWithIssuer(issuer)`, `JwtAudienceValidator`, `ConfiguredIdentityValidator` (unchanged), then `ApplicationTokenValidator`: `azp` equals this application's client id; for the BFF, `scp` present (user token); for onboarding, `scp` absent (app-only) and `roles` contains `ONBOARDING_SYSTEM` (to confirm in the tenant: `azp`, `scp`, `idtyp`, `roles` as stated).
4. **Caller.** `CallerClaims.callerOf(Jwt)` reads `azp`: BFF branch as today (`oid`, exactly one human role, `booking_centre` claim); onboarding branch (`oid`, role `ONBOARDING_SYSTEM`, booking centre from the registration, no booking-centre claim read). Then `identify()` keeps the in-scope check.

The configurer takes `.authenticationManagerResolver(resolver)` instead of `.jwt(...)`; Spring Security 7.1.2 states that the resolver takes precedence over `jwt()` (string in `OAuth2ResourceServerConfigurer`). The bean `rateLimitedKeySet` and `noKeyWhenLimited` are removed; the header-only token resolver, the failure-handler post-processor, the firewall and the 404 filter stay.

A request without a token, or to an unknown path, is answered as today (resource 401, firewall 400, 404): these answers do not depend on the certificate, the same reasoning as the LOW items accepted on 2026-11-11.

**Why subject selection, not one CA per application.** Boot's listener has one trust store (`server.ssl.bundle`, one bundle); `server-name-bundles` selects by server name (SNI), not by client. One CA per application would need one listener per application and would still accept any certificate that CA issues. The subject check is in code, is tested, and does not depend on how the PKI is organised. The deployment should still use a dedicated internal CA for these client certificates (section 14).

**11.2 Configuration.**

```java
@Validated
@ConfigurationProperties(prefix = "app.identity", ignoreUnknownFields = false)
record IdentityRegistry(@NotBlank String actorClaim, @NotBlank String roleClaim, @NotBlank String bookingCentreClaim,
        @NotNull @Valid Bff bff,
        @NotEmpty Map<@NotBlank String, @NotNull @Valid OnboardingApplication> onboardingApplications,
        @NotNull @Valid KeySet keySet) {
    record Bff(@NotBlank String clientId, @NotBlank String certificateSubject) {}
    record OnboardingApplication(@NotBlank String bookingCentre, @NotBlank String certificateSubject) {}
    record KeySet(@NotNull Duration cacheTimeToLive, @NotNull Duration rateLimitInterval,
                  @NotNull Duration lastKnownWindow) {}
}
```

- Keys of `onboarding-applications` are client ids. `@AssertTrue` methods: every `certificate-subject` parses as an `X500Principal` and all subjects (BFF included) are distinct; every `booking-centre` passes the canonical rule of `ActorContext.parse`; no onboarding client id equals the BFF's; `0 < rate-limit-interval < cache-time-to-live < last-known-window <= 300s`.
- The existing claim names move into this record (`CallerClaims` takes it instead of `@Value`), so `ignoreUnknownFields = false` refuses a misspelt key under `app.identity`.
- **Boot ignores a wrong prefix silently** (an `app.identiy.onboarding-applications...` key binds to nothing). The guards: `@NotEmpty` on the map and `@NotNull` on `bff` and `key-set` stop startup, and `ProductionIdentitySettingsTest` pins every key in `application.yml`.
- **Cross-check against `app.booking-centres`.** A second validated record `OnboardingCentresInScope` at prefix `app` binds `bookingCentres` (`Map<UUID, Jurisdiction>`, as `RiskProperties`) and `identity` (`IdentityRegistry`), with `@AssertTrue isEveryOnboardingCentreConfigured()`: every registered booking centre is a key of `app.booking-centres`. Validation failures stop startup without a `throw` in `src/main`, as `IdentitySettings` does.

`application.yml` (environment variables, no defaults):

```yaml
app.identity:
  bff: { client-id: ${BFF_CLIENT_ID}, certificate-subject: ${BFF_CERTIFICATE_SUBJECT} }
  key-set: { cache-time-to-live: 60s, rate-limit-interval: 30s, last-known-window: 300s }   # D-21
```

The map is supplied per deployment as `app.identity.onboarding-applications.<client-id>.booking-centre` and `.certificate-subject`; a missing map fails `@NotEmpty`.

**11.3 The service's server certificate and client authentication** (names checked in Boot metadata and fields of `PemSslBundleProperties`):

```yaml
server.ssl: { enabled: true, bundle: service-tls, client-auth: need }
spring.ssl.bundle.pem.service-tls:
  keystore:   { certificate: ${SERVICE_TLS_CERTIFICATE}, private-key: ${SERVICE_TLS_PRIVATE_KEY} }
  truststore: { certificate: ${CLIENT_CA_CERTIFICATE} }
```

## 12. Key source chain (D-21, F1)

**Why v2 failed (checked in `nimbus-jose-jwt-10.9.1.jar`).** `JWKSourceBuilder.build()` wraps, from inside out: `URLBased`, `Retrying`, `OutageTolerant`, `HealthReporting`, `RateLimited`, `Caching`, then `JWKSetBasedJWKSource`. `OutageTolerantJWKSetSource.getJWKSet` catches only `JWKSetUnavailableException`; `RateLimitedJWKSetSource` throws `RateLimitReachedException`, a sibling under `KeySourceException`; and it sits inside the limiter anyway. When `outageTolerant` is on without a TTL, the builder sets it to cache TTL x 10 (`ldc 10L; lmul`). The builder is therefore not used.

**Chain, built by hand per application in `BearerTokenSecurityConfiguration`:**

```
JWKSetBasedJWKSource
  └ LastKnownKeySetSource(window = key-set.last-known-window)          new, risk/adapters/web
     └ CachingJWKSetSource(ttl = key-set.cache-time-to-live, refresh timeout = DEFAULT_CACHE_REFRESH_TIMEOUT)
        └ RateLimitedJWKSetSource(min interval = key-set.rate-limit-interval)
           └ URLBasedJWKSetSource(JWK_SET_URI, DefaultResourceRetriever(2 000 ms, 2 000 ms, DEFAULT_HTTP_SIZE_LIMIT))
```

**`LastKnownKeySetSource<C extends SecurityContext> extends JWKSetSourceWrapper<C>`.**
- Fields: `private final long windowMillis;` `private final AtomicReference<@Nullable FetchedKeySet> last;` with `record FetchedKeySet(JWKSet keys, long fetchedAtMillis)`.
- `getJWKSet(JWKSetCacheRefreshEvaluator refresh, long now, C context) throws KeySourceException`: `try { var keys = getSource().getJWKSet(refresh, now, context); remember(keys, now); return keys; } catch (RateLimitReachedException | JWKSetUnavailableException refused) { return lastKnownAt(now); }`. Two named types, no catch-all, no `throw`. Any other `KeySourceException` propagates and ends, as today, in the constant 401 with one WARN (I-05).
- `remember`: `last.updateAndGet(previous -> previous != null && previous.keys() == keys ? previous : new FetchedKeySet(keys, now))`. The cache returns the same instance while it is valid, and a new instance after every successful fetch, so `fetchedAtMillis` is the time of the last **successful fetch**, not of the last cache hit.
- `lastKnownAt(now)`: the last set if `now - fetchedAtMillis < windowMillis`, else `new JWKSet()` (no key: the token fails as an ordinary invalid token, constant 401). One WARN when the window lapses and one INFO when a fetch succeeds again, per transition, never per request, with no URL or token.
- **Why above the cache.** Below it, the cache would store the served last set again with a fresh TTL and stretch the 300 s. Above it, nothing re-caches what it serves. Why not Nimbus's outage layer: it never sees the limiter's refusal, and two windows would make D-21's bound depend on two settings.

**What a token gets** (t0 = last successful fetch; cache 60 s, limiter 30 s, window 300 s):

| Time since t0 | Known `kid` | Unknown `kid` |
|---|---|---|
| 0 to 60 s | From the cache, no fetch: verified | Refresh attempt; within the limiter's budget a fetch (a success restarts t0, the `kid` is still unknown); over budget the last set: 401 |
| 60 to 300 s, every refresh refused (flood) or failing (outage) | The cache tries a load, the limiter refuses or Entra fails, `LastKnownKeySetSource` serves the t0 set: **verified** | Last set has no match: 401 |
| 300 s or later with no successful fetch | Empty set: **401** | 401 |

While Entra answers, the limiter's own budget still yields successful fetches during a flood, which restart t0. A flood alone no longer refuses a known key; the 300 s lapse needs fetches to fail. A key Entra withdraws is dropped at the next successful fetch, and stays usable at most 300 s after the last successful fetch (D-21).

## 13. What the BFF logs (BR-43, A-29)

One line per request: timestamp, method, **route template** (e.g. `/api/risk-profiles/{customerNumber}/decisions`), status, duration, correlation id (generated, forwarded to the service as `X-Correlation-Id`). A refusal code is logged **only** for the BFF's own refusals: `bff-session-missing`, `bff-origin-refused`, `bff-csrf-refused`, `request-not-handled`, `bff-unavailable`, `bff-timeout`. For a forwarded answer on a decision or risk route only the status is logged; the service's `type` is never read into a log.

Never logged: customer number, a filled-in path, token, cookie, CSRF value, `Authorization`, body, claim, `oid`, booking centre, risk word. Tomcat access log off (`server.tomcat.accesslog.enabled: false`, checked). `org.springframework.security` and `org.springframework.web` at INFO; `spring.mvc.log-request-details` off.

**Customer number: open.** A-29 allows the customer number in a log, but never together with risk information. A decision-route line with a status (200, 422) is information about a decision for that customer, so this draft keeps the number out of every BFF line. The product owner decides (section 19).

## 14. Deployment controls, each with a test

Deployment controls like D-11 in AGENTS.md; each runs as a test at every deployment.
1. No route from the internet to the service: a connection from an external test host is refused.
2. Ingress only from the BFF and onboarding segments: an unlisted internal host is refused.
3. Mutual TLS: no client certificate, or one from another CA, fails the handshake; the BFF's and each onboarding certificate succeed. A certificate from the trusted CA with an unregistered subject passes the handshake and gets 401 (section 11; test in section 17).
4. A dedicated internal CA issues only these client certificates.
5. The BFF refuses a service certificate outside its trust store. The 404 for `/.well-known/oauth-protected-resource/**` still holds.

## 15. Consequences and residual risks

- F1 from the BFF's segment is closed by subject selection and per-application budgets. A compromised BFF host and certificate can still spend the BFF's own budget, never onboarding's; known keys keep working (section 12).
- A compromised BFF can act for its users within their rights. Mitigations: certificate key in a vault, short token lifetimes, alerting on refusal rates.
- A revoked signing key may verify for up to 300 s after the last successful fetch (D-21, accepted by the spec).
- Logout leaves the Entra session and refresh token alive (5.5).
- Redis unavailable or full: 503, sessions kept; failure is closed for sign-in and refresh.
- A new onboarding application, or a changed booking centre, is a configuration change and restart.
- Certificates need issuance, rotation and revocation: removal from the registry is effective at restart; no CRL or OCSP in this design.

## 16. Verification status

**Checked with `javap` / jar contents in `~/.m2` (no Maven run, no test run, nothing compiled):**
- `nimbus-jose-jwt-10.9.1.jar`: `JWKSourceBuilder.build()` wrapping order (bytecode); `outageTolerant(long)`, `outageTolerant(boolean)`, `outageTolerantForever()`; default outage TTL = cache TTL x 10, or 3 000 000 ms without cache; the builder refuses a rate interval not below the cache TTL; `DEFAULT_CACHE_TIME_TO_LIVE` 300000, `DEFAULT_RATE_LIMIT_MIN_INTERVAL` 30000, `DEFAULT_CACHE_REFRESH_TIMEOUT` 15000, `DEFAULT_HTTP_SIZE_LIMIT` 51200; `OutageTolerantJWKSetSource.getJWKSet` exception table catches only `JWKSetUnavailableException`; `RateLimitedJWKSetSource` throws `new RateLimitReachedException()`; `RateLimitReachedException extends KeySourceException`, `JWKSetUnavailableException extends KeySourceException` (siblings), `JWKSetRetrievalException` and `JWKSetParseException extend JWKSetUnavailableException`; `CachingJWKSetSource` rethrows its source's exception after unlocking and throws `JWKSetUnavailableException` on refresh timeout; `JWKSetBasedJWKSource.get` reads with `noRefresh()` and refreshes with `referenceComparison(...)` on no match; public constructors of `URLBasedJWKSetSource`, `RateLimitedJWKSetSource`, `CachingJWKSetSource`, `JWKSetBasedJWKSource`, `ImmutableJWKSet`, `JWKSetSourceWrapper(JWKSetSource)` and its `getSource()`.
- `spring-security-config-7.1.2.jar`: `OAuth2ResourceServerConfigurer.authenticationManagerResolver(AuthenticationManagerResolver<HttpServletRequest>)`; the precedence message over `jwt()`.
- `spring-security-oauth2-resource-server-7.1.2.jar`: `BearerTokenAuthenticationFilter.doFilterInternal` calls the converter, then `resolve`, then `authenticate`; `JwtAuthenticationProvider(JwtDecoder)`.
- `spring-security-oauth2-jose-7.1.2.jar`: `NimbusJwtDecoder.withJwkSource(JWKSource)`, `JwkSourceJwtDecoderBuilder.jwsAlgorithm`, `setJwtValidator`; `JwtValidators.createDefaultWithIssuer`; `JwtAudienceValidator(String)`. `spring-security-core-7.1.2.jar`: `ProviderManager(AuthenticationProvider...)`, `AuthenticationManagerResolver.resolve`.
- `spring-boot-web-server-4.1.2.jar` metadata: `server.ssl.enabled`, `.bundle`, `.client-auth`, `.server-name-bundles`, `.trust-store`; `Ssl$ClientAuth.NEED`. `spring-boot-autoconfigure-4.1.2.jar`: `spring.ssl.bundle.pem`, `PemSslBundleProperties` fields `keystore`, `truststore`, `Store.certificate`, `Store.privateKey`. `spring-boot-webmvc-4.1.2.jar`: `spring.mvc.formcontent.filter.enabled`. `spring-boot-servlet-4.1.2.jar`: `spring.servlet.multipart.enabled`. `spring-boot-tomcat-4.1.2.jar`: `server.tomcat.accesslog.enabled`, `.pattern`. `spring-boot-4.1.2.jar`: `@ConfigurationProperties` has `ignoreUnknownFields`, `ignoreInvalidFields`.
- `tomcat-embed-core-11.0.27.jar`: `SSLSupport.CERTIFICATE_KEY` = `jakarta.servlet.request.X509Certificate`.

**Not checked:** Spring Session, the Redis client and Redis properties, Spring Security's OAuth2 client (no jars in `~/.m2`, only `spring-session-bom` 4.1.1); whether Boot binds `IdentityRegistry` nested inside `OnboardingCentresInScope` with both validations (test in section 17); behaviour of `X500Principal` matching for the subjects Entra-issued or internal-CA certificates actually carry.

**To confirm in the tenant:** `azp` in user and app-only tokens; `scp` present only in user tokens; `idtyp`; `roles` contents; `tid`; that the service's v2.0 access tokens verify with the tenant keys at the BFF; refresh-token rotation and lifetime; certificate credential support for the BFF registration; `accessTokenAcceptedVersion: 2` (I-10).

## 17. Tests required before stage 4

Stage 3 writes these red. Unit tests on the source chain pass `now` explicitly (every layer takes it as a parameter).
1. **Unknown-kid flood after cache expiry keeps a known key valid:** limiter exhausted, cache expired, t0 + 61 s to t0 + 299 s: a token with a known `kid` verifies; unknown `kid`s get 401; the key-set server sees no request beyond the limiter's budget.
2. **After 300 s without a successful fetch a known key is refused:** key-set server down, t0 + 300 s: the known `kid` gets the constant 401; at t0 + 299 s it verified.
3. A served last set is never re-cached with a new expiry (refused at t0 + 300 s even after many hits in between).
4. **An unregistered certificate from a trusted CA gets 401 with zero fetches:** the key-set server records zero requests; the answer is byte-identical to the other 401s.
5. A token of one application on another application's certificate: 401. A flood on one application's certificate leaves the other's known keys and budget untouched.
6. **A decision refusal produces no code in the BFF log** (BFF test suite: a forwarded 403, 404, 409, 422 on the decision route logs template and status only; a BFF CSRF refusal logs `bff-csrf-refused`).
7. **Empty or unknown onboarding map stops startup:** empty map; a key under a misspelt prefix; an unknown key under `app.identity`; a booking centre not in `app.booking-centres`; a non-canonical booking centre; two applications with one subject; `last-known-window` above 300 s or not above the cache TTL.
8. `ProductionIdentitySettingsTest` pins `key-set.last-known-window: 300s`, `server.ssl.client-auth: need` and the new keys.
9. Onboarding app-only token without `booking_centre` on its own certificate: accepted with the registered centre. The v2 claim combinations of section 10: 401 each.
10. BFF: service 401 then refresh then repeat (once only); Entra `invalid_grant` deletes the session; Redis write failure after refresh gives 503 and keeps the session; oversized decision body: the service answers 413 after identifying, an unidentified caller gets 401 first; callback refuses a missing or reused pre-login cookie, a wrong `state`, `nonce`, `aud`, `azp` or `oid`.

## 18. Not doing

Token introspection, per-request revocation, CRL/OCSP, refresh-ahead caching, Nimbus retrying, a `JwtDecoder` bean.

## 19. Decision requested

1. **BFF in this increment.** D-03 excludes the user interface. Build the BFF now, or only the service side (sections 11, 12) with the human routes unused until it exists.
2. **Entra model.** One confidential BFF registration with a certificate credential; one registration per onboarding application with its booking centre in configuration.
3. **Human roles of D-19.** Read as L1, L2, MLRO and Front-Office Employee.
4. **One session per user.** Read as one live session per user, bound to one booking centre.
5. **Session lifetimes.** Idle and absolute timeouts.
6. **Entra revocation at logout.** Keep logout local, or also revoke the user's refresh tokens at Entra.
7. **Customer number in the BFF's logs** (A-29). Keep it out of every BFF line, as proposed.
8. **Owner of the BFF:** code, Entra registration, certificates, Redis.
