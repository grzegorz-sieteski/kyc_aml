# ADR: Backend-for-frontend boundary, fifth draft (v5)

> **Audit of v4 (2026-11-11, fourth): `[CHANGES REQUIRED]`. Do not implement.** Blocker B1 is resolved by D-22 (section 4). Warnings W1 and W3 to W14 are answered in section 1. W2 and W8 are named neither in the brief nor in the v4 banner, so v5 changes nothing for them; the auditor must confirm they are closed (section 12, item 6).

- **Mode:** `@architect`, design mode, stage 2, fifth draft. **Status:** Proposed, 2026-11-11. Section 13 lists what the product owner decides.
- **Replaces:** `docs/adr/bff-boundary-v4.md` where the sections below say so. Section 3 lists what stands unchanged.
- **Scope:** unchanged from v4: the boundary, and identity in `risk/adapters/web`, `application.yml` and `ProductionIdentitySettingsTest`. No change to `domain/`. No user interface (D-03).
- **Verification:** each mechanism named here was checked in the jars under `~/.m2` (javap, unzip), in `src/main` or `src/test` (read), or by running `keytool` and a small Java probe in the scratchpad, on 2026-11-11. Anything else is marked "to confirm in the tenant", "deployment check" or "stage 3". Nothing was compiled; no Maven was run.

## 1. Audit findings of v4 and where v5 answers them

| Finding | Answer in v5 |
|---|---|
| B1 (blocker): a decision-route log line carries the route template and status; BR-43 and D-22 forbid it | Section 4: a decision-route line carries time, correlation id and duration only. Test 18.11 |
| W1: a null record triggers LAPSED; the first-use WARN is incomplete | Section 8.3: four states; a null record never gives LAPSED; one WARN at first use from UNKNOWN. Test 18.12 |
| W3: the serving layer catches too much; the 401 path logs per request | Section 8.2: catches exactly `RateLimitReachedException` and `JWKSetUnavailableException` (and their subclasses); the per-request WARN in `CallerNotIdentifiedAnswer` is removed |
| W4: the sequential race variant asserts X = S2, which the design cannot produce | Section 8.4: X = S1 when X is released before Y. Tests 18.1, 18.1a, 18.1b. The race is held by the injected `Clock`, not by a production hook |
| W5: test 18.2 assumed exhaustion; Nimbus admits two fetches per window | Section 8.5: admission logic checked in bytecode. Test 18.2 gives the IdP one answer, then failures |
| W6: an unset variable survives binding as literal text | Section 7.2: `isNoUnresolvedPlaceholder()` refuses any `${` after binding. Test 18.6(a) leaves the variable unset |
| W7: booking centres not pairwise distinct; registration names unchecked | Section 7.2: `OnboardingCentresInScope` restated with `@Validated` and `@AssertTrue`. Test 18.6(d) to (g) |
| W9: BouncyCastle is absent | Section 9.1: JDK `keytool` from test sources. Probe run. BouncyCastle rejected (copilot 0.1 and 0.7) |
| W10: Micrometer is absent | Section 7.1: no metric; one count-free WARN per process. Test 18.3 |
| W11: the X.509 attribute is an array | Sections 7.1 and 9.2: `X509Certificate[]`, element 0; null, non-array and empty are refused. Test 18.9(d) |
| W12: the forged-header test cannot fail | Section 9.3: valve-chain test 18.9(a); falsifiable forged-header test 18.9(b) |
| W13: renewal and nonce count | Section 6: renewal resets TTLs only; key rotation sized by encryption count. Test 18.14 |
| W14: window ordering | Section 7.2: strict `0 < interval < TTL < window <= 300 s`; equality refused. Test 18.6(h) to (j) |
| Notes: seams, class names, role rule, `oid`, decryption alarm, section 20, quarantine conflict | Sections 6, 7.1, 7.2, 9.1, 12, 13 |
| W2, W8 | Not stated in the brief or banner. No change; section 12, item 6 |

## 2. Decisions

Kept from v4: D1 to D9 (v4 section 2). D7 is read as: the last successfully fetched key set is served for at most 300 s after that fetch (D-21).

Changed or added:
- **D10.** A decision route's BFF log line has three fields only: time, correlation identifier and duration (D-22). The same three fields apply to the two risk read routes until the product owner decides otherwise (section 13, item 4).
- **D11.** Key-set health is a four-state machine, UNKNOWN, OK, FAILING and LAPSED, logging once per entry into FAILING or LAPSED and once on recovery (section 8.3).
- **D12.** The test PKI is made by the JDK `keytool` from test sources. No BouncyCastle; no committed key material (section 9.1).
- **D13.** No metric in this increment. Micrometer is not in `pom.xml` or `~/.m2` (section 7.1).
- **D14.** Role rule: a BFF user token has exactly one ActorRole name among its roles; names that are not ActorRole names are ignored. An app-only token's `roles` holds exactly one name, and it is an ActorRole (section 7.1).
- **D15.** One live session per user is a design policy, not a business question. It is no longer in section 13.

