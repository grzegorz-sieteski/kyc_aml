# ADR: Backend-for-frontend boundary, sixth draft (v6)

> **Audit of v5 (2026-11-11, fifth): `[CHANGES REQUIRED]`, one blocker.** Section 0 answers each audit item and records
> three corrections found while verifying. Do not implement until the tests in section 13 are red in stage 3 and the
> decisions in section 14 are taken.

- **Mode:** `@architect`, design mode, stage 2, sixth draft. **Status:** Proposed, 2026-11-11.
- **Replaces:** `docs/adr/bff-boundary-v5.md` where the sections below say so (section 2 lists what stands).
- **Scope:** unchanged from v5. No change to `domain/`. No user interface (D-03).
- **Verification:** each mechanism named here was checked on 2026-11-11 in the jars under `~/.m2` (javap, bytecode
  offsets where the design depends on them), in `src/main`, `src/test`, `pom.xml` and the Checkstyle file (read), or by
  two scratchpad probes. Nothing was compiled; no Maven was run. Unchecked items are marked "to confirm in the tenant",
  "deployment check" or "stage 3".

## 0. Delta on v5

| # | Audit item | Answer in v6 |
|---|---|---|
| 1 | Role rule conflicts with D-19 and D-15 (blocker) | D16 (section 1), section 6 step 3. D14 and "one rule for both channels" removed. Test 18.5 changed |
| 2 | Race order; 18.1b vacuous without a forced refresh | Section 9.4: clock read first, then the pair. 18.1 holds as written. 18.1b rebuilt with limiter-correct times |
| 3 | Cache-side exceptions counted as IdP failures; propagation unstated | Section 9.2 (three catch clauses), section 9.6 (propagation table) |
| 4 | Record and state not one pair; lazy expiry unstated | Section 9.3 (one `AtomicReference`, compare-and-set on the pair; lazy expiry stated) |
| 5 | Placeholders: no per-field constraint; TLS and database secrets unguarded; wrong citation | Section 7.2: per-field constraint, 22 variables, one test each. Citation is `spring-boot-4.1.2.jar` |
| 6 | Hand-built decoder keeps timestamp validation only | Section 8 (one `setJwtValidator` call with the four validators) |
| 7 | Decision log cannot show success; 400, 413, upstream failures untested | Section 3 (consequence, bounds, tests 18.11a and 18.11b) |
| 8 | Key rotation not defined; retired keys | Section 5 (key id; alarm only for an unknown id; test 18.15b) |
| 9 | Affected files not listed | Section 7.6. Seven test classes set `app.booking-centres`, not "about ten" |
| 10 | D-20 residual not stated | Section 12 item 1; section 14 item 2 |
| 11 | `@AssertTrue` not null-safe; 18.6(c) asserts no message | Section 7.4 |
| 12 | Notes: W2, W8, race lines, 18.2 timeline, one-WARN singleton, resolver name, keytool, BFF dependencies | Sections 6, 9.1, 9.2, 9.4, 9.5, 10, 12 |

Corrections found while verifying (not in the audit list):
- **`azp` (section 6).** v5 said `azp` equals this application's client id. Wrong: `azp` names the calling client (the BFF or an onboarding application). v6 matches it against the registrations. To confirm in the tenant.
- **Limiter arithmetic (section 9.4).** v5's 18.1b fetch at 5 500 and late call at 5 000 fall in one limiter window. The 5 500 fetch uses that window's second admission, so the late call is refused and the IdP is asked once. v6 moves the times.
- **FLYWAY_USERNAME (section 7.2).** Same gap as the password, and the Flyway block of `application.yml` says a missing owner must fail startup. Added: 22 variables.

## 1. Decisions

Kept from v5: D1 to D9 (v4), D10 (three-field decision log, section 3), D11 (four health states, section 9.3), D12 (keytool PKI), D13 (no metric).

- **D14: removed.** The role rule is D-23 applied directly (D16).
- **D15: withdrawn to section 14, item 5.** One live session per user is a product choice.
- **D16 (new): role rule (D-23, D-19, D-15, BR-01).** A user token (`scp` present) holds exactly one name in `roles`, one of AML_ANALYST_L1, AML_ANALYST_L2, MLRO, FRONT_OFFICE. An app-only token (`scp` absent) holds exactly one name, ONBOARDING_SYSTEM. Any other name refuses: ONBOARDING_SYSTEM in a user token, a foreign application role, a case variant of an ActorRole name. This closes v5 section 13 item 7, since D-23 refuses "any role other than" the caller's one role.
- **D17 (new): one decoder, every validator explicit (section 8).**
- **D18 (new): a configured String holding an unresolved `${...}` refuses startup, naming its variable (section 7.2).**
- **D19 (new): the audience is one string, matched exactly (behaviour change, section 7.3).**
- **D20 (new): a key-set record is stamped with the request's `now`; an older stamp never replaces a newer record (section 9.3).**
- **D21 (new): each session record carries the id of the key that encrypted it; a retired key stays readable until the maximum session lifetime (section 5).**

## 2. Carried over from v5, and superseded

Unchanged: v5 sections 3, 5, 6 (what the BFF never does), 8, 9.1 (server TLS block), 9.2, 9.3, 10, and the v4 sections
the v5 banner lists as carried. Section 4 of v5 keeps its routes and log rules; section 3 here replaces its decision line.

