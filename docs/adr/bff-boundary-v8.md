# ADR: Backend-for-frontend boundary, eighth draft (v8)

> **Audit 2026-11-27 (eighth): `[CHANGES REQUIRED]`, one blocker. Do not implement.** B1 of v7 is closed on the
> service side (a body-less GET sends no Content-Length; verified in the JDK client). Blocker F1: the set of
> answers before identification is not closed. (a) OPTIONS answers 200 with `Allow` before identification today,
> and a method-less `/**` cannot catch it; a CORS pre-flight is answered too. Fix in design: OPTIONS identifies
> first. (b) Tomcat refuses some requests before any application code (TRACE 405, `OPTIONS *`, Host, URI,
> version 505, Expect 417, Transfer-Encoding, encoding 501), none token-dependent, not in D-26: owner decision.
> Warnings: v8 predates D-27 to D-35 (split this increment from the BFF increment, D-34); the Operations role
> (D-30) leaves the spec inconsistent (five roles in D-15, four in D-19, no release command, no channel); the
> BFF's repeat after a refresh must re-identify; `IdentityIT.shouldNotServeSpringSecurityEndpoints` changes to
> 401; today a wrong method answers 405, and a bad token vs. bad claims differ on unknown routes (I-04).

> **Status: Proposed, 2026-11-27. Not audited. Do not implement** until the tests in section 14 are red in stage 3
> and the items in section 15 are decided. v8 is a delta on v7 that answers the seventh audit (blocker B1, warnings
> W1 to W6, notes N1 to N7) under the new spec decisions D-25 (two-step body route) and D-26 (accepted exceptions).

- **Mode:** `@architect`, design mode, stage 2, eighth draft. The architect does not decide business rules; every open business question is in section 15.
- **Replaces:** `docs/adr/bff-boundary-v7.md` where section 2 says so. Everything not listed there stands as v7 wrote it.
- **Scope:** unchanged. No change to `domain/`. No user interface (D-03). One new service route (section 3.2).
- **Verification (2026-11-27):** each mechanism below was checked with `javap -c -p` in the jars under `~/.m2` and in the JDK 25.0.4.1 runtime, in `src/main` and `src/test` (read), and by one probe run with plain `java`. Excerpts and probe output are saved in `/tmp/claude-0/-home-user-kyc-aml/52ec4e60-260e-5863-9603-67b60a05607e/scratchpad/v8/` (named per fact in section 12). No Maven was run, nothing in the repository was compiled. Unchecked facts are marked "to confirm in the tenant", "deployment check" or "stage 3".

## 0. Delta on v7

| # | Audit item | Answer in v8 |
|---|---|---|
| B1 | JDK client sends the body after 5 000 ms without `100 Continue` | Section 3: `Expect: 100-continue` and `continueResponseTiming` are dropped. Two calls (D-25): `GET /api/caller-identification` with no body, then the decision with the body the BFF read only after a 204. Tests 18.11a, 18.11c, 18.11d |
| W1 | Answers before identification not all listed; LOW citation wrong | Section 4.1: the closed set of D-26, citation corrected. Two answers outside it found on the service and designed away (4.2) |
| W2 | DEBUG on `org.springframework.web` | Section 5: pinned to INFO, asserted, AGENTS.md line required |
| W3 | Option B needs `throw`; class name ends in Manager | Section 9: `RegisteredClientTrust` delegates to two JDK trust managers; no `throw`, no `catch` |
| W4 | Five ITs on plain http | Section 10 |
| W5 | Claim-reading rewrite not listed | Section 7 (rewrites, converted versus raw claims, 18.5 additions) |
| W6 | CAS-loser rule contradicts v6 | Section 8.1: read the pair again, then the clock, then decide again. Test 18.1d |
| N1 | Stale-stamp availability cost | Section 8.2, asserted in 18.1b |
| N2 | Read timeout is per read | Section 8.3 (key fetch) and 3.6 (BFF body read) |
| N3 | TTL cap | Section 6: every write, milliseconds, rounded down. Test 18.14d |
| N4 | Typed protocol customizer | Moot: nothing customises the protocol any more; option B must be a connector customizer (section 9, order verified) |
| N5 | ADR decision numbers clash with the spec | Section 1: the ADR's own decisions are now `BD`-numbered. The suggested "D26" would itself clash with the spec's new D-26 |
| N6 | Quarantine conflict | Section 11: an architect amendment before stage 3, not an owner item |
| N7 | Chunked reset | Moot: the BFF forwards a known length of at most 16 385 bytes (3.5); never chunked |

## 1. Decisions

The ADR's own decisions are renamed `BD1` to `BD26`: v7's D1 to D26 keep their numbers with the new prefix, so `BD13` is never read as the spec's D-13. The spec keeps `D-nn`.