## 3. Carried over from v4 unchanged, and superseded

Unchanged from v4: section 3 (increment), section 4 (routes), section 5 (sign-in, tokens, cookies), section 6 (what the BFF never does), section 7 except renewal and decryption (replaced in section 6 here), section 8 (CSRF and body bounds), section 10 (roles by operation), section 11.1 steps 1 and 2 as changed in section 7.1 here, section 15 items 1, 2, 3 and 5, section 16 and section 19. v4 section 9 stands, with the log column of section 5 here.

Superseded: v4 sections 1, 11.2, 11.4, 12, 13, 14, 15.4, 17, 18 and 20.

## 4. What the BFF logs (B1, D-22, BR-43, A-29)

One fixed format, written by one formatter in the BFF. Fields per route class:

| Route class | Routes | Log fields |
|---|---|---|
| Decision | `POST /api/risk-profiles/{customerNumber}/decisions` | Time (UTC, ISO-8601), correlation id, duration in ms. Nothing else: no method, no template, no status, no refusal code. This includes the BFF's own refusals (401, 403, 503, 504) on this route |
| Risk read | `GET /api/risk-profiles/{customerNumber}`, `GET .../front-office-status` | As decision (proposed; section 13, item 4). The template and status would show whether a profile exists |
| Session and sign-in | `/auth/*`, `GET /api/session` | Time, method, route template (no customer number occurs on these routes), status, duration, correlation id, and the BFF refusal code where one applies: `bff-session-missing`, `bff-origin-refused`, `bff-csrf-refused`, `bff-unavailable`, `bff-timeout` |
| Not handled | any other path, Evaluate included | Time, duration, correlation id and `request-not-handled`. Never the path, which may carry a customer number |

On every route the BFF never logs: the customer number, a filled-in path, a token, a cookie, the CSRF value, `Authorization`, a body, a claim, `oid`, a booking centre or a risk word. It never reads a service refusal `type` into a log. Tomcat's access log stays off. `org.springframework.security` and `org.springframework.web` stay at INFO, and `spring.mvc.log-request-details` stays off.

**Test 18.11:** forwarded 403, 404, 409 and 422 on the decision route, and the BFF's own 401, 403, 503 and 504 on the same route. Each log line matches the three-field pattern exactly, `<time> corr=<id> durationMs=<n>`. It contains neither the template string, nor a status field, nor a refusal code. The BFF CSRF refusal on the decision route logs no code.

## 5. Refusals through the BFF (v4 section 9, with the log column)

| Situation | Browser receives | Basis | Log line (section 4) |
|---|---|---|---|
| No session, expired, logged out, decryption failure | Constant 401 (service caller-not-identified bytes) | BR-01, I-04 | Decision: three fields. Other: `bff-session-missing` |
| Service 401 | Refresh once, repeat once, then the service's answer; session kept | v4 5.4 | Per route class |
| Entra refuses the refresh | Constant 401; session deleted | D-13 | Per route class |
| Service 403, 400, 404, 409, 413, 422 | Unchanged | Service catalogue | Decision: three fields only |
| BFF origin or CSRF refusal | Constant 403, no risk word | v4 section 8; BR-41 | Decision: three fields. Other: `bff-origin-refused` or `bff-csrf-refused` |
| Route not in section 4 of v4 | Service's constant 404, no call | I-07 | `request-not-handled` |
| Service 5xx or unexpected status; unreachable; TLS failure; Redis or Entra unavailable | Constant 503 | Operational | Decision: three fields. Other: `bff-unavailable` |
| `POST` timeout | Constant 504: outcome unknown, reload before repeating | v4 section 9 | Decision: three fields. Other: `bff-timeout` |

## 6. Redis: renewal, nonces and decryption failure (W13, note on section 7)

- **Renewal writes no record.** A renewal is one Lua script that sets the TTL of the session record and of its index key (`EXPIRE` or `PEXPIRE`, the same new value), and does so only while the index still names this session (v4 section 7, compare-and-set). It writes no value, so the ciphertext and the nonce stay as they were.
- **Nonces are drawn per encryption.** Each protected field of each record write takes a fresh 96-bit nonce: sign-in, token refresh, back-off set. Renewal draws none (test 18.14).
- **Key rotation is sized by encryptions, not only by time.** NIST SP 800-38D bounds random 96-bit nonces to 2^32 invocations per key (external standard, not checked in this repository). At the v4 proposal of 90 days, 2^32 encryptions allow about 552 field encryptions per second averaged over all instances (2^32 / 7 776 000 s). Deployment check: measure the field-encryption rate. If it exceeds the bound for the chosen period, shorten the period. A key whose budget is spent is retired early.
- **A decryption failure raises an alarm.** The record is deleted and the caller gets the constant 401 (v4 section 7). The service also writes one ERROR line with the fixed text `session record could not be decrypted; check the encryption keys`, with no session id, key id or counter. A cookie alone cannot cause a failure: the record key is an HMAC of the cookie's session id, so a failure needs a record written under a key the service no longer holds. That is a rotation or secret-store fault, which would otherwise log out every session with no sign. The lines are bounded by live sessions (test 18.15).

