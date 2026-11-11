# ADR: Backend-for-frontend boundary, fourth draft (v4)

> Replaces `docs/adr/bff-boundary-v3.md`, audited 2026-11-11 as `[CHANGES REQUIRED]`: one blocker, ten warnings, all answered in section 1. v2, v3 and the first draft stay as the audit record. "To confirm in the tenant" and "deployment check" mark what only Entra or a running deployment can show. Mechanisms were checked in the jars under `~/.m2` (section 17) unless listed as not checked.

- **Mode:** `@architect`, design mode, stage 2. **Status:** Proposed, 2026-11-11; section 20 lists what the product owner decides.
- **Scope:** the boundary between browser, BFF, risk service and onboarding system, and the identity changes in the service (`risk/adapters/web`, `application.yml`, `ProductionIdentitySettingsTest`). No change to `domain/`. No user interface (D-03).

## 1. Audit findings of v3 and where v4 answers them

| Finding (v3 audit) | Answer in v4 |
|---|---|
| Blocker: `LastKnownKeySetSource.remember` compares set identity and stamps the request's start time; out of order it can stretch the window and serve a replaced set | Section 12: a recorder between cache and rate limiter stores `(keys, fetchedAt)` at each real fetch, `fetchedAt` only moves forward; the serving layer above the cache reads only that record and checks the window with a clock read after the cache returns. Test 18.1 |
| W1: onboarding token needs exactly one role, `ONBOARDING_SYSTEM` (D-15) | Section 11.1 step 3; test 18.5 |
| W2: outage no longer logged | Section 12.5: one WARN per entry into "refresh failing", also at first use with no fetch ever succeeded; root-cause type only; no record means an empty set |
| W3: GUID client ids cannot be map keys from environment variables | Section 11.2: no map; three named registrations (`us`, `gb`, `de`), client id as a field; `azp` compared exactly |
| W4: mutual TLS breaks the integration tests | Section 14: test PKI per run, client presentation, shared test configuration |
| W5: the top key source reads the system clock | Section 12.6: window tests enter the serving layer with explicit `now` and an injected `Clock`; one IT uses the real clock |
| W6: `<= 300 s` allowed a smaller window than decided | Section 11.2: `0 < rate-limit-interval < cache-time-to-live <= last-known-window <= 300 s` (named constant); configured value is a deployment choice up to it |
| W7: certificate binding; TLS termination | Section 11.3: issuer and subject both compared. Section 15.4: TLS ends at the service's own Tomcat, with a test |
| W8: `jws-algorithms` unread | Section 12.6: RS256 pinned in code, property removed, test 18.8 |
| W9: increment scope decided and asked | Section 3: a proposal |
| W10: notes | Sections 5.1 (`response_mode=query`), 5.4 (back-off), 4 (CORS), 7 (Redis key, renewal, rotation), 11.1 (counter) |

Kept from v2, not faulted: last-known keys (D7), certificate subject checked before any key fetch (11.1), explicit window in validated configuration (11.2), BFF logs no service refusal code (13).

## 2. Decisions

- **D1.** Only the BFF holds tokens. The browser holds an opaque session cookie.
- **D2.** The BFF is a confidential OpenID Connect client (authorization code with PKCE, certificate credential); its access token is for the service's scope; no on-behalf-of exchange. (To confirm in the tenant: `azp` is the BFF's client id.)
- **D3.** The service identifies the application as well as the user (D-19). Human roles come only from a user token whose `azp` is the BFF. `ONBOARDING_SYSTEM` comes only from an app-only token of a registered onboarding application; its booking centre comes from that registration.
- **D4.** TLS with a client certificate on every connection (D-20). The handshake checks only the trusted CA. A certificate from that CA with an unregistered issuer or subject passes the handshake and gets the constant 401 at its first token, before any key fetch (11.1). The handshake is not the registration check.
- **D5.** A user whose claims do not identify them gets the service's constant 401, unchanged.
- **D6.** Each registered application has its own decoder, key chain, rate limit, cache and record. An unregistered certificate has none.
- **D7.** The service verifies with the last successfully fetched key set for at most 300 s after that fetch, whatever refused the refresh (D-21).
- **D8.** Redis holds only encrypted confidential material, bound to its session (section 7).
- **D9.** Onboarding has one registration per booking centre in scope (D-06): `us`, `gb`, `de`, each a separate Entra application (to confirm in the tenant), with its own decoder and budget.

## 3. Increment

**Proposal:** the BFF is not built in this increment, nor in this repository. D-03 excludes the user interface and the BFF has no other client; this ADR is its contract. The service-side changes (sections 11 and 12) are proposed for this increment, because D-19 to D-21 are confirmed rules and onboarding needs them. Whether the BFF is built now is open (section 20).

## 4. BFF routes (complete)

| Method and path | Purpose | Forwarded |
|---|---|---|
| `GET /auth/login` | Start sign-in | no |
| `GET /auth/callback` | Finish sign-in | no |
| `POST /auth/logout` | End the BFF session | no |
| `GET /api/session` | `{"csrfToken": ...}` only: no identity, no role | no |
| `POST /api/risk-profiles/{customerNumber}/decisions` | Record compliance decision | yes, path byte for byte |
| `GET /api/risk-profiles/{customerNumber}` | Compliance view | yes |
| `GET /api/risk-profiles/{customerNumber}/front-office-status` | Front-office status | yes |