- **BD22 (replaced, D-24, D-25): two calls on a body route.** The BFF asks the service to identify the caller with no body; only after a 204 does it read the client body, bounded, and forward it with a known length. The service identifies again and `BoundedBody` decides the size (section 3).
- **BD23 (withdrawn):** `continueResponseTiming=onRead` and `Expect: 100-continue`. No connector or protocol customizer remains for them.
- **BD24, BD25, BD26:** as v7 D24 (one chain per registered application), D25 (pair first, then the clock; made complete in 8.1) and D26 (secrets by `@AssertTrue`).
- **BD27 (new, D-26): the answers given before the service identifies the caller are exactly the closed set of section 4.1.** Any other is a defect with a red test.
- **BD28 (new, D-20 option B, only if the owner chooses B): `RegisteredClientTrust`** (section 9).

## 2. Carried over from v7, and superseded

Unchanged: v7 sections 1 (as renamed), 3.4 (log line and correlation id), 6.1, 6.2, 8, 9.1, 9.2, 10 option A, 11, and 13 items 4 and 6.

Superseded: v7 sections 0, 3.1 to 3.3 (section 3 here), 4 (section 4), 5 (section 6), 6.3 (section 7), 9.3 (section 8), 10 option B (section 9), 12 (section 12), 13 (section 13), 14 (section 14), 15 (section 15).

## 3. Decision route under D-25 (B1)

### 3.1 Order at the BFF

`POST /api/risk-profiles/{customerNumber}/decisions`:

1. **Session** (cookie, record, decryption). Failure: the constant 401. No body byte read.
2. **Origin**, then **CSRF**, header only. Failure: 403 (D-26: a session counts as identification for these checks). `CsrfTokenRequestHandler.resolveCsrfTokenValue` by default falls back to `request.getParameter` (verified, offsets 52 to 57), which parses a form body; the BFF's handler overrides it to read the `X-CSRF-TOKEN` header only (v4 section 8, kept).
3. **Identify call** to the service (3.2). Any answer but 204: section 3.4. No body byte read.
4. **Body read** (3.5), only after a 204.
5. **Decision call** with that body (3.4). The service's answer is forwarded byte for byte.

### 3.2 The identification route (service)

- **Route:** `GET /api/caller-identification`, a new `CallerIdentificationResource` in `risk/adapters/web` (`*Resource`, `RiskWebRulesTest`). No path variable and no query: the request carries no customer number, so nothing in it can reach a log. It is outside `/api/risk-profiles/`, so it can never be read as `GET /api/risk-profiles/{customerNumber}`.
- **What it runs:** the same security chain as every route (certificate resolver, header-only bearer resolver, `CallerNotIdentifiedAnswer`), then `ActorContextResolver.identify(bookingCentres)`, the call `RecordComplianceDecisionResource.decide` makes first. That is the whole of BR-01: token (D-13), presenting application (D-19), certificate (D-20), claims (D-14 to D-16, D-23), booking centre in scope (D-06).
- **Success: `204 No Content`**, `Cache-Control: no-store`, no body, no other application header. It names no role, centre, actor or customer, and reads no risk data (it calls no use case and no reader).
- **Refusals:** the constant 401, the bytes of `ProblemResponses.notIdentified()`, which is what `ProblemResponses.access` returns for `CallerNotIdentified` on the decision route (read), so the two routes give the same bytes. Otherwise only the D-26 container and firewall answers, and a 5xx on a defect (v7 section 8).
- **Body:** the resource never calls `getInputStream`, `getReader` or `getParameter`.

### 3.3 What the identify step decides, and what it cannot

- **It decides BR-01 only.** For a decision, the permission depends on the kind in the body (D-08: an unrecognised kind is judged as freezing assets). And BR-37 puts *request too large* (D-09) and *not readable* (D-12) **before** the role. A role check in the identify step would therefore change the refusal order: a `FRONT_OFFICE` caller (no decision permission, `RiskPermissions.forDecision`) with a 20 000-byte body must get 413, not 403. So the identify step checks no role, not even "may make some decision".
- **Left for the second call,** in BR-37 order: BR-01 again, size, readability, role on the kind, kind, customer, justification, version, critical fact, change, concurrency.
- **Other body routes:** the BFF has none (v4 section 4: the evaluation route is onboarding-only and never through the BFF, D-19). A later body route whose permission does not depend on the body needs its own design.

### 3.4 The two calls

- **First call:** `HttpRequest.newBuilder(uri).GET()` with `Authorization` and the BFF's `X-Correlation-Id`. The probe shows that `GET()` sends no `Content-Length` and 0 bytes after the header block; `method("GET", noBody())` would send `Content-Length: 0`, so it is not used.
  - 204: go on to the body read.
  - 401: one refresh and one repeat of the identify call (v4 5.4, back-off kept). Still 401: the service's constant 401 to the client, 0 body bytes read.
  - Any other status: `bff-unavailable` 503, 0 body bytes read. Timeout: `bff-timeout` 504, 0 body bytes read.