## 7. Identity in the service (v4 section 11, changed)

### 7.1 Order of checks, certificate, roles and metric

1. **Certificate.** `CertificateSubjectResolver implements AuthenticationManagerResolver<HttpServletRequest>`. The name avoids `*Manager` (checkstyle `noServiceSuffixDeclaration` lists `Service|Facade|Manager|Helper|Util|Utils`; ArchUnit bans the same suffixes). It reads `jakarta.servlet.request.X509Certificate` (Tomcat `SSLSupport.CERTIFICATE_KEY`). **The attribute is an `X509Certificate[]`:** Tomcat's `AbstractProcessor` sets the array returned by `getPeerCertificateChain()`. The resolver takes element 0 and refuses a null attribute, a non-array or an empty array. The issuer must equal `app.identity.client-certificate-issuer` and the subject must be registered. Both are compared as `X500Principal` with `equals`, which ignores spacing (probe, section 9.1). Any other case gets the refusing manager: `JwtAuthenticationProvider` over a `NimbusJwtDecoder` built on `new ImmutableJWKSet<>(new JWKSet())`. It answers the constant 401 with zero key fetches.
   - **No metric (D13).** The unregistered-certificate counter is dropped; Micrometer is absent. The refusing path writes one WARN per process, at the first refusal, with the fixed text `client certificate not registered; caller not identified`. It writes no subject, issuer or token. It writes nothing per request (test 18.3).
2. **Signature and key fetch.** `NimbusJwtDecoder.withJwkSource(chain).jwsAlgorithm(SignatureAlgorithm.RS256)` (both verified on `NimbusJwtDecoder` and its `JwkSourceJwtDecoderBuilder`), inside `new ProviderManager(new JwtAuthenticationProvider(decoder))` (constructors verified).
3. **Claims**, in the decoder's validators: `JwtValidators.createDefaultWithIssuer(issuer)`, `new JwtAudienceValidator(audience)` (both verified), `ConfiguredIdentityValidator`, then `ApplicationTokenValidator`:
   - `azp` equals this application's client id exactly, case-sensitive.
   - **BFF user token:** `scp` present; exactly one ActorRole name among `roles`; other names are ignored, as they are roles of other applications.
   - **Onboarding app-only token:** `scp` absent; `roles` holds exactly one element, and it is `ONBOARDING_SYSTEM`. Two entries are refused even if both are `ONBOARDING_SYSTEM`; so is `[ONBOARDING_SYSTEM, AML_ANALYST_L1]`.
   - **Choice stated:** the rule is one rule for both channels, "exactly one ActorRole". The app-only branch is stricter, because an application's own token carries no other application's roles, so any extra name is a refusal, not noise.
   - `oid` is the actor claim. For an app-only token it is the service principal's object id. To confirm in the tenant, with `azp`, `scp`, `idtyp`, `roles` and `tid`.
   - Near-miss role names (a case variant of an ActorRole) are ignored today. D-15 may mean they refuse (section 13, item 7).
4. **Caller.** `CallerClaims.callerOf(Jwt)` reads `azp`. The BFF branch takes the actor from `oid` and the booking centre from its claim. The onboarding branch takes the booking centre from its registration and reads no booking-centre claim. Claim names come from `OnboardingCentresInScope` (7.2), not from `@Value`. Then `identify()` keeps the in-scope check.

The configurer takes `.authenticationManagerResolver(resolver)`, which takes precedence over `.jwt(...)` (checked in v4; stage 3 confirms). `rateLimitedKeySet` and `noKeyWhenLimited` are removed. Certificate subject is checked before any key fetch, as in v4.

### 7.2 Configuration: one validated record (W6, W7, W14)

`RiskProperties` (prefix `app`, `Map<UUID, Jurisdiction> bookingCentres`, verified in `src/main`) is replaced by `OnboardingCentresInScope`, which keeps the same binding and adds the identity settings, so that the cross-checks see both. Every nested record is bound under the same prefix.