Any other method or path, Evaluate included, gets the service's constant 404 `request-not-handled` (I-07) without a call. No static content, list, search or enrichment route (D-03). The BFF sends no CORS headers and answers no preflight (a preflight gets that 404). The browser and the BFF share one origin; a cross-origin request is refused by the origin check (section 8).

## 5. Sign-in, tokens and cookies

**5.1 Login.** `GET /auth/login` creates `state`, `nonce`, a PKCE verifier (S256) and a random 256-bit pre-login id. Redis stores the three values under `bff:prelogin:<HMAC-SHA-256 of the id>`, TTL 300 s. The browser gets `__Host-bff-login=<id>`: `Secure`, `HttpOnly`, `SameSite=Lax`, `Path=/`, `Max-Age=300`. The authorize request carries `response_mode=query`: with `form_post`, Entra would POST the callback cross-site, which a `Lax` cookie is not sent with. (To confirm in the tenant: the registration allows the code flow with query response mode.) Then 302 to Entra.

**5.2 Callback.** Every check, in order; any failure creates no session and answers the constant 401:
1. The pre-login cookie is present; its record exists and is deleted at once (`GETDEL`). The cookie is cleared whatever the outcome.
2. `state` equals the record's. An `error` parameter from Entra ends here.
3. The code is redeemed with the PKCE verifier and the certificate credential, over TLS to the configured tenant only.
4. ID token: `alg` RS256 against the BFF's own key source; `iss` the tenant's v2.0 issuer; `aud` the BFF's client id; `exp` future; `iat`/`nbf` not future (default skew); `nonce` equal; `tid` the configured tenant (to confirm in the tenant). Then discarded.
5. Access token: `token_type` `Bearer`; signature against the same source; `iss`; `aud` the service's client id; `azp` the BFF's client id; `exp` future; `scp` contains the service's scope; `oid` equal to the ID token's. The BFF reads no role or booking centre.
6. A refresh token is present. 7. New 256-bit session id (no fixation), record written (section 7), 302 with the session cookie.

**5.3 Session cookie.** `__Host-bff-session`: `Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/`, no `Max-Age`; lifetime on the server. A link from another site opens logged out: accepted.

**5.4 A service 401.** The BFF refreshes once (unless already refreshed for this request), stores the result and repeats once with the same bounded bytes. Whatever the repeat answers passes through, a 401 included, and the session stays. Repeating a `POST` is safe: the service identifies first (BR-37) and a refused request records nothing (BR-42). Only an Entra `invalid_grant` on refresh deletes the session (constant 401). Entra unreachable or 5xx: constant 503, session kept. *Back-off (proposal values):* if the repeat is also a 401, the record gets `backoffUntil = now + 60 s`; while set, a service 401 passes through after one call with no refresh and no repeat. A permanent 401 costs one call per request.

**5.5 Logout and lifetimes.** `POST /auth/logout` with the CSRF header deletes the record and its index entry and clears the cookie. **Logout is local only:** Entra's session and the refresh token stay live until they expire; revocation is open (section 20). Idle and absolute timeouts: no value in the spec (section 20). Each key's TTL is the shortest of idle timeout, absolute timeout and refresh-token expiry.

## 6. What the BFF never does

1. No token in a URL, log, response or browser; no `Authorization` or cookie forwarded.
2. No caching of `/api/**`; every answer is `Cache-Control: no-store`.
3. No change to a service answer: status, forwarded headers, `type`, `title`, `detail`, `instance` pass through byte for byte.
4. No decision the service makes: no role, input, size or existence check. Its own refusals are session (401), origin and CSRF (403), unknown route (404) and availability (503, 504).
5. The path is forwarded byte for byte; no identity from a client header, `X-Forwarded-*` included.
6. No automatic retry of `POST`, except the single repeat after a 401 (5.4). No Evaluate route. No CORS headers.

## 7. Redis

| Control | Decision |
|---|---|
| Content | Per session: encrypted access and refresh tokens and their expiry, CSRF token, index key, `backoffUntil`. No ID token, customer number, risk data or response |
| Encryption | AES-256-GCM in the application; key from the secret store, identified by a key id stored with the record. Fresh 96-bit random nonce per encryption (`SecureRandom`). Associated data `bff:v1 \| session id \| key id \| field name`. A record that fails to decrypt is deleted: constant 401 |
| Key rotation | By time, not by counter (a shared counter across instances is impractical). Each key encrypts for one period (proposal: 90 days, deployment choice); a retired key decrypts until its last record expires. The NIST SP 800-38D random-nonce bound (2^32 per key) is kept by the period (deployment check against measured volume) |
| Keys | Session record `bff:session:<HMAC-SHA-256 of the session id>`; the raw id exists only in the cookie. HMAC key separate from the encryption key. Index `bff:user:<HMAC of oid>`. One per session; no key holds a customer number or booking centre (BR-04) |
| Transport, access, eviction | Redis over TLS, persistence off or encrypted; dedicated ACL user on `bff:*`, no `KEYS`, `SCAN` or admin command; `maxmemory-policy noeviction` |
| One live session per user | Sign-in runs one Lua script: read the index, delete the session it names, write the new record and index with one TTL |
| Sliding renewal | One Lua script rewrites the record and its index with the same new TTL, only if the index still names this session (compare-and-set). Logout and expiry delete the index only if it names this session |