- **Second call:** `POST` to the same path byte for byte, `HttpRequest.BodyPublishers.ofByteArray(bytes)` with the client's `Content-Type`. The JDK sets `Content-Length` to the array length (probe: 16 385). The service trusts nothing from the first call: it identifies again (3.2), then `BoundedBody` refuses a declared length above 16 384 or a 16 385th byte with 413 (read).
  - 401 on the second call: if no refresh was made for this client request, one refresh and one repeat with the same buffered bytes (no client re-read); otherwise forwarded. At most one refresh per client request in total.
- **Identity changes between the calls** (token expired, role or centre changed, key retired, service restarted with other centres): **the second call decides.** Its answer (401, 403 or any other) is forwarded; the BFF never answers from the first call's result once it has read the body. A caller who loses identity between the calls has had the body read by the BFF but not by the service; the BFF discards it.

### 3.5 Body read at the BFF

- **Only after the 204.** Before it, the BFF's code calls nothing that reads the body. Bytes that arrive in the same TCP segments as the header block may sit in Tomcat's socket buffer; they are not parsed, judged or used.
- **Bound:** at most 16 385 bytes (`DECISION_LIMIT + 1`), whatever `Content-Length` says or whether the client sends chunked. The BFF forwards exactly the bytes read, so a body above the limit reaches the service as 16 385 bytes and gets the service's 413 (N7: no chunked forward, no reset between BFF and service).
- **Whole-body deadline: 10 000 ms**, a named constant, from the first read. The read loop calls `InputStream.read(buffer, offset, remaining)` and checks the clock after each read. Tomcat's per-read timeout is the connector's `connectionTimeout`, which also applies to body reads because `disableUploadTimeout` is true by default (verified: `NioEndpoint.setSocketOptions` 123 to 127; `AbstractHttp11Protocol` constructor 73 to 75; `Http11Processor.service` 227 to 242). The BFF sets `server.tomcat.connection-timeout: 5s`, so a client dripping one byte per read is cut after at most 15 000 ms.
- **Deadline passed, or an `IOException` from the read** (client gone, premature end, Tomcat's read timeout): `bff-timeout` 504, nothing forwarded, `Connection: close`. Only `IOException` is caught (copilot section 1).
- **After the answer:** unread client bytes are Tomcat's (swallow up to `max-swallow-size`, then close). A client still sending may see a reset instead of the answer. That happens only after identification and reveals nothing.

### 3.6 Cost and timeouts

| Step | Bound |
|---|---|
| `HttpClient.connectTimeout` | 2 000 ms |
| Identify call (`HttpRequest.timeout`, until response headers) | 10 000 ms, once more after a refresh |
| Body read | 10 000 ms + one per-read timeout (5 000 ms) |
| Decision call | 10 000 ms, once more after a refresh |

- **Why 10 000 ms for the identify call:** identification can wait for the key cache (refresh timeout 5 000 ms, v7 9.2) and the fetching thread is not bounded by that (8.3). A stub that delays the identify answer by 6 000 ms changes nothing (test 18.11a (v)).
- **Cost:** two service calls per decision on a pooled HTTP/1.1 TLS connection: one more RS256 verification and one more BR-01 evaluation, no IdP request unless a `kid` is unknown (limited per chain, v7 6.1). Decisions are single human actions; the read routes are unchanged (one call).
- **One log line per client request** (v7 3.4): time, correlation id, duration of the whole exchange, both calls included. The same correlation id goes on both calls.

### 3.7 Malformed `Content-Length`

As v7 3.3, now accepted by D-26: Tomcat's 400 (`Http11Processor.prepareInputFilters`) before any application code, at the BFF and at the service, with `ErrorReportValve` showing no report and no server info. The BFF never parses the header and, under D-25, no longer forwards it: the service always receives the length of the bytes the BFF read.

## 4. Answers on the decision route

- **Forwarded byte for byte from the second call:** as v7 section 4 (200; 400; 401 after at most one refresh; 403; 404; 409; 413; 422; any 5xx).
- **The BFF's own:** 401 (no session, expired, decryption failure, Entra `invalid_grant`, or the identify call still 401 after one refresh); 403 (origin, CSRF); 503 (`bff-unavailable`, including an identify answer other than 204 or 401); 504 (`bff-timeout`: either call, or the body read).
- **Never from the BFF:** 400 or 413 about the body.

### 4.1 Answers before the service identifies the caller (W1, D-26): the closed set

| Answer | Where | Depends on the token? | Reveals |
|---|---|---|---|
| `StrictHttpFirewall` 400 (malformed path) | Service and BFF | No | Only that the path is malformed |
| Container 400 for an oversized header block | Service and BFF | No | Only that the header block is too large |
| Container 400 for a malformed or duplicated `Content-Length` (3.7) | Service and BFF | No | Only that the framing is malformed |
| BFF 404 `request-not-handled` for an unknown route | BFF | No | That the route is not one of v4 section 4 |
| BFF 403 for a failed origin or CSRF check of a session holder | BFF | No (the session is the identification, D-26) | That the request is cross-site or lacks the CSRF header |

None depends on the token or reveals risk data. **Citation corrected:** v7 3.3 wrote that the product owner had accepted Tomcat's oversized-header refusal as LOW. The LOW rating came from the stage 6 reviews; the acceptance recorded in the design (decision 2026-11-11) predates D-24 and named neither the BFF nor `Content-Length`. D-26 (2026-11-27) is the decision that accepts these answers. **Anything else answered before identification is a defect** (BD27).

### 4.2 Two service answers outside that set (found while listing W1)

- **Unknown route on the service.** Today an unknown path or method reaches `ContainerErrorResource`, which answers 404 `request-not-handled` without identifying (read). With no token, an unidentified caller gets 404, not the constant 401. D-26 accepts the BFF's 404, not the service's. Design: `UnknownRouteResource` maps `@RequestMapping("/**")`, the least specific pattern, so every explicit mapping wins over it. It calls `identify` first: empty, the constant 401; present, the 404 `request-not-handled` bytes of today. Test 18.17. Stage 3 confirms that a wrong method on a known path is matched by it, not answered 405.
- **`/.well-known/oauth-protected-resource/**`.** `ProtectedResourceMetadataNotServed` sends 404 before the bearer filter runs, so it cannot identify. Design: it answers the constant 401 bytes (`ProblemResponses.notIdentified()`) to every caller on that path. No client uses the path, the answer judges nothing, and an unidentified caller gets the answer D-24 requires. Test 18.17b.

## 5. Web logging pinned to INFO (W2)

- **Why** (`DispatcherServlet` in spring-webmvc 7.0.10). `logRequest` goes through `LogFormatUtils.traceDebug`, which builds the message only when DEBUG is on (`isDebugEnabled` at 1). The message lambda then: (a) with `enableLoggingRequestDetails`, calls `getParameterMap` for any non-multipart type (offset 34), and Tomcat's `Request.parseParameters` reads the whole body for `application/x-www-form-urlencoded` (verified: content-type check at 162, `readPostBodyFully` at 322); (b) without it, calls `getParameterMap` for a non-form type (offset 89), which parses the query string only, and writes the request path, which carries the customer number. All of this runs in the dispatcher, before the resource identifies the caller.
- **Change:** `application.yml` of the service and of the BFF gets `logging.level.org.springframework.web: INFO` with a comment. `ProductionIdentitySettingsTest` gets `shouldPinSpringWebLoggingToInfo` asserting the shipped value `INFO` (test 18.18), next to the `org.springframework.security` pin.
- **AGENTS.md must get the line** (the architect does not edit it): "Never set DEBUG or TRACE on `org.springframework.web`. `DispatcherServlet` logs the request path (customer numbers) and, with `spring.mvc.log-request-details`, parses a form body before the caller is identified (BR-01, BR-43, D-24). `application.yml` pins it to INFO and `ProductionIdentitySettingsTest` fails if that line goes."

## 6. Redis: the TTL cap on every write (N3)

v7 section 5 stands, with the cap made general: **every** write of a session record or its index (sign-in, renewal, token refresh, any rewrite) passes `ttlMillis = min(idleTimeoutMillis, absoluteExpiresAtMillis - nowMillis)`, in milliseconds (`PEXPIRE` or `SET ... PX`), rounded down: `absoluteExpiresAt` is stored as epoch milliseconds and a sub-millisecond remainder is dropped, never rounded up. `ttlMillis <= 0`: record and index deleted, constant 401. **Test 18.14d:** a token refresh 10 500 ms before the absolute expiry writes a TTL of at most 10 500 ms; a refresh 0.4 ms before it is treated as `<= 0` (record deleted).

## 7. Claims read raw (W5, v7 6.3)

**Which claims arrive converted.** `NimbusJwtDecoder.decode` calls `createJwt`, which applies `MappedJwtClaimSetConverter.withDefaults` (verified: converters for `aud`, `exp`, `iat`, `iss`, `jti`, `nbf`, `sub` only), and then `validateJwt` (offsets 52, 58). So validators see `iss` as `String`, `aud` as `List<String>` (a single string becomes a list), `exp`, `iat`, `nbf` as `Instant`. `roles`, `scp`, `azp`, `oid` and the booking-centre claim arrive as Nimbus parsed them: `String`, `List`, `Number`, `Boolean` or `Map`.

**Rewrites in stage 4** (stage 3 writes the tests):

- **`ConfiguredIdentityValidator`:** no `getClaimAsString`, `getAudience` or `getExpiresAt`. It reads `getClaims()`: `iss` `instanceof String` and equal to the configured issuer; `aud` `instanceof List<?>` with every element a `String` and at least one equal to the configured audience; `exp` `instanceof Instant`. Total by construction, no `throw`, no catch-all (v7 section 8).
- **`ApplicationTokenValidator`** (v7 6.3): `azp` `instanceof String`, exact; "`scp` present" is `containsKey("scp")`.
- **`CallerClaims`:** `roleValues` no longer accepts a single string, and `onlyRole` no longer filters foreign names. New rule: the value is a `List` with exactly one element, that element a `String` equal to an `ActorRole` name of the branch (v7 6.3). A single string, duplicates (`[MLRO, MLRO]`), any second name, `[]`, `[""]` and non-strings refuse. The Javadoc ("a string or an array", "ignored") is rewritten with it. Actor and centre stay `instanceof String`.

**Test 18.5 additions** (to v7's complete list): app-only token with no `roles`, with `"roles": []` and with `"roles": [""]`: each 401. Any token with `azp` missing, `"azp": 123`, `"azp": ["<bff id>"]` or `"azp": null`: each 401. User token with `"scp": ""` and with `"scp": []`, `roles` `[MLRO]`, BFF `azp`: **accepted** (present by the definition). App-only token with `"scp": []`: 401. `"aud": "<audience>"` as a single string: accepted (converted to a list by the library). `"iss": 5`: 401.

## 8. Key chain (W6, N1, N2)

### 8.1 Deciding, and the CAS loser (W6)

```
getJWKSet(evaluator, now, ctx):
  try { source.getJWKSet(evaluator, now, ctx); } catch ... as v6 9.2
  loop:
    pair = health.current();                        // the pair first
    t = clock.millis();                             // then the clock
    if (pair.record == null) return EMPTY;
    if (t - pair.record.fetchedAtMillis < windowMillis) return pair.record.keys;
    if (health.compareAndSet(pair, pair.lapsed())) { log LAPSED once if state changed; return EMPTY; }
    // CAS lost: another thread changed the pair. Read the pair again, then the clock, then decide again.
```

This restores v6's rule ("a CAS loser reads again and decides again") inside v7's pair-first order. v7 returned EMPTY even when the CAS lost, so a request racing a successful fetch was refused although a fresh set was already stored. The loop ends: each turn either returns or follows another thread's transition. A pair already LAPSED is replaced by an equal one and nothing is logged; only a change of state logs. **Test 18.1d:** X reads (S1, 0) and blocks before its clock read until 300 000; meanwhile Y stores (S2, 299 000, OK). X's CAS fails, X re-reads (S2, 299 000), reads the clock (300 000), answers S2 and writes no LAPSED line.

### 8.2 Stale stamps cost availability (N1)

A stale success (stamp below the record's) is discarded by the serving layer, but `CachingJWKSetSource` keeps the set the source returned (v7 9.1). The next unknown-`kid` request asks with `referenceComparison(record.keys)`; the cache holds a different instance, so `requiresRefresh` is false and no fetch happens. **Cost:** a key that only the discarded set holds is refused until the cache entry expires (one TTL, 60 s) and a new fetch is admitted. Safe for D-21. **18.1b extended:** S3 (the discarded set) holds a new `kid` K3; a K3 token is refused at 31 000 and 89 999 with zero IdP requests between, and verifies at the first request at or after 90 000 (S3's cache expiry: `cacheJWKSet(set, now)` builds `CachedObject` with expiry `now + getTimeToLive()`, verified, so the entry is stamped with the caller's `now` 30 000), after exactly one IdP request.

### 8.3 Read timeouts are per read (N2)

`DefaultResourceRetriever` calls `URLConnection.setConnectTimeout` and `setReadTimeout` (offsets 34, 42) and bounds the body with `BoundedInputStream` (`DEFAULT_HTTP_SIZE_LIMIT` 51 200). `setReadTimeout` bounds each read, not the fetch: an IdP that drips bytes can hold the fetching thread far beyond 2 000 ms. v7 9.2's "the refresh timeout covers one fetch" was wrong: the 5 000 ms refresh timeout bounds only threads waiting for the lock. Consequence: a slow IdP holds the fetching request until it fails; the BFF's identify call ends at 10 000 ms with 504 and zero body bytes read (3.6). Residual, section 13: no whole-fetch deadline exists in the library, and the IdP is Entra over TLS.

## 9. D-20 option B without `throw` (W3, N4)

Applies only if the owner chooses B (section 15 item 2). Option A stands as v7 wrote it.

- **`RegisteredClientTrust extends X509ExtendedTrustManager`** (not `*Manager`: checkstyle `noServiceSuffixDeclaration`, ArchUnit `no_grab_bag_classes`). Two delegates: the CA trust manager from the server bundle (`SslManagerBundle.getTrustManagers()`), and a leaf trust manager from `TrustManagerFactory.getInstance("PKIX")` initialised over a truststore holding **only the registered leaf certificates** (one PEM bundle, `spring.ssl.bundle.pem.registered-clients.truststore.certificate`, deployment value). `PKIX` is the JDK default algorithm (`java.security`: `ssl.TrustManagerFactory.algorithm=PKIX`), served by `sun.security.ssl.TrustManagerFactoryImpl$PKIXFactory`, whose `X509TrustManagerImpl` extends `X509ExtendedTrustManager` (verified).
- **Each `checkClientTrusted` overload** (`chain, authType`; with `Socket`; with `SSLEngine`) calls the CA delegate, then the leaf delegate, with the same arguments, and declares `throws CertificateException`. `checkServerTrusted` delegates the same way (never called on a server socket). `getAcceptedIssuers` returns the CA delegate's, so the certificate request names the CA, not the leaves. Our code neither throws nor catches: the JDK's `PKIXValidator` throws for an unregistered leaf, because no path to a trust anchor exists in a store of leaves only.
- **Why the leaf passes:** `PKIXValidator.engineValidate` returns `{chain[0]}` at once when `chain[0]` is in `trustedCerts` (offsets 132 to 152, `Set.contains`, which compares the encoded certificate). That is the leaf shortcut.
- **Wiring:** a `TomcatConnectorCustomizer` bean, not a protocol customizer. In `TomcatWebServerFactory.customizeConnector`, protocol handler customizers run at 66, `customizeSsl` (Boot's `SslConnectorCustomizer`) at 130 and connector customizers at 190 (verified). Only the last see the `SSLHostConfig` Boot created. It calls `connector.findSslHostConfigs()` and, for each `SSLHostConfigCertificate`, `setSslContext(...)` with a hand-written implementation of Tomcat's `org.apache.tomcat.util.net.SSLContext` (8 methods, verified) over a JSSE `SSLContext` built from the bundle's key managers and `RegisteredClientTrust`. Tomcat's `JSSESSLContext` is package-private, so the comment names it (copilot 0.7). N4: nothing customises the protocol, so no typed `TomcatProtocolHandlerCustomizer` is needed; one would run before the SSL host config exists.
- **Trade-offs.** (1) This pins **certificates, not subjects**: a renewed leaf is refused until the PEM bundle holds it (deployment check: add the new leaf before rollout, remove the old after). (2) The leaf shortcut skips date and constraint checks for the leaf; the CA delegate runs first and keeps them, so an expired registered leaf still fails. (3) Revocation is not checked by either delegate (as today). (4) Boot's bundle reload would re-run `SslConnectorCustomizer` (`addBundleUpdateHandler`, verified) and may replace the context: `reload-on-update` stays off (deployment check; stage 3 confirms). (5) The HTTP-level subject check of the resolver stays as a second guard.
- **Test 18.19:** registered leaf from the trusted CA: handshake succeeds; unregistered leaf from the trusted CA: handshake refused, no HTTP exchange, no application log; registered leaf whose CA is not trusted: refused by the CA delegate; expired registered leaf: refused; the certificate request lists only the CA. Stage 3 confirms how TLS 1.3 reports the refusal to the client.

## 10. ITs on plain http (W4)

Under D-20 the service's connector requires TLS and a client certificate (`server.ssl.client-auth: need`), so these five ITs move to `https://localhost:<port>` with a TLS client from `ClientCertificates` (v7 section 11): `HttpClient.newBuilder().version(HTTP_1_1).sslContext(ClientCertificates.sslContext(registration)).build()`, trusting only `TestPki`'s CA; the service certificate carries `SAN=dns:localhost`, so the JDK's hostname check passes unchanged.

| IT | Identity today | Certificate presented |
|---|---|---|
| `RiskApiHardeningIT` | `ActorContextResolver` replaced (`callAs`), no token | `bff` for human roles; `de` when `callAs(ONBOARDING_SYSTEM)`. One client per registration |
| `RiskApiAdversarialIT` | Same | Same rule (`ONBOARDING_SYSTEM` on `de`, `AML_ANALYST_L2` on `bff`) |
| `IdentityIT` | Real tokens (`TestTokens.claims`) | `bff` for user tokens; `de` for the `ONBOARDING_SYSTEM` cases, which move to `TestTokens.appClaims("de")` (D-19) |
| `IdentityKeysUnavailableIT` | Real token, `MLRO` | `bff` |
| `IdentityClaimNamesIT` | Real tokens, `AML_ANALYST_L2` | `bff` |

`RiskApiFlowIT` uses MockMvc and gets the certificate as a request attribute (`ClientCertificates`, `requestAttr`). Each IT adds one case with no client certificate: handshake refused.

## 11. Quarantine conflict (N6)

`docs/adr/customer-risk-scoring-design.md`, "Not doing" (lines 310 to 311), defers quarantine and says the relay retries forever. D-18 and BR-44 to BR-46 put quarantine in this increment. This is an **architect amendment** to that design, needed before stage 3 writes the relay tests: ten attempts, quarantine, front-office status verification pending, later events of the customer waiting, one ERROR line and a metric, a release record. It is not an owner decision. Only the release role is (spec, under BR-46: "a product-owner decision still open"; section 15 item 5).

## 12. Verification status (2026-11-27)

Files under `/tmp/claude-0/-home-user-kyc-aml/52ec4e60-260e-5863-9603-67b60a05607e/scratchpad/v8/`:

- **Probe** `probe-first-call.txt` (source `probe-src/FirstCallProbe.java`, JDK 25.0.4.1 `HttpClient` to a raw `ServerSocket`): `GET()` sends no `Content-Length` and 0 bytes after the header block; `method("GET", noBody())` sends `Content-Length: 0`; `POST(ofByteArray(16 385 bytes))` sends `Content-Length: 16385` and 16 385 bytes.
- **Spring Framework 7.0.10:** `dispatcher-logRequest.txt`, `w2-logRequest-lambda.txt` (`traceDebug`; `getParameterMap` at 34 and 89; form type check at 79 to 85).
- **Tomcat 11.0.27:** `tomcat-parseParameters.txt` (body read for form content only); `tomcat-readtimeout.txt` (per-read `connectionTimeout`, `disableUploadTimeout` true).
- **Spring Security 7.1.2:** `csrf-handler.txt` (header, then `getParameter`); `mapped-converter.txt`, `mapped-defaults.txt` (seven converted claims); `nimbus-decode-order.txt` (convert, then validate).
- **JDK 25 runtime:** `jdk-pkixvalidator.txt`, `jdk-pkix-leaf-shortcut.txt`, `jdk-x509tm.txt`, `jdk-default-tm-alg.txt`.
- **Spring Boot 4.1.2:** `boot-tomcatfactory.txt`, `boot-customizeConnector-order.txt` (66, 130, 190); `TomcatProtocolHandlerCustomizer<T extends ProtocolHandler>`; `Connector.findSslHostConfigs`, `SSLHostConfigCertificate.setSslContext` (Tomcat).
- **Nimbus 10.9.1:** `nimbus-retriever.txt` (`setReadTimeout`, `BoundedInputStream`, size limit 51 200); `nimbus-cache-stamp.txt` (cache expiry from the caller's `now`).
- **Repository (read):** `BoundedBody`, `RecordComplianceDecisionResource` (identify first; `notIdentified()` constant), `ProblemResponses`, `ContainerErrorResource`, `ProtectedResourceMetadataNotServed`, `BearerTokenSecurityConfiguration`, `ConfiguredIdentityValidator` (`getClaimAsString`, `getAudience`, `getExpiresAt`), `CallerClaims` (single string and foreign names accepted), `RiskPermissions`, `application.yml` (no `org.springframework.web` pin), `ProductionIdentitySettingsTest`, the five ITs of section 10.
- **Not checked:** as v7 section 12, minus the `onRead` items; plus the `/**` mapping against a wrong method (stage 3), TLS 1.3 refusal reporting, bundle reload behaviour, Entra token claim shapes (to confirm in the tenant).

## 13. Open items and residuals

1. **D-20:** section 9, section 15 item 2.
2. **Unbounded key fetch** (8.3): a dripping IdP holds one request per chain; bounded at the BFF by 504. Accepted as a residual.
3. **Reset after an unread client body** (3.5): after the answer; reveals nothing.
4. **Older set served once more inside its window** (v7 9.3) and the stale-stamp cost (8.2): safe for D-21.
5. **Tenant facts, deployment checks, BFF dependencies, `OutageTolerantJWKSetSource`:** as v6 12 items 5 to 7 and 9. Added deployment checks: the BFF sets `spring.web.error.include-stacktrace: never` and `server.tomcat.connection-timeout: 5s`; under option B, the registered-leaf PEM bundle and `reload-on-update` off.
6. **Quarantine amendment:** section 11, before stage 3.
7. **AGENTS.md line:** section 5, to be added by whoever may edit it.

## 14. Tests required before stage 4

Stage 3 writes these red (test-compile passes, `test` fails). Serving-layer tests pass `now` and a test `Clock` explicitly.

- **18.1, 18.1a, 18.1c:** as v7. **18.1b:** as v6 plus the K3 availability cost (8.2). **18.1d:** CAS loser re-reads (8.1).
- **18.2:** outage per chain, as v7.
- **18.3:** unregistered certificate from the trusted CA: 401, zero IdP requests, same bytes, one WARN per process, no subject, issuer or token in logs (under option B: 18.19).
- **18.4, 18.4b:** as v7.
- **18.5:** v7's complete list plus section 7's additions.
- **18.6 (a) to (l), 18.6p, 18.6s:** as v7.
- **18.7, 18.7b, 18.8, 18.8b to 18.8f:** as v7.
- **18.9(a) to (d):** as v7; (c) per the option chosen.
- **18.10:** BFF behaviours of v4 18.10, except its body bound (18.11a).
- **18.11:** every answer of section 4 on the decision route writes one line `<time> corr=<id> durationMs=<n>` and nothing else; a client `X-Correlation-Id: attacker` appears nowhere; both service calls carry the same generated UUID.
- **18.11a (BFF against a recording service stub; the BFF's request is wrapped by a test filter whose `getInputStream`, `getReader`, `getParameter*` and `getParts` count calls and bytes):** (i) no session, declared lengths 0, 16 385 and 20 000 000, and chunked 20 000 bytes: the constant 401, no call to the stub, 0 calls and 0 bytes on the wrapper; (ii) session, stub answers the identify call 401 twice, same sizes: the stub's constant 401, one refresh, two identify calls, no decision call, 0 bytes read; (iii) session, CSRF header missing with a form body `_csrf=<valid>`: 403, 0 bytes read; (iv) identified, 20 000 000 declared: one identify call, then a decision call with `Content-Length: 16385` and exactly 16 385 bytes, the stub's 413 forwarded; (v) the stub delays the identify answer by 6 000 ms, once with 401 and once with 204: answers as in (ii) and (vi), 0 bytes read before the stub answered; (vi) identified, 16 384 bytes: forwarded in full with that length; (vii) identified, chunked 300 bytes: forwarded with `Content-Length: 300`, never chunked; (viii) malformed `Content-Length` (two headers equal and different, `abc`, `5, 5`, `+5`): Tomcat 400 with `Connection: close`, no stub call, no log line; (ix) identify answer 500: 503, 0 bytes read; identify timeout: 504, 0 bytes read; (x) body dripping one byte every 4 s: 504 within 15 000 ms, no decision call.
- **18.11b:** as v7.
- **18.11c (service, real TLS, `CallerIdentificationResource`):** identified caller: 204, no body, `Cache-Control: no-store`, no header naming role, centre or actor; not identified (no token, bad token, foreign role, out-of-scope centre, unregistered `azp`): the exact bytes of the decision route's 401; a `FRONT_OFFICE` caller gets 204 (no role check, 3.3); a request with a body is not read (counting wrapper); the log holds no customer number and no line on success; ArchUnit green.
- **18.11d (identity changes between the calls):** stub answers identify 204, then the decision 401: one refresh and one repeat with the same bytes, then the repeat's answer; with the refresh already used: the decision 401 forwarded; decision 403 after identify 204: 403 forwarded.
- **18.12, 18.12b to 18.12f:** as v7.
- **18.13, 18.14, 18.14b, 18.14c:** as v7. **18.14d:** TTL cap on every write (section 6).
- **18.15, 18.15b, 18.15c, 18.16:** as v7.
- **18.17:** service, no token: unknown path and wrong method on a known path get the constant 401; identified: 404 `request-not-handled`. **18.17b:** `GET /.well-known/oauth-protected-resource/x`: the constant 401 bytes, with and without a token.
- **18.18:** `ProductionIdentitySettingsTest.shouldPinSpringWebLoggingToInfo`.
- **18.19:** option B handshakes (section 9), only if B is chosen.
- **18.20:** the five ITs of section 10 over TLS with the listed certificates; each refuses a connection without a client certificate.
- **ArchUnit:** `ArchitectureRulesTest`, `RiskModuleArchitectureRulesTest` and `RiskWebRulesTest` green with the new classes (`CallerIdentificationResource`, `UnknownRouteResource`, `RegisteredClientTrust`), none in `domain/`.

## 15. Decision requested

What the product owner must still decide (v7 item 3 is closed by D-26):

1. **D-22 consequence.** The decision log cannot show success, and failed decisions (503 and 504 included) are invisible to operations. Amend D-22 to allow a coarse outcome, or accept the gap.
2. **D-20.** Option A (amend D-20: the handshake checks the CA, HTTP checks the subject) or option B (section 9: handshake refuses unregistered leaves; no exception to the `throw new` ban is needed any more; the cost is certificate pinning and a PEM bundle to maintain on renewal).
3. **Validator defect gives 5xx.** Accept that a defect in a validator answers 5xx rather than 401 (no catch-all is allowed), or name another treatment (v7 section 8).
4. **BFF dependency decision.** The Redis client, the session store and the Entra client; none is in `pom.xml` (the forwarding client is the JDK's).
5. **Quarantine release role** (D-18, BR-46).
6. **One live session per user.**
7. **Session lifetimes:** idle and absolute timeouts. The absolute timeout is also the retired-key period (v7 section 5).
8. **Logout:** local only, or also revoke the user's refresh tokens at Entra.
9. **Owner of the BFF:** code, Entra registration, certificates and Redis.
10. **BFF in this increment,** or the service side only.
11. **Log fields on the risk read routes:** the decision-route set, or the template and status as well.