```java
@Validated
@ConfigurationProperties(prefix = "app")
record OnboardingCentresInScope(
        @NotNull Map<UUID, Jurisdiction> bookingCentres,
        @NotNull @Valid Identity identity) {

    record Identity(@NotBlank String issuer, @NotBlank String audience, @NotBlank String jwkSetUri,
            @NotBlank String clientCertificateIssuer, @NotBlank String actorClaim, @NotBlank String roleClaim,
            @NotBlank String bookingCentreClaim, @NotNull @Valid Bff bff, @NotNull @Valid Onboarding onboarding,
            @NotNull @Valid KeySet keySet) {
        record Bff(@NotBlank String clientId, @NotBlank String certificateSubject) {}
        record Onboarding(@NotNull @Valid Application us, @NotNull @Valid Application gb, @NotNull @Valid Application de) {
            record Application(@NotBlank String clientId, @NotBlank String bookingCentre, @NotBlank String certificateSubject) {}
        }
        record KeySet(@NotNull Duration cacheTimeToLive, @NotNull Duration rateLimitInterval, @NotNull Duration lastKnownWindow) {}
    }

    @AssertTrue public boolean isNoUnresolvedPlaceholder() { ... }   // W6: every String of the identity tree listed explicitly
    @AssertTrue public boolean isOnboardingCentresInScope() { ... }  // W7
    @AssertTrue public boolean isKeySetWindowOrdered() { ... }       // W14
}
```

The three methods are named as getters because Bean Validation reads `is`-getters of a bound object; stage 3 confirms this with test 18.6.

- **`isNoUnresolvedPlaceholder()` (W6).** Boot binds placeholders with `PropertySourcesPlaceholdersResolver`, whose `PropertyPlaceholderHelper` is built with `ignoreUnresolvablePlaceholders = true` (verified in the `spring-boot-autoconfigure` bytecode). With `ONBOARDING_US_CLIENT_ID` unset, the value binds as the literal `${ONBOARDING_US_CLIENT_ID}`, and `@NotBlank` passes it. An empty value is already refused by `@NotBlank`. The unset case is the one this check exists for. The mechanism that makes an unset variable fail is therefore this check, not the binder. Today's code has the same gap: with `ISSUER_URI` or `TOKEN_AUDIENCE` unset, the service starts and refuses every token; with `JWK_SET_URI` unset, `URI.create` fails by accident.
- **`isOnboardingCentresInScope()` (W7).** For `us`, `gb` and `de`, `CanonicalUuid.parse(bookingCentre)` (verified: returns `Optional<UUID>`) must be present; the UUID must be a key of `bookingCentres`; and its jurisdiction must equal the one the name claims (`us` US, `gb` GB, `de` DE; `Jurisdiction` has these three constants). The three booking centres are pairwise distinct. So are the four client ids and the four certificate subjects, the BFF included.
- **`isKeySetWindowOrdered()` (W14).** `0 < rateLimitInterval < cacheTimeToLive < lastKnownWindow <= 300 s`, where 300 s is the named constant `LAST_KNOWN_WINDOW_MAXIMUM` in the serving layer (D-21). Equality is refused for both strict comparisons. The configured window is a deployment choice up to the constant.

```yaml
app:
  identity:
    issuer: ${ISSUER_URI}
    audience: ${TOKEN_AUDIENCE}
    jwk-set-uri: ${JWK_SET_URI}
    client-certificate-issuer: ${CLIENT_CA_SUBJECT}
    actor-claim: oid
    role-claim: roles
    booking-centre-claim: booking_centre
    bff: { client-id: ${BFF_CLIENT_ID}, certificate-subject: ${BFF_CERTIFICATE_SUBJECT} }
    onboarding:   # us, gb, de: same shape, with ONBOARDING_US_*, ONBOARDING_GB_*, ONBOARDING_DE_* variables
      us: { client-id: ${ONBOARDING_US_CLIENT_ID}, booking-centre: ${ONBOARDING_US_BOOKING_CENTRE}, certificate-subject: ${ONBOARDING_US_CERTIFICATE_SUBJECT} }
    key-set: { cache-time-to-live: 60s, rate-limit-interval: 30s, last-known-window: 300s }   # D-21
```

The booking-centre keys of `app.booking-centres` are configured as today, with the UUID as key. The registrations take their centre as a value, so no environment variable forms a map key. Environment variable names of the existing settings do not change. `spring.security.oauth2.resourceserver.jwt.*` moves to `app.identity` and leaves the `spring.` namespace (test 18.7).

## 8. Key source chain (W1, W3, W4, W5, W14)

### 8.1 Chain

The chain of v4 section 12.2 stands: `JWKSetBasedJWKSource`, then `LastKnownKeySetSource` (serving layer, new), `CachingJWKSetSource` (ttl 60 s, refresh timeout, listener), `FetchRecordingJWKSetSource` (new, between cache and limiter, v4 12.3, unchanged), `RateLimitedJWKSetSource` (interval 30 s), and `URLBasedJWKSetSource` over `DefaultResourceRetriever`. `JWKSetBasedJWKSource.get` reads `System.currentTimeMillis()` (verified), so the serving layer takes a `java.time.Clock` and production passes the system clock. Its state holder, `LastFetch` (v4 12.2), holds the record and the state of section 8.3, changed by compare-and-set.

### 8.2 Serving layer, and what propagates (W3)