| Failure | Answer (none deletes a session except where stated) |
|---|---|
| Redis unreachable, or full (`OOM` on write) | Constant 503; sessions with a valid access token still read |
| Refresh succeeded at Entra, Redis write failed | Constant 503; new tokens dropped, old record kept. If Entra rotated the refresh token, its refusal later ends the session (5.4). (To confirm in the tenant: the previous refresh token's validity after rotation.) |

## 8. CSRF and body bounds

Order on `POST`:
1. **Session.** No cookie or no record: constant 401; no body read.
2. **Origin.** `Sec-Fetch-Site` is `same-origin`, and `Origin` (if present) is the BFF's. Else constant 403; no body read.
3. **CSRF.** Synchronizer token from the record, in header `X-CSRF-TOKEN`. A `CsrfTokenRequestHandler` reads the header only; the default falls back to `request.getParameter`, which parses a form body (checked in v2). Mismatch: constant 403. Mirrored from the service: `spring.servlet.multipart.enabled: false` and `spring.mvc.formcontent.filter.enabled: false` (checked in Boot metadata), so no form body is parsed before this check.
4. **Body.** At most `limit + 1` bytes (16 KiB + 1, D-09), whatever `Content-Length` says; the bytes read are forwarded with `Content-Type` and the correlation id. No 413 from the BFF: the service identifies the caller first and refuses an oversized body as `request-too-large` (BR-37). Unread bytes close the connection.
5. **Forward.** The service decides role, then input.

## 9. Refusals through the BFF

| Situation | Browser receives | Basis |
|---|---|---|
| No session, expired, logged out, decryption failure | Constant 401, the service's caller-not-identified bytes (instance `/api/risk-profiles`, `WWW-Authenticate: Bearer`, `no-store`) | BR-01, I-04 |
| Service 401 | Refresh once, repeat once, then the service's answer; session kept | 5.4 |
| Entra refuses the refresh | Constant 401; session deleted | D-13 |
| Service 403, 400, 404, 409, 413, 422 | Unchanged | Service catalogue |
| BFF origin or CSRF refusal | Constant 403, no risk word | Section 8; BR-41 |
| Route not in section 4 | Service's constant 404, no call | I-07 |
| Service 5xx or unexpected status; unreachable; TLS failure; Redis or Entra unavailable | Constant 503 | Operational |
| `POST` timeout | Constant 504: outcome unknown, reload before repeating | 6.6 |

## 10. Role by operation by channel

Unchanged from v3. "403" is the service's `permission-denied`; "no route" means the BFF exposes none. A human role in an app-only token, `ONBOARDING_SYSTEM` in a user token, a user token with an onboarding `azp`, an app-only token with the BFF's `azp`, and any token on another application's certificate: 401.

| Role | Channel | Evaluate | Escalate | Freeze | Clear | Compliance view | Front-office status |
|---|---|---|---|---|---|---|---|
| ONBOARDING_SYSTEM | Onboarding | yes | 403 | 403 | 403 | 403 | yes (A-02) |
| AML_ANALYST_L1 | BFF | no route | yes | 403 | 403 | yes | yes |
| AML_ANALYST_L2 | BFF | no route | yes | yes | yes | yes | yes |
| MLRO | BFF | no route | yes | yes | yes | yes | yes |
| FRONT_OFFICE | BFF | no route | 403 | 403 | 403 | 403 | yes (neutral, BR-40) |

## 11. Identity in the service

New classes in `com.gs5.kycaml.risk.adapters.web`, package-private, beside `BearerTokenSecurityConfiguration`. `ActorContextResolver.resolve()` and `identify()` keep their signatures.

**11.1 Order of checks for a request with a token.**
1. **Certificate.** `BearerTokenAuthenticationFilter` extracts the token, calls `AuthenticationManagerResolver.resolve(request)`, then `authenticate` (order checked in `spring-security-oauth2-resource-server-7.1.2.jar`). `CertificateSubjectManagerResolver implements AuthenticationManagerResolver<HttpServletRequest>` reads `jakarta.servlet.request.X509Certificate` (Tomcat `SSLSupport.CERTIFICATE_KEY`, checked). The leaf's issuer must equal `app.identity.client-certificate-issuer` and its subject must be registered; both are `X500Principal` values compared with `equals`. Any other leaf, or none, gets the `refuseEvery` manager: a `JwtAuthenticationProvider` over a decoder whose key source is `new ImmutableJWKSet<>(new JWKSet())`. Constant 401, zero fetches, no WARN, and the counter `kyc.identity.unregistered-certificate` increments (no tags: no subject, issuer or application).
2. **Signature and key fetch** with the application's decoder (section 12): `NimbusJwtDecoder.withJwkSource(chain).jwsAlgorithm(SignatureAlgorithm.RS256)` in `new ProviderManager(new JwtAuthenticationProvider(decoder))`.
3. **Claims**, in the decoder's validator: `JwtValidators.createDefaultWithIssuer(issuer)`, `JwtAudienceValidator`, `ConfiguredIdentityValidator` (unchanged), then `ApplicationTokenValidator`:
   - `azp` equals this application's client id, exactly.
   - BFF: `scp` present (user token); exactly one human role (D-15).
   - Onboarding: `scp` absent (app-only); `roles` has exactly one element, and it is `ONBOARDING_SYSTEM`. Two entries are refused even if both are `ONBOARDING_SYSTEM`.
   - (To confirm in the tenant: `azp`, `scp`, `idtyp`, `roles`.)
4. **Caller.** `CallerClaims.callerOf(Jwt)` reads `azp`: the BFF branch as today; the onboarding branch takes the booking centre from the registration and reads no booking-centre claim. Then `identify()` keeps the in-scope check.

The configurer takes `.authenticationManagerResolver(resolver)` instead of `.jwt(...)`, which takes precedence (string in `OAuth2ResourceServerConfigurer`, checked). `rateLimitedKeySet` and `noKeyWhenLimited` are removed.

**11.2 Configuration.**

```java
@Validated
@ConfigurationProperties(prefix = "app.identity", ignoreUnknownFields = false)
record IdentityRegistry(@NotBlank String issuer, @NotBlank String audience, @NotBlank String jwkSetUri,
        @NotBlank String clientCertificateIssuer, @NotBlank String actorClaim, @NotBlank String roleClaim,
        @NotBlank String bookingCentreClaim, @NotNull @Valid Bff bff, @NotNull @Valid Onboarding onboarding,
        @NotNull @Valid KeySet keySet) {
    record Bff(@NotBlank String clientId, @NotBlank String certificateSubject) {}
    record Onboarding(@NotNull @Valid Application us, @NotNull @Valid Application gb, @NotNull @Valid Application de) {
        record Application(@NotBlank String clientId, @NotBlank String bookingCentre, @NotBlank String certificateSubject) {}
    }
    record KeySet(@NotNull Duration cacheTimeToLive, @NotNull Duration rateLimitInterval, @NotNull Duration lastKnownWindow) {}
}
```

- `@AssertTrue`: the issuer and every subject parse as `X500Principal`; subjects are pairwise distinct, the BFF's included; the four client ids are pairwise distinct; each booking centre is canonical (`CanonicalUuid`, shared kernel) and a key of `app.booking-centres` (the `OnboardingCentresInScope` cross-check of v3, which fails startup without a `throw`).
- Window: `0 < rateLimitInterval < cacheTimeToLive <= lastKnownWindow <= LAST_KNOWN_WINDOW_MAXIMUM`, with `LAST_KNOWN_WINDOW_MAXIMUM = Duration.ofSeconds(300)` a named constant in the serving layer (D-21). The configured window is a deployment choice up to it; the constant is the safety bound.
- Client ids are values, never map keys, so no environment variable carries a key with dashes or dots. The three onboarding entries are fixed by the record and this file: a new registration is a code change and restart. A missing variable is an unresolved placeholder: startup fails (test 18.6).

```yaml
app.identity:
  issuer: ${ISSUER_URI}
  audience: ${TOKEN_AUDIENCE}
  jwk-set-uri: ${JWK_SET_URI}
  client-certificate-issuer: ${CLIENT_CA_SUBJECT}
  bff: { client-id: ${BFF_CLIENT_ID}, certificate-subject: ${BFF_CERTIFICATE_SUBJECT} }
  onboarding:   # us, gb, de: same shape, with ONBOARDING_US_*, ONBOARDING_GB_*, ONBOARDING_DE_* variables
    us: { client-id: ${ONBOARDING_US_CLIENT_ID}, booking-centre: ${ONBOARDING_US_BOOKING_CENTRE}, certificate-subject: ${ONBOARDING_US_CERTIFICATE_SUBJECT} }
  key-set: { cache-time-to-live: 60s, rate-limit-interval: 30s, last-known-window: 300s }   # D-21
```

*Migration:* `spring.security.oauth2.resourceserver.jwt.issuer-uri`, `audiences`, `jwk-set-uri` and `jws-algorithms` move to `app.identity` and leave the `spring.` namespace (12.6). `IdentitySettings`, `IdentitySettingsStartupIT`, `IdentityIT` and the test configuration's override change with them (stage 3). Environment variable names do not change.

**11.3 Certificate binding.** Issuer and subject are both compared. The issuer is one value per deployment (the dedicated internal CA's subject); the subject is registered per application. Pinning each certificate's SubjectPublicKeyInfo SHA-256 was rejected: it breaks every renewal until configuration changes. Subject selection is in code because one CA per application would need one listener each and would still accept any certificate that CA issues. Residual risk: a holder of a certificate the dedicated CA issued for a registered subject can present it; the CA's dedicated use (15.3) is the control.

**11.4 Server certificate and client authentication** (names checked in Boot metadata):

```yaml
server.ssl: { enabled: true, bundle: service-tls, client-auth: need }
server.forward-headers-strategy: none
spring.ssl.bundle.pem.service-tls:
  keystore:   { certificate: ${SERVICE_TLS_CERTIFICATE}, private-key: ${SERVICE_TLS_PRIVATE_KEY} }
  truststore: { certificate: ${CLIENT_CA_CERTIFICATE} }
```

## 12. Key source chain (D-21, F1)

**12.1 Why v3 failed, and what the fix relies on.** v3's `LastKnownKeySetSource` called `remember(keys, now)` after the cache returned, comparing set identity and stamping the request's start time. Requests could reach it out of order, so a stale stamp or a replaced set could be stored after a newer fetch. The fix records at the point of fetch. Facts from `nimbus-jose-jwt-10.9.1.jar` it relies on (checked): `CachingJWKSetSource` runs its source inside `loadJWKSetNotThreadSafe` under a `ReentrantLock`, waits with `tryLock(refreshTimeout)` (library default `DEFAULT_CACHE_REFRESH_TIMEOUT` = 15 000 ms), and on a source exception unlocks and rethrows; `cacheJWKSet(keys, now)` computes expiry as `now` plus TTL; `RateLimitedJWKSetSource.getJWKSet` is `synchronized` and calls its source only when it does not refuse, otherwise throwing `RateLimitReachedException`.

**12.2 Chain, built by hand per application in `BearerTokenSecurityConfiguration`:**

```
JWKSetBasedJWKSource                                   Nimbus; get() reads System.currentTimeMillis()
  └ LastKnownKeySetSource(window, Clock)               new; serving layer; reads the record only
     └ CachingJWKSetSource(ttl, 15 000 ms, listener)   Nimbus; ReentrantLock
        └ FetchRecordingJWKSetSource                   new; stores (keys, fetchedAt) at each fetch
           └ RateLimitedJWKSetSource(min interval, listener)
              └ URLBasedJWKSetSource(JWK_SET_URI, DefaultResourceRetriever(2 000, 2 000, DEFAULT_HTTP_SIZE_LIMIT))
```

Both new layers extend `JWKSetSourceWrapper<C>` (public constructor taking the `JWKSetSource<C>` they wrap; `getSource()` returns it) and share `LastFetch`, which holds the record and the health state (12.5). Constructors used: `CachingJWKSetSource(source, ttl, refreshTimeout, listener)`, `RateLimitedJWKSetSource(source, minInterval, listener)`, `URLBasedJWKSetSource(URL, ResourceRetriever)`, `DefaultResourceRetriever(int, int, int)`, `JWKSetBasedJWKSource(JWKSetSource)`; a no-op listener where required.

**12.3 `FetchRecordingJWKSetSource`** (between cache and rate limiter):

```
getJWKSet(refresh, now, ctx):
  keys = getSource().getJWKSet(refresh, now, ctx)      // a KeySourceException propagates; nothing stored
  last.updateAndGet(prev -> new FetchedKeySet(keys, prev == null ? now : max(prev.fetchedAtMillis(), now)))
  last.fetchSucceeded()                                // INFO if recovering
  return keys
```

`record FetchedKeySet(JWKSet keys, long fetchedAtMillis)` lives in one `AtomicReference`.
- Every value it returns is a real fetch: the rate limiter returns only fetched sets, and the cache calls this layer only to load.
- The stamp is `now`, read before the request waited for the lock and before the fetch. It is never later than the fetch's real start, so the window ends no later than 300 s after the real fetch.
- `fetchedAt` never moves backward; the cache lock serialises the calls, so the last store holds the newest fetched set. A key Entra withdraws is dropped at the next successful fetch and is usable at most 300 s after the last successful fetch (D-21).

**12.4 `LastKnownKeySetSource`** (above the cache; the serving layer):

```
getJWKSet(refresh, now, ctx):
  try { getSource().getJWKSet(refresh, now, ctx); }
  catch (RateLimitReachedException e) { }                        // budget spent: no state change, no log
  catch (JWKSetUnavailableException e) { last.fetchFailed(e); }  // identity provider or timeout
  // any other KeySourceException propagates: constant 401, one WARN (I-05)
  r = last.record();                                             // the cache's return value is not used
  t = clock.millis();                                            // read after the cache returned
  if (r == null || t - r.fetchedAtMillis() >= windowMillis) { last.lapsed(); return EMPTY; }
  return r.keys();
```

- `EMPTY` is one constant empty `JWKSet`: the token fails as invalid, constant 401.
- The clock is read after the cache because a request can wait up to the refresh timeout (15 s) for the lock; a `now` read before the wait would extend the window by that wait.
- The cache's return value is not used: the cache can return an entry its own expiry still accepts when a refresh cannot complete (javap: `loadJWKSetBlocking` tests `CachedObject.isValid` before its `UnableToRefreshEvent` path). This layer stores nothing, so the record is never re-cached.

**12.5 Health and logging.** States UNKNOWN (no fetch has succeeded), OK, FAILING, LAPSED, changed by compare-and-set so concurrent requests log once. OK is set only by a successful fetch (12.3); a cache hit proves nothing about the identity provider.

| Transition | Log |
|---|---|
| UNKNOWN or OK → FAILING | One WARN `identity provider key fetch failing`, root cause class simple name only (last cause in the `getCause()` chain); no message, URL or token. From UNKNOWN this is first use with no fetch ever succeeded; the text is `no key set has been fetched yet` |
| OK or FAILING → LAPSED (a request finds the record older than the window) | One WARN `last known keys expired; known keys refused until a fetch succeeds` |
| FAILING or LAPSED → OK (a fetch succeeds) | One INFO `key fetch recovered` |
| Rate-limit refusal | No state change, no log: a flood is not an outage, and per-request logs are forbidden |

**12.6 Clock and tests; algorithm pin.** `JWKSetBasedJWKSource.get` reads `System.currentTimeMillis()`, asks with `noRefresh()`, and on no match asks again with `referenceComparison(set)` (checked). Window tests therefore call the serving layer through the public `JWKSetSource.getJWKSet(refresh, now, ctx)` with an explicit `now`, and inject a `MutableClock` into `LastKnownKeySetSource`; in tests both carry the same value. The cache's expiry is `now + TTL`, so cache tests need no wall clock. One integration test (`IdentityIT`) uses the real clock through the full chain. The decoder accepts RS256 only, in code (`.jwsAlgorithm(SignatureAlgorithm.RS256)`). The property `spring.security.oauth2.resourceserver.jwt.jws-algorithms` is defined in `spring-boot-security-oauth2-resource-server-4.1.2.jar` (checked) and read only by Boot's own decoder, which this design does not use; it is removed from `application.yml`, and `ProductionIdentitySettingsTest` asserts its absence in place of the RS256 pin (test 18.7).

**12.7 Outcomes** (t0 = last successful fetch; TTL 60 s, interval 30 s, window 300 s):

| Time since t0 | Known `kid` | Unknown `kid` |
|---|---|---|
| 0 to 60 s | From the cache, no fetch: verified | Refresh attempt; within budget a fetch (a success restarts t0); otherwise 401 |
| 60 to 300 s; every refresh refused (flood) or failing (outage) | Record served: verified | No match: 401 |
| 300 s or later, no successful fetch since | Empty set: 401 | 401 |

## 13. What the BFF logs (BR-43, A-29)

One line per request: timestamp, method, **route template** (e.g. `/api/risk-profiles/{customerNumber}/decisions`), status, duration, correlation id (forwarded as `X-Correlation-Id`). A refusal code is logged **only** for the BFF's own refusals: `bff-session-missing`, `bff-origin-refused`, `bff-csrf-refused`, `request-not-handled`, `bff-unavailable`, `bff-timeout`. For a forwarded answer on a decision or risk route only the status is logged; the service's `type` is never read into a log. So a decision refusal produces no code in the BFF log (test 18.11).

Never logged: customer number, filled-in path, token, cookie, CSRF value, `Authorization`, body, claim, `oid`, booking centre, risk word. Tomcat access log off (`server.tomcat.accesslog.enabled: false`). `org.springframework.security` and `org.springframework.web` at INFO; `spring.mvc.log-request-details` off. **Customer number: open.** A-29 allows the customer number in a log, but never with risk information; a decision-route line with a status (200, 422) is information about a decision, so this draft keeps the number out of every BFF line. The product owner decides (section 20).

## 14. Mutual TLS in the tests

- **Why it breaks.** Production requires a client certificate (`server.ssl.client-auth: need`). A test with a real port (`IdentityIT`, `RANDOM_PORT`, checked; stage 3 greps for the rest) fails the handshake without one. MockMvc tests (`RiskApiFlowIT` and others) open no connection but must present the certificate on the request.
- **Test PKI, per run.** `TestPki` (test sources, static, before the context starts) writes PEM files to a temporary directory: a test CA (basic constraints CA); the BFF leaf (EKU clientAuth, test BFF subject); three onboarding leaves (`us`, `gb`, `de`, with their test subjects); an unregistered leaf from the same CA; a leaf from a second CA; a service leaf for `service-tls` (EKU serverAuth, SAN `127.0.0.1`, `localhost`). Nothing is committed, so no repository certificate expires.
- **Library.** BouncyCastle `bcpkix`, test scope. Not in the offline Maven cache (checked); stage 3 picks a version at least two weeks old, and the pom change comes first. Not verified here. The JDK `keytool` avoids a dependency but runs an external process, so it is not chosen.
- **Presentation.** The JDK `HttpClient` with an `SSLContext` built from a PKCS12 of the leaf it presents, trusting only the test CA. MockMvc tests instead set `jakarta.servlet.request.X509Certificate` (Tomcat's name, checked) through `requestAttr(String, Object)` of `AbstractMockHttpServletRequestBuilder` (checked), via a helper `ClientCertificates`.
- **Shared configuration.** `src/test/resources/config/application.yml` sets `server.ssl.enabled: true`, `server.ssl.bundle: service-tls`, `server.ssl.client-auth: need`; the bundle's file paths come from a `@DynamicPropertySource` supplied by `TestPki`. `ProductionIdentitySettingsTest` reads the production file, so the production pins are unchanged.

## 15. Deployment controls, each with a test or check

1. No route from the internet, and ingress only from the BFF and onboarding segments: external and unlisted internal hosts are refused. (Deployment check.)
2. Mutual TLS: no client certificate, or one from another CA, fails the handshake; each registered certificate succeeds (test 18.9 in code; deployment check with real certificates).
3. The dedicated internal CA issues only these client certificates. (Deployment check.)
4. **TLS ends at the service's own Tomcat.** No reverse proxy, load balancer or gateway terminates TLS or re-sends a client certificate; the path from the BFF is TCP passthrough (deployment check). No `SSLValve`, `RemoteIpValve` or forwarded-certificate header is trusted: `server.forward-headers-strategy: none` (`NONE` and the key checked), and no `server.tomcat.remoteip.*` key (pinned, test 18.7). Test 18.9: a TLS connection without a client certificate that carries an `ssl_client_cert` header (the name Tomcat's `SSLValve` reads, checked) fails the handshake.
5. The BFF refuses a service certificate outside its trust store; the 404 for `/.well-known/oauth-protected-resource/**` still holds.

## 16. Consequences and residual risks

- F1 from the BFF's segment is closed by certificate selection and per-application budgets; a compromised BFF can spend only its own budget, and known keys keep working (12.7).
- A compromised BFF can act for its users within their rights. Mitigations: certificate key in a vault, short token lifetimes, alerts on refusal rates.
- A revoked signing key may verify for up to 300 s after the last successful fetch (D-21, accepted by the spec). Logout leaves Entra's session and refresh token alive (5.5).
- Redis unavailable or full: 503, sessions kept. A changed registration or booking centre is a configuration change and restart; certificate removal is effective at restart; no CRL or OCSP.

## 17. Verification status

**Checked with `javap` and `unzip` in `~/.m2`. No Maven or test run; nothing compiled.**
- Nimbus 10.9.1: `JWKSetSource.getJWKSet(JWKSetCacheRefreshEvaluator, long, C) throws KeySourceException`; `JWKSetSourceWrapper(JWKSetSource)` and `getSource()` public; `CachingJWKSetSource(source, ttl, refreshTimeout, listener)` with `ReentrantLock`, `tryLock(refreshTimeout)`, the source call in `loadJWKSetNotThreadSafe`, unlock-then-rethrow, `UnableToRefreshEvent` path; `cacheJWKSet(keys, now)` expiry from `now` + TTL; `RateLimitedJWKSetSource(source, minInterval, listener)` `synchronized`, source called only when not refused; `RateLimitReachedException` and `JWKSetUnavailableException` extend `KeySourceException`; `JWKSetBasedJWKSource(JWKSetSource)` with `System.currentTimeMillis()`, `noRefresh()`, `referenceComparison`; `URLBasedJWKSetSource(URL, ResourceRetriever)`; `DefaultResourceRetriever(int, int, int)`; `ImmutableJWKSet(JWKSet)`; `JWKSetCacheRefreshEvaluator` factories; `JWKSourceBuilder.DEFAULT_CACHE_REFRESH_TIMEOUT` = 15000, `DEFAULT_CACHE_TIME_TO_LIVE` = 300000; `DEFAULT_HTTP_SIZE_LIMIT` = 51200 (v3).
- Spring Security 7.1.2 (v3 list, unchanged): `authenticationManagerResolver` and its precedence over `jwt()`; `BearerTokenAuthenticationFilter` order; `JwtAuthenticationProvider(JwtDecoder)`; `ProviderManager`; `NimbusJwtDecoder.withJwkSource`, `jwsAlgorithm`; `JwtValidators.createDefaultWithIssuer`; `JwtAudienceValidator`.
- Spring Boot 4.1.2: `server.ssl.*`, `Ssl$ClientAuth.NEED`, `spring.ssl.bundle.pem`, `spring.mvc.formcontent.filter.enabled`, `spring.servlet.multipart.enabled`, `server.tomcat.accesslog.enabled`; `server.forward-headers-strategy` with `ForwardHeadersStrategy` constants `NONE`, `FRAMEWORK`, `NATIVE`; `server.tomcat.remoteip.*`; `spring.security.oauth2.resourceserver.jwt.jws-algorithms` in `spring-boot-security-oauth2-resource-server-4.1.2.jar`; `ignoreUnknownFields`.
- Tomcat 11.0.27: `SSLSupport.CERTIFICATE_KEY` = `jakarta.servlet.request.X509Certificate`; `SSLValve` (header `ssl_client_cert`) and `RemoteIpValve` in `tomcat-embed-core`. spring-test 7.0.10: `AbstractMockHttpServletRequestBuilder.requestAttr(String, Object)`.

**Not checked:** Spring Session; the Redis client and the atomicity of its Lua scripts (deployment check); Micrometer and BouncyCastle (neither in `~/.m2`); Spring Security's OAuth2 client; `X500Principal` matching for real Entra or internal-CA subjects; `IdentitySettings` internals.

**To confirm in the tenant:** `azp` in user and app-only tokens; `scp` only in user tokens; `idtyp`; `roles`; `tid`; that the service's v2.0 access tokens verify with the tenant keys; refresh-token rotation and lifetime; certificate credentials for the registrations; `accessTokenAcceptedVersion: 2` (I-10); that `response_mode=query` is allowed; three onboarding applications, each with its own client id.

## 18. Tests required before stage 4

Stage 3 writes these red. Serving-layer tests pass `now` and a `MutableClock` explicitly.

1. **Concurrency: a stale cache hit interleaved with a refresh.** Fetch 1 at t = 0 returns S1 (kid K1); TTL 60 s; window 300 s. Thread X calls with `now` = 10 000 and gets the cache hit S1; a package-private test hook in `LastKnownKeySetSource` (no-op in production) holds X after the cache returns. Thread Y calls with `now` = 60 000; the cache refreshes to S2 (kid K2; K1 withdrawn); the recorder stores (S2, 60 000). Release X. Assert: X and every later answer are S2; K1 is refused from 60 000; `fetchedAt` is 60 000 and never decreases; K2 verifies at 359 999 and is refused at 360 000 (fetchedAt + 300 s); two fetches in total. Repeat with the hook released before Y starts; same assertions.
2. **Unknown-kid flood after expiry.** Fetch at t0 = 0 returns S1 (K1); limiter exhausted; cache expired. From 61 s to 299 999 ms, K1 verifies, unknown kids get 401, and the key-set server sees no request beyond the budget. At 300 000 ms, K1 gets the constant 401. A served set is never re-cached: later hits with no successful fetch keep refusing K1.
3. **Unregistered certificate from the trusted CA:** 401 with zero requests to the key-set server; the bytes equal the other 401s; the counter increments; no subject or issuer in any log.
4. A token of one application on another's certificate: 401. A flood on one application leaves the others' keys and budgets untouched.
5. **Onboarding role combinations** (app-only, `azp` = registered client id): `roles` [ONBOARDING_SYSTEM] accepted with the registered centre; [ONBOARDING_SYSTEM, AML_ANALYST_L1] 401; [ONBOARDING_SYSTEM, ONBOARDING_SYSTEM] 401; [AML_ANALYST_L1] 401; `azp` differing in case 401; a user token with an onboarding `azp` 401; an app-only token with the BFF's `azp` 401.
6. **Configuration refuses to start:** an unset onboarding variable; an unknown key under `app.identity`; a misspelt prefix (`app.identiy`); a booking centre not in `app.booking-centres`; a non-canonical booking centre; two applications with one client id; two with one subject; window 301 s; interval not below TTL; window below TTL; zero interval. Accepted: window equal to TTL, and 300 s.
7. `ProductionIdentitySettingsTest` pins `key-set.last-known-window: 300s`, `server.ssl.client-auth: need`, `server.forward-headers-strategy: none` and the `app.identity` keys; asserts the absence of `spring.security.oauth2.resourceserver.jwt.*` and `server.tomcat.remoteip.*`.
8. **Algorithm pin:** HS256 and ES256 tokens refused; RS256 from a registered key accepted.
9. **Mutual TLS:** no certificate fails the handshake; the BFF and each onboarding certificate succeed; a leaf from the second CA fails; a leaf from the trusted CA with an unregistered subject passes the handshake and gets 401; a TLS connection without a client certificate carrying an `ssl_client_cert` header fails.
10. **BFF:** service 401 leads to one refresh and one repeat; a repeat that is also 401 sets the back-off, and during back-off only one call is made; Entra `invalid_grant` deletes the session; a Redis write failure after refresh gives 503 and keeps the session; an oversized decision body gets 413 after identification, and an unidentified caller gets 401 first; the callback refuses a missing or reused pre-login cookie, a wrong `state`, `nonce`, `aud`, `azp` or `oid`; the authorize URL carries `response_mode=query`.
11. **A decision refusal produces no code in the BFF log:** forwarded 403, 404, 409 and 422 on the decision route log template and status only; a BFF CSRF refusal logs `bff-csrf-refused`; a preflight gets the constant 404 with no `Access-Control-*` header.
12. **Logging:** one WARN per entry into FAILING (from OK, and from UNKNOWN at first use); one WARN per entry into LAPSED; one INFO on recovery; nothing per request; root cause type only.
13. **Redis keys:** every session key has the form `bff:session:<HMAC>`; the raw session id appears in no key or value.

## 19. Not doing

Token introspection, per-request revocation, CRL/OCSP, refresh-ahead caching, Nimbus retrying, Nimbus's outage layer (`OutageTolerantJWKSetSource`), a `JwtDecoder` bean, a CORS policy.

## 20. Decision requested

1. **BFF in this increment** (proposal, section 3): build the BFF now, or only the service side (sections 11 and 12) with the human routes unused until it exists.
2. **Entra model:** one confidential BFF registration with a certificate credential; three onboarding registrations (`us`, `gb`, `de`), each with its booking centre in configuration (D9).
3. **Human roles of D-19:** read as L1, L2, MLRO and Front-Office Employee. **One session per user:** read as one live session per user, bound to one booking centre.
4. **Session lifetimes:** idle and absolute timeouts; the spec gives no value.
5. **Entra revocation at logout:** keep logout local, or also revoke the user's refresh tokens at Entra.
6. **Customer number in BFF logs** (A-29): keep it out of every BFF line, as proposed.
7. **Owner of the BFF:** code, Entra registration, certificates, Redis.