Superseded: v5 sections 1, 2 (D10 wording, D14, D15), 4 (decision line), 6 (key id and alarm, section 5 here), 7.1 and
7.2 (sections 6 and 7 here), 8.1 to 8.5 (section 9 here), 9.1 PKI paragraph (section 10 here), 10 (section 13 here), 12
and 13 (sections 12 and 14 here).

## 3. What the BFF logs, and the bounds of the decision route (D-22, D-09, D-12, BR-43, A-29)

**Decision route** `POST /api/risk-profiles/{customerNumber}/decisions` writes one line per request:
`<time UTC ISO-8601> corr=<correlation id> durationMs=<n>`. Nothing else: no method, template, status, refusal code,
booking centre, role or outcome. This holds for the BFF's own refusals (401, 403, 503, 504) and for forwarded service
refusals (403, 404, 409, 413, 422). Other routes: as v5 section 4. Risk read routes: section 14 item 10.

**Consequence (product-owner item, section 14 item 1).** The three-field line does not show whether a decision succeeded.
A failed decision is invisible to operations in this increment, and a 503 or 504 (outcome unknown) looks like a success.
D-22 must be amended to allow a coarse outcome, or operations must accept the gap. The architect does not choose.

**Bounds on the decision route.**
- The BFF checks session, origin and CSRF before reading the body, as BR-37 orders (the human is identified first).
- The BFF reads at most 16 385 bytes (D-09's 16 KiB plus one) and forwards what it read. The service sees one byte more than its limit and refuses 413 after identifying the caller.
- A longer body is not forwarded. The BFF answers the service's constant 413 bytes with `Connection: close` and does not read the rest. A client may see a reset before the answer, and then a network error, not a status. Accepted. Deployment check: the servlet container does not drain a refused body.
- The BFF's own 400 covers only faults visible without reading the body: a duplicated or non-numeric `Content-Length`. It answers the service's constant "request not readable" bytes. The BFF never judges the body (D-12 belongs to the service).
- Upstream exceptions map to `bff-unavailable` or `bff-timeout` in the BFF's code. On this route that code is not written, and no exception text, URL or message reaches a log.

**Test 18.11:** as v5 (forwarded 403, 404, 409, 422 and the BFF's own 401, 403, 503, 504; each line matches the three-field pattern).
**Test 18.11a:** the BFF's own 400; 16 385 bytes forwarded and answered 413 by the service; 16 386 bytes not forwarded, answered 413 with `Connection: close`.
**Test 18.11b:** an upstream failure (unreachable with the URL in the message; a timeout; a 500) writes only the three fields.

## 4. Refusals through the BFF

As v5 section 5, with the decision-route log of section 3: forwarded 403, 404, 409, 413, 422, and the BFF's 400 and 413 as section 3.

## 5. Redis: renewal, nonces, key id and rotation (D21; v5 section 6 changed)

- **Renewal, nonces and decryption:** as v5 section 6. Renewal writes no value. Each protected field of each write takes a fresh 96-bit nonce. The NIST SP 800-38D bound is external and unchecked. Deployment check: measure the encryption rate.
- **Key id.** Each protected record stores in clear the id of the key that encrypted it (for example `enc-2026-11`). It is not a secret, and renewal keeps it.
- **Rotation.** The new key becomes the only write key. A retired key stays in the secret store and only decrypts, until the maximum session lifetime has passed since the rotation (section 14 item 6). A record under a retired key is rewritten under the active key on its next write.
- **Alarm.** The ERROR line of v5 section 6 (fixed text, no session id, key id or counter) fires only for a key id the service does not hold. A tag failure under a held key deletes the record and gives the constant 401 with no ERROR line (section 12 item 2).
- **Test 18.15:** unknown key id: record deleted, 401, one ERROR line. **18.15b (planned rotation):** a session written under K1; K2 made the write key; the session reads and renews with no 401 and no ERROR line; its next write is under K2. **18.15c:** tag failure under a held key: record deleted, 401, no ERROR line.

## 6. Identity in the service: order of checks (v5 section 7.1, changed)

1. **Certificate.** `CertificateSubjectResolver implements AuthenticationManagerResolver<HttpServletRequest>`. The name is kept: Checkstyle's `noServiceSuffixDeclaration` (regex verified) bans the suffixes Service, Facade, Manager, Helper, Util and Utils, and this name has none. It reads `jakarta.servlet.request.X509Certificate`, which Tomcat sets to the `X509Certificate[]` of `getPeerCertificateChain()`, takes element 0, and refuses a null, non-array or empty attribute. Issuer and subject are compared as `X500Principal` with `equals`, against `app.identity.client-certificate-issuer` and the registered subjects. No match gives the refusing manager (section 8): zero key fetches, constant 401. The resolver is a singleton bean, so `client certificate not registered; caller not identified` is written once per process, with no subject, issuer or token (test 18.3).
2. **Signature.** `NimbusJwtDecoder.withJwkSource(chain).jwsAlgorithm(SignatureAlgorithm.RS256)` in `new ProviderManager(new JwtAuthenticationProvider(decoder))`, with the decoder of section 8. The configurer's `.authenticationManagerResolver(resolver)` takes precedence over `.jwt(...)` (stage 3 confirms).
3. **Claims and role (D16).** `azp` selects the registration:
   - `azp` = BFF client id: a user token. `scp` present; `roles` holds exactly one of the four human roles.
   - `azp` = a registered onboarding client id: an app-only token. `scp` absent; `roles` holds exactly ONBOARDING_SYSTEM.
   - Any other `azp`, a user token from an onboarding client, or an app-only token from the BFF client is refused. Exact, case-sensitive.
   - The certificate's registration must be the `azp`'s registration (a token of one application on another's certificate: 401, test 18.4).
   - `oid` is the actor. To confirm in the tenant, with `azp`, `scp`, `idtyp`, `roles` and `tid`.
4. **Caller.** `CallerClaims.callerOf(Jwt)`. BFF branch: actor from `oid`, centre from its claim. Onboarding branch: centre from the registration; no booking-centre claim is read. Claim names stay configurable (D-14). `identify()` keeps the in-scope check (BR-04).

**Behaviour changes:** a user token naming any other role is refused (v5 accepted `[MLRO, OTHER_APPLICATION_ROLE]`); a case variant of an ActorRole name is refused (v5 ignored it).

## 7. Configuration: one validated record (W6, W7, W8, W14, placeholders, audience)

### 7.1 The record

`OnboardingCentresInScope` lives in `risk/adapters/config`, never in `domain/`. It replaces `RiskProperties` and
`IdentitySettings`, binds under prefix `app` as v5 section 7.2 did, and adds `secrets`. It has three nested trees:
`identity` (issuer, audience, jwk-set-uri, client-certificate-issuer, the three claim names, `bff`, `onboarding.us|gb|de`
with client id, booking centre and certificate subject, and `key-set` with TTL, rate-limit interval and last-known window),
`secrets` (section 7.2), and `bookingCentres`. Every nested record is `@Valid`. Every String carries the constraint of 7.2.

### 7.2 Placeholders (D18, W6, application.yml:12)

- **Constraint.** Each String has `@NotBlank` and `@Pattern(regexp = "(?s)(?!.*\\$\\{).*", message = "<property> (<VARIABLE>) must be set: the variable is unset")`. The probe shows it refuses `${ISSUER_URI}`, `/etc/tls/${NAME}.p12` and `x ${`, and accepts a URL and multi-line text with no `${`.
- **Mechanism.** Boot binds with `PropertySourcesPlaceholdersResolver`, in `spring-boot-4.1.2.jar`. Its helper is built with `ignoreUnresolvablePlaceholders = true` (`iconst_1` in the constructor). An unset variable binds as the literal `${NAME}`, which `@NotBlank` passes. v5's `isNoUnresolvedPlaceholder()` could not name the setting; the per-field message can.
- **Variables (22).** ISSUER_URI, TOKEN_AUDIENCE, JWK_SET_URI, CLIENT_CA_SUBJECT, BFF_CLIENT_ID, BFF_CERTIFICATE_SUBJECT; ONBOARDING_{US,GB,DE}_{CLIENT_ID, BOOKING_CENTRE, CERTIFICATE_SUBJECT} (9); DATABASE_PASSWORD, FLYWAY_USERNAME, FLYWAY_PASSWORD, SERVICE_TLS_KEYSTORE, SERVICE_TLS_KEYSTORE_PASSWORD, CLIENT_CA_TRUSTSTORE, CLIENT_CA_TRUSTSTORE_PASSWORD.
- **Secrets bound twice, on purpose.** `app.secrets.database-password: ${DATABASE_PASSWORD}` exists for the check; the same variable still feeds `spring.datasource.password`. The record's `toString` prints no value, and nothing logs it.
- **Honest statement.** `application.yml:12` says a missing DATABASE_PASSWORD "must fail startup", and the Flyway block says a missing owner must fail startup. Today that is not guaranteed: the literal `${DATABASE_PASSWORD}` goes to the database, and startup fails only if the database rejects it. This change makes the claim true for the 22 variables.
- **Test 18.6p:** one parameterised test, 22 cases. Each variable left UNSET in a `MockEnvironment` (the test first asserts absence); startup refused; the message names the variable. **18.6(b):** set to empty, refused by `@NotBlank`.

### 7.3 Audience (D19, behaviour change)

Today `spring.security.oauth2.resourceserver.jwt.audiences` is a list, and Boot splits comma-separated text. v6: one string, `app.identity.audience` (`TOKEN_AUDIENCE`), matched exactly by `JwtAudienceValidator(String)` (verified). **Behaviour change:** a comma-separated value is one audience and matches no token of its parts. **Test 18.7b:** `a,b` refuses `aud=a` and `aud=b`; `a` accepts `aud=[x, a]`.

### 7.4 Cross-checks and null safety (W7, W8, W14, item 11)

- `isOnboardingCentresInScope()`: for us, gb, de, `CanonicalUuid.parse` (verified `Optional<UUID>`) is present, the UUID is a key of `bookingCentres`, and its `Jurisdiction` equals the registration's. The three centres, four client ids and four certificate subjects (BFF included) are pairwise distinct.
- `isKeySetWindowOrdered()`: `0 < interval < TTL < window <= 300 s` (`LAST_KNOWN_WINDOW_MAXIMUM`, D-21). Equality refused for both strict comparisons.
- `isClientCertificateSubjectsParse()` (W8): each issuer and subject parses with `new X500Principal(text)`. The probe shows `not a distinguished name` and `CN=x,,` throw `IllegalArgumentException`, and the empty string parses to an empty name, so `@NotBlank` stays. Only `IllegalArgumentException` is caught.
- **Null safety.** Each `@AssertTrue` method checks identity, onboarding, bookingCentres and each registration for null, and returns false. Method names are `is...`, so Bean Validation reads them as getters (stage 3 confirms with 18.6). Test 18.6(c) asserts the message text.

### 7.5 Configuration keys

`app.identity.audience: ${TOKEN_AUDIENCE}`; `app.identity.*` as v5 with the names of section 7.2; `app.secrets.*` as section 7.2; `key-set` as v5. The booking-centre keys of `app.booking-centres` stay as today. `spring.security.oauth2.resourceserver.jwt.*` leaves the `spring.` namespace (test 18.7). Environment variable names of existing settings do not change.

### 7.6 Affected files (item 9)

| File | Change |
|---|---|
| `risk/adapters/config/RiskProperties.java` | Replaced by `OnboardingCentresInScope` (same `bookingCentres` binding) |
| `risk/adapters/config/RiskModuleConfiguration.java` | `@EnableConfigurationProperties(OnboardingCentresInScope.class)`; the `bookingCentres` bean reads the new record. The existing `Clock` bean is the clock of section 9 |
| `risk/adapters/web/IdentitySettings.java` | Deleted; settings move to `OnboardingCentresInScope.Identity` |
| `risk/adapters/web/BearerTokenSecurityConfiguration.java` | `IdentitySettings` and `rateLimitedKeySet` (with `noKeyWhenLimited`) removed; decoder and resolver beans added; firewall bean stays |
| `ConfiguredIdentityValidator`, `CallerClaims`, `CallerNotIdentifiedAnswer` | Audience is one string; role rule D16; onboarding branch reads no centre claim; per-request WARN removed (v5 8.2) |
| `risk/adapters/web/` (new) | `CertificateSubjectResolver`, `IdentityJwtDecoder`, `ApplicationTokenValidator`, `LastKnownKeySetSource`, `FetchRecordingJWKSetSource`, `KeySetHealth` |
| `src/main/resources/application.yml`, `src/test/resources/config/application.yml` | Identity to `app.identity`; `jws-algorithms` removed; `app.secrets`; TLS bundle (section 10); test values shared |
| `ProductionIdentitySettingsTest`, `IdentitySettingsStartupIT`, `IdentityIT`, `IdentityClaimNamesIT`, `IdentityKeysUnavailableIT` | Renamed keys; the tests of section 13 |
| `ProductionRolesIT`, `RiskApiAdversarialIT`, `RiskApiFlowIT`, `RiskApiHardeningIT` | Each context loads the record, so each must supply all 22 values from the shared test configuration |
| `ArchitectureRulesTest`, `RiskModuleArchitectureRulesTest` | No change expected; the new classes stay out of `domain/` |

## 8. Decoder and validators (item 6)

- **Verified.** The `NimbusJwtDecoder(JWTProcessor)` constructor sets `JwtValidators.createDefault()`, timestamp validation only (offset 26). The `JwkSourceJwtDecoderBuilder` from `withJwkSource(...)` has no `setJwtValidator` call. `setJwtValidator(OAuth2TokenValidator<Jwt>)` is public. So a hand-built decoder gets no issuer, audience or application check unless this design adds them.
- **One bean, `IdentityJwtDecoder`:**

```java
var decoder = NimbusJwtDecoder.withJwkSource(keySource).jwsAlgorithm(SignatureAlgorithm.RS256).build();
decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(issuer),   // timestamps and JwtIssuerValidator(issuer)
        new JwtAudienceValidator(audience),              // aud contains the audience
        configuredIdentityValidator,                     // issuer, audience and exp present
        applicationTokenValidator));                     // azp, scp and roles (D16)
```

  The refusing decoder of section 6 gets the same composition, so no path depends on the constructor default.
- **Verified in jose and oauth2-core 7.1.2:** `JwtValidators.createDefaultWithIssuer(String)`, `JwtAudienceValidator(String)`, `JwtIssuerValidator(String)`, `JwtTimestampValidator` (setter for an empty `exp`), `DelegatingOAuth2TokenValidator(OAuth2TokenValidator<T>...)`.
- `ConfiguredIdentityValidator` is kept: it refuses a missing `exp` whatever the timestamp validator's setting is.
- **Tests through the real decoder** (tokens signed with the test key served by IdentityIT): 18.8b wrong issuer, 18.8c wrong audience, 18.8d missing `exp`; each 401.

## 9. Key source chain, serving layer and health (items 2, 3, 4; W2, W4, W5)

### 9.1 Chain

`JWKSetBasedJWKSource` → `LastKnownKeySetSource` (serving layer, singleton) → `CachingJWKSetSource` (TTL 60 s, refresh timeout, listener) → `FetchRecordingJWKSetSource` → `RateLimitedJWKSetSource` (30 s) → `URLBasedJWKSetSource` over `DefaultResourceRetriever`. Built by hand: `JWKSourceBuilder` references no `RemoteJWKSet`, so `RemoteKeySourceException` is not thrown here.

The interface takes an evaluator, not a boolean: `JWKSetSource.getJWKSet(JWKSetCacheRefreshEvaluator, long, C)` (verified). v5's `getJWKSet(refresh, now, ctx)` was wrong. `JWKSetBasedJWKSource.get` calls `noRefresh()` first and `referenceComparison(set)` if no key matches, with `System.currentTimeMillis()`. A valid cache with an unknown `kid` therefore fetches nothing (section 9.4).

### 9.2 Serving layer and catch clauses (item 3, W2)

```
getJWKSet(evaluator, now, ctx):
  try { source.getJWKSet(evaluator, now, ctx); }    // return value unused (W2)
  catch (RateLimitReachedException e) { }            // silent: no state change; the window below still runs
  catch (JWKSetRetrievalException | JWKSetParseException e) { health.fetchFailed(); }   // IdP failed
  catch (JWKSetUnavailableException e) { }           // cache side: lock timeout, no valid cache, interrupt. Not counted
  // every other KeySourceException and RuntimeException propagates. No catch-all.
  t = clock.millis();                                // read first (9.4)
  pair = health.current();                           // one read of (record, state)
  if (pair.record == null) return EMPTY;
  if (t - pair.record.fetchedAtMillis >= windowMillis) { health.expire(); return EMPTY; }
  return pair.record.keys;
```

- **Classes.** The three refusal types extend `KeySourceException`. `JWKSetRetrievalException` and `JWKSetParseException` extend `JWKSetUnavailableException`, so they are caught first. `URLBasedJWKSetSource` throws retrieval at offset 20 (IO, `IOException` handler at 0 to 15) and parse at 64. The limiter throws at 110. The cache throws the base class at 387 ("Timeout while waiting for cache refresh"), 472 ("Unable to refresh cache", when the wait ends with no valid entry) and 490 ("Interrupted while waiting for cache refresh").
- **Each IdP failure is counted once**, by the thread that asked the IdP. A waiter given "Unable to refresh cache" did not ask, and is not counted.
- **W2 rationale.** An IdP refresh failure is never dropped silently: it is recorded and the request is answered from the record. The only silent case is the limiter, which is not an IdP failure. The cache's return is ignored: it carries no fetch time and can differ from the record during a race.
- **Log content.** The health log writes the root cause's class name only (`NestedExceptionUtils.getMostSpecificCause`, verified in spring-core 7.0.10), never the message, which can carry the URL.

### 9.3 Health: one immutable pair (item 4, W1)

`record KeySetRecord(JWKSet keys, long fetchedAtMillis)`; `enum KeySetState { UNKNOWN, OK, FAILING, LAPSED }`; `record Health(@Nullable KeySetRecord record, KeySetState state)`, immutable, in one `AtomicReference` (one singleton bean).

- Every transition is a compare-and-set on the whole `Health`. No transition writes the record without deciding the state. A CAS loser reads again and decides again. Only the winner logs.
- **Stamps never decrease.** `fetchSucceeded(keys, stamp)` with `stamp < record.fetchedAtMillis` is discarded silently. An equal stamp replaces the keys. The stamp is the request's `now`, which precedes the IdP answer when the request waited for the lock. That is conservative for D-21: the window closes earlier, never later. The cost: a late, older set waits for the next successful fetch (section 12 item 3).
- **Lazy expiry (intended).** The window is checked on the next request. In a long outage with no requests the state stays OK or FAILING. The first request after the window logs LAPSED; if that request also has a failed fetch, OK to FAILING and FAILING to LAPSED both log in that one request. Each transition logs once, and nothing is written while nobody asks.

| Transition | Trigger | Log |
|---|---|---|
| UNKNOWN to OK | Fetch succeeds | None |
| UNKNOWN to FAILING | Fetch fails, no fetch ever succeeded | One WARN: `no key set has been fetched yet; identity provider key fetch failing`, with the root-cause class |
| OK to FAILING | Fetch fails | One WARN: `identity provider key fetch failing`, with the root-cause class |
| OK or FAILING to LAPSED | A request finds the record older than the window | One WARN: `last known keys expired; known keys refused until a fetch succeeds` |
| FAILING or LAPSED to OK | Fetch succeeds | One INFO: `key fetch recovered` |
| FAILING to FAILING, LAPSED to LAPSED, any rate-limit refusal, any stale success | Another failure, a refusal, or an older stamp | None |

Rules: a null record never gives LAPSED. Without a record, UNKNOWN and FAILING serve EMPTY (constant 401) with no further log. A non-null record implies OK, FAILING or LAPSED.

### 9.4 Race and clock (item 2, W4)

The serving layer reads the clock after the cache returns and before the pair. The race is held in the test `Clock`; there is no production hook. Setup: S1 fetched at 0 (kid K1), TTL 60 000, window 300 000. Every test passes `now` and the `Clock` explicitly.

- **18.1 (concurrent).** Setup: X calls with now 10 000 and gets the cache hit; it blocks in the test clock before its read. Race: Y calls with now 60 000, refreshes to S2 (K2), and stores (S2, 60 000, OK). Assert: X is released after Y completes and reads the pair after Y's store, so X answers S2; K1 refused from 60 000; K2 verifies at 359 999 and is refused at 360 000; two successful fetches in total.
- **18.1a (sequential).** Release X before Y starts: X reads S1 (age 10 000, in window). Every answer from Y on is S2.
- **Negative age.** A record stamped after X's clock read has a negative age. The test is `age >= window → expired`, so it is served.
- **18.1b (stale write, forced refresh).** Bytecode: `CachingJWKSetSource.getJWKSet` returns the cached set without a fetch unless the evaluator requires one or the entry has expired (offsets 50, 72 to 80). `ReferenceComparisonRefreshJWKSetEvaluator.requiresRefresh` is `this.jwkSet != set` (`if_acmpne`, offset 5). `ForceRefreshJWKSetCacheEvaluator.requiresRefresh` is true (`iconst_1`). So the late call uses `JWKSetCacheRefreshEvaluator.forceRefresh()`. The limiter must admit both fetches, so the times are: S1 at 0 (window W0 opens: next opening 30 000, counter 1); forced fetch at 35 000 (window W1 opens: next opening 65 000, counter 1) stores (S2, 35 000); late forced call at now 30 000, admitted as W1's second admission, fetches S3. Its stamp 30 000 is below 35 000, so S3 is discarded. Assert: two IdP requests after S1; the record stays (S2, 35 000); the late call answers S2.

### 9.5 Limiter and outage (W5, 18.2)

`RateLimitedJWKSetSource.getJWKSet` (bytecode): a call with `now` at or after `nextOpeningTime` sets it to `now + interval`, the counter to 1, and admits (offsets 6 to 26). Otherwise it admits while the counter is above zero, decrements it, and refuses with `RateLimitReachedException` at zero (offsets 36 to 61, 110). Two admissions per window. A window opens at the first call at or after the previous opening plus the interval, not on a fixed grid.

- **18.2:** the IdP returns S1 once, at 0, and fails every later fetch. K1 and unknown kids are probed every second from 61 000 to 299 999. Assert: K1 verifies at every probe in [61 000, 299 999]; each unknown kid gets 401; IdP requests in [0, 300 000] are at most 22, as an upper bound, with no exact enumeration of request times; K1 is refused from 300 000. The log holds exactly two lines: OK to FAILING at 61 000, FAILING to LAPSED at the first request at or after 300 000. With no successful fetch, K1 stays refused.

### 9.6 What a propagated exception becomes

| Exception | Path | Answer |
|---|---|---|
| `RateLimitReachedException` | Caught, silent (9.2) | 401 for an unknown kid |
| `JWKSetRetrievalException`, `JWKSetParseException` | Caught, `fetchFailed` | 401 |
| `JWKSetUnavailableException` (base class) | Caught, silent | 401 |
| Other `KeySourceException` | Into `processor.process`: `createJwt` catches `RemoteKeySourceException` and `JOSEException` into `JwtException`; the provider maps it to `AuthenticationServiceException` | Constant 401 |
| `RuntimeException` from the key source | Into `processor.process`: `createJwt` catches `Exception` (offset 185) into `BadJwtException`; the provider maps it to `InvalidBearerTokenException` | Constant 401, no log |
| `RuntimeException` from a validator | `validateJwt` has no handler; the provider catches only `BadJwtException` and `JwtException`; the filter catches `OAuth2AuthenticationException` and `AuthenticationException` | Not 401: a 5xx from the container (test 18.12e) |

No catch-all is written. The `createJwt` trace line is at TRACE, which the pinned INFO level for `org.springframework.security` keeps off (BR-43).

## 10. Mutual TLS, test PKI and keytool (W8, W9, W11, W12)

- **Server TLS:** as v5 section 9.1, with the bundle under `spring.ssl.bundle.jks`. `JksSslBundleProperties$Store` has `type`, `provider`, `location` and `password` (verified), so `type: PKCS12` is accepted and no JKS conversion is needed. Stage 3 confirms the bundle loads a PKCS12 file.
- **PKI:** as v5 section 9.1. The JDK `keytool` runs from test sources into a temporary directory with `-storetype PKCS12`. No BouncyCastle, no committed key material. Cost measured in stage 3.
- **Certificate shape, valve chain, forged header, handshakes:** as v5 sections 9.2 and 9.3 (tests 18.9(a) to (d)). A bare certificate, an empty array and a missing attribute are each 401.
- **D-20 residual:** test 18.9(c). A trusted-CA certificate with an unregistered subject completes the handshake and gets 401 at HTTP (section 12 item 1).
- **Deployment check:** TLS ends at the service's Tomcat; no proxy or gateway terminates TLS or re-sends a client certificate.

## 11. Verification status

Checked 2026-11-11 by javap and bytecode reading in `~/.m2` jars, by reading the repository, and by two scratchpad probes (`X500Principal`; the placeholder pattern). No Maven run; nothing compiled.

- **Nimbus 10.9.1:** `JWKSetSource` evaluator signature; `CachingJWKSetSource.getJWKSet` (offsets 50, 72 to 80) and `loadJWKSetBlocking` throws (387, 472, 490); `ReferenceComparisonRefreshJWKSetEvaluator` (`if_acmpne` at 5); `ForceRefreshJWKSetCacheEvaluator` (true); `JWKSetBasedJWKSource.get`; `RateLimitedJWKSetSource` (6 to 26, 36 to 61, 110); `URLBasedJWKSetSource` throws (20, 64); exception hierarchy; `JWKSourceBuilder` has no `RemoteJWKSet`; `OutageTolerantJWKSetSource` present, not used.
- **Spring Security 7.1.2:** `NimbusJwtDecoder` constructor (offset 26) and `setJwtValidator`; `createJwt` catch clauses (76, 147, 185); `validateJwt` without handler; `JwkSourceJwtDecoderBuilder` with no validator call; `JwtAuthenticationProvider.getJwt` catch clauses (0 to 13, 52); `BearerTokenAuthenticationFilter.doFilterInternal` catch clauses; `JwtValidators.createDefaultWithIssuer(String)` and `createDefault()`; `JwtAudienceValidator(String)`; `JwtIssuerValidator(String)`; `JwtTimestampValidator` setters; `DelegatingOAuth2TokenValidator` (oauth2-core).
- **Spring Boot 4.1.2:** `PropertySourcesPlaceholdersResolver` in `spring-boot-4.1.2.jar`, helper with `ignoreUnresolvablePlaceholders` true (`iconst_1`); `ValidationBindHandler`; `JwkSetUriJwtDecoderBuilderCustomizer` in `spring-boot-security-oauth2-resource-server`; `JksSslBundleProperties$Store` fields; `Ssl$ClientAuth.NEED`, `ServerProperties$ForwardHeadersStrategy.NONE` (v5).
- **Spring Framework and Tomcat:** `NestedExceptionUtils.getMostSpecificCause` (spring-core 7.0.10); `requestAttr(String, Object)` (spring-test 7.0.10); Tomcat items as v5.
- **Probes:** `X500Principal` parses `CN=Onboarding US,O=Bank,C=US` and its spacing variant as equal; `not a distinguished name` and `CN=x,,` throw `IllegalArgumentException`; the empty string parses to an empty name. The pattern `(?s)(?!.*\$\{).*` (full match) refuses `${ISSUER_URI}`, `x ${` and `/etc/tls/${NAME}.p12`, and accepts a URL and multi-line text.
- **Repository:** `CallerClaims` reads claim names with `@Value` and ignores foreign roles today. `CallerNotIdentifiedAnswer` logs one WARN per `AuthenticationServiceException` today. `pom.xml` has no Redis client, Spring Session, OAuth2 client or Micrometer. Seven test classes set `app.booking-centres`. BouncyCastle and `micrometer-core` are absent from `~/.m2`.

**Not checked:** the Redis client and its Lua scripts; Spring Session; every Entra fact; NIST SP 800-38D; `X500Principal` for real Entra or internal-CA subjects; the valve beyond class names; the container's response to a validator `RuntimeException` (18.12e); the exact path to "Unable to refresh cache" (18.12b).

## 12. Open items and residuals

1. **D-20 residual.** A trusted-CA certificate with an unregistered subject completes the handshake (18.9(c)). D-20's "only the BFF and onboarding may connect" is met in code only through the internal CA's issuance policy (deployment check). The owner must accept this (section 14 item 2).
2. **No alarm for a tag failure under a held key (section 5).** Accepted for this increment. Deployment check: track record deletions in Redis.
3. **Stamp is the request's `now` (section 9.3).** A late, older set waits up to one TTL for the next successful fetch. Safe for D-21.
4. **Quarantine conflict (v5 12.1).** The design (`docs/adr/customer-risk-scoring-design.md`, "Not doing") defers quarantine; D-18 and BR-44 to BR-46 put it in this increment. Amend the design before stage 3. Release role: section 14 item 4.
5. **Tenant facts, to confirm in the tenant:** `azp`, `oid`, `scp`, `idtyp`, `roles`, `tid` in user and app-only tokens; `accessTokenAcceptedVersion: 2`; `response_mode=query` allowed; three onboarding registrations, each with its own client id and certificate; refresh-token rotation and lifetime.
6. **Deployment checks:** ingress only from the BFF and onboarding segments; the internal CA issues only these certificates; the measured encryption rate (section 5); no re-sent client certificate; the BFF-to-service path is TCP passthrough.
7. **BFF dependencies (copilot 0.1 and 0.7).** None of Redis client, Spring Session, OAuth2 client or Micrometer is in `pom.xml`. Stage 3 cannot compile the BFF tests until section 14 item 3 is decided. This design adds no dependency.
8. **W2 and W8 are answered here:** W2 in section 9.2; W8 in sections 7.4 and 10. The auditor confirms.
9. **Considered, not used:** Nimbus `OutageTolerantJWKSetSource` (present in 10.9.1), not evaluated further. The serving layer is kept because D-21's window and D11's single-log states are specified here.

## 13. Tests required before stage 4

Stage 3 writes these red (test-compile passes, `test` fails). Serving-layer tests pass `now` and a test `Clock` explicitly.

- **18.1, 18.1a, 18.1b:** concurrent race, sequential race, stale write with a forced refresh (9.4).
- **18.2:** outage: one IdP answer, then failures; two WARN lines; at most 22 IdP requests (9.5).
- **18.3:** unregistered certificate from the trusted CA: 401, zero IdP requests, bytes equal to the other 401s, one WARN per process, no subject, issuer or token in logs.
- **18.4:** a token of one application on another's certificate: 401; a flood on one application leaves the other's keys untouched.
- **18.5 (changed):** user `[ONBOARDING_SYSTEM]`: 401. User `[MLRO, OTHER_APPLICATION_ROLE]`: 401. User `[MLRO, AML_ANALYST_L1]`: 401. User `[FRONT_OFFICE]`: accepted. User `[aml_analyst_l1]`: 401. App-only `[ONBOARDING_SYSTEM]` with the registered centre: accepted. App-only `[ONBOARDING_SYSTEM, OTHER_APPLICATION_ROLE]`: 401. App-only `[ONBOARDING_SYSTEM, ONBOARDING_SYSTEM]` and `[AML_ANALYST_L1]`: 401. `azp` differing in case: 401. User token with an onboarding `azp`: 401. App-only token with the BFF's `azp`: 401.
- **18.6 (a) to (k):** configuration refuses to start, each message naming the setting (v5 list), with (c) asserting the message. **(l):** a malformed certificate subject refused with its message.
- **18.6p:** the 22 variables, one case each, left unset: refused, message naming the variable (7.2).
- **18.7:** `ProductionIdentitySettingsTest` pins `app.identity.key-set.last-known-window: 300s`, `server.ssl.client-auth: need`, `server.forward-headers-strategy: none`, and the `app.identity` and `app.secrets` keys; asserts the absence of `spring.security.oauth2.resourceserver.jwt.*` and `server.tomcat.remoteip.*`.
- **18.7b:** single audience (7.3).
- **18.8:** HS256 and ES256 refused; RS256 from a registered key accepted.
- **18.8b, 18.8c, 18.8d:** through `IdentityJwtDecoder`: wrong issuer, wrong audience, missing `exp`: each 401.
- **18.9(a) to (d):** valve chain, forged header, handshakes, attribute shapes (section 10).
- **18.10:** BFF behaviours of v4 test 18.10, unchanged.
- **18.11, 18.11a, 18.11b:** decision log lines; BFF's own 400 and 413 bounds; upstream failure writes three fields only (section 3).
- **18.12:** health (9.3): first use from UNKNOWN with one WARN and the root-cause class; later failures silent; 400 000 ms with no record: 401, no LAPSED; OK to FAILING: one WARN; FAILING to LAPSED: one WARN; recovery: one INFO; rate-limit refusals and stale successes: no log, no state change; the 401 path writes no WARN.
- **18.12b:** cache-side exceptions do not count: lock timeout (refresh timeout set small, IdP slow), "Unable to refresh cache", interrupt: no `fetchFailed`, no WARN, no IdP request by the waiter. The refreshing thread's failure counts once.
- **18.12c:** `JWKSetRetrievalException` (IO) and `JWKSetParseException` (malformed JSON) count; another `KeySourceException` propagates to a 401 through the decoder.
- **18.12d:** `RuntimeException` from the key source: 401 through the decoder, no log.
- **18.12e:** `RuntimeException` from a validator: a 5xx, never 401 or an accepted token. Documents 9.6; a later catch-all fails it.
- **18.13:** Redis keys as v4 test 18.13, with the key id field.
- **18.14:** renewal: ciphertext, nonce and key id bytes unchanged; both TTLs equal the new value.
- **18.15, 18.15b, 18.15c:** unknown key id (ERROR line, 401, record deleted); planned rotation keeps the session; tag failure under a held key (401, no ERROR line).
- **ArchUnit:** `ArchitectureRulesTest` and `RiskModuleArchitectureRulesTest` green with the new classes.

## 14. Decision requested

What the product owner must still decide:

1. **D-22 consequence.** The decision log cannot show success, and failed decisions (including 503 and 504) are invisible to operations. Amend D-22 to allow a coarse outcome, or accept the gap (section 3).
2. **D-20 residual.** Accept that a trusted-CA certificate with an unregistered subject completes the handshake and is refused at HTTP, so D-20 is met only through the CA's issuance policy (section 12 item 1).
3. **BFF dependency decision.** The Redis client, the session store (Spring Session or the BFF's own) and the Entra client (Spring's OAuth2 client or the BFF's own flow). None is in `pom.xml`; stage 3 cannot compile the BFF tests without this (section 12 item 7).
4. **Quarantine release role (D-18, BR-46).** Who may release a quarantined event; each release is recorded with who and when.
5. **One live session per user** (v5 D15, withdrawn to here).
6. **Session lifetimes.** Idle and absolute timeouts. The absolute timeout also sets how long a retired key is kept (section 5).
7. **Logout.** Keep logout local, or also revoke the user's refresh tokens at Entra.
8. **Owner of the BFF:** code, Entra registration, certificates and Redis.
9. **BFF in this increment** (v4 section 3): build it now, or the service side only, with the human routes unused until it exists.
10. **Log fields on the risk read routes.** D-22 covers decision routes only. Confirm the decision field set (section 3), or allow the template and status there.