```
getJWKSet(refresh, now, ctx):
  try { getSource().getJWKSet(refresh, now, ctx); }
  catch (RateLimitReachedException e) { }                          // budget spent: no state change, no log
  catch (JWKSetUnavailableException e) { last.fetchFailed(e); }     // includes JWKSetRetrievalException, JWKSetParseException
  // any other KeySourceException propagates
  r = last.record();                                               // the cache's return value is not used
  if (r == null) return EMPTY;                                     // no fetch ever succeeded: never LAPSED
  if (clock.millis() - r.fetchedAtMillis() >= windowMillis) { last.expired(); return EMPTY; }
  return r.keys();
```

Facts checked: `RateLimitReachedException` and `JWKSetUnavailableException` extend `KeySourceException`. So do `RemoteKeySourceException` and, through `JOSEException`, every other key-source failure. `JWKSetRetrievalException` and `JWKSetParseException` extend `JWKSetUnavailableException`, and `URLBasedJWKSetSource` wraps IO failures in `JWKSetRetrievalException`. The cache creates `JWKSetUnavailableException` on lock timeout and on interruption. `NimbusJwtDecoder` catches `RemoteKeySourceException` and `JOSEException` and throws `JwtException`; `JwtAuthenticationProvider` maps `JwtException` to `AuthenticationServiceException`.

So an unclassified `KeySourceException` reaches `CallerNotIdentifiedAnswer` and gets the constant 401. That path writes no log. The WARN in `onAuthenticationFailure` (current code, per `AuthenticationServiceException`) is removed. The root cause is logged only at health transitions (8.3), as `NestedExceptionUtils.getMostSpecificCause(e).getClass() .getSimpleName()`: the class name only, which `CallerNotIdentifiedAnswer` already uses (spring-core 7.0.10 contains `NestedExceptionUtils`). Accepted residual: an unclassified exception type is invisible in the logs. It should be classified in code, not logged per request.

### 8.3 Health states (W1)

| State | Meaning | Record |
|---|---|---|
| UNKNOWN | No fetch has succeeded since start | None |
| OK | The last fetch succeeded | Set, in window |
| FAILING | A fetch failed; any record is still in window | None, or set and in window |
| LAPSED | The record is older than the window | Set, expired |

| Transition | Trigger | Log |
|---|---|---|
| UNKNOWN to OK | Fetch succeeds | None |
| UNKNOWN to FAILING | Fetch fails: first use, no fetch ever succeeded | One WARN, `no key set has been fetched yet; identity provider key fetch failing`, plus the root-cause class |
| OK to OK | Fetch succeeds | None |
| OK to FAILING | Fetch fails | One WARN, `identity provider key fetch failing`, plus the root-cause class |
| OK to LAPSED, FAILING to LAPSED | A request finds the record older than the window | One WARN, `last known keys expired; known keys refused until a fetch succeeds` |
| FAILING to OK, LAPSED to OK | Fetch succeeds | One INFO, `key fetch recovered` |
| FAILING to FAILING, LAPSED to LAPSED | Another fetch fails | None |
| Any | Rate-limit refusal | None |

Rules: a null record never produces LAPSED; without a record, UNKNOWN and FAILING serve EMPTY (the constant 401) with no further log. Only the compare-and-set that wins logs. Invariant: a non-null record implies OK, FAILING or LAPSED.

### 8.4 Race (W4)

The race is held inside the injected `Clock`. The serving layer reads the clock after the cache returns (8.2), so a test clock whose first read by thread X blocks on a latch holds X exactly where the design needs it. No hook exists in production code.

Setup: fetch at t0 = 0 returns S1 (kid K1); TTL 60 000; window 300 000. X calls with `now` = 10 000 and gets the cache hit; X blocks in the test clock after the cache returns. Y calls with `now` = 60 000: the cache refreshes to S2 (kid K2), and the recorder stores (S2, 60 000). The IdP fails every fetch after Y, so the record is not re-stamped.

- **18.1 (concurrent):** release X after Y completes. X and every later answer are S2. K1 is refused from 60 000. K2 verifies at 359 999 and is refused at 360 000 (fetchedAt + 300 000). Two successful fetches in total.
- **18.1a (sequential):** release X before Y starts. X = S1, because its read ran before Y stored S2. Every answer from Y onward is S2, with the same timings as 18.1.
- **18.1b (max branch):** a fetch at 5 500 stores (S2, 5 500). A late refresh with `now` = 5 000 that waited for the lock stores its set. The stored `fetchedAt` is 5 500 and never decreases, because the recorder takes `max`. The window check reads the clock after the cache returns, so a request that waited cannot stretch the window: the outcome reads the stored stamp, and the clock read happens after the wait.

### 8.5 Limiter, and the outage test (W5)

Checked in `RateLimitedJWKSetSource.getJWKSet` bytecode. `nextOpeningTime` starts at -1. A call with `now` at or after `nextOpeningTime` sets `nextOpeningTime = now + interval`, sets the counter to 1 and admits the fetch. Otherwise it admits the fetch while the counter is above zero, decrements it, and throws `RateLimitReachedException` when the counter is zero. So each window admits two fetches, and windows open at least one interval apart. A sliding 30 s span can hold three fetches. v4's "at most two per 30 s" holds per window only.

**Test 18.2:** the IdP returns S1 once, at t0 = 0, and fails every later fetch. K1 is probed every second from 61 000 to 299 999, and unknown kids are probed in the same run. Assertions: K1 verifies at every probe in [61 000, 299 999]; each unknown kid gets 401; IdP requests in [0, 300 000] number at most 22 (11 window openings at 0, 30 000, ..., 300 000, two each); K1 gets the constant 401 at 300 000. The log holds exactly two lines: one WARN on OK to FAILING at 61 000, and one WARN on FAILING to LAPSED at 300 000. After 300 000, with no successful fetch, K1 stays refused: a served set is never re-cached.

### 8.6 Algorithm pin

`.jwsAlgorithm(SignatureAlgorithm.RS256)` in code. The property `spring.security.oauth2.resourceserver.jwt.jws-algorithms` is removed from `application.yml` and asserted absent (test 18.7).

## 9. Mutual TLS, test PKI and the valve chain (W8 and W9, W11, W12; v4 section 14 and 15.4)

### 9.1 Test PKI (W9): recommendation

**Use the JDK `keytool`, run from the test sources through `ProcessBuilder`, once per test JVM, into a temporary directory.** Rejected: committed PEM fixtures (private keys in the repository, and expiry that breaks tests) and BouncyCastle (not in `pom.xml` and not in `~/.m2`; copilot 0.1 and 0.7 reject it). The probe, run on JDK 25.0.4.1 on 2026-11-11, produced:
- a CA with `-ext bc:critical=ca:true` and key usage `keyCertSign`;
- client leaves signed from a CSR (`-certreq`, `-gencert`) with `-ext eku=clientAuth` (listed as `clientAuth` by `-list -v`), `-validity 2`, and the subject given by `-dname`; the issuer equals the CA subject;
- an expired leaf with `-startdate -30d -validity 1` (valid 12 to 13 October 2026, with today 11 November);
- PKCS12 keystores;
- `X500Principal.equals` on the probe leaf: the exact subject DN matches, a spacing variant matches, the issuer matches the CA subject, and a different country does not.

Cost: six or seven keytool processes per leaf, measured in stage 3. Nothing is committed.

Server TLS (replaces v4 section 11.4; keys verified in Boot 4.1.2 metadata and classes):

```yaml
server.ssl: { enabled: true, bundle: service-tls, client-auth: need }     # Ssl$ClientAuth.NEED
server.forward-headers-strategy: none                                      # ServerProperties$ForwardHeadersStrategy.NONE
spring.ssl.bundle.jks.service-tls:                                         # JksSslBundleProperties: type, location, password
  keystore:   { location: ${SERVICE_TLS_KEYSTORE}, password: ${SERVICE_TLS_KEYSTORE_PASSWORD}, type: PKCS12 }
  truststore: { location: ${CLIENT_CA_TRUSTSTORE}, password: ${CLIENT_CA_TRUSTSTORE_PASSWORD}, type: PKCS12 }
```

### 9.2 Presentation (W11)

- Real TLS tests (`IdentityIT`): the JDK `HttpClient` with an `SSLContext` built from the client keystore, trusting only the test CA.
- MockMvc tests: `requestAttr("jakarta.servlet.request.X509Certificate", new X509Certificate[] { leaf })` through `AbstractMockHttpServletRequestBuilder.requestAttr(String, Object)` (verified, spring-test 7.0.10). The value is an array because Tomcat sets one (section 7.1). A helper, `ClientCertificates`, builds it.
- **Test 18.9(d):** a bare `X509Certificate` (not an array), an empty array and a missing attribute are each 401.

### 9.3 Valve chain and forged headers (W12)

Tomcat 11.0.27 contains `org.apache.catalina.valves.SSLValve`, which reads header `ssl_client_cert` and parses `-----BEGIN CERTIFICATE-----`, and `RemoteIpValve` (both verified in the jar). With `server.forward-headers-strategy: none`, this configuration is expected to add neither; test 18.9(a) confirms it.

- **Test 18.9(a), structural:** through `TomcatWebServer.getTomcat().getEngine()` (verified), walk the engine, each of its hosts (`findChildren`) and each context, and read `getPipeline().getValves()` (verified). No `SSLValve` and no `RemoteIpValve` is present anywhere. This test fails if one is added.
- **Test 18.9(b), falsifiable (replaces the v4 test that could not fail):** a valid BFF client certificate over TLS, a BFF token, and a forged `ssl_client_cert` header carrying the PEM of the `us` onboarding certificate. The request is identified as the BFF; it is not 401. If `SSLValve` were in the chain, it would replace the certificate with the header's, and the BFF token would be refused for an unregistered subject.
- **Test 18.9(c), handshakes:** no client certificate fails the handshake; a certificate from the second CA fails it; a trusted-CA certificate with an unregistered subject passes the handshake and gets 401 (test 18.3).
- **Deployment check:** no reverse proxy, load balancer or gateway terminates TLS or re-sends a client certificate (v4 section 15.4, unchanged), and the path from the BFF is TCP passthrough.

## 10. Tests required before stage 4

Stage 3 writes these red. Serving-layer tests pass `now` and a test `Clock` explicitly.

- **18.1, 18.1a, 18.1b:** race, concurrent, sequential and max branch (8.4).
- **18.2:** outage, one IdP answer then failures; two WARN lines only (8.5).
- **18.3:** unregistered certificate from the trusted CA: 401 with zero IdP requests; bytes equal to the other 401s; at most one WARN per process; no subject, issuer or token in any log.
- **18.4:** a token of one application on another application's certificate: 401. A flood on one application leaves the other keys and budgets untouched.
- **18.5:** onboarding, app-only, `azp` registered: `[ONBOARDING_SYSTEM]` accepted with the registered centre; `[ONBOARDING_SYSTEM, AML_ANALYST_L1]`, `[ONBOARDING_SYSTEM, ONBOARDING_SYSTEM]` and `[AML_ANALYST_L1]` 401; `azp` differing in case 401; a user token with an onboarding `azp` 401; an app-only token with the BFF's `azp` 401. BFF user token with `[MLRO, OTHER_APPLICATION_ROLE]` accepted; with `[MLRO, AML_ANALYST_L1]` 401.
- **18.6:** configuration refuses to start, each message naming the setting. Runs through Boot's `ValidationBindHandler` on the shipped `application.yml` with a `MockEnvironment`, so the system environment is not read. (a) `ONBOARDING_US_CLIENT_ID` unset, not empty. The test asserts the variable is absent and fails if it is set. (b) The same variable set to empty. (c) `app.identiy` misspelt: the identity values are absent and startup fails. (d) A booking centre not in `app.booking-centres`. (e) The `us` registration with its centre mapped to GB. (f) A non-canonical centre. (g) Two registrations sharing a client id, a subject or a centre. (h) Window 301 s. (i) Interval equal to TTL, and interval above TTL. (j) Window equal to TTL. (k) Zero interval. Accepted: interval 30 s, TTL 60 s, window 300 s; and interval 30 s, TTL 60 s, window 61 s.
- **18.7:** `ProductionIdentitySettingsTest` pins `app.identity.key-set.last-known-window: 300s`, `server.ssl.client-auth: need`, `server.forward-headers-strategy: none` and the `app.identity` keys with `${...}` values. It asserts the absence of `spring.security.oauth2.resourceserver.jwt.*` and `server.tomcat.remoteip.*`.
- **18.8:** HS256 and ES256 refused; RS256 from a registered key accepted.
- **18.9(a), (b), (c), (d):** valve chain, forged header, handshakes, attribute shapes (section 9).
- **18.10:** BFF behaviours of v4 test 18.10, unchanged.
- **18.11:** decision log lines (section 4).
- **18.12:** health (8.3): first use from UNKNOWN gives one WARN with `no key set has been fetched yet` and the root-cause class; later failing requests log nothing; requests at 400 000 ms with no record get 401 and no LAPSED line; OK to FAILING gives one WARN; FAILING to LAPSED one WARN; recovery one INFO; rate-limit refusals log nothing; an unclassified `KeySourceException` gives 401 with no log; the 401 path has no WARN.
- **18.13:** Redis keys, as v4 test 18.13.
- **18.14:** renewal: after renewal the stored ciphertext and nonce bytes are identical, and both TTLs equal the new value.
- **18.15:** decryption failure: the record is deleted; the caller gets 401; one ERROR line with the fixed text and no session id, key id or counter.

## 11. Verification status

Checked on 2026-11-11 by javap or unzip in `~/.m2`, by reading `src/main` and `src/test`, and by running `keytool` and a Java probe in the scratchpad. No Maven run; nothing compiled.

- **Nimbus 10.9.1:** `RateLimitedJWKSetSource` admission logic (8.5); `CachingJWKSetSource(source, ttl, refreshTimeout, listener)`; exception hierarchy and subclasses (8.2); `URLBasedJWKSetSource` wraps IO failures as `JWKSetRetrievalException`; lock-timeout and interrupt paths create `JWKSetUnavailableException`; `JWKSetBasedJWKSource` reads `System.currentTimeMillis()`; `ImmutableJWKSet(JWKSet)`; `JWKSourceBuilder.DEFAULT_CACHE_REFRESH_TIMEOUT` 15 000, `DEFAULT_RATE_LIMIT_MIN_INTERVAL` 30 000, `DEFAULT_HTTP_SIZE_LIMIT` 51 200.
- **Spring Security 7.1.2:** `NimbusJwtDecoder.withJwkSource`; `JwkSourceJwtDecoderBuilder.jwsAlgorithm`, `jwtProcessorCustomizer`; `RemoteKeySourceException` and `JOSEException` mapped to `JwtException`; `JwtAuthenticationProvider` mapping `JwtException` to `AuthenticationServiceException`; `JwtAuthenticationProvider(JwtDecoder)`; `JwtValidators.createDefaultWithIssuer`; `JwtAudienceValidator(String)`; `BearerTokenAuthenticationFilter(AuthenticationManagerResolver)`; `AuthenticationManagerResolver` in spring-security-core.
- **Spring Boot 4.1.2:** `Ssl$ClientAuth.NEED`; `ServerProperties$ForwardHeadersStrategy.NONE`; metadata keys `server.forward-headers-strategy`, `server.ssl.client-auth`, `server.ssl.bundle`; `JksSslBundleProperties$Store` with type, location and password; `PropertySourcesPlaceholdersResolver` builds its helper with `ignoreUnresolvablePlaceholders = true`; `ValidationBindHandler` in spring-boot.
- **Spring Framework:** `NestedExceptionUtils` (spring-core 7.0.10); `requestAttr(String, Object)` (spring-test 7.0.10).
- **Tomcat 11.0.27:** `SSLSupport.CERTIFICATE_KEY` is `jakarta.servlet.request.X509Certificate`; `AbstractProcessor` sets the attribute from `getPeerCertificateChain()` (`X509Certificate[]`); `SSLValve` (header `ssl_client_cert`, PEM); `RemoteIpValve`; `Pipeline.getValves()`, `Container.getPipeline()`, `Container.findChildren()`, `Tomcat.getEngine()`, `TomcatWebServer.getTomcat()`.
- **Probes:** the `keytool` PKI and the `X500Principal` probe (section 9.1).
- **Absent:** BouncyCastle (no `bcpkix` or `bcprov` in `~/.m2`); `micrometer-core` (`~/.m2/io/micrometer` holds only the bom, commons, observation and tracing bom).
- **Repository:** `OnboardingCentresInScope` does not exist in `src/main`, so it is new here. `CanonicalUuid.parse(String)` returns `Optional<UUID>`. `Jurisdiction` has US, GB and DE. `ActorRole` has the five roles. `CallerNotIdentifiedAnswer` logs a WARN per `AuthenticationServiceException` today. `IdentitySettings` uses `@NotBlank` on `${...}` values, which passes them.

**Not checked:** the Redis client and the atomicity of its Lua scripts (not in `pom.xml`; deployment check); Spring Session; every Entra fact (tenant); NIST SP 800-38D (external); `X500Principal` matching for real Entra or internal-CA subjects; the valve behaviour beyond the class names (test 18.9(a)).

## 12. Open items (not product-owner decisions)

1. **Conflict: quarantine.** The design (`docs/adr/customer-risk-scoring-design.md`, "Not doing", lines 310 and 311) defers quarantine to a later increment. D-18 and BR-44 to BR-46 put it in this increment, and the spec governs. The design must be amended before stage 3. The release role is in section 13, item 6.
2. **The unset check beyond identity.** The `${NAME}` gap (7.2) also applies to `DATABASE_PASSWORD`, `FLYWAY_PASSWORD` and the TLS keystore and truststore passwords (section 9.1), which are outside the brief's scope. Recommend a separate task extends the check to the whole file.
3. **Unregistered-certificate line.** The one WARN per process (7.1) is the architect's choice, not a product-owner decision. A line per request would be a flood vector.
4. **Tenant facts (to confirm in the tenant):** `oid`, `azp`, `scp`, `idtyp`, `roles`, `tid` in user and app-only tokens; refresh-token rotation and lifetime; `accessTokenAcceptedVersion: 2`; that `response_mode=query` is allowed; three onboarding registrations, each with its own client id and certificate credential.
5. **Deployment checks:** TLS ends at the service's own Tomcat; ingress only from the BFF and onboarding segments; the internal CA issues only these certificates; the measured field-encryption rate (section 6); no reverse proxy re-sends a client certificate.
6. **W2 and W8.** Neither is stated in the brief or the banner, and the v4 audit text is not in the repository. The auditor confirms they are closed, or names their content for a v6.

## 13. Decision requested

1. **BFF in this increment** (v4 section 3): build the BFF now, or only the service side (sections 7 and 8), with the human routes unused until it exists.
2. **Session lifetimes:** idle and absolute timeouts. The spec gives no value.
3. **Entra revocation at logout:** keep logout local, or also revoke the user's refresh tokens at Entra.
4. **Log fields on the two risk read routes:** D-22 covers decision routes only. Section 4 proposes the decision field set for the compliance view and the front-office status. Confirm, or allow the template and status on those routes.
5. **Owner of the BFF:** code, Entra registration, certificates and Redis.
6. **Who may release a quarantined event (D-18, BR-46):** the spec leaves the role open. Every release is recorded with who released it and when.
7. **Near-miss role names (D-15):** a name that differs from an ActorRole only by case. Refuse the caller, as D-15's wording "a role name that does not match exactly" suggests, or ignore the name, as the current code does.
